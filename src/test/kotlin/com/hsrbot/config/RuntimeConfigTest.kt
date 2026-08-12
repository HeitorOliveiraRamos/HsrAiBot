package com.hsrbot.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser for the `.bot.runtime` file that `bot.sh --model` and `/ia` write. The cases that
 * matter: model tags contain `:` (so the split must be on the FIRST `=` only) and a
 * half-written or hand-edited file must never blank out a model name.
 */
class RuntimeConfigTest {

    private fun parse(s: String) = RuntimeConfig.parse(s)

    @Test
    fun `model tags keep their colon`() {
        val v = parse("voice=gemma4:12b-it-q8_0\nbrain=qwen2.5vl:latest\n")
        assertEquals("gemma4:12b-it-q8_0", v["voice"])
        assertEquals("qwen2.5vl:latest", v["brain"])
    }

    @Test
    fun `blank lines, comments and junk lines are ignored`() {
        val v = parse("\n# comentário\nvoice=a:1\nlixo sem igual\n  \n")
        assertEquals(mapOf("voice" to "a:1"), v)
    }

    @Test
    fun `keys and values are trimmed, keys lower-cased`() {
        assertEquals("a:1", parse("  VOICE = a:1  ")["voice"])
    }

    @Test
    fun `an empty vision value survives parsing as empty, not missing`() {
        // Distinct outcomes downstream: absent = keep current, empty = disable vision.
        val v = parse("vision=\n")
        assertTrue("vision" in v)
        assertEquals("", v["vision"])
        assertNull(parse("voice=a:1")["vision"])
    }
}
