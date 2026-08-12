package com.hsrbot.card

import com.hsrbot.card.Enquadramento.Dir
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The button framing has no pixels to assert on, but the arithmetic behind the arrows is exactly the
 * kind that silently inverts: the crop moves opposite to the character, and a zoom has to hold the
 * subject still. These pin the direction of each nudge, that zoom keeps the centre, that nothing
 * leaves the drawable range, and that an upload opens framed to its subject rather than its canvas.
 */
class EnquadramentoTest {

    private val meio = Foco(0.4, 0.4, 0.2, 0.2) // a small centred box, room to move every way

    @Test fun `arrows move the character the way they point`() {
        // The crop slides the OPPOSITE way to the character: right on the card is x down.
        assertTrue(Enquadramento.aplicar(meio, Dir.DIREITA).x < meio.x)
        assertTrue(Enquadramento.aplicar(meio, Dir.ESQUERDA).x > meio.x)
        // Up on the card is a LARGER y, the sense drawScene reads.
        assertTrue(Enquadramento.aplicar(meio, Dir.CIMA).y > meio.y)
        assertTrue(Enquadramento.aplicar(meio, Dir.BAIXO).y < meio.y)
    }

    @Test fun `zoom scales the box but keeps the character where they are`() {
        val perto = Enquadramento.aplicar(meio, Dir.MAIS_ZOOM)
        val longe = Enquadramento.aplicar(meio, Dir.MENOS_ZOOM)
        assertTrue(perto.w < meio.w && perto.h < meio.h, "zoom in tightens the crop")
        assertTrue(longe.w > meio.w && longe.h > meio.h, "zoom out loosens it")
        // Centre (x + w/2, y + h/2) is untouched by a zoom.
        assertEquals(meio.x + meio.w / 2, perto.x + perto.w / 2, 1e-9)
        assertEquals(meio.y + meio.h / 2, perto.y + perto.h / 2, 1e-9)
    }

    @Test fun `pan is bounded but reaches a full image-width past either edge`() {
        // A flat upload opens at the whole image; the character can only traverse the card if the
        // anchor may cross the centre in BOTH directions, so pan must be free of the old [0,1] wall.
        val inteiro = Foco(0.0, 0.0, 1.0, 1.0)
        val direitaDemais = generateSequence(inteiro) { Enquadramento.aplicar(it, Dir.DIREITA) }.take(200).last()
        assertTrue(direitaDemais.x < 0.0, "▶ on an edge frame must pan past the image, not stall at 0")
        // Still bounded: a runaway press can't send the anchor to infinity.
        val esquerdaDemais = generateSequence(inteiro) { Enquadramento.aplicar(it, Dir.ESQUERDA) }.take(200).last()
        assertTrue(direitaDemais.x >= -1.0 && esquerdaDemais.x <= 2.0)

        // Zooming in forever floors the box instead of collapsing it to nothing.
        val minimo = generateSequence(meio) { Enquadramento.aplicar(it, Dir.MAIS_ZOOM) }.take(100).last()
        assertTrue(minimo.w > 0.0 && minimo.h > 0.0)
        assertEquals(minimo.w, Enquadramento.aplicar(minimo, Dir.MAIS_ZOOM).w, 1e-12, "floored, so it stops shrinking")
    }

    @Test fun `auto frames an upload to its opaque subject`() {
        // A 100x200 canvas with the character in a 20,40 - 60,140 block, the rest transparent.
        val img = BufferedImage(100, 200, BufferedImage.TYPE_INT_ARGB)
        for (y in 40 until 140) for (x in 20 until 60) img.setRGB(x, y, 0xFF3366CC.toInt())
        val f = Enquadramento.auto(img)
        assertEquals(0.20, f.x, 1e-9)
        assertEquals(0.20, f.y, 1e-9) // 40 / 200
        assertEquals(0.40, f.w, 1e-9) // (60-20) / 100
        assertEquals(0.50, f.h, 1e-9) // (140-40) / 200
    }

    @Test fun `auto falls back to the whole image when there is nothing to trim`() {
        val opaca = BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB) // no alpha at all
        assertEquals(Foco.INTEIRO, Enquadramento.auto(opaca))
        val vazia = BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB) // fully transparent
        assertEquals(Foco.INTEIRO, Enquadramento.auto(vazia))
    }
}
