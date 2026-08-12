package com.hsrbot.hsr

import com.hsrbot.config.BotProperties
import com.hsrbot.knowledge.NanokaIngestionSource
import com.hsrbot.knowledge.StarRailStationIngestionSource
import com.fasterxml.jackson.databind.ObjectMapper
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * Manual one-off entrypoint to populate the V17 tables WITHOUT booting the whole bot — the full
 * app needs `BOT_TOKEN` and logs in to Discord, neither of which a data populate should require.
 * Applies pending Flyway migrations (so V16/V17 land, recorded in `flyway_schema_history`) then
 * runs the exact same [SrsNanokaPopulator.populate]. In-app, the identical work is triggered by
 * `POPULATE_SRS_NANOKA=true` on a normal boot.
 *
 * DB coordinates come from the same env vars as `application.yml` (defaults localhost/hsrbot).
 * Run:
 *   `mvn -q -DskipTests compile spring-boot:run -Dspring-boot.run.main-class=com.hsrbot.hsr.SrsNanokaStandaloneKt`
 */
private val log = LoggerFactory.getLogger("SrsNanokaStandalone")

fun main() {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/hsrbot"
    val user = System.getenv("DB_USER") ?: "hsrbot"
    val pass = System.getenv("DB_PASSWORD") ?: "hsrbot"

    val ds = DriverManagerDataSource(url, user, pass).apply { setDriverClassName("org.postgresql.Driver") }

    log.info("Aplicando migrações Flyway (inclui V16/V17)...")
    val result = Flyway.configure().dataSource(ds).load().migrate()
    log.info("Flyway: {} migração(ões) aplicada(s), schema agora em {}", result.migrationsExecuted, result.targetSchemaVersion)

    val props = BotProperties(token = "populate")
    val mapper = ObjectMapper()
    val harvester = SrsNanokaHarvester(
        props, mapper,
        NanokaIngestionSource(props),
        StarRailStationIngestionSource(props),
    )
    SrsNanokaPopulator(props, JdbcTemplate(ds), harvester).populate()
    log.info("Populate concluído.")
}
