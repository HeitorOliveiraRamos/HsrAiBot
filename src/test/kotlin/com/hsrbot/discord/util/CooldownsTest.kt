package com.hsrbot.discord.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Cooldowns] is the only thing pacing the expensive commands, so a wrong branch here either
 * lets a held-down Enter through or locks everyone out. Pure logic, no Spring context.
 */
class CooldownsTest {

    @Test
    fun `the first attempt passes and the next one waits`() {
        val c = Cooldowns()
        assertEquals(0L, c.tentar("u:/build", 10))
        val espera = c.tentar("u:/build", 10)
        assertTrue(espera in 1..10, "esperava um resto de 1..10s, veio $espera")
    }

    @Test
    fun `zero seconds means no pacing at all, however many times it is called`() {
        val c = Cooldowns()
        repeat(5) { assertEquals(0L, c.tentar("u:/uid", 0)) }
    }

    @Test
    fun `keys are independent, so one command's wait never blocks another`() {
        val c = Cooldowns()
        assertEquals(0L, c.tentar(Cooldowns.comando("u", "build"), 10))
        assertEquals(0L, c.tentar(Cooldowns.comando("u", "uid"), 10))
        assertEquals(0L, c.tentar(Cooldowns.comando("outra", "build"), 10))
        assertEquals(0L, c.tentar(Cooldowns.mencao("u"), 10))
        assertTrue(c.tentar(Cooldowns.comando("u", "build"), 10) > 0)
    }

    @Test
    fun `an elapsed wait frees the key again`() {
        val c = Cooldowns()
        // Uma janela que expira dentro do teste. `tentar` conta em ms por dentro, então
        // 1s é o menor valor que a API aceita e ainda termina rápido.
        assertEquals(0L, c.tentar("u:/rank", 1))
        assertTrue(c.tentar("u:/rank", 1) > 0)
        Thread.sleep(1_100)
        assertEquals(0L, c.tentar("u:/rank", 1))
    }

    @Test
    fun `two threads racing on the same key let exactly one through`() {
        // O caso que a pressa causa de verdade: dois cliques no mesmo comando caindo em
        // threads de gateway diferentes. Sem o compute() atômico, os dois passavam.
        val c = Cooldowns()
        val passaram = java.util.concurrent.atomic.AtomicInteger()
        val largada = java.util.concurrent.CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                largada.await()
                if (c.tentar("u:/tierlist", 15) == 0L) passaram.incrementAndGet()
            }.apply { start() }
        }
        largada.countDown()
        threads.forEach { it.join() }
        assertEquals(1, passaram.get())
    }
}
