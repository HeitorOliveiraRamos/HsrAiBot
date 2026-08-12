package com.hsrbot.card

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [normalizarArte] is the only place a stranger's bytes are turned into something this bot will
 * decode again later, so it is a trust boundary and gets tested like one: what it accepts, what it
 * refuses, and — the case that matters — that it refuses the dangerous one WITHOUT decoding it.
 */
class ArteEnviadaTest {

    private fun png(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.createGraphics().apply {
            color = java.awt.Color(0x30, 0x90, 0xC0)
            fillRect(0, 0, w, h)
            dispose()
        }
        return ByteArrayOutputStream().use {
            ImageIO.write(img, "png", it)
            it.toByteArray()
        }
    }

    private fun tamanho(bytes: ByteArray): Pair<Int, Int> =
        assertNotNull(ImageIO.read(ByteArrayInputStream(bytes))).let { it.width to it.height }

    @Test
    fun `uma imagem de verdade volta como png`() {
        val saida = assertNotNull(normalizarArte(png(400, 300)))
        assertEquals(400 to 300, tamanho(saida), "uma imagem pequena não devia ser redimensionada")
    }

    @Test
    fun `uma imagem grande demais volta encolhida, com a proporcao intacta`() {
        // The card scales the illustration to ~82% of a 1080px canvas, so storing more than this is
        // decoding and keeping pixels that get thrown away at render time anyway.
        val (w, h) = tamanho(assertNotNull(normalizarArte(png(4000, 2000))))
        assertEquals(1280, w, "o lado maior tinha que cair no teto")
        assertEquals(640, h, "a proporção mudou")
    }

    @Test
    fun `o que nao e imagem nao vira arte`() {
        assertNull(normalizarArte("nem de longe um png".toByteArray()))
        assertNull(normalizarArte(ByteArray(0)))
        // A real PNG with its tail cut off: the header parses, the pixels do not.
        assertNull(normalizarArte(png(64, 64).copyOfRange(0, 40)))
    }

    @Test
    fun `um png que diz ter 25000 por 25000 e recusado sem ser decodificado`() {
        // The bomb: a PNG of flat colour compresses to almost nothing, so a few harmless-looking
        // kilobytes claim a 2.5GB raster. If normalizarArte ever stops reading the header FIRST,
        // this test does not fail politely — it takes the JVM's heap with it, which is exactly the
        // failure it exists to prevent happening in the bot.
        val bomba = comTamanhoNoCabecalho(png(64, 64), 25_000, 25_000)
        assertNull(normalizarArte(bomba))
    }

    /**
     * The same PNG, with the width/height in its IHDR rewritten and the chunk's CRC fixed up so a
     * reader still accepts the header. Layout: 8-byte signature, 4-byte length, `IHDR`, then the
     * 13 bytes of header data; the CRC covers the type and the data.
     */
    private fun comTamanhoNoCabecalho(png: ByteArray, w: Int, h: Int): ByteArray {
        val out = png.copyOf()
        ByteBuffer.wrap(out).putInt(16, w).putInt(20, h)
        val crc = CRC32().apply { update(out, 12, 17) }
        ByteBuffer.wrap(out).putInt(29, crc.value.toInt())
        // Sanity: the doctored header has to still READ as 25000x25000, or the test proves nothing.
        val lido = ImageIO.createImageInputStream(ByteArrayInputStream(out)).use { entrada ->
            ImageIO.getImageReaders(entrada).next().let {
                it.input = entrada
                it.getWidth(0) to it.getHeight(0)
            }
        }
        assertTrue(lido == w to h, "o cabeçalho remendado não colou: $lido")
        return out
    }
}
