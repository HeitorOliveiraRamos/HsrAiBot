package com.hsrbot.card

import com.hsrbot.hsr.BuildAnalyzer
import com.hsrbot.hsr.ShowcaseCharacter
import com.hsrbot.hsr.ShowcaseStat
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Turns a showcased character plus its [BuildAnalyzer.BuildReport] into the card [AvaliacaoRenderer]
 * draws — the read side of `/build`.
 *
 * The numbers all arrive already computed; the database is consulted for one thing only, art and
 * icons, which is why every lookup here is optional. mihomo alone carries the name, level, element,
 * path and every stat, so a character `personagem_hsr` has never heard of (a fresh banner) still
 * gets a complete card, just without the illustration. That is the opposite of [AscensaoService],
 * where the database IS the content.
 *
 * Set icons are matched by *name*, since a showcase says "Poetisa do Colapso Enlutada" and the V17
 * tables are keyed on exactly that string in the same language. A rename therefore costs an icon and
 * nothing else — the row keeps its name, its score and its advice.
 */
@Component
class AvaliacaoService(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun cartao(
        personagem: ShowcaseCharacter,
        report: BuildAnalyzer.BuildReport,
        regua: String,
        jogador: String?,
        uid: String?,
    ): Avaliacao {
        val p = linha(personagem.id)
        // v2 (the whole illustration with its scenery) whenever a focus box exists, same as /guia.
        val base = p?.let { fichaBase(it, personagem.name, v2 = true) }
        val icones = icones(personagem)

        val notas = report.relics.associateBy { it.slot }
        val conjunto = personagem.relics.associate { it.slot to it.setName }
        return Avaliacao(
            nome = base?.nome ?: personagem.name,
            elemento = base?.elemento ?: personagem.elementName,
            caminho = base?.caminho ?: personagem.pathName,
            raridade = base?.raridade ?: 5,
            arte = base?.arte ?: Arte(),
            nivel = personagem.level,
            eidolon = personagem.eidolon,
            nota = report.totalScore,
            rank = report.totalRank,
            regua = regua,
            jogador = jogador,
            uid = uid,
            cone = personagem.lightCone?.let {
                Cone(it.name, icone = cone(it.name), sobreposicao = it.superimposition)
            },
            conjuntos = personagem.relicSets.map { Parte(it.name, icones[it.name.lowercase()]?.get("icone"), it.pieces) },
            stats = stats(personagem.stats),
            pecas = (1..6).map { slot ->
                val r = notas[slot] ?: return@map Peca(slot, BuildAnalyzer.SLOT_NAMES[slot].orEmpty(), vazio = true)
                Peca(
                    slot = slot,
                    nome = BuildAnalyzer.SLOT_NAMES[slot].orEmpty(),
                    nivel = r.level,
                    main = r.mainName,
                    nota = r.score,
                    rank = r.rank,
                    icone = icones[conjunto[slot]?.lowercase()]?.get(PECA_ICONE[slot]),
                    // Slots 1-2 have fixed mains, so only 3-6 can carry a wrong one — and only when
                    // the ruler actually knows this slot's ideal mains.
                    mainErrado = slot >= 3 && r.mainWeight == 0.0,
                    subs = r.subs.map {
                        Sub(BuildAnalyzer.statCurto(it.prop), it.valor, it.melhorias, it.util)
                    },
                )
            },
        )
    }

    /**
     * The eight stats the panel shows: the six the game always lists, then whatever the build adds
     * that has no base row of its own — the elemental damage bonus, effect hit rate — in mihomo's
     * order. Two of those fit, and they are the two that distinguish one build of a character from
     * another.
     */
    private fun stats(stats: List<ShowcaseStat>): List<Stat> {
        val (base, extras) = stats.partition { it.field in STAT_BASE }
        return (base + extras.take(STATS_NO_CARD - base.size)).map {
            // mihomo labels the base rows "PV Base"/"ATQ Base" because that is what its `attributes`
            // block holds; the card shows the total, so the word would be a lie.
            Stat(STAT_PT[it.field] ?: it.name.removeSuffix(" Base").uppercase(), it.display)
        }
    }

    private fun linha(characterId: String): Map<String, Any?>? = try {
        // Bound as Int like AscensaoService: `character_id` is INTEGER and PgJDBC types a String
        // param as varchar, which Postgres refuses outright.
        characterId.toIntOrNull()?.let {
            jdbc.queryForList("SELECT * FROM personagem_hsr WHERE character_id = ? LIMIT 1", it).firstOrNull()
        }
    } catch (e: Exception) {
        log.warn("arte de {} indisponível: {}", characterId, e.message)
        null
    }

    /** Lower-cased set name → its row, from both tables at once: a showcase mixes relics and ornaments freely. */
    private fun icones(personagem: ShowcaseCharacter): Map<String, Map<String, String?>> {
        val nomes = (personagem.relics.map { it.setName } + personagem.relicSets.map { it.name })
            .filter { it.isNotBlank() }
        if (nomes.isEmpty()) return emptyMap()
        return try {
            // Case-fold in the JVM, never in SQL. This database's C locale makes Postgres lower()
            // ASCII-only, so `lower(nome)` leaves an accented capital untouched ("Águas" -> "Águas")
            // and never matches a JVM-lower-cased name — the piece icon then silently vanishes. Both
            // tables are tiny, so fetch them whole and key by the JVM's lower-case.
            jdbc.queryForList(
                "SELECT nome, icone, cabeca_icone, maos_icone, corpo_icone, pes_icone, " +
                    "NULL AS esfera_icone, NULL AS corda_icone FROM reliquias " +
                    "UNION ALL SELECT nome, icone, NULL, NULL, NULL, NULL, esfera_icone, corda_icone " +
                    "FROM ornamentos_planos",
            ).associate { l -> str(l["nome"]).orEmpty().lowercase() to l.mapValues { (_, v) -> str(v) } }
        } catch (e: Exception) {
            log.warn("ícones de conjunto indisponíveis: {}", e.message)
            emptyMap()
        }
    }

    private fun cone(nome: String): String? = try {
        jdbc.queryForList("SELECT icone FROM cones_de_luz WHERE lower(nome) = lower(?) LIMIT 1", nome)
            .firstOrNull()?.let { str(it["icone"]) }
    } catch (e: Exception) {
        log.warn("ícone do cone '{}' indisponível: {}", nome, e.message)
        null
    }

    private companion object {

        /** Slot → the per-piece icon column. Slots 1–4 are relics, 5–6 ornaments. */
        val PECA_ICONE = mapOf(
            1 to "cabeca_icone", 2 to "maos_icone", 3 to "corpo_icone",
            4 to "pes_icone", 5 to "esfera_icone", 6 to "corda_icone",
        )

        /** Stats every character has, in the order the game's own panel lists them. */
        val STAT_BASE = listOf("hp", "atk", "def", "spd", "crit_rate", "crit_dmg")

        const val STATS_NO_CARD = 8

        /**
         * Short labels, because the panel gives each stat half a column. mihomo's own names are the
         * fallback and are correct, just long ("Bônus de Dano Quântico").
         */
        val STAT_PT = mapOf(
            "hp" to "PV", "atk" to "ATQ", "def" to "DEF", "spd" to "VEL",
            "crit_rate" to "CRIT", "crit_dmg" to "DANO CRIT",
            "effect_hit" to "EFEITO", "effect_res" to "RES EFEITO",
            "break_dmg" to "QUEBRA", "sp_rate" to "ENERGIA", "heal_ratio" to "CURA",
            "physical_dmg" to "DANO FÍS", "fire_dmg" to "DANO FOGO", "ice_dmg" to "DANO GELO",
            "lightning_dmg" to "DANO RAIO", "wind_dmg" to "DANO VENTO",
            "quantum_dmg" to "DANO QUÂNT", "imaginary_dmg" to "DANO IMAG",
        )
    }
}
