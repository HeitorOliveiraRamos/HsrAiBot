package com.hsrbot.rank

import com.hsrbot.config.BotProperties
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
import java.util.concurrent.Executor

/**
 * Popula o `/rank` de uma vez com quem já tem UID vinculado, em vez de esperar cada pessoa rodar
 * `/build`. Trabalho de UMA vez: o caminho normal é o `/build`, que grava a vitrine inteira a cada
 * chamada.
 *
 * Rodar (o bot pode estar no ar; isto só escreve em `nota_build`):
 *   `mvn -q -DskipTests compile spring-boot:run \
 *      -Dspring-boot.run.main-class=com.hsrbot.rank.RankBackfillStandaloneKt`
 *
 * Passa pelo MESMO [ShowcaseService] e [RankingService] do comando — Enka primeiro, mihomo atrás —
 * então as notas que caem aqui são idênticas às que o `/build` gravaria. Uma vitrine privada ou um
 * UID errado é registrado e pulado: uma conta que não responde não pode derrubar as outras 36.
 */
private val log = LoggerFactory.getLogger("RankBackfill")

fun main() {
    val ds = DriverManagerDataSource(
        System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/hsrbot",
        System.getenv("DB_USER") ?: "hsrbot",
        System.getenv("DB_PASSWORD") ?: "hsrbot",
    ).apply { setDriverClassName("org.postgresql.Driver") }
    val jdbc = JdbcTemplate(ds)
    val props = BotProperties(token = "backfill")

    val mapper = ObjectMapper()
    val personagens = HsrCharacterService(jdbc, mapper, FribbelsHarvester(props, mapper))
    // @Scheduled não roda fora do container: sem isto o EnkaClient recusa e tudo viria do mihomo.
    val nomes = StarRailResNames(props, mapper).apply { refresh() }
    val vitrines = ShowcaseService(
        EnkaClient(props, mapper, personagens, nomes),
        MihomoClient(props, mapper),
        props,
        Executor { it.run() },
    )
    val ranking = RankingService(jdbc, personagens)

    val usuarios = jdbc.queryForList(
        "SELECT usuario_id, nome, uid_hsr FROM usuario " +
            "WHERE uid_hsr IS NOT NULL AND uid_hsr <> '' ORDER BY usuario_id",
    )
    log.info("{} usuários com UID vinculado", usuarios.size)

    var ok = 0
    var vazias = 0
    val falhas = mutableListOf<String>()
    usuarios.forEachIndexed { i, u ->
        val usuarioId = u["usuario_id"].toString()
        val uid = u["uid_hsr"].toString()
        val nome = u["nome"]?.toString() ?: usuarioId
        when (val r = vitrines.fetch(uid)) {
            is ShowcaseResult.Ok -> {
                val antes = contar(jdbc, usuarioId)
                ranking.registrar(usuarioId, uid, r.profile)
                val gravadas = contar(jdbc, usuarioId) - antes
                if (r.profile.characters.isEmpty()) vazias++ else ok++
                log.info(
                    "[{}/{}] {} (uid {}): {} na vitrine, {} linha(s) nova(s)",
                    i + 1, usuarios.size, nome, uid, r.profile.characters.size, gravadas,
                )
            }
            else -> {
                falhas += "$nome ($uid): $r"
                log.warn("[{}/{}] {} (uid {}): {}", i + 1, usuarios.size, nome, uid, r)
            }
        }
        // Duas APIs públicas de graça — dá um respiro entre as contas.
        if (i < usuarios.lastIndex) Thread.sleep(1500)
    }

    val total = jdbc.queryForObject("SELECT count(*) FROM nota_build", Int::class.java)
    log.info("Pronto: {} vitrines lidas, {} vazias, {} falhas. nota_build tem {} linhas.",
        ok, vazias, falhas.size, total)
    falhas.forEach { log.info("  falhou: {}", it) }
}

private fun contar(jdbc: JdbcTemplate, usuarioId: String): Int =
    jdbc.queryForObject("SELECT count(*) FROM nota_build WHERE usuario_id = ?", Int::class.java, usuarioId) ?: 0
