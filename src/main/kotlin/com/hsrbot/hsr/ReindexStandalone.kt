package com.hsrbot.hsr

import com.hsrbot.HsrBotApplication
import com.hsrbot.config.BotProperties
import com.hsrbot.knowledge.HsrKnowledgeIngestion
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * Manual one-off entrypoint to rebuild the HSR vector store from the V17 tables WITHOUT booting
 * Discord — the sibling of [SrsNanokaStandalone], and the offline read-half of the same pipeline.
 * Boots the real Spring context (so the Spring AI pgvector [org.springframework.ai.vectorstore
 * .VectorStore] + Ollama embedding beans are reused verbatim, exact config) MINUS the
 * `com.hsrbot.discord` package — the only thing that builds the JDA client, which needs `BOT_TOKEN`
 * and would bring the bot online. Then calls [HsrKnowledgeIngestion.reindex] directly. In-app the
 * identical work runs on a normal boot with `HSR_REINDEX=true`.
 *
 * BOT_TOKEN still has no default in `application.yml`, so pass a dummy (it's never read here):
 *   `BOT_TOKEN=reindex mvn -q -DskipTests compile spring-boot:run \
 *      -Dspring-boot.run.main-class=com.hsrbot.hsr.ReindexStandaloneKt`
 */
// A hand-rolled @SpringBootApplication (config + autoconfig + our OWN component scan). Two things
// must be kept out or the JDA client is built and dies on the dummy token: the whole
// `com.hsrbot.discord` package, and the real [HsrBotApplication] — scanning `com.hsrbot` reaches
// it, and its own unrestricted @ComponentScan would re-register everything (discord included).
// This config lives in com.hsrbot.hsr, so auto JPA-repository/entity scanning would narrow to that
// package (the root HsrBotApplication gets com.hsrbot for free by sitting at the root); pin both
// back to com.hsrbot so beans like ConversationService find their repositories.
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableJpaRepositories(basePackages = ["com.hsrbot"])
@EntityScan(basePackages = ["com.hsrbot"])
@ConfigurationPropertiesScan(basePackageClasses = [BotProperties::class])
@ComponentScan(
    basePackages = ["com.hsrbot"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.hsrbot\\.discord\\..*"]),
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [HsrBotApplication::class]),
    ],
)
class ReindexStandalone

private val log = LoggerFactory.getLogger("ReindexStandalone")

fun main() {
    SpringApplicationBuilder(ReindexStandalone::class.java)
        .web(WebApplicationType.NONE)
        .run()
        .use { ctx ->
            log.info("Reindexando o vector store a partir das tabelas V17...")
            ctx.getBean(HsrKnowledgeIngestion::class.java).reindex()
            log.info("Reindex concluído.")
        }
}
