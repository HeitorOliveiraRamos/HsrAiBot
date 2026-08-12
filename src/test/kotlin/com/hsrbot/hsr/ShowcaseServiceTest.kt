package com.hsrbot.hsr

import com.hsrbot.config.BotProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.Executor

/**
 * The failover contract behind `/build`. The whole reason both sources exist is that either can
 * have a bad minute, so what matters here is WHEN the second one gets asked — and just as much,
 * when it doesn't: a typo'd UID must not cost the member two round trips before being told.
 *
 * The clients are subclassed rather than mocked (the `spring` Kotlin plugin leaves `@Component`
 * classes open), which keeps this test dependency-free like the rest of the suite.
 */
class ShowcaseServiceTest {

    private val mapper = ObjectMapper()
    private val chamadas = mutableListOf<String>()

    private fun props(fonte: String = "enka") =
        BotProperties(token = "t", knowledge = BotProperties.Knowledge(showcaseSource = fonte))

    private val perfilEnka = ShowcaseProfile("da enka", emptyList())
    private val perfilMihomo = ShowcaseProfile("do mihomo", emptyList())

    private fun servico(
        enka: ShowcaseResult,
        mihomo: ShowcaseResult,
        fonte: String = "enka",
    ): ShowcaseService {
        val p = props(fonte)
        val personagens = HsrCharacterService(JdbcTemplate(), mapper, FribbelsHarvester(p, mapper))
        val nomes = StarRailResNames(p, mapper)
        return ShowcaseService(
            object : EnkaClient(p, mapper, personagens, nomes) {
                override fun fetch(uid: String): ShowcaseResult = enka.also { chamadas += "enka" }
            },
            object : MihomoClient(p, mapper) {
                override fun fetch(uid: String): ShowcaseResult = mihomo.also { chamadas += "mihomo" }
            },
            p,
            // Same thread, so a warm-up has finished by the time the assertion reads the cache.
            Executor { it.run() },
        )
    }

    @Test
    fun `falls through to mihomo when enka errors`() {
        val r = servico(ShowcaseResult.Error, ShowcaseResult.Ok(perfilMihomo)).fetch("600281322")
        assertEquals(perfilMihomo, (r as ShowcaseResult.Ok).profile)
        assertEquals(listOf("enka", "mihomo"), chamadas)
    }

    /** Both proxy the same upstream, so "no such player" is final — asking twice only adds latency. */
    @Test
    fun `does not second-guess a not-found`() {
        val r = servico(ShowcaseResult.NotFound, ShowcaseResult.Ok(perfilMihomo)).fetch("123")
        assertEquals(ShowcaseResult.NotFound, r)
        assertEquals(listOf("enka"), chamadas)
    }

    @Test
    fun `leaves mihomo alone when enka answers`() {
        val r = servico(ShowcaseResult.Ok(perfilEnka), ShowcaseResult.Ok(perfilMihomo)).fetch("600281322")
        assertEquals(perfilEnka, (r as ShowcaseResult.Ok).profile)
        assertEquals(listOf("enka"), chamadas)
    }

    /** The second `/build` in a row (comparing two characters) must not re-fetch the showcase. */
    @Test
    fun `caches a hit so a repeat costs no request`() {
        val s = servico(ShowcaseResult.Ok(perfilEnka), ShowcaseResult.Error)
        s.fetch("600281322")
        val r = s.fetch("600281322")
        assertEquals(perfilEnka, (r as ShowcaseResult.Ok).profile)
        assertEquals(listOf("enka"), chamadas)
    }

    /** A failure must NOT stick: the next attempt gets to try again rather than serving the error. */
    @Test
    fun `does not cache a failure`() {
        val s = servico(ShowcaseResult.Error, ShowcaseResult.Error)
        s.fetch("600281322")
        s.fetch("600281322")
        assertEquals(listOf("enka", "mihomo", "enka", "mihomo"), chamadas)
    }

    /**
     * The `/build` picker reads [ShowcaseService.cached] on every keystroke and warms on a miss, so
     * the guard that matters is that a warm on an already-cached UID buys no request at all.
     */
    @Test
    fun `warm fetches once and cached serves the picker for free`() {
        val s = servico(ShowcaseResult.Ok(perfilEnka), ShowcaseResult.Error)
        assertNull(s.cached("600281322"))
        s.warm("600281322")
        assertEquals(perfilEnka, s.cached("600281322"))
        s.warm("600281322")
        assertEquals(listOf("enka"), chamadas)
    }

    /** Flipping the env var reverses the chain without disabling either half of it. */
    @Test
    fun `showcase-source mihomo puts mihomo first and enka behind it`() {
        val r = servico(ShowcaseResult.Ok(perfilEnka), ShowcaseResult.Error, fonte = "mihomo").fetch("600281322")
        assertEquals(perfilEnka, (r as ShowcaseResult.Ok).profile)
        assertEquals(listOf("mihomo", "enka"), chamadas)
    }
}
