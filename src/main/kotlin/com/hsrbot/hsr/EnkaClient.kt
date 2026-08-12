package com.hsrbot.hsr

import com.hsrbot.config.BotProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Reads a player's showcase from Enka (`{enkaUrl}/{uid}`, no trailing slash) — the primary source
 * for `/build`.
 *
 * Enka answers the RAW game payload where mihomo answered a pre-chewed one: ids instead of names,
 * and no computed stat panel. Everything the scorer needs survives that difference, because the two
 * integers [FribbelsScorer] rebuilds each substat's value from — `subAffixList[].cnt` and `.step` —
 * are in the raw payload and are exactly what mihomo was reprinting as `count`/`step` (fribbels'
 * own importer reads the same two). The combat panel is not in
 * the payload at all, and is summed in [StatPanel] from the StarRailRes tables — the same thing
 * enka.network's own site and fribbels' optimizer do, since nobody is served that block finished.
 *
 * No retry and no cache here on purpose — [ShowcaseService] owns both, and its mihomo fallback IS
 * the retry: a second attempt against a source that just failed is worth less than a first attempt
 * against the other one.
 */
@Component
class EnkaClient(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
    private val personagens: HsrCharacterService,
    private val nomes: StarRailResNames,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // followRedirects: same trap StarRailStationIngestionSource hit — Enka runs on SvelteKit, which
    // 308-normalizes `/uid/123/` → `/uid/123`, and the JDK client defaults to Redirect.NEVER. The
    // request below already asks for the normalized form so this costs no extra round trip; it is
    // here so the next normalization tweak upstream is a slower call, not a silent mihomo fallback.
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fetch(uid: String): ShowcaseResult {
        // Without the name tables every relic set and cone on the card would be blank. mihomo does
        // that lookup server-side, so falling through to it beats publishing a nameless card.
        if (!nomes.ready()) {
            log.warn("Enka: tabelas de nome do StarRailRes ainda não carregaram — caindo pro mihomo")
            return ShowcaseResult.Error
        }
        return try {
            val url = "${properties.knowledge.enkaUrl.trimEnd('/')}/$uid"
            val req = HttpRequest.newBuilder(URI.create(url))
                // Enka asks integrators to identify themselves; an anonymous UA is what gets a
                // project rate-limited off the API.
                .header("User-Agent", properties.knowledge.enkaUserAgent)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            when {
                // 400 is a malformed UID and 404 an unknown player — both read to the member as
                // "check the number you linked", and neither improves by asking mihomo.
                resp.statusCode() == 404 || resp.statusCode() == 400 -> ShowcaseResult.NotFound
                resp.statusCode() !in 200..299 -> {
                    // 424 (game in maintenance) and 429 (rate limited) both land here and both are
                    // worth a mihomo attempt, so they stay Error rather than becoming NotFound.
                    log.warn("Enka: HTTP {} para uid {} — {}", resp.statusCode(), uid, resp.body().take(200))
                    ShowcaseResult.Error
                }
                else -> ShowcaseResult.Ok(EnkaParser.parse(mapper.readTree(resp.body()), lookups(uid)))
            }
        } catch (e: Exception) {
            log.warn("Enka: falha ao buscar uid {}: {}", uid, e.message)
            ShowcaseResult.Error
        }
    }

    /**
     * The name-resolution POLICY, kept here so [EnkaParser] stays pure: `personagem_hsr` first
     * (PT, richer, and already disambiguated for the Desbravadora/7 de Março variants), then
     * StarRailRes for a character the SRS harvest hasn't caught up with yet, then the bare id.
     * A numeric card beats a dropped character — the member still sees it was showcased, and the
     * score is computed all the same.
     */
    private fun lookups(uid: String) = EnkaLookups(
        personagem = { id ->
            personagens.byId(id)?.let {
                StarRailResNames.Personagem(
                    nome = personagens.displayName(id),
                    caminho = HsrTaxonomy.canonicalPath(it.caminho) ?: it.caminho,
                    elemento = HsrTaxonomy.canonicalElement(it.elemento) ?: it.elemento,
                )
            }
                ?: nomes.character(id)
                ?: StarRailResNames.Personagem(nome = id, caminho = null, elemento = null).also {
                    log.warn("Enka: personagem {} desconhecido na vitrine de {}", id, uid)
                }
        },
        conjunto = nomes::relicSet,
        cone = nomes::lightCone,
        promocoes = nomes::promocoes,
        traco = nomes::traco,
        bonusConjunto = nomes::bonusConjunto,
        conePassivo = nomes::conePassivo,
    )
}

/**
 * The three id→name lookups [EnkaParser] needs. Passed in as functions so parsing is a pure
 * transform testable from a JSON fixture and a couple of maps — the same shape [MihomoParser] and
 * [MihomoParser.parse] already has, and the reason neither needs a mocking library.
 */
internal class EnkaLookups(
    val personagem: (String) -> StarRailResNames.Personagem,
    val conjunto: (String) -> String?,
    val cone: (String) -> String?,
    /** Character id → growth per ascension, for the computed stat panel. */
    val promocoes: (String) -> List<Map<String, StatPanel.Crescimento>> = { emptyList() },
    /** Trace node id + unlocked level → what it grants. */
    val traco: (String, Int) -> List<StatPanel.Prop> = { _, _ -> emptyList() },
    /** Set id + pieces worn → the active bonus. */
    val bonusConjunto: (String, Int) -> List<StatPanel.Prop> = { _, _ -> emptyList() },
    /** Cone id + superimposition → what its passive grants unconditionally. */
    val conePassivo: (String, Int) -> List<StatPanel.Prop> = { _, _ -> emptyList() },
)

/** Raw Enka JSON → the same [ShowcaseProfile] mihomo produced, so nothing downstream can tell which source a card came from. */
internal object EnkaParser {

    fun parse(root: JsonNode, nomes: EnkaLookups): ShowcaseProfile {
        val info = root.path("detailInfo")
        // A player who switched the showcase off still has a profile; they just have no characters.
        val vitrine = info.path("avatarDetailList")
            .takeIf { info.path("isDisplayAvatar").asBoolean(true) }
        return ShowcaseProfile(
            nickname = info.path("nickname").asText(""),
            characters = vitrine?.mapNotNull { personagem(it, nomes) }.orEmpty(),
        )
    }

    private fun personagem(node: JsonNode, nomes: EnkaLookups): ShowcaseCharacter? {
        val id = node.path("avatarId").asText("").ifBlank { return null }
        val quem = nomes.personagem(id)
        val relicas = node.path("relicList").mapNotNull { reliquia(it, nomes) }
        return ShowcaseCharacter(
            id = id,
            name = quem.nome,
            level = node.path("level").asInt(0),
            eidolon = node.path("rank").asInt(0),
            pathName = quem.caminho.orEmpty(),
            elementName = quem.elemento.orEmpty(),
            lightCone = node.path("equipment").takeIf { it.isObject && !it.isEmpty }?.let { cone ->
                ShowcaseLightCone(
                    name = nomes.cone(cone.path("tid").asText("")).orEmpty(),
                    superimposition = cone.path("rank").asInt(0),
                    level = cone.path("level").asInt(0),
                )
            },
            relics = relicas,
            relicSets = conjuntos(relicas),
            stats = painel(node, id, nomes),
        )
    }

    /**
     * Enka ships no combat panel, so it is summed here — see [StatPanel]. Set bonuses are counted off
     * `setID` rather than the names [conjuntos] groups by, because a set we failed to NAME still
     * grants its stats, and the panel would be quietly short without them.
     */
    private fun painel(node: JsonNode, id: String, nomes: EnkaLookups): List<ShowcaseStat> =
        StatPanel.compute(
            nivel = node.path("level").asInt(0),
            promocao = node.path("promotion").asInt(0),
            promocoes = nomes.promocoes(id),
            tracos = node.path("skillTreeList").flatMap {
                nomes.traco(it.path("pointId").asText(""), it.path("level").asInt(0))
            },
            // The one piece of the sum Enka computes for us.
            coneBase = props(node.path("equipment").path("_flat").path("props")),
            // The cone's PASSIVE is a separate thing from its base stats and just as real to the
            // panel: a signature cone granting a flat "+30% Max HP" is most of a build's HP.
            conePassivo = node.path("equipment").let {
                nomes.conePassivo(it.path("tid").asText(""), it.path("rank").asInt(0))
            },
            // Main and substats alike: `_flat.props` is already valued, so the panel needs no
            // affix tables of its own.
            reliquias = node.path("relicList").flatMap { props(it.path("_flat").path("props")) },
            conjuntos = node.path("relicList")
                .map { it.path("_flat").path("setID").asText("") }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .flatMap { (setId, pecas) -> nomes.bonusConjunto(setId, pecas) },
        )

    /**
     * `_flat.props` carries Enka's raw spelling, so it needs the same [COM_BASE] normalization the
     * substats get — a relic's rolled `CriticalChance` must land on the `CriticalChanceBase` the
     * trace and set tables use, or the panel sums the two into separate piles and silently reports
     * crit as traces-only. The cone's `BaseHP`/`BaseAttack`/`BaseDefence` aren't in the map and pass
     * through untouched.
     */
    private fun props(node: JsonNode): List<StatPanel.Prop> = node.mapNotNull {
        val tipo = it.path("type").asText("")
        if (tipo.isBlank()) null else StatPanel.Prop(COM_BASE[tipo] ?: tipo, it.path("value").asDouble(0.0))
    }

    /**
     * `_flat.props[0]` is the main stat and `props[1..]` the substats, positionally parallel to
     * `subAffixList` — that pairing is what carries the roll counts onto the right property. A
     * relic with no props at all is dropped rather than scored, since a piece that scored 0 for a
     * parsing reason would read to the member as their own fault.
     */
    private fun reliquia(node: JsonNode, nomes: EnkaLookups): ShowcaseRelic? {
        val flat = node.path("_flat")
        val props = flat.path("props").takeIf { it.isArray && !it.isEmpty } ?: return null
        return ShowcaseRelic(
            // setID lives in `_flat`. A set we can't name still scores correctly — the score reads
            // main/sub properties only — it just loses its label and icon on the card.
            setName = nomes.conjunto(flat.path("setID").asText("")).orEmpty(),
            slot = node.path("type").asInt(0),
            level = node.path("level").asInt(0),
            // `tid` is `<rarity+1><set×3><slot>` — a 5★ piece is `6xxxx`. Same offset fribbels'
            // `gradeConversion` applies; a tid we can't read is scored as 5★, which every showcased
            // relic is.
            grade = node.path("tid").asText("").firstOrNull()?.digitToIntOrNull()?.minus(1) ?: 5,
            mainAffix = props.first().path("type").asText("").takeIf { it.isNotBlank() }?.let {
                // `name` is only ever a fallback label: BuildAnalyzer.statLabel maps every real
                // relic main stat from the property key, so the key itself never surfaces.
                ShowcaseAffix(type = it, name = it, display = "")
            },
            subAffixes = node.path("subAffixList").mapIndexedNotNull { i, sub ->
                val tipo = props.path(i + 1).path("type").asText("").ifBlank { return@mapIndexedNotNull null }
                    .let { COM_BASE[it] ?: it }
                ShowcaseSubAffix(
                    type = tipo,
                    name = tipo,
                    display = "",
                    count = sub.path("cnt").asInt(0),
                    // `step` is absent on a substat that never rolled up — that is a real 0, not
                    // missing data, and the formula's `step * 0.1` term must see it as one.
                    step = sub.path("step").asInt(0),
                )
            },
        )
    }

    /**
     * Enka spells a SUBstat bare (`CriticalChance`) and the same stat as a MAIN with a `Base` suffix
     * (`CriticalChanceBase`). The scoring weights — and so [BuildAnalyzer.PROP_PT] — use the
     * suffixed form for both, because mihomo normalized it server-side and nobody downstream ever saw
     * the bare one. Left unmapped, the weight lookup for `CriticalChance` misses, and crit rate and crit damage
     * score ZERO and get printed as dead rolls on the card — the two substats that decide most builds.
     *
     * Mapped here at the parse boundary rather than in the scorer or the label table, so the single
     * lookup covers the score, the dead-roll list and the PT label at once. These five are the whole
     * difference between the two spellings; the other seven substats are already identical.
     */
    private val COM_BASE = listOf(
        "CriticalChance", "CriticalDamage", "StatusProbability", "StatusResistance",
        "BreakDamageAddedRatio",
    ).associateWith { it + "Base" }

    /**
     * Active set bonuses, counted off the equipped pieces. Only 2+ counts: a lone piece grants
     * nothing and mihomo never reported one, so the card keeps the same rows it always had. Four
     * pieces is one 4pc bonus, not a 2pc and a 4pc — mihomo sent both and the old parser deduped
     * them; counting straight off the pieces gets there without the round trip.
     */
    private fun conjuntos(relics: List<ShowcaseRelic>): List<ShowcaseRelicSet> =
        relics.filter { it.setName.isNotBlank() }
            .groupingBy { it.setName }
            .eachCount()
            .filterValues { it >= 2 }
            .map { (nome, pecas) -> ShowcaseRelicSet(nome, pecas) }
}
