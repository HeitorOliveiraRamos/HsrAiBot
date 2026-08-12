package com.hsrbot.hsr

import com.hsrbot.config.BotProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.net.InetSocketAddress

/**
 * [EnkaClient]'s HTTP half — which status means "tell the member their UID is wrong" and which means
 * "ask mihomo instead". Getting that backwards is invisible until a real outage, when every member
 * is told their UID doesn't exist.
 *
 * The stub serves the StarRailRes `pt/` tables as well as the showcase, because the client refuses
 * to build a profile it can't put names on — which is itself one of the cases pinned here.
 */
class EnkaClientHttpTest {

    private val mapper = ObjectMapper()

    private val vitrine = javaClass.getResourceAsStream("/enka-showcase.json")!!.readAllBytes().decodeToString()

    /**
     * Serves the three name tables always, and [showcase] (status to body) for the uid path.
     * Reports how many showcase requests were made.
     */
    private fun serve(
        showcase: Pair<Int, String>,
        semTabelas: Boolean = false,
        redireciona: Boolean = false,
        block: (BotProperties) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            val (status, body) = when {
                path.startsWith("/pt/") ->
                    if (semTabelas) 500 to "" else 200 to TABELAS.getValue(path.removePrefix("/pt/"))
                // Enka's own shape: the un-normalized path bounces once, the target serves [showcase].
                redireciona && !path.endsWith("/normalizado") -> {
                    ex.responseHeaders.add("Location", "$path/normalizado")
                    308 to ""
                }
                else -> showcase
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://localhost:${server.address.port}"
            block(
                BotProperties(
                    token = "t",
                    knowledge = BotProperties.Knowledge(enkaUrl = base, starRailResBase = base),
                ),
            )
        } finally {
            server.stop(0)
        }
    }

    private fun client(props: BotProperties): EnkaClient {
        val nomes = StarRailResNames(props, mapper).apply { refresh() }
        return EnkaClient(props, mapper, HsrCharacterService(JdbcTemplate(), mapper, FribbelsHarvester(props, mapper)), nomes)
    }

    @Test
    fun `a showcase comes back parsed`() {
        serve(200 to vitrine) { props ->
            val r = client(props).fetch("600281322")
            assertInstanceOf(ShowcaseResult.Ok::class.java, r)
            val perfil = (r as ShowcaseResult.Ok).profile
            assertEquals("tore", perfil.nickname)
            // Names resolved through the served tables, not the (absent) database.
            assertEquals("Poeta do Colapso do Luto", perfil.characters.first().relics.first().setName)
        }
    }

    /** A bad UID is the member's typo, not an outage — it must not cost a mihomo round trip. */
    @Test
    fun `404 and 400 both read as not found`() {
        serve(404 to """{"detail":"Player does not exist"}""") { props ->
            assertEquals(ShowcaseResult.NotFound, client(props).fetch("600281322"))
        }
        serve(400 to """{"detail":"Wrong UID format"}""") { props ->
            assertEquals(ShowcaseResult.NotFound, client(props).fetch("1"))
        }
    }

    /** 429 (throttled) and 424 (game in maintenance) are exactly what the fallback exists for. */
    @Test
    fun `a rate limit or an outage stays an error so the chain can fall through`() {
        serve(429 to """{"detail":"Too many requests"}""") { props ->
            assertEquals(ShowcaseResult.Error, client(props).fetch("600281322"))
        }
        serve(424 to """{"detail":"Game maintenance"}""") { props ->
            assertEquals(ShowcaseResult.Error, client(props).fetch("600281322"))
        }
    }

    /**
     * Enka runs on SvelteKit and 308s `/uid/123/` → `/uid/123`. The JDK client does not follow
     * redirects unless told to, so this shipped as a 3xx that read as "outage" and sent EVERY
     * showcase to mihomo — invisible except for one warn line, since the fallback card is fine.
     */
    @Test
    fun `a normalization redirect is followed, not read as an outage`() {
        serve(200 to vitrine, redireciona = true) { props ->
            val r = client(props).fetch("600281322")
            assertInstanceOf(ShowcaseResult.Ok::class.java, r)
            assertEquals("tore", (r as ShowcaseResult.Ok).profile.nickname)
        }
    }

    /** No name tables means every set and cone on the card would be blank — mihomo does that job better. */
    @Test
    fun `refuses to answer at all while the name tables are missing`() {
        serve(200 to vitrine, semTabelas = true) { props ->
            assertEquals(ShowcaseResult.Error, client(props).fetch("600281322"))
        }
    }

    private companion object {
        val TABELAS = mapOf(
            "relic_sets.json" to """{"118":{"id":"118","name":"Poeta do Colapso do Luto"},
                                    "310":{"id":"310","name":"Duran, Dinastia Dançante"}}""",
            "light_cones.json" to """{"23037":{"id":"23037","name":"Assim Falou Fósforo Branco"}}""",
            "characters.json" to """{"1407":{"id":"1407","name":"Castorice","path":"Memory","element":"Quantum"}}""",
        )
    }
}
