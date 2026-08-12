package com.hsrbot.card

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The palette extraction is a bucket-and-rank parser — the kind of thing that silently returns one
 * hue three times, or the wrong three. This pins that a three-colour image gives three DISTINCT
 * hues, and that a greyscale one gives nothing (so the caller falls back to the element colour).
 */
class PaletaTest {

    /** A tall opaque portrait split into lavender / cyan / pink patches — Castorice's palette, roughly. */
    private fun tresCores(): BufferedImage {
        val img = BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(160, 120, 200); g.fillRect(0, 0, 400, 600)       // lavender ground
        g.color = Color(80, 200, 220); g.fillRect(20, 40, 150, 150)      // cyan patch
        g.color = Color(230, 120, 180); g.fillRect(220, 380, 150, 150)   // pink patch
        g.dispose()
        return img
    }

    @Test fun `palette pulls three distinct hues`() {
        val cores = CardRenderer.palette(tresCores(), 3)
        assertEquals(3, cores.size, "three colours in, three hues out")
        val matizes = cores.map { Color.RGBtoHSB(it.red, it.green, it.blue, null)[0] }
        // Every pair is more than a bin apart on the wheel — no colour is a near-duplicate of another.
        for (i in matizes.indices) for (j in i + 1 until matizes.size) {
            val d = kotlin.math.abs(matizes[i] - matizes[j])
            assertTrue(minOf(d, 1 - d) > 0.06f, "hues $i and $j collapsed to the same colour")
        }
    }

    @Test fun `greyscale art yields no palette`() {
        val cinza = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        cinza.createGraphics().apply { color = Color(120, 120, 120); fillRect(0, 0, 64, 64); dispose() }
        assertTrue(CardRenderer.palette(cinza, 3).isEmpty(), "no hue to extract → empty → element fallback")
    }

    /**
     * Not an assertion — a way to eyeball a card without a running bot. Opt-in: set `CARD_DUMP` to
     * an output path and it renders the three-colour art panned to expose a gap, so palette, blur
     * fill and edge feather can all be seen at once.
     */
    @Test fun `dump a card to eyeball`() {
        val out = System.getenv("CARD_DUMP") ?: return
        val artFile = File.createTempFile("castorice", ".png").also { ImageIO.write(tresCores(), "png", it) }
        val ficha = Ficha(
            nome = "CASTORICE",
            elemento = "quantum",
            caminho = "remembrance",
            raridade = (System.getenv("RARIDADE") ?: "5").toInt(),
            // Panned up so the image's bottom edge lifts onto the canvas — the gap the user hit.
            arte = Arte(enviada = artFile.toPath(), foco = Foco(0.0, 0.55, 1.0, 1.0), enquadramentoLivre = true, v2 = true),
            rastros = listOf(Rastro("Talento"), Rastro("Habilidade")),
            cones = listOf(Cone("O Adeus Mais Belo", sobreposicao = 2)),
            reliquias = listOf(Linha(Parte("Poetisa do Colapso Enlutada", pecas = 4))),
            ornamentos = listOf(Linha(Parte("Domínio Sereno", pecas = 2))),
            status = Status(corpo = "PV%", pes = "VEL", esfera = "Dano Quântico", corda = "PV%"),
            sinergias = listOf(Sinergia("Tribbie"), Sinergia("Remembrance")),
        )
        ImageIO.write(CardRenderer.render(ficha), "png", File(out))
    }
}
