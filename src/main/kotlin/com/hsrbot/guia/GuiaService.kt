package com.hsrbot.guia

import com.hsrbot.card.CardRenderer
import com.hsrbot.card.Cone
import com.hsrbot.card.Enquadramento
import com.hsrbot.card.Ficha
import com.hsrbot.card.Foco
import com.hsrbot.card.Linha
import com.hsrbot.card.Meta
import com.hsrbot.card.Parte
import com.hsrbot.card.RASTRO_ICONE
import com.hsrbot.card.Sinergia
import com.hsrbot.card.Status
import com.hsrbot.card.fichaBase
import com.hsrbot.card.normalizarArte
import com.hsrbot.card.rastro
import com.hsrbot.card.str
import com.hsrbot.hsr.HsrCharacterService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * The lifecycle of a user-written guide: a draft the wizard fills in, the hash that decides whether
 * its card already exists, and the rendered PNG.
 *
 * Reuse is the whole point of the hash: two authors who answer identically get the same guide row
 * and the same cached image, and re-opening a finished guide never re-renders. A finished guide is
 * immutable — its hash IS its identity, so [salvar] only touches drafts.
 *
 * Nothing here is fail-open: the database holds the wizard's state, so a failed write must surface
 * as an error rather than silently drop what the author typed. Only the PNG cache is best-effort,
 * since a card re-renders deterministically from its spec.
 */
@Service
class GuiaService(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val personagens: HsrCharacterService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The author's open draft for this character, resumed if it exists and created if it doesn't. */
    fun rascunho(autorId: String, personagemId: Int): Rascunho? {
        val pk = pkDe(personagemId) ?: return null
        rascunhoDe(autorId, pk)?.let { return it }
        jdbc.update(
            "INSERT INTO guia (chave, id_personagem_hsr, autor_id, spec) VALUES (?, ?, ?, ?::jsonb) " +
                "ON CONFLICT DO NOTHING",
            UUID.randomUUID().toString().take(8), pk, autorId, json(specInicial(personagemId, pk)),
        )
        // Re-read rather than trusting the insert: a double-clicked command races here, and the
        // partial unique index means exactly one of the two rows exists afterwards.
        return rascunhoDe(autorId, pk)
    }

    fun carregar(chave: String): Rascunho? =
        jdbc.queryForList("SELECT chave, spec, hash FROM guia WHERE chave = ?", chave)
            .firstOrNull()
            ?.let { Rascunho(str(it["chave"])!!, spec(it["spec"]), str(it["hash"])) }

    /** Saves the wizard's progress. A finished guide is immutable, so this is a no-op for one. */
    fun salvar(chave: String, spec: GuiaSpec) {
        jdbc.update(
            "UPDATE guia SET spec = ?::jsonb, atualizado_em = now() WHERE chave = ? AND hash IS NULL",
            json(spec), chave,
        )
    }

    /**
     * The draft to send someone back into when they ask to adjust a guide. A finished guide is
     * immutable — its hash is its identity and a rendered card is cached under it — so adjusting one
     * means starting from a COPY. Without this the wizard would happily redraw a finished guide and
     * drop every edit on the floor, since [salvar] ignores it.
     */
    fun editar(chave: String, autorId: String): Rascunho? {
        val atual = carregar(chave) ?: return null
        if (atual.hash == null) return atual
        val pk = pkDe(atual.spec.personagemId) ?: return null
        // Their earlier draft for this character, if any, is what the unique index would collide on.
        rascunhoDe(autorId, pk)?.let {
            salvar(it.chave, atual.spec)
            return carregar(it.chave)
        }
        val nova = UUID.randomUUID().toString().take(8)
        jdbc.update(
            "INSERT INTO guia (chave, id_personagem_hsr, autor_id, spec) VALUES (?, ?, ?, ?::jsonb) " +
                "ON CONFLICT DO NOTHING",
            nova, pk, autorId, json(atual.spec),
        )
        return carregar(nova) ?: rascunhoDe(autorId, pk)
    }

    /**
     * Finishes the draft and returns its card. If an identical guide already exists, the draft is
     * dropped and that one is handed back instead — same answers, same card, no second render.
     */
    fun cartao(chave: String): Cartao? {
        val r = carregar(chave) ?: return null
        val hash = hash(r.spec)
        val existente = jdbc.queryForList("SELECT chave FROM guia WHERE hash = ?", String::class.java, hash)
            .firstOrNull()
        val reaproveitada = existente != null && existente != chave
        when {
            reaproveitada -> jdbc.update("DELETE FROM guia WHERE chave = ? AND hash IS NULL", chave)
            existente == null ->
                jdbc.update("UPDATE guia SET hash = ?, atualizado_em = now() WHERE chave = ?", hash, chave)
        }
        val png = png(hash, r.spec) ?: return null
        return Cartao(existente ?: chave, hash, png, reaproveitada)
    }

    /**
     * The card for the CURRENT draft, drawn without finishing it — no hash, no dedup, no disk cache.
     *
     * [cartao] is the finishing move: it writes the hash that freezes a guide and may drop the draft
     * as a duplicate of an existing one. The framing pad calls THIS instead, once per nudge, so it
     * has to leave a draft a draft and simply render what the spec says at this moment.
     */
    fun previa(chave: String): ByteArray? =
        carregar(chave)?.spec?.let { resolver(it) }?.let { CardRenderer.png(it) }

    fun publicar(chave: String) {
        jdbc.update("UPDATE guia SET publicado_em = now() WHERE chave = ?", chave)
    }

    /**
     * Attaches an uploaded image to a draft, or explains why it could not be. The bytes are
     * normalised BEFORE anything is stored ([normalizarArte] caps and re-encodes them), so nothing
     * a stranger uploaded is ever handed back to the renderer in the shape it arrived in.
     *
     * Keyed by the sha256 of the STORED bytes, so the same picture uploaded twice is one row — and
     * the same picture uploaded by two authors is one row and one guide-level identity.
     */
    fun anexarArte(chave: String, bytes: ByteArray, autor: String?): String? {
        val normalizada = normalizarArte(bytes)
            ?: return "Não consegui abrir essa imagem — manda um PNG ou JPG de verdade, e nada gigante."
        val hash = sha256(normalizada)
        jdbc.update(
            "INSERT INTO guia_arte (hash, bytes) VALUES (?, ?) ON CONFLICT (hash) DO NOTHING",
            hash, normalizada,
        )
        val spec = carregar(chave)?.spec ?: return "Essa guia não existe mais."
        // A fresh picture is a fresh framing problem: the box that placed the old art means nothing
        // on a new one. Rather than dump the author at the whole image, open on the character — the
        // same auto-frame every uploaded card starts from, so a clean upload needs no nudging.
        val foco = runCatching { ImageIO.read(ByteArrayInputStream(normalizada)) }.getOrNull()
            ?.let { Enquadramento.auto(it) }?.let { FocoSpec(it.x, it.y, it.w, it.h) }
        salvar(chave, spec.copy(arte = hash, foco = foco, arteAutor = autor))
        return null
    }

    /**
     * Drops the uploaded art, and the framing that only made sense with it.
     *
     * The `guia_arte` row stays for now — another guide may share the hash, and the author is one
     * click from putting it back. [limparArte] is what actually collects it.
     */
    fun removerArte(chave: String) {
        carregar(chave)?.spec?.let { salvar(chave, it.copy(arte = null, foco = null, arteAutor = null)) }
    }

    /**
     * Collects uploaded art nothing live still points at.
     *
     * An upload is the one thing in this schema that regenerates from nothing (V26), so it is kept
     * — but only while it can still be USED. Once a guide is published its card is an attachment
     * Discord hosts forever, the publish handler clears the wizard's buttons, and re-running
     * `/guia` opens a fresh draft rather than resuming it: there is no route back to those bytes.
     * At ~1.3 MB an upload they'd otherwise sit in the table and in all 14 nightly dumps forever.
     *
     * The trigger is "no live spec mentions this hash", NOT "published" — deleting at publish time
     * looks equivalent and isn't. Every wizard nudge goes through [editar], which clones a finished
     * guide into a NEW draft carrying the same hash, and two authors who upload the same picture
     * share one row by content hash. Either case leaves a live guide pointing at bytes that publish
     * would have taken. Asking the specs is the reference count, without keeping one.
     *
     * [JANELA_ARTE_DIAS] is the grace period, measured on `atualizado_em`: a guide touched inside
     * it is still being worked on. Missing bytes are not a failure anyway — [arteEnviada] warns and
     * the card falls back to the official illustration — so the cost of cutting one early is a
     * card that looks like it never had an upload, not a broken guide.
     */
    @Scheduled(initialDelay = 10, fixedDelay = 24 * 60, timeUnit = TimeUnit.MINUTES)
    fun limparArte() {
        val apagadas = jdbc.update(
            "DELETE FROM guia_arte a WHERE NOT EXISTS (" +
                "SELECT 1 FROM guia g WHERE g.spec->>'arte' = a.hash " +
                "AND g.atualizado_em > now() - make_interval(days => ?))",
            JANELA_ARTE_DIAS,
        )
        if (apagadas > 0) log.info("arte enviada: {} imagem(ns) sem guia viva apagada(s)", apagadas)
    }

    /**
     * The uploaded image as a file the renderer can open, materialised from `guia_arte` on first
     * use. Disk is a pure cache here — the row is the durable copy — so a wiped `target/` costs one
     * re-read and nothing else.
     */
    private fun arteEnviada(hash: String): Path? {
        val file = ARTE.resolve("$hash.png")
        if (Files.exists(file)) return file
        val bytes = jdbc.queryForList("SELECT bytes FROM guia_arte WHERE hash = ?", ByteArray::class.java, hash)
            .firstOrNull() ?: return null.also { log.warn("arte {} sumiu do banco", hash.take(8)) }
        return runCatching { Files.write(file, bytes) }.getOrElse {
            log.warn("não consegui gravar a arte {}: {}", hash.take(8), it.message)
            null
        }
    }

    fun nomePersonagem(personagemId: Int): String? = jdbc
        .queryForList("SELECT nome FROM personagem_hsr WHERE character_id = ?", String::class.java, personagemId)
        .firstOrNull()

    /**
     * A draft opens as a copy of the curated `builds` recommendation, so the wizard asks the author
     * to CONFIRM twelve answers instead of typing them. Nothing here is authoritative — every field
     * is overwritten the moment they touch it — and a character without a curated row simply starts
     * empty.
     */
    private fun specInicial(personagemId: Int, pk: Int): GuiaSpec {
        val p = jdbc.queryForList("SELECT * FROM personagem_hsr WHERE id_personagem_hsr = ?", pk).firstOrNull()
            ?: return GuiaSpec(personagemId)
        // The reference guides all lead with the talent, and the card's row holds four — so the
        // euphoria and memosprite abilities are offered but never seeded. The author puts one in
        // from the trace selects, which is where the order is decided anyway.
        val rastros = RASTRO_ICONE.filterValues { p[it] != null }.keys
            .take(GuiaWizard.MAX_RASTROS)
            .map { RastroSpec(it) }

        val b = jdbc.queryForList("SELECT * FROM builds WHERE id_personagem_hsr = ? LIMIT 1", pk).firstOrNull()
            ?: return GuiaSpec(personagemId, rastros = rastros)

        fun nomes(tabela: String, coluna: String, prefixo: String) = (1..3).mapNotNull { i ->
            b["$prefixo$i"]?.let {
                jdbc.queryForList("SELECT nome FROM $tabela WHERE $coluna = ?", String::class.java, it).firstOrNull()
            }
        }
        val eu = str(p["nome"])
        return GuiaSpec(
            personagemId = personagemId,
            rastros = rastros,
            // The curated row says WHICH cone, never at what rank, so each one opens at the rank
            // most people own it at — S5 for a 4★, S1 for a 5★.
            cones = nomes("cones_de_luz", "id_cone_de_luz", "id_cone_de_luz").map { ConeSpec(it, sobreposicao(it)) },
            reliquias = nomes("reliquias", "id_reliquia", "id_reliquia").map { LinhaSpec(listOf(ParteSpec(it, 4))) },
            ornamentos = nomes("ornamentos_planos", "id_ornamento_plano", "id_ornamento_plano")
                .map { LinhaSpec(listOf(ParteSpec(it, 2))) },
            corpo = str(b["main_stat_corpo"]), pes = str(b["main_stat_pes"]),
            esfera = str(b["main_stat_esfera"]), corda = str(b["main_stat_corda"]),
            // Curated substats are a bare priority order — the targets are what the author adds.
            metas = (b["substatus_recomendados"] as? String).orEmpty()
                .split(">", ",").map { it.trim() }.filter { it.isNotEmpty() }
                .take(GuiaFormulario.MAX_METAS).map { MetaSpec(it) },
            // Canonicalised here and not at render time: what the modal shows the author has to be
            // what the card will draw, and a curated "Desbravadora" names five different people.
            sinergias = (b["equipe_recomendada"] as? String).orEmpty()
                .split(",").map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(eu, ignoreCase = true) }
                .mapNotNull { personagens.canonicalName(it) }
                .take(GuiaFormulario.MAX_SINERGIAS),
        )
    }

    /** A cone's opening superimposition, from its rarity — see [GuiaFormulario.sobreposicaoPadrao]. */
    private fun sobreposicao(nome: String): Int = GuiaFormulario.sobreposicaoPadrao(
        jdbc.queryForList("SELECT raridade FROM cones_de_luz WHERE nome = ?", Int::class.java, nome).firstOrNull(),
    )

    // -------------------- render -------------------- //

    /** The card for a finished spec: served from disk when it has been drawn before. */
    private fun png(hash: String, spec: GuiaSpec): ByteArray? {
        val file = CACHE.resolve("$hash.png")
        runCatching { if (Files.exists(file)) return Files.readAllBytes(file) }
            .onFailure { log.warn("cache de guia {} ilegível: {}", hash.take(8), it.message) }
        val ficha = resolver(spec) ?: return null
        val bytes = CardRenderer.png(ficha)
        runCatching { Files.write(file, bytes) }
            .onFailure { log.warn("não consegui gravar o cache de {}: {}", hash.take(8), it.message) }
        return bytes
    }

    /**
     * The author's choices plus everything the card needs to draw them: canonical names and icon
     * hashes, looked up fresh. A name that no longer resolves keeps its text and loses its icon —
     * a renamed set must not blank out a guide.
     */
    private fun resolver(spec: GuiaSpec): Ficha? {
        val p = jdbc.queryForList("SELECT * FROM personagem_hsr WHERE character_id = ? LIMIT 1", spec.personagemId)
            .firstOrNull() ?: return null

        val cones = porNome("SELECT nome, icone FROM cones_de_luz", spec.cones.map { it.nome })
        val reliquias = porNome("SELECT nome, icone, corpo_icone, pes_icone FROM reliquias", nomes(spec.reliquias))
        val ornamentos = porNome(
            "SELECT nome, icone, esfera_icone, corda_icone FROM ornamentos_planos",
            nomes(spec.ornamentos),
        )
        // Synergies go through the gazetteer, never through `lower(nome)`: ten Trailblazer rows
        // share two names, so a name on its own cannot pick which portrait to draw.
        val sinergiaIds = spec.sinergias.mapNotNull { personagens.resolveId(it) }.distinct()
        val minis = porId(sinergiaIds)

        fun linhas(ls: List<LinhaSpec>, fonte: Map<String, Map<String, Any?>>) = ls.map { l ->
            Linha(
                l.partes.map { parte ->
                    val row = fonte[parte.nome.lowercase()]
                    Parte(str(row?.get("nome")) ?: parte.nome, str(row?.get("icone")), parte.pecas)
                },
            )
        }
        // The slot icons come from the first line of each column — the set the author leads with.
        // That line is a "2 + 2" whenever there is one, so pés shows the SECOND set: a split build
        // wears one set on corpo and the other on pés, and repeating the first would picture a
        // 4-piece build the author did not ask for. Ornaments never split — a planar set is always
        // its own sphere plus rope — so both of those keep coming from one row.
        val primeiraLinha = spec.reliquias.firstOrNull()?.partes.orEmpty()
        val reliquiaCorpo = primeiraLinha.getOrNull(0)?.let { reliquias[it.nome.lowercase()] }
        val reliquiaPes = (primeiraLinha.getOrNull(1) ?: primeiraLinha.getOrNull(0))
            ?.let { reliquias[it.nome.lowercase()] }
        val primeiroOrnamento = spec.ornamentos.firstOrNull()?.partes?.firstOrNull()
            ?.let { ornamentos[it.nome.lowercase()] }

        val base = fichaBase(p, "?", v2 = true)
        // The author's framing wins over the curated columns, for this guide only, and it is also
        // what lets v2 render at all for a character nobody has curated a box for. With an uploaded
        // picture the curated box is meaningless — it measures a different image — so an unframed
        // upload starts as the WHOLE image rather than falling back to the v1 bust.
        val enviada = spec.arte?.let { arteEnviada(it) }
        val foco = spec.foco?.let { Foco(it.x, it.y, it.largura, it.altura) }
            ?: Foco.INTEIRO.takeIf { enviada != null }
            ?: base.arte.foco
        return base.copy(
            // The credit only travels with the upload — `enviada` null means the card is drawing the
            // official illustration, which is nobody's to sign. The frame is the author's to pan
            // freely once they own it: an upload, or an official box they nudged (spec.foco set).
            arte = base.arte.copy(
                enviada = enviada,
                autor = spec.arteAutor.takeIf { enviada != null },
                foco = foco,
                enquadramentoLivre = enviada != null || spec.foco != null,
            ),
            rastros = spec.rastros.mapNotNull { rastro(p, it.rotulo) },
            cones = spec.cones.map { c ->
                val row = cones[c.nome.lowercase()]
                Cone(str(row?.get("nome")) ?: c.nome, str(row?.get("icone")), c.sobreposicao)
            },
            reliquias = linhas(spec.reliquias, reliquias),
            ornamentos = linhas(spec.ornamentos, ornamentos),
            status = Status(
                corpo = spec.corpo, pes = spec.pes, esfera = spec.esfera, corda = spec.corda,
                icones = listOf(
                    str(reliquiaCorpo?.get("corpo_icone")), str(reliquiaPes?.get("pes_icone")),
                    str(primeiroOrnamento?.get("esfera_icone")), str(primeiroOrnamento?.get("corda_icone")),
                ),
            ),
            metas = spec.metas.map { Meta(it.stat, it.alvo) },
            sinergias = sinergiaIds.map { Sinergia(personagens.displayName(it), minis[it]) },
        )
    }

    // -------------------- plumbing -------------------- //

    private fun pkDe(personagemId: Int): Int? = jdbc
        .queryForList("SELECT id_personagem_hsr FROM personagem_hsr WHERE character_id = ?", Int::class.java, personagemId)
        .firstOrNull()

    private fun rascunhoDe(autorId: String, pk: Int): Rascunho? = jdbc
        .queryForList("SELECT chave, spec FROM guia WHERE autor_id = ? AND id_personagem_hsr = ? AND hash IS NULL", autorId, pk)
        .firstOrNull()
        ?.let { Rascunho(str(it["chave"])!!, spec(it["spec"]), null) }

    /** Rows keyed by lower-cased name, so a lookup never depends on how the author cased it. */
    private fun porNome(sql: String, nomes: Collection<String>): Map<String, Map<String, Any?>> {
        if (nomes.none { it.isNotBlank() }) return emptyMap()
        // Case-fold in the JVM, never in SQL. This database's C locale makes Postgres lower()
        // ASCII-only, so `lower(nome)` leaves an accented capital untouched ("Águia" -> "Águia") and
        // never equals the JVM-lower-cased name we would bind ("águia") — the icon then silently
        // vanishes. These lookup tables are tiny, so fetch them whole and match here.
        return jdbc.queryForList(sql).associateBy { str(it["nome"]).orEmpty().lowercase() }
    }

    /** Game id → portrait, for the synergy row: the one key that tells the ten Trailblazers apart. */
    private fun porId(ids: List<String>): Map<String, String?> {
        val numericos = ids.mapNotNull { it.toIntOrNull() }
        if (numericos.isEmpty()) return emptyMap()
        return jdbc.queryForList(
            "SELECT character_id, icone_mini FROM personagem_hsr WHERE character_id IN " +
                "(${numericos.joinToString(",") { "?" }})",
            *numericos.toTypedArray(),
        ).associate { it["character_id"].toString() to str(it["icone_mini"]) }
    }

    private fun nomes(linhas: List<LinhaSpec>): List<String> = linhas.flatMap { l -> l.partes.map { it.nome } }

    private fun json(spec: GuiaSpec): String = mapper.writeValueAsString(spec)

    private fun spec(value: Any?): GuiaSpec = mapper.readValue(value.toString(), GuiaSpec::class.java)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val CACHE: Path = Path.of("target", "guia-cache").also { Files.createDirectories(it) }
        private val ARTE: Path = Path.of("target", "guia-arte").also { Files.createDirectories(it) }

        /**
         * How long after a guide's last edit its upload is still worth keeping — see [limparArte].
         * Generous on purpose: nobody adjusts a guide a month later, and the whole grace period
         * costs about one wallpaper.
         */
        private const val JANELA_ARTE_DIAS = 30

        /**
         * Its own mapper on purpose: this hash is a durable identity, and Spring's ObjectMapper is
         * configurable from application.yml. A property added there must not silently repartition
         * every stored guide.
         */
        private val CANONICO = ObjectMapper()

        /**
         * Identity of a set of answers. Serialisation is deterministic (declaration order, defaults
         * omitted), so the same form always hashes the same.
         *
         * ponytail: adding a field to [GuiaSpec] changes what a spec hashes to only if the author
         * actually uses it — a stored guide whose hash no longer matches just renders once more
         * under a new row, which is why nothing here needs a schema version.
         */
        fun hash(spec: GuiaSpec): String =
            MessageDigest.getInstance("SHA-256")
                .digest(CANONICO.writeValueAsBytes(spec))
                .joinToString("") { "%02x".format(it) }
    }
}

/** A guide being filled in. [hash] is null while it is still a draft. */
data class Rascunho(val chave: String, val spec: GuiaSpec, val hash: String?)

/** A finished guide's card. [reaproveitada] means an identical guide already existed. */
data class Cartao(val chave: String, val hash: String, val png: ByteArray, val reaproveitada: Boolean) {
    // ByteArray in a data class: equals/hashCode would compare references, which is never what a
    // caller means. Nothing compares Cartaos, so make that explicit instead of shipping a trap.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
