package com.hsrbot.card

import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card must never cut a gear name, so the only thing worth pinning down is the wrapper:
 * every character survives, and no line is wider than the box it was given.
 */
class CardWrapTextTest {

    private val fm = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        .also { it.font = Font(Font.SANS_SERIF, Font.PLAIN, 13) }.fontMetrics

    @Test
    fun `wraps without losing text`() {
        val name = "Sigonia, a Desolação Abandonada"
        val lines = wrapText(fm, name, 120)
        assertTrue(lines.size > 1, "esperava quebra em várias linhas, veio $lines")
        assertEquals(name.replace(" ", ""), lines.joinToString("").replace(" ", ""))
        assertTrue(lines.all { fm.stringWidth(it) <= 120 }, "linha estourou a caixa: $lines")
    }

    @Test
    fun `hard-breaks a single word wider than the box`() {
        val lines = wrapText(fm, "Contrarrevolucionariamente", 60)
        assertTrue(lines.size > 1)
        assertEquals("Contrarrevolucionariamente", lines.joinToString(""))
        assertTrue(lines.all { fm.stringWidth(it) <= 60 })
    }
}
