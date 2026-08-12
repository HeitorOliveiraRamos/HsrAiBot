package com.hsrbot.hsr

import com.hsrbot.config.BotProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The PT-BR name tables [EnkaClient] needs and Enka does not ship.
 *
 * Enka's showcase endpoint is the raw game payload: a relic carries `setID` 101, a light cone
 * carries `tid` 23004, and no name in any language. mihomo did that lookup for us server-side
 * (`?lang=pt`); reading Enka directly means doing it here. StarRailRes is the same source mihomo
 * itself localizes from — same maintainer (Mar-7th), same ids — so the names come out byte-identical
 * to what `/build` printed before the switch, which is what keeps the set-icon join in
 * [com.hsrbot.card.AvaliacaoService] (matched on NAME, not id) working untouched.
 *
 * [characters] is the fallback tier only. `personagem_hsr` via [HsrCharacterService] is the primary
 * source for a character's name/path/element and is richer; this covers the days between a banner
 * dropping and the SRS harvest catching up, where the alternative is a card titled "1414".
 *
 * Keep-last-good on refresh: a failed refresh keeps the last good table, and each of
 * the three files is kept independently so one 404 can't blank the other two.
 */
@Component
class StarRailResNames(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** Name plus the two taxonomy fields, already canonicalized to PT. */
    data class Personagem(val nome: String, val caminho: String?, val elemento: String?)

    @Volatile
    private var relicSets: Map<String, String> = emptyMap()

    @Volatile
    private var lightCones: Map<String, String> = emptyMap()

    @Volatile
    private var characters: Map<String, Personagem> = emptyMap()

    /** Character id → growth per ascension (index = promotion), for [StatPanel]. */
    @Volatile
    private var promocoes: Map<String, List<Map<String, StatPanel.Crescimento>>> = emptyMap()

    /** Trace node id → its properties per level, so an unlocked node contributes what it grants. */
    @Volatile
    private var tracos: Map<String, List<List<StatPanel.Prop>>> = emptyMap()

    /** Relic set id → `[2pc props, 4pc props]`. Read off the same file the names come from. */
    @Volatile
    private var conjuntoProps: Map<String, List<List<StatPanel.Prop>>> = emptyMap()

    /** Light cone id → the passive's stat grant per superimposition (index = rank − 1). */
    @Volatile
    private var conePassivos: Map<String, List<List<StatPanel.Prop>>> = emptyMap()

    /**
     * Whether a showcase can be named at all. [EnkaClient] refuses to build a profile when this is
     * false so the chain falls through to mihomo — a card whose every relic set is blank is worse
     * than a card from the slower source.
     */
    fun ready(): Boolean = relicSets.isNotEmpty() && lightCones.isNotEmpty()

    fun relicSet(id: String): String? = relicSets[id]

    fun lightCone(id: String): String? = lightCones[id]

    fun character(id: String): Personagem? = characters[id]

    internal fun promocoes(characterId: String): List<Map<String, StatPanel.Crescimento>> =
        promocoes[characterId].orEmpty()

    /** What an unlocked trace node grants at [nivel]. Skill-level nodes carry none and sum to nothing. */
    internal fun traco(pointId: String, nivel: Int): List<StatPanel.Prop> =
        tracos[pointId]?.getOrNull(nivel - 1).orEmpty()

    /**
     * What a cone's passive grants flat out at superimposition [rank] — an unconditional
     * "increases Max HP by 30%" is part of the panel exactly like a relic roll is, and skipping it
     * is a big miss on any character running their signature cone.
     */
    internal fun conePassivo(id: String, rank: Int): List<StatPanel.Prop> =
        conePassivos[id]?.getOrNull(rank - 1).orEmpty()

    /** The bonus for wearing [pecas] of set [id]: 4 pieces grants the 2pc AND the 4pc, as in game. */
    internal fun bonusConjunto(id: String, pecas: Int): List<StatPanel.Prop> {
        val niveis = conjuntoProps[id] ?: return emptyList()
        return buildList {
            if (pecas >= 2) addAll(niveis.getOrElse(0) { emptyList() })
            if (pecas >= 4) addAll(niveis.getOrElse(1) { emptyList() })
        }
    }

    /** Loads on startup, then daily — these files change once per patch. */
    @Scheduled(initialDelay = 0, fixedDelay = 24 * 60, timeUnit = TimeUnit.MINUTES)
    @Synchronized
    fun refresh() {
        baixar("relic_sets.json")?.let { node ->
            nomes(node).takeIf { it.isNotEmpty() }?.let { relicSets = it }
            porNivel(node).takeIf { it.isNotEmpty() }?.let { conjuntoProps = it }
        }
        baixar("character_promotions.json")?.let { node ->
            promocoesDe(node).takeIf { it.isNotEmpty() }?.let { promocoes = it }
        }
        baixar("character_skill_trees.json")?.let { node ->
            tracosDe(node).takeIf { it.isNotEmpty() }?.let { tracos = it }
        }
        baixar("light_cone_ranks.json")?.let { node ->
            porNivel(node).takeIf { it.isNotEmpty() }?.let { conePassivos = it }
        }
        baixar("light_cones.json")?.let { node ->
            nomes(node).takeIf { it.isNotEmpty() }?.let { lightCones = it }
        }
        baixar("characters.json")?.let { node ->
            personagens(node).takeIf { it.isNotEmpty() }?.let { characters = it }
        }
        log.info(
            "StarRailRes: {} conjuntos, {} cones, {} personagens, {} promoções, {} traços",
            relicSets.size, lightCones.size, characters.size, promocoes.size, tracos.size,
        )
    }

    /** One index file, or null when it couldn't be read — the caller keeps its previous table. */
    private fun baixar(arquivo: String): JsonNode? = try {
        val url = properties.knowledge.starRailResBase.trimEnd('/') + "/pt/" + arquivo
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Mozilla/5.0 (compatible; HsrBot/1.0; +discord)")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            log.warn("StarRailRes: HTTP {} em {} — mantendo a tabela anterior", resp.statusCode(), arquivo)
            null
        } else {
            mapper.readTree(resp.body())
        }
    } catch (e: Exception) {
        log.warn("StarRailRes: falha ao ler {} — mantendo a tabela anterior: {}", arquivo, e.message)
        null
    }

    internal companion object {

        /**
         * StarRailRes spells a path with the game's internal codename ("Knight"), not the English
         * one [HsrTaxonomy] knows ("preservation"). Kept private rather than added to
         * [HsrTaxonomy.PATHS] on purpose: those aliases are also matched whole-word against free
         * user text for roster filters, and "memory"/"mage"/"rogue" firing a path filter on an
         * ordinary sentence is a worse bug than the one this fixes.
         */
        private val CODINOMES = mapOf(
            "Knight" to "A Preservação", "Rogue" to "A Caça", "Warlock" to "A Inexistência",
            "Mage" to "A Erudição", "Priest" to "A Abundância", "Shaman" to "A Harmonia",
            "Warrior" to "A Destruição", "Memory" to "A Recordação",
        )

        /** `{"101": {"id": "101", "name": "..."}}` → `{"101": "..."}`. Pure. */
        internal fun nomes(root: JsonNode): Map<String, String> = buildMap {
            root.fields().forEach { (id, node) ->
                node.path("name").asText("").takeIf { it.isNotBlank() }?.let { put(id, it) }
            }
        }

        /** `[{"type": "...", "value": 0.16}]` → props, skipping anything unvalued. Pure. */
        private fun props(node: JsonNode): List<StatPanel.Prop> = node.mapNotNull {
            val tipo = it.path("type").asText("")
            if (tipo.isBlank()) null else StatPanel.Prop(tipo, it.path("value").asDouble(0.0))
        }

        /**
         * `id → properties[tier]`, the shape `relic_sets.json` and `light_cone_ranks.json` share:
         * `[[2pc], [4pc]]` for a set, one entry per superimposition for a cone.
         *
         * A CONDITIONAL bonus is an empty list there — Watchmaker's 4pc only fires after an ultimate,
         * Arcadia's whole effect depends on team size — and that is exactly right: the game's own
         * panel doesn't count those either, so the empty list needs no special case. Pure.
         */
        internal fun porNivel(root: JsonNode): Map<String, List<List<StatPanel.Prop>>> = buildMap {
            root.fields().forEach { (id, node) ->
                put(id, node.path("properties").map { props(it) })
            }
        }

        /** `values[promotion][stat] = {base, step}` → the same, indexed by ascension. Pure. */
        internal fun promocoesDe(root: JsonNode): Map<String, List<Map<String, StatPanel.Crescimento>>> =
            buildMap {
                root.fields().forEach { (id, node) ->
                    put(
                        id,
                        node.path("values").map { valores ->
                            buildMap {
                                valores.fields().forEach { (stat, v) ->
                                    put(
                                        stat,
                                        StatPanel.Crescimento(
                                            base = v.path("base").asDouble(0.0),
                                            step = v.path("step").asDouble(0.0),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
            }

        /**
         * Trace node → its properties at each level. A skill-level-up node carries none, so it lands
         * as an empty list and contributes nothing to the panel — no need to tell the two apart. Pure.
         */
        internal fun tracosDe(root: JsonNode): Map<String, List<List<StatPanel.Prop>>> = buildMap {
            root.fields().forEach { (id, node) ->
                put(id, node.path("levels").map { props(it.path("properties")) })
            }
        }

        /** Same shape, plus the taxonomy pair canonicalized to PT. Pure. */
        internal fun personagens(root: JsonNode): Map<String, Personagem> = buildMap {
            root.fields().forEach { (id, node) ->
                val nome = node.path("name").asText("")
                if (nome.isBlank()) return@forEach
                val bruto = node.path("path").asText("")
                put(
                    id,
                    Personagem(
                        nome = nome,
                        caminho = CODINOMES[bruto] ?: HsrTaxonomy.canonicalPath(bruto),
                        elemento = HsrTaxonomy.canonicalElement(node.path("element").asText("")),
                    ),
                )
            }
        }
    }
}
