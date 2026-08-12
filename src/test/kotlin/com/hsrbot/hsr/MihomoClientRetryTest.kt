package com.hsrbot.hsr

import com.hsrbot.config.BotProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The live symptom this covers: mihomo answered HTTP 500 for a UID that fetched fine seconds
 * later, and `/build` turned that into "deu ruim". A stub server (JDK's own, no framework)
 * replays that exact sequence.
 */
class MihomoClientRetryTest {

    private fun client(url: String) =
        MihomoClient(BotProperties(token = "t", knowledge = BotProperties.Knowledge(mihomoUrl = url)), ObjectMapper())

    /** Serves [responses] in order (status to body), one per request, and reports the count. */
    private fun serve(vararg responses: Pair<Int, String>, block: (String, AtomicInteger) -> Unit) {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { ex ->
            val (status, body) = responses[hits.getAndIncrement().coerceAtMost(responses.size - 1)]
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://localhost:${server.address.port}", hits)
        } finally {
            server.stop(0)
        }
    }

    private val profileJson = """{"player":{"nickname":"tore"},"characters":[]}"""

    @Test
    fun `retries once past a transient 500`() {
        serve(500 to """{"detail":"boom"}""", 200 to profileJson) { url, hits ->
            val result = client(url).fetch("600281322")
            assertInstanceOf(ShowcaseResult.Ok::class.java, result)
            assertEquals("tore", (result as ShowcaseResult.Ok).profile.nickname)
            assertEquals(2, hits.get())
        }
    }

    @Test
    fun `gives up after two 500s`() {
        serve(500 to """{"detail":"boom"}""") { url, hits ->
            assertEquals(ShowcaseResult.Error, client(url).fetch("600281322"))
            assertEquals(2, hits.get())
        }
    }

    /** A real answer is final — a bad UID must not cost a second round trip. */
    @Test
    fun `does not retry a 404, and 400 reads as not found too`() {
        serve(404 to """{"detail":"User not found"}""") { url, hits ->
            assertEquals(ShowcaseResult.NotFound, client(url).fetch("600281322"))
            assertEquals(1, hits.get())
        }
        serve(400 to """{"detail":"Invalid uid"}""") { url, hits ->
            assertEquals(ShowcaseResult.NotFound, client(url).fetch("123"))
            assertEquals(1, hits.get())
        }
    }
}
