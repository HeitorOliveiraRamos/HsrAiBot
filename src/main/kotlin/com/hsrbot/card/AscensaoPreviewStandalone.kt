package com.hsrbot.card

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the ascension guide card for one character so the layout can be eyeballed without going
 * through Discord — the [CardPreviewStandalone] of [AscensaoRenderer].
 *
 * Run (writes ascensao-preview-<nome>.png into the repo root):
 *   `mvn -q -DskipTests compile spring-boot:run \
 *      -Dspring-boot.run.main-class=com.hsrbot.card.AscensaoPreviewStandaloneKt \
 *      -Dspring-boot.run.arguments=Blade`
 *
 * `ARTE=/caminho/pra/imagem.png` renders the member-uploaded layout instead of the official art, and
 * `ARTE_AUTOR=nome` puts the credit on it — env vars because the arguments are all one character
 * name. Framing is the whole picture, which is where `/ascensao` opens before anyone nudges it.
 *
 * Goes through the SAME [AscensaoService] the command does — it is a plain class that takes a
 * JdbcTemplate, so no Spring context is needed — which is what makes the preview worth trusting:
 * a card that renders here renders identically in the channel.
 */
private val log = LoggerFactory.getLogger("AscensaoPreview")

fun main(args: Array<String>) {
    // Joined, not `first()`: Maven splits `-Dspring-boot.run.arguments` on spaces, so a multi-word
    // name ("Dan Heng - Embebidor Lunae") arrives here as five arguments.
    val nome = args.joinToString(" ").trim().ifEmpty { "Blade" }

    val ds = DriverManagerDataSource(
        System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/hsrbot",
        System.getenv("DB_USER") ?: "hsrbot",
        System.getenv("DB_PASSWORD") ?: "hsrbot",
    ).apply { setDriverClassName("org.postgresql.Driver") }
    val jdbc = JdbcTemplate(ds)

    // The command resolves a typed name through HsrCharacterService; here the name is trusted, so
    // one lookup for the game id is enough.
    val id = jdbc.queryForList(
        "SELECT character_id FROM personagem_hsr WHERE lower(nome) = lower(?) OR lower(nome_en) = lower(?) LIMIT 1",
        nome, nome,
    ).firstOrNull()?.get("character_id") as? Number ?: error("personagem '$nome' não encontrada em personagem_hsr")

    val a = AscensaoService(jdbc).cartao(id.toInt()) ?: error("'$nome' não tem custos_melhoria — rodou o populate?")
    // Through normalizarArte like the command's, so what is eyeballed here is what a member's upload
    // actually becomes — capped, re-encoded and nothing else.
    val enviada = System.getenv("ARTE")?.let { caminho ->
        ImageIO.scanForPlugins()
        val bytes = normalizarArte(File(caminho).readBytes()) ?: error("não consegui ler a arte '$caminho'")
        ImageIO.read(bytes.inputStream())
    }
    val autor = System.getenv("ARTE_AUTOR")

    val out = File(System.getProperty("user.dir"), "ascensao-preview-${a.nome.lowercase().replace(' ', '-')}.png")
    ImageIO.write(AscensaoRenderer.render(a.copy(arte = a.arte.copy(autor = autor)), enviada), "png", out)
    log.info("Preview gravado: {}", out.absolutePath)
}
