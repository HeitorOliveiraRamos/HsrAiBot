package com.hsrbot.card

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The card draws pixels, so what is worth pinning is that the requester's own picture actually
 * reaches the canvas — the whole point of the upload — and that a card without one still renders.
 *
 * No network: every asset hash is null here, and a null hash is answered without a request.
 */
class AscensaoRendererTest {

    private val base = Ascensao(
        nome = "Blade",
        elemento = "Vento",
        caminho = "A Destruição",
        personagem = listOf(Custo(308_000), Custo(290), Custo(65)),
        rastros = listOf(Custo(3_000_000)),
    )

    private fun pixels(img: BufferedImage): IntArray =
        img.getRGB(0, 0, img.width, img.height, null, 0, img.width)

    /**
     * Deliberately NOT a flat fill: a uniform picture looks the same however it is framed, so a
     * re-framing test against one passes whether or not the framing is applied at all.
     */
    private fun imagem(cor: Color) = BufferedImage(600, 900, BufferedImage.TYPE_INT_ARGB).apply {
        createGraphics().apply {
            color = cor
            fillRect(0, 0, 600, 900)
            color = Color.WHITE
            fillOval(120, 80, 200, 200)
            color = Color.BLACK
            fillRect(0, 700, 600, 200)
            dispose()
        }
    }

    @Test
    fun `renderiza sem arte nenhuma sem explodir`() {
        val img = AscensaoRenderer.render(base)
        assertEquals(1080, img.width)
        assertEquals(1080, img.height)
    }

    @Test
    fun `a imagem enviada vai pro card, mesmo sem arte_foco guardado`() {
        // An upload has no curated box and must not need one: it is framed as the whole picture, or
        // the member's own art would be silently dropped for every character nobody has framed.
        assertFalse(
            pixels(AscensaoRenderer.render(base)).contentEquals(
                pixels(AscensaoRenderer.render(base, imagem(Color(0x30, 0x90, 0xC0)))),
            ),
            "a arte enviada não desenhou nada",
        )
    }

    @Test
    fun `reenquadrar move a arte enviada`() {
        // What the "Alterar enquadramento" modal is for: the same picture, framed twice, cannot come
        // back as the same card — otherwise the member nudges the numbers and nothing happens.
        val arte = imagem(Color(0x30, 0x90, 0xC0))
        assertFalse(
            pixels(AscensaoRenderer.render(base, arte)).contentEquals(
                pixels(AscensaoRenderer.render(base, arte, Foco(0.4, 0.1, 0.2, 0.3))),
            ),
            "o enquadramento não mudou nada no card",
        )
    }

    @Test
    fun `o credito so aparece na arte de quem mandou ela`() {
        val comNome = base.copy(arte = base.arte.copy(autor = "heitorzinho"))
        val arte = imagem(Color(0x30, 0x90, 0xC0))
        assertFalse(
            pixels(AscensaoRenderer.render(base, arte)).contentEquals(pixels(AscensaoRenderer.render(comNome, arte))),
            "o crédito não foi desenhado na arte enviada",
        )
        // The official illustration is nobody here's to sign, so a name with no upload behind it
        // must leave the card exactly as it was.
        assertContentEquals(
            pixels(AscensaoRenderer.render(base)),
            pixels(AscensaoRenderer.render(comNome)),
            "assinou um card que está usando a arte oficial",
        )
    }
}
