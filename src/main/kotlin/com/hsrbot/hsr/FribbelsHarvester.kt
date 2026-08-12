package com.hsrbot.hsr

import com.hsrbot.config.BotProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One-shot harvest of the `hsr_build_meta` cache (game id → [FribbelsMeta]) from:
 *
 *  - fribbels/hsr-optimizer: per-character build metadata regex-parsed out of the
 *    machine-formatted TypeScript configs (`scoring().stats/parts`, `simulation()`
 *    relic/ornament sets, substat priority and stat breakpoints). Spread constants (generic
 *    alternative sets) and teammates/rotations are deliberately ignored — only the explicit,
 *    character-specific recommendation is kept. The spreads in particular are near-universal
 *    (60 of ~100 configs list the same 4pc pool), so they say nothing about THIS character
 *    and would make [BuildAnalyzer]'s wrong-set alert unfireable;
 *  - StarRailRes (Mar-7th): only the en→pt relic-set name mapping, to render set names in PT.
 *
 * Names and full kit text come from the V17 tables ([PersonagemHsr]); what this source carries is
 * the `/build` RULER — `scoring().stats` and `scoring().parts` are the weights [FribbelsScorer]
 * runs on, and there is no second table behind them, so a broken harvest is a `/build` that
 * abstains rather than one that scores differently. Called only from [HsrCharacterService]'s
 * scheduled staleness check (~monthly). Shared fetches throw on failure so the caller keeps the
 * previous rows; a single unparsable character file is skipped with a warning.
 */
@Component
class FribbelsHarvester(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        // The multi-MB git-tree response gets RST_STREAM-cancelled mid-body on HTTP/2
        // (JDK client + GitHub API); HTTP/1.1 streams it reliably and this client only
        // ever does a monthly batch, so multiplexing buys nothing.
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    /** Game id → fribbels build metadata. Configs with no numeric id (the `*B1.ts` reruns, whose
     *  `1217b1` has no showcase to join to) and unparsable ones are skipped; names/kit come from
     *  the V17 tables. */
    fun harvest(): Map<String, FribbelsMeta> {
        val srr = properties.knowledge.starRailResBase
        val setPtByEn = relicSetPtByEnName(srr)

        val raw = properties.knowledge.fribbelsRawBase
        val setsEnum = parseSetsEnum(fetch(raw + "src/lib/constants/constants.ts"))
        val spreads = parseSpreads(fetch(raw + "src/lib/scoring/scoringConstants.ts"))
        val paths = characterPaths(fetch(properties.knowledge.fribbelsTreeUrl))
        log.info("fribbels: harvesting {} character configs", paths.size)

        return paths.flatMap { path ->
            try {
                val parsed = parseCharacter(fetch(raw + path), spreads)
                // Expected for the *B1.ts enhanced-rerun variants (id '1005b1'): mihomo and Enka
                // always report the base game id, so their metadata has no join key.
                if (parsed == null) {
                    log.info("fribbels: no numeric config id in {} — skipped", path)
                    return@flatMap emptyList()
                }
                // One meta per file, shared by every id the file exports — the Desbravador pairs.
                val meta = parsed.toMeta(setsEnum, setPtByEn)
                parsed.ids.map { it to meta }
            } catch (e: Exception) {
                log.warn("fribbels: failed to fetch/parse {} — skipped: {}", path, e.message)
                emptyList()
            }
        }.toMap()
    }

    private fun relicSetPtByEnName(base: String): Map<String, String> {
        val en = mapper.readTree(fetch("${base}en/relic_sets.json"))
        val pt = mapper.readTree(fetch("${base}pt/relic_sets.json"))
        return buildMap {
            en.fields().forEach { (id, node) ->
                val ptName = pt.path(id).path("name").asText("")
                if (ptName.isNotBlank()) put(node.path("name").asText(""), ptName)
            }
        }
    }

    private fun characterPaths(treeJson: String): List<String> =
        mapper.readTree(treeJson).path("tree")
            .map { it.path("path").asText("") }
            .filter { it.startsWith("src/lib/conditionals/character/") && it.endsWith(".ts") }

    private fun fetch(url: String): String {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Mozilla/5.0 (compatible; HsrBot/1.0; +discord)")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()} for $url" }
        return checkNotNull(resp.body()) { "corpo vazio para $url" }
    }

    internal companion object {

        /** fribbels `Stats.*` enum key → game property key (the mihomo/Enka vocabulary). */
        internal val STAT_PROPERTY = mapOf(
            "HP" to "HPDelta", "ATK" to "AttackDelta", "DEF" to "DefenceDelta",
            "HP_P" to "HPAddedRatio", "ATK_P" to "AttackAddedRatio", "DEF_P" to "DefenceAddedRatio",
            "SPD" to "SpeedDelta", "CR" to "CriticalChanceBase", "CD" to "CriticalDamageBase",
            "EHR" to "StatusProbabilityBase", "RES" to "StatusResistanceBase",
            "BE" to "BreakDamageAddedRatioBase", "ERR" to "SPRatioBase", "OHB" to "HealRatioBase",
            "Physical_DMG" to "PhysicalAddedRatio", "Fire_DMG" to "FireAddedRatio",
            "Ice_DMG" to "IceAddedRatio",
            // yes, Thunder — the game's internal key for Lightning damage.
            "Lightning_DMG" to "ThunderAddedRatio",
            "Wind_DMG" to "WindAddedRatio", "Quantum_DMG" to "QuantumAddedRatio",
            "Imaginary_DMG" to "ImaginaryAddedRatio",
        )

        /** fribbels `Parts.*` enum key → mihomo relic slot (1–6; head/hands have fixed mains). */
        internal val PART_SLOT = mapOf(
            "Head" to 1, "Hands" to 2, "Body" to 3, "Feet" to 4, "PlanarSphere" to 5, "LinkRope" to 6,
        )

        /**
         * The id of every exported `CharacterConfig` in the file, not the file's last `id:`. The
         * five Desbravador files each export TWO configs off ONE `scoring()` — Caelus and Stelle,
         * `8003` and `8004` — and taking the last id silently dropped one of each pair, which is
         * five of the roster with no ruler at all.
         */
        private val ID_RE = Regex("CharacterConfig = \\{\\s*\\n\\s*id: '(\\d+)'")
        private val STAT_WEIGHT_RE = Regex("\\[Stats\\.(\\w+)]: ([0-9.]+)")
        private val PART_RE = Regex("\\[Parts\\.(\\w+)]: \\[([^]]*)]")
        private val STAT_REF_RE = Regex("Stats\\.(\\w+)")
        private val SET_PAIR_RE = Regex("\\[\\s*Sets\\.(\\w+),\\s*Sets\\.(\\w+)")
        private val SET_REF_RE = Regex("Sets\\.(\\w+)")
        private val SET_ENUM_RE = Regex("(\\w+): '((?:\\\\'|[^'])*)'")
        private val CONST_DECL_RE = Regex("export const (\\w+) =")
        private val SPREAD_RE = Regex("\\.\\.\\.(\\w+)")
        private val BREAKPOINT_RE = Regex("\\{\\s*stat: Stats\\.(\\w+),\\s*threshold: ([0-9.]+)\\s*}")
        private val FLAT_BOOST_RE = Regex("flatMainstatBoost: Stats\\.(\\w+)")

        internal data class ParsedChar(
            /** Every game id this one `scoring()` block rules — two for the Desbravador files. */
            val ids: List<String>,
            /** Substat weights keyed by fribbels `Stats.*` enum key. */
            val stats: Map<String, Double>,
            /** Ideal main stats per `Parts.*` enum key, as `Stats.*` keys. */
            val parts: Map<String, List<String>>,
            /** Recommended relic-set pairs / ornament sets as `Sets.*` enum keys. */
            val relicSets: List<Pair<String, String>>,
            val ornamentSets: List<String>,
            /** Substat priority order as `Stats.*` keys. */
            val substats: List<String>,
            /** Stat gates as `Stats.*` key → threshold, hard ones first. */
            val breakpoints: List<Pair<String, Double>> = emptyList(),
            /** `scoring().flatMainstatBoost` as a `Stats.*` key, when the config declares one. */
            val flatMainstatBoost: String? = null,
        ) {
            fun toMeta(setsEnum: Map<String, String>, setPtByEn: Map<String, String>): FribbelsMeta {
                fun setName(enumKey: String): String =
                    setsEnum[enumKey]?.let { en -> setPtByEn[en] ?: en } ?: enumKey
                return FribbelsMeta(
                    subWeights = stats.mapNotNull { (k, w) -> STAT_PROPERTY[k]?.let { it to w } }.toMap(),
                    mainStats = parts.mapNotNull { (part, statKeys) ->
                        PART_SLOT[part]?.let { slot -> slot to statKeys.mapNotNull { STAT_PROPERTY[it] } }
                    }.toMap().filterValues { it.isNotEmpty() },
                    relicSets = relicSets.map { (a, b) -> listOf(setName(a), setName(b)) },
                    ornamentSets = ornamentSets.map { setName(it) },
                    substatPriority = substats.mapNotNull { STAT_PROPERTY[it] },
                    breakpoints = breakpoints.mapNotNull { (stat, threshold) ->
                        STAT_PROPERTY[stat]?.let { FribbelsMeta.Breakpoint(it, threshold) }
                    },
                    flatMainstatBoost = flatMainstatBoost?.let { STAT_PROPERTY[it] },
                )
            }
        }

        /**
         * Pure regex parse of one machine-formatted character config. Anchors on the
         * `const scoring` / `const simulation` blocks; a repo-side format change makes this
         * return empty fields, never wrong ones — the service's sanity floor catches that.
         */
        internal fun parseCharacter(ts: String, spreads: Map<String, String> = emptyMap()): ParsedChar? {
            val ids = ID_RE.findAll(ts).map { it.groupValues[1] }.toList().ifEmpty { return null }
            val scoring = ts.substringAfterLast("const scoring")
            val sim = ts.substringAfter("const simulation", "").substringBefore("const scoring")
            return ParsedChar(
                ids = ids,
                flatMainstatBoost = FLAT_BOOST_RE.find(scoring)?.groupValues?.get(1),
                stats = STAT_WEIGHT_RE.findAll(slice(scoring, "stats:", '{', '}'))
                    .associate { it.groupValues[1] to it.groupValues[2].toDouble() },
                parts = PART_RE.findAll(slice(scoring, "parts:", '{', '}'))
                    .associate { m ->
                        m.groupValues[1] to STAT_REF_RE.findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
                    },
                // `distinct` because a config commonly names a set literally AND spreads a constant
                // that already contains it (Bronya: Sacerdos, then ...SPREAD_RELICS_4P_SUPPORT).
                // First occurrence wins, so the literal entries stay in front — fribbels' own
                // benchmark defaults to `relicSets[0]`/`ornamentSets[0]`.
                relicSets = SET_PAIR_RE.findAll(expandSpreads(slice(sim, "relicSets:", '[', ']'), spreads))
                    .map { it.groupValues[1] to it.groupValues[2] }.distinct().toList(),
                ornamentSets = SET_REF_RE.findAll(expandSpreads(slice(sim, "ornamentSets:", '[', ']'), spreads))
                    .map { it.groupValues[1] }.distinct().toList(),
                substats = STAT_REF_RE.findAll(slice(sim, "substats:", '[', ']'))
                    .map { it.groupValues[1] }.toList(),
                // Hard first so `distinctBy` keeps it when a stat somehow has both — fribbels'
                // own merge gives the hard gate priority too. No config declares both today.
                breakpoints = BREAKPOINT_RE
                    .findAll(slice(sim, "hardBreakpoints:", '[', ']') + slice(sim, "softBreakpoints:", '[', ']'))
                    .map { it.groupValues[1] to it.groupValues[2].toDouble() }
                    .distinctBy { it.first }
                    .toList(),
            )
        }

        /**
         * `export const NAME = [...]` bodies from fribbels' `scoringConstants.ts`, so that a
         * config's `...SPREAD_ORNAMENTS_2P_SUPPORT` can be inlined by [expandSpreads].
         *
         * Not optional: EVERY config that declares sets spreads at least one of these constants,
         * and reading only the literal entries captured 66% of the relic pairs and 37% of the
         * ornaments (Tribbie: 1 of 11). What upstream keeps in them is exactly the sets whose
         * value their sim cannot model — the ones a real build is most likely to be wearing.
         */
        internal fun parseSpreads(constantsTs: String): Map<String, String> =
            CONST_DECL_RE.findAll(constantsTs)
                .associate { it.groupValues[1] to slice(constantsTs, it.value, '[', ']') }

        /**
         * Inlines `...NAME` references from [spreads]. Recursive because the constants nest —
         * `SPREAD_RELICS_4P_HEAL` spreads `SPREAD_RELICS_4P_SUPPORT` — and bounded by [depth] so a
         * constant that ever spreads itself costs a shallow list, not the harvest. An unknown name
         * inlines to nothing, which is the old behaviour for that one entry.
         */
        internal tailrec fun expandSpreads(text: String, spreads: Map<String, String>, depth: Int = 4): String =
            if (depth == 0 || !text.contains("...")) text
            else expandSpreads(SPREAD_RE.replace(text) { spreads[it.groupValues[1]].orEmpty() }, spreads, depth - 1)

        /** `Sets.*` enum key → English in-game set name, from fribbels constants.ts. */
        internal fun parseSetsEnum(constantsTs: String): Map<String, String> =
            SET_ENUM_RE.findAll(slice(constantsTs, "export const Sets =", '{', '}'))
                .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

        /** Content between the balanced [open]/[close] pair that follows [marker]; "" if absent. */
        internal fun slice(text: String, marker: String, open: Char, close: Char): String {
            val at = text.indexOf(marker)
            if (at < 0) return ""
            val start = text.indexOf(open, at)
            if (start < 0) return ""
            var depth = 0
            for (i in start until text.length) {
                when (text[i]) {
                    open -> depth++
                    close -> if (--depth == 0) return text.substring(start + 1, i)
                }
            }
            return ""
        }
    }
}
