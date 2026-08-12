package com.hsrbot.card

import com.hsrbot.config.BotProperties
import com.hsrbot.discord.command.BuildCommand
import com.hsrbot.hsr.BuildAnalyzer
import com.hsrbot.hsr.EnkaClient
import com.hsrbot.hsr.FribbelsHarvester
import com.hsrbot.hsr.HsrCharacterService
import com.hsrbot.hsr.MihomoClient
import com.hsrbot.hsr.ShowcaseResult
import com.hsrbot.hsr.ShowcaseService
import com.hsrbot.hsr.StarRailResNames
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.File
import java.util.concurrent.Executor
import javax.imageio.ImageIO

/**
 * Renders the `/build` card for a live showcase so the layout can be eyeballed without going through
 * Discord — the [AscensaoPreviewStandalone] of [AvaliacaoRenderer].
 *
 * Run (writes build-preview-<nome>.png into the repo root):
 *   `mvn -q -DskipTests compile spring-boot:run \
 *      -Dspring-boot.run.main-class=com.hsrbot.card.AvaliacaoPreviewStandaloneKt \
 *      -Dspring-boot.run.arguments="600281322 Castorice"`
 *
 * `ARTE=/caminho/pra/imagem.png` renders the member-uploaded layout instead of the official art.
 *
 * Goes through the SAME [ShowcaseService], [BuildAnalyzer] and [AvaliacaoService] the command does — all
 * plain classes — which is what makes the preview worth trusting: a card that renders here renders
 * identically in the channel.
 */
private val log = LoggerFactory.getLogger("AvaliacaoPreview")

fun main(args: Array<String>) {
    val uid = args.firstOrNull() ?: error("uso: <uid> <personagem>")
    // Joined, not `get(1)`: Maven splits the arguments on spaces, so a multi-word name arrives here
    // as several of them.
    val nome = args.drop(1).joinToString(" ").trim().ifEmpty { error("uso: <uid> <personagem>") }

    val ds = DriverManagerDataSource(
        System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/hsrbot",
        System.getenv("DB_USER") ?: "hsrbot",
        System.getenv("DB_PASSWORD") ?: "hsrbot",
    ).apply { setDriverClassName("org.postgresql.Driver") }
    val jdbc = JdbcTemplate(ds)
    val props = BotProperties(token = "preview")

    // Assembled by hand because there is no Spring context here — and deliberately the SAME chain
    // the command uses, Enka first, so the preview exercises the real source and not a shortcut.
    val mapper = ObjectMapper()
    val personagens = HsrCharacterService(jdbc, mapper, FribbelsHarvester(props, mapper))
    // @Scheduled doesn't fire outside the container, so the name tables are primed explicitly;
    // without them EnkaClient refuses and every preview would silently come from mihomo.
    val nomes = StarRailResNames(props, mapper).apply { refresh() }
    val vitrines = ShowcaseService(
        EnkaClient(props, mapper, personagens, nomes),
        MihomoClient(props, mapper),
        props,
        // Only feeds ShowcaseService.warm, which the preview never calls — same thread will do.
        Executor { it.run() },
    )

    val perfil = when (val r = vitrines.fetch(uid)) {
        is ShowcaseResult.Ok -> r.profile
        else -> error("nenhuma fonte devolveu a vitrine de $uid ($r)")
    }
    val personagem = perfil.characters.firstOrNull { normalizar(it.name) == normalizar(nome) }
        ?: error("'$nome' não está na vitrine — tem: ${perfil.characters.joinToString { it.name }}")

    val relatorio = BuildAnalyzer.analyze(personagem, personagens.fribbelsMeta(personagem.id))
        ?: error("o fribbels ainda não pontua ${personagem.name}")
    val cartao = AvaliacaoService(jdbc)
        .cartao(personagem, relatorio, BuildCommand.rotuloSpd(null), perfil.nickname, uid)

    val enviada = System.getenv("ARTE")?.let { caminho ->
        ImageIO.scanForPlugins()
        val bytes = normalizarArte(File(caminho).readBytes()) ?: error("não consegui ler a arte '$caminho'")
        ImageIO.read(bytes.inputStream())
    }

    val out = File(System.getProperty("user.dir"), "build-preview-${cartao.nome.lowercase().replace(' ', '-')}.png")
    ImageIO.write(AvaliacaoRenderer.render(cartao, enviada), "png", out)
    log.info("Preview gravado: {} (nota {} {})", out.absolutePath, cartao.nota, cartao.rank)
}

private fun normalizar(s: String) = HsrCharacterService.normalize(s)
