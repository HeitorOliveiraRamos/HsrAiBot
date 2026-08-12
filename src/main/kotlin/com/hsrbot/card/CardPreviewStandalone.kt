package com.hsrbot.card

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the CURATED build card for one character — the `builds` row the harvester populated — so
 * layout changes can be eyeballed without going through Discord. The drawing itself lives in
 * [CardRenderer]; this file is only the `builds` → [Ficha] mapping plus a `main`.
 *
 * Run (writes ficha-preview-<nome>-<versão>.png into the repo root); arguments are separated by
 * SPACES, and a name that contains one goes in `NOME=` instead of the argument list:
 *   `NOME=Blade mvn -q -DskipTests compile spring-boot:run \
 *      -Dspring-boot.run.main-class=com.hsrbot.card.CardPreviewStandaloneKt \
 *      -Dspring-boot.run.arguments="x v2"`
 *
 * A third `demo` argument layers the things only a guide author can supply (superimposition ranks,
 * a "2 + 2" relic split, status targets) onto the curated data — the way to eyeball those parts of
 * the layout without going through the wizard.
 */
private val log = LoggerFactory.getLogger("CardPreview")

fun main(args: Array<String>) {
    // Maven splits `-Dspring-boot.run.arguments` on SPACES (not commas — the header's `Blade,v2`
    // arrives as a single argument and silently renders v1), so a multi-word name can never get
    // here in one piece: "Robin • Summeretto" lands its second word in `versao` and trips the
    // require below. NOME= passes it through intact, the same way ARTE=/ARTE_AUTOR= already
    // sidestep the splitting for their own values.
    val nome = System.getenv("NOME")?.trim()?.ifEmpty { null } ?: args.firstOrNull() ?: "Blade"
    val versao = args.getOrNull(1)?.lowercase() ?: "v1"
    require(versao in setOf("v1", "v2")) { "versão '$versao' desconhecida — use v1 ou v2" }
    val demo = args.getOrNull(2)?.lowercase() == "demo"
    // A 4th argument is a file to use as the illustration, the way `/guia`'s `arte` option does —
    // through the same normalisation, so the preview shows what an upload would really produce.
    val enviada = args.getOrNull(3)?.let { File(it) }
    require(enviada == null || enviada.isFile) { "arquivo '$enviada' não existe" }

    val ds = DriverManagerDataSource(
        System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/hsrbot",
        System.getenv("DB_USER") ?: "hsrbot",
        System.getenv("DB_PASSWORD") ?: "hsrbot",
    ).apply { setDriverClassName("org.postgresql.Driver") }

    val curada = fichaCurada(JdbcTemplate(ds), nome, versao == "v2")
        ?: error("personagem '$nome' não encontrada em personagem_hsr")
    val ficha = (if (demo) comDadosDeGuia(curada) else curada).let { f ->
        val arte = enviada?.let { comArteEnviada(it) } ?: return@let f
        // The whole picture is where an upload starts: nothing knows where the character is in it.
        // `ARTE_AUTOR` is who gets credited on it — an env var, since the args are already full.
        f.copy(
            arte = f.arte.copy(
                enviada = arte, autor = System.getenv("ARTE_AUTOR"), foco = Foco.INTEIRO, v2 = true,
            ),
        )
    }

    val img = CardRenderer.render(ficha)
    val slug = ficha.nome.lowercase().replace(' ', '-')
    val sufixo = (if (ficha.arte.podeV2) "v2" else "v1") + (if (demo) "-demo" else "") +
        if (enviada != null) "-arte" else ""
    val out = File(System.getProperty("user.dir"), "ficha-preview-$slug-$sufixo.png")
    ImageIO.write(img, "png", out)
    log.info("Preview gravado: {}", out.absolutePath)
}

/**
 * The curated recommendation for [nome] as a [Ficha]: the character row, its `builds` row, and the
 * sets/cones that row points at. Returns null when the character does not exist; a character with
 * no `builds` row still renders (art, badges and an empty right column).
 */
internal fun fichaCurada(jdbc: JdbcTemplate, nome: String, v2: Boolean): Ficha? {
    val p = jdbc.queryForList(
        "SELECT * FROM personagem_hsr WHERE lower(nome) = lower(?) OR lower(nome_en) = lower(?) LIMIT 1",
        nome, nome,
    ).firstOrNull() ?: return null

    val b = jdbc.queryForList(
        "SELECT * FROM builds WHERE id_personagem_hsr = ? LIMIT 1",
        p["id_personagem_hsr"],
    ).firstOrNull()

    fun item(table: String, pkCol: String, id: Any?): Map<String, Any?>? = id?.let {
        jdbc.queryForList("SELECT * FROM $table WHERE $pkCol = ?", it).firstOrNull()
    }
    val relics = (1..3).mapNotNull { item("reliquias", "id_reliquia", b?.get("id_reliquia$it")) }
    val ornaments = (1..3).mapNotNull { item("ornamentos_planos", "id_ornamento_plano", b?.get("id_ornamento_plano$it")) }
    val cones = (1..3).mapNotNull { item("cones_de_luz", "id_cone_de_luz", b?.get("id_cone_de_luz$it")) }

    val nomePt = str(p["nome"]) ?: nome
    // Synergies: equipe_recomendada is a comma-joined name list starting with the character itself,
    // which is not a synergy — drop it.
    val sinergias = (b?.get("equipe_recomendada") as? String).orEmpty()
        .split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.equals(nomePt, ignoreCase = true) }
        .mapNotNull { n ->
            jdbc.queryForList("SELECT nome, icone_mini FROM personagem_hsr WHERE lower(nome) = lower(?) LIMIT 1", n)
                .firstOrNull()
        }
        .map { Sinergia(str(it["nome"]).orEmpty(), str(it["icone_mini"])) }

    // ponytail: fixed order — the curated data has no per-character priority to derive one from. A
    // user-written guide supplies it; this preview cannot. Capped at four like the form, so the
    // units with a fifth ability (euphoria, memosprite) preview the row the card can actually hold.
    val rastros = RASTRO_ICONE.keys.mapNotNull { rastro(p, it) }.take(4)

    return fichaBase(p, nome, v2).copy(
        rastros = rastros,
        cones = cones.map { Cone(str(it["nome"]).orEmpty(), str(it["icone"])) },
        // Piece counts are structural in HSR: relic sets are worn 4pc, ornaments 2pc. Splits like
        // "2 + 2" only ever come from a guide author, never from the curated row.
        reliquias = relics.map { Linha(Parte(str(it["nome"]).orEmpty(), str(it["icone"]), pecas = 4)) },
        ornamentos = ornaments.map { Linha(Parte(str(it["nome"]).orEmpty(), str(it["icone"]), pecas = 2)) },
        status = Status(
            corpo = str(b?.get("main_stat_corpo")),
            pes = str(b?.get("main_stat_pes")),
            esfera = str(b?.get("main_stat_esfera")),
            corda = str(b?.get("main_stat_corda")),
            icones = listOf(
                str(relics.firstOrNull()?.get("corpo_icone")), str(relics.firstOrNull()?.get("pes_icone")),
                str(ornaments.firstOrNull()?.get("esfera_icone")), str(ornaments.firstOrNull()?.get("corda_icone")),
            ),
        ),
        // No targets in the curated data — these are a bare substat priority, so they stay numbered.
        metas = (b?.get("substatus_recomendados") as? String).orEmpty()
            .split(">", ",").map { it.trim() }.filter { it.isNotEmpty() }
            .map { Meta(it) },
        sinergias = sinergias,
    )
}

/** The file, put through what a real upload goes through, written where the renderer can open it. */
private fun comArteEnviada(arquivo: File): java.nio.file.Path {
    val bytes = normalizarArte(arquivo.readBytes()) ?: error("não consegui ler '$arquivo' como imagem")
    return java.nio.file.Files.createTempFile("guia-arte", ".png").also { it.toFile().writeBytes(bytes) }
}

/**
 * Stands in for a filled-out guide form: ranks on the cones, the first two relic sets merged into
 * one "2 + 2" line, and status targets instead of a bare substat priority. Preview-only — the real
 * values come from the wizard.
 */
private fun comDadosDeGuia(f: Ficha): Ficha = f.copy(
    cones = f.cones.mapIndexed { i, c -> c.copy(sobreposicao = if (i == 0) 1 else 5) },
    reliquias = if (f.reliquias.size < 2) f.reliquias else listOf(
        Linha(f.reliquias[0].partes.first().copy(pecas = 2), f.reliquias[1].partes.first().copy(pecas = 2)),
        f.reliquias[0],
    ),
    metas = listOf(
        Meta("Chance de Crit", "100% em combate"),
        Meta("Dano Crit", "120%+ pré-combate"),
        Meta("ATQ", "3200+"),
        Meta("Velocidade", "134+"),
    ),
)
