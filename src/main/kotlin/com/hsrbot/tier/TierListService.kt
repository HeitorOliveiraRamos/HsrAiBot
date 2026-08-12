package com.hsrbot.tier

import com.hsrbot.card.Avatar
import com.hsrbot.card.Coluna
import com.hsrbot.card.Mudanca
import com.hsrbot.card.TierList
import com.hsrbot.card.TierRenderer
import com.hsrbot.card.norm
import com.hsrbot.card.str
import com.hsrbot.hsr.HsrCharacterService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The lifecycle of a user-written tier list: the draft the wizard fills in, the previous published
 * list it is seeded from, the author's corrections to who moved, and the rendered PNG.
 *
 * The seed is the whole reason a second list is cheap. The first one is ~97 characters placed by
 * hand; every one after it opens as a copy of the author's last published list for the same mode, so
 * the work becomes "move the handful that changed" — and the image then MARKS what moved, which is
 * only knowable because the previous list is still a row.
 *
 * Nothing here is fail-open: the database holds the wizard's state, so a failed write has to surface
 * rather than silently drop what the author placed.
 */
@Service
class TierListService(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val personagens: HsrCharacterService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The author's open draft for this mode, resumed if it exists and created from their previous
     * published list if it does not.
     *
     * [versao] and [anonimo] come from the slash command's options and are applied on every run, so
     * re-running `/tierlist` is also how they get changed — the draft resumes either way, which
     * means fixing the version never costs a placement.
     */
    fun rascunho(autorId: String, modo: String, versao: String?, anonimo: Boolean?): Rascunho {
        if (rascunhoAberto(autorId, modo) == null) {
            val anterior = anterior(autorId, modo)
            jdbc.update(
                "INSERT INTO tier_list (chave, autor_id, modo, versao, anonimo, grade, ajustes) " +
                    "VALUES (?, ?, ?, ?, ?, ?::jsonb, '{}'::jsonb) ON CONFLICT DO NOTHING",
                UUID.randomUUID().toString().take(8), autorId, modo, versao, anonimo ?: false,
                json(anterior?.grade ?: emptyMap<String, List<List<String>>>()),
            )
        }
        // Re-read rather than trusting the insert: a double-clicked command races here, and the
        // partial unique index means exactly one of the two rows survives it.
        val r = rascunhoAberto(autorId, modo)
            ?: error("rascunho de tier list não materializou para $autorId/$modo")
        if (versao == null && anonimo == null) return r
        jdbc.update(
            "UPDATE tier_list SET versao = COALESCE(?, versao), anonimo = COALESCE(?, anonimo), " +
                "atualizado_em = now() WHERE chave = ? AND publicado_em IS NULL",
            versao, anonimo, r.chave,
        )
        return rascunhoAberto(autorId, modo) ?: r
    }

    fun carregar(chave: String): Rascunho? = umaLista("WHERE chave = ?", chave)

    /** The author's open draft for a mode, or null when they have none. */
    fun rascunhoAberto(autorId: String, modo: String): Rascunho? =
        umaLista("WHERE autor_id = ? AND modo = ? AND publicado_em IS NULL", autorId, modo)

    /** The author's most recent published list for this mode — the seed, and the diff's baseline. */
    fun anterior(autorId: String, modo: String): Rascunho? = umaLista(
        "WHERE autor_id = ? AND modo = ? AND publicado_em IS NOT NULL ORDER BY publicado_em DESC LIMIT 1",
        autorId, modo,
    )

    /** Saves the wizard's progress. A published list is final, so this is a no-op for one. */
    fun salvar(chave: String, grade: Grade) {
        jdbc.update(
            "UPDATE tier_list SET grade = ?::jsonb, atualizado_em = now() " +
                "WHERE chave = ? AND publicado_em IS NULL",
            json(grade), chave,
        )
    }

    /**
     * Replaces one papel's markers, or hands the column back to the automatic diff when [manual] is
     * null. Nothing else in the object is touched, so the other three columns keep whatever mode
     * they were in.
     */
    fun salvarAjustes(chave: String, papel: Papel, manual: MudancasSpec?) {
        val atual = carregar(chave)?.ajustes ?: return
        val novo = if (manual == null) atual - papel.chave else atual + (papel.chave to manual)
        jdbc.update(
            "UPDATE tier_list SET ajustes = ?::jsonb, atualizado_em = now() " +
                "WHERE chave = ? AND publicado_em IS NULL",
            json(novo), chave,
        )
    }

    fun publicar(chave: String) {
        jdbc.update("UPDATE tier_list SET publicado_em = now() WHERE chave = ? AND publicado_em IS NULL", chave)
    }

    /**
     * Someone else's published list, copied into the caller's own draft — grade, adjustments and
     * game version, which together are everything an opinion consists of.
     *
     * Overwrites their open draft for that mode, because the unique index allows exactly one. The
     * caller is responsible for confirming that with the author first when the draft has anything
     * in it; this method just does what it is told.
     */
    fun copiar(autorId: String, origem: Rascunho): Rascunho {
        val meu = rascunhoAberto(autorId, origem.modo)
        if (meu == null) {
            jdbc.update(
                "INSERT INTO tier_list (chave, autor_id, modo, versao, anonimo, grade, ajustes) " +
                    "VALUES (?, ?, ?, ?, false, ?::jsonb, ?::jsonb) ON CONFLICT DO NOTHING",
                UUID.randomUUID().toString().take(8), autorId, origem.modo, origem.versao,
                json(origem.grade), json(origem.ajustes),
            )
        } else {
            // anonimo is deliberately NOT copied: it is a fact about the person publishing, not
            // about the list, and inheriting a stranger's choice would decide it for them.
            jdbc.update(
                "UPDATE tier_list SET grade = ?::jsonb, ajustes = ?::jsonb, versao = ?, " +
                    "atualizado_em = now() WHERE chave = ? AND publicado_em IS NULL",
                json(origem.grade), json(origem.ajustes), origem.versao, meu.chave,
            )
        }
        return rascunhoAberto(autorId, origem.modo)
            ?: error("cópia de tier list não materializou para $autorId/${origem.modo}")
    }

    /**
     * Characters the roster has and this list places nowhere.
     *
     * Only worth showing on a SEEDED draft, where it means "released or forgotten since your last
     * list". On an empty one it is the whole roster, which is not news.
     */
    fun naoColocadas(grade: Grade): List<String> {
        val colocadas = grade.todos()
        return personagens.all().map { it.id }
            .filter { it !in colocadas }
            .map { personagens.displayName(it) }
            .sortedBy { norm(it) }
    }

    // -------------------- mudanças -------------------- //

    /**
     * What one column's markers currently are: the author's answer if they gave one, and the diff
     * against their previous list otherwise.
     *
     * This is what the Ajustes form opens prefilled with, which is the point — the author starts
     * from what the list worked out on its own and edits it, rather than from an empty box that
     * makes them retype what was already right.
     */
    fun mudancasDe(r: Rascunho, papel: Papel): MudancasSpec =
        r.ajustes.semRestos(r.grade)[papel.chave] ?: automatico(baseDoDiff(r), r.grade, papel)

    /** True when this column is the author's own answer rather than the computed one. */
    fun manual(r: Rascunho, papel: Papel): Boolean = papel.chave in r.ajustes

    /**
     * The grade the diff is measured against: the author's previous published list for this mode.
     *
     * A list compared to itself would mark nothing, so a published list being re-rendered skips
     * itself and looks one further back.
     */
    private fun baseDoDiff(r: Rascunho): Grade? =
        anterior(r.autorId, r.modo)?.takeIf { it.chave != r.chave }?.grade

    // -------------------- render -------------------- //

    /**
     * The list as an image. [autorNome] is passed in rather than stored: it is the author's current
     * Discord name, the bot has it on the interaction, and a list nobody has published has no
     * business keeping a copy of it. An anonymous list drops it here and nowhere else.
     */
    fun png(chave: String, autorNome: String?): ByteArray? = carregar(chave)?.let { r ->
        TierRenderer.png(resolver(r, autorNome))
    }

    /**
     * A stored grade plus everything the image needs to draw it: display names, avatar hashes,
     * elements and each character's marker.
     *
     * A character the roster no longer has is dropped rather than drawn nameless — the list is a
     * snapshot of opinions about characters that exist, and a removed row means the id no longer
     * names anyone.
     */
    internal fun resolver(r: Rascunho, autorNome: String?): TierList {
        val anterior = baseDoDiff(r)
        val ajustes = r.ajustes.semRestos(r.grade)
        val linhas = personagemPorId(r.grade.todos())
        return TierList(
            modo = MODOS[r.modo] ?: r.modo,
            versao = r.versao,
            autor = autorNome?.takeIf { !r.anonimo },
            colunas = Papel.entries.map { papel ->
                // Resolved once per column, not once per character: the automatic diff walks the
                // whole column, and doing that ~100 times would be the same answer every time.
                val mudancas = ajustes[papel.chave] ?: automatico(anterior, r.grade, papel)
                Coluna(
                    rotulo = papel.rotulo,
                    tiers = (0 until TIERS.size).map { tier ->
                        r.grade.celula(papel, tier)
                            .mapNotNull { id ->
                                val p = linhas[id] ?: return@mapNotNull null
                                Avatar(
                                    nome = personagens.displayName(id),
                                    icone = str(p["icone_mini"]),
                                    elemento = str(p["elemento"]),
                                    mudanca = mudancas.de(id),
                                )
                            }
                            // Sorted here and not stored: a cell is a set, and drawing it in the
                            // order the selects happened to report would move faces around between
                            // two renders of the same list.
                            .sortedBy { norm(it.nome) }
                    },
                )
            },
        )
    }

    // -------------------- nomes -------------------- //

    /**
     * A typed line of names → the ids meant, plus a complaint for each that named no single
     * character.
     *
     * Goes through the same resolver as everything else in the bot, so "RMC" and "Desbravadora
     * (Recordação)" both work and a bare "Desbravadora" is refused rather than picking one of five
     * at random. Names that resolve to someone NOT in this column are kept here and dropped at
     * render by [Ajustes.semRestos] — telling the author "she is not in Suporte" is the form's job,
     * not the parser's.
     */
    fun resolverNomes(linha: String): Pair<List<String>, List<String>> {
        val ids = mutableListOf<String>()
        val erros = mutableListOf<String>()
        linha.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { nome ->
            val id = personagens.resolveId(nome)
            if (id == null) erros += nome else ids += id
        }
        return ids.distinct() to erros
    }

    /** Ids back to the comma-separated line the Ajustes form shows. */
    fun nomesDe(ids: List<String>): String =
        ids.map { personagens.displayName(it) }.sortedBy { norm(it) }.joinToString(", ")

    // -------------------- plumbing -------------------- //

    private fun umaLista(where: String, vararg args: Any?): Rascunho? = jdbc
        .queryForList("$SELECT $where", *args)
        .firstOrNull()
        ?.let {
            Rascunho(
                chave = str(it["chave"])!!,
                autorId = str(it["autor_id"])!!,
                modo = str(it["modo"])!!,
                versao = str(it["versao"]),
                anonimo = it["anonimo"] as? Boolean ?: false,
                grade = grade(it["grade"]),
                ajustes = ajustes(it["ajustes"]),
                publicada = it["publicado_em"] != null,
            )
        }

    /** Rows keyed by `character_id` — the one key that tells the five Desbravadoras apart. */
    private fun personagemPorId(ids: Set<String>): Map<String, Map<String, Any?>> {
        val numericos = ids.mapNotNull { it.toIntOrNull() }
        if (numericos.isEmpty()) return emptyMap()
        return jdbc.queryForList(
            "SELECT character_id, icone_mini, elemento FROM personagem_hsr WHERE character_id IN " +
                "(${numericos.joinToString(",") { "?" }})",
            *numericos.toTypedArray(),
        ).associateBy { it["character_id"].toString() }
    }

    private fun json(valor: Any): String = mapper.writeValueAsString(valor)

    private fun grade(value: Any?): Grade = leia(value, GRADE) ?: emptyMap()

    private fun ajustes(value: Any?): Ajustes = leia(value, AJUSTES) ?: emptyMap()

    /** A column that will not parse is treated as empty: a bad JSON blob must not hide a whole list. */
    private fun <T> leia(value: Any?, tipo: TypeReference<T>): T? =
        value?.let {
            runCatching { mapper.readValue(it.toString(), tipo) }.getOrElse { e ->
                log.warn("coluna jsonb ilegível, tratando como vazia: {}", e.message)
                null
            }
        }

    companion object {
        private const val SELECT =
            "SELECT chave, autor_id, modo, versao, anonimo, grade, ajustes, publicado_em FROM tier_list"

        private val GRADE = object : TypeReference<Map<String, List<List<String>>>>() {}
        private val AJUSTES = object : TypeReference<Map<String, MudancasSpec>>() {}

        /** The three endgames, as the command's choices and as the image's title. */
        val MODOS = linkedMapOf(
            "moc" to "Memória do Caos",
            "pf" to "Pura Ficção",
            "as" to "Sombra Apocalíptica",
        )
    }
}

/**
 * `"Memória do Caos (4.4)"` — the one place a list's name is spelled, so the wizard header, the
 * published message and the image cannot drift apart.
 */
fun titulo(r: Rascunho): String =
    (TierListService.MODOS[r.modo] ?: r.modo) + r.versao?.let { " ($it)" }.orEmpty()

/** A tier list being filled in, or a published one read back to seed or copy. */
data class Rascunho(
    val chave: String,
    val autorId: String,
    val modo: String,
    val versao: String?,
    val anonimo: Boolean,
    val grade: Grade,
    val ajustes: Ajustes = emptyMap(),
    val publicada: Boolean = false,
)
