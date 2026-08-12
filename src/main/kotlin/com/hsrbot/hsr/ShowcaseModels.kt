package com.hsrbot.hsr

import com.fasterxml.jackson.databind.JsonNode
import java.util.Locale

/** Outcome of a showcase fetch — NotFound (bad UID / hidden profile) reads differently to the user than a transient error. */
sealed interface ShowcaseResult {
    data class Ok(val profile: ShowcaseProfile) : ShowcaseResult
    data object NotFound : ShowcaseResult
    data object Error : ShowcaseResult
}

/**
 * Minimal projection of a player's showcase — only the fields the build analyzer needs, and
 * deliberately source-agnostic: [EnkaClient] and [MihomoClient] both produce exactly this, so
 * nothing downstream of [ShowcaseService] can tell which API a card came from. Parsed by hand from
 * [JsonNode] (no jackson-kotlin module in the project), on the StarRailRes
 * `CharacterInfo`/`RelicInfo` vocabulary that both sources speak:
 *
 *  - [ShowcaseRelic.slot] is the 1–6 piece type (head/hands/body/feet/sphere/rope) and lines
 *    up with [FribbelsScorer.SLOT_MAIN_STATS];
 *  - affix [ShowcaseAffix.type] carries the game property key ("AttackAddedRatio",
 *    "CriticalChanceBase", "SpeedDelta"...) — exactly the keys the fribbels weights are mapped to;
 *  - sub-affix [ShowcaseSubAffix.count]/[ShowcaseSubAffix.step] are the roll count and the total
 *    upgrade increments across those rolls — the two integers [FribbelsScorer.substatValue] turns
 *    back into the substat's exact value.
 *
 * [ShowcaseCharacter.stats] is the one field that is not source-agnostic: mihomo computes the
 * finished combat panel and Enka does not, so it arrives empty from Enka and the renderer drops
 * that block. Nothing that feeds the SCORE is ever empty.
 */
data class ShowcaseProfile(
    val nickname: String,
    val characters: List<ShowcaseCharacter>,
)

data class ShowcaseCharacter(
    val id: String,
    val name: String,
    val level: Int,
    val eidolon: Int,
    val pathName: String,
    val elementName: String,
    val lightCone: ShowcaseLightCone?,
    val relics: List<ShowcaseRelic>,
    val relicSets: List<ShowcaseRelicSet>,
    /** Finished combat stats — what the game's own detail panel shows. Card-only; the score
     *  is computed from the relics, never from these. */
    val stats: List<ShowcaseStat> = emptyList(),
)

/** One finished stat: `field` is mihomo's key ("crit_rate"), [display] is already formatted. */
data class ShowcaseStat(val field: String, val name: String, val display: String)

data class ShowcaseLightCone(
    val name: String,
    val superimposition: Int,
    val level: Int,
)

/**
 * The piece's own name is deliberately absent: nothing reads it, and Enka would need a whole extra
 * id→relic table fetched just to fill a field no card or score has ever shown.
 *
 * [grade] is the rarity (2–5) and it does score: the roll tiers, the maxed main stat a wrong main is
 * charged for and the letter grade are all per-rarity, and [FribbelsScorer] refuses to letter
 * anything below 5★ the way fribbels does. Both sources carry it, so it is never guessed.
 */
data class ShowcaseRelic(
    val slot: Int,
    val setName: String,
    val level: Int,
    val mainAffix: ShowcaseAffix?,
    val subAffixes: List<ShowcaseSubAffix>,
    val grade: Int = 5,
)

data class ShowcaseAffix(
    val type: String,
    val name: String,
    val display: String,
)

data class ShowcaseSubAffix(
    val type: String,
    val name: String,
    val display: String,
    val count: Int,
    val step: Int,
)

/** Active set bonus: name + how many pieces of it are equipped (2/4). */
data class ShowcaseRelicSet(
    val name: String,
    val pieces: Int,
)

internal object MihomoParser {

    /** Maps the parsed-API JSON to [ShowcaseProfile]. Pure, so it's testable from a fixture. */
    fun parse(root: JsonNode): ShowcaseProfile = ShowcaseProfile(
        nickname = root.path("player").path("nickname").asText(""),
        characters = root.path("characters").map { c ->
            ShowcaseCharacter(
                id = c.path("id").asText(""),
                name = c.path("name").asText(""),
                level = c.path("level").asInt(0),
                eidolon = c.path("rank").asInt(0),
                pathName = c.path("path").path("name").asText(""),
                elementName = c.path("element").path("name").asText(""),
                lightCone = c.path("light_cone").takeIf { it.isObject }?.let { lc ->
                    ShowcaseLightCone(
                        name = lc.path("name").asText(""),
                        superimposition = lc.path("rank").asInt(0),
                        level = lc.path("level").asInt(0),
                    )
                },
                relics = c.path("relics").map { r ->
                    ShowcaseRelic(
                        slot = r.path("type").asInt(0),
                        setName = r.path("set_name").asText(""),
                        level = r.path("level").asInt(0),
                        grade = r.path("rarity").asInt(5),
                        mainAffix = r.path("main_affix").takeIf { it.isObject }?.let { affix(it) },
                        subAffixes = r.path("sub_affix").map { s ->
                            ShowcaseSubAffix(
                                type = s.path("type").asText(""),
                                name = s.path("name").asText(""),
                                display = s.path("display").asText(""),
                                count = s.path("count").asInt(0),
                                step = s.path("step").asInt(0),
                            )
                        },
                    )
                },
                relicSets = c.path("relic_sets").map { s ->
                    ShowcaseRelicSet(name = s.path("name").asText(""), pieces = s.path("num").asInt(0))
                    // The API repeats a 4pc set as separate 2pc+4pc entries; dedupe keeps the max.
                }.groupBy { it.name }.map { (name, entries) -> ShowcaseRelicSet(name, entries.maxOf { it.pieces }) },
                stats = stats(c),
            )
        },
    )

    /**
     * `attributes` is the character's own base and `additions` is everything the relics, cone and
     * traces add on top — the game shows the SUM, so that is what a card must show. Stats that only
     * ever exist as a bonus (elemental damage, effect hit rate) have no base row and are appended
     * after, keeping mihomo's own ordering.
     */
    private fun stats(c: JsonNode): List<ShowcaseStat> {
        val additions = c.path("additions").associateBy { it.path("field").asText("") }
        val base = c.path("attributes").map { a ->
            val field = a.path("field").asText("")
            val total = a.path("value").asDouble() + (additions[field]?.path("value")?.asDouble() ?: 0.0)
            ShowcaseStat(field, a.path("name").asText(""), display(total, a.path("percent").asBoolean()))
        }
        val cobertos = base.mapTo(mutableSetOf()) { it.field }
        return base + c.path("additions")
            .filter { it.path("field").asText("") !in cobertos }
            // The bonus-only rows are already totals, so their own display is the right one.
            .map { ShowcaseStat(it.path("field").asText(""), it.path("name").asText(""), it.path("display").asText("")) }
    }

    /** Percentages arrive as fractions (0.637 → "63.7%"); flat stats are floored, like in game. */
    private fun display(value: Double, percent: Boolean): String =
        if (percent) String.format(Locale.ROOT, "%.1f%%", value * 100) else value.toInt().toString()

    private fun affix(node: JsonNode): ShowcaseAffix = ShowcaseAffix(
        type = node.path("type").asText(""),
        name = node.path("name").asText(""),
        display = node.path("display").asText(""),
    )
}
