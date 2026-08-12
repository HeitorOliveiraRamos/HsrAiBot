package com.hsrbot.card

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.LinearGradientPaint
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The card draws pixels, so what is worth pinning is what the live previews cannot show: the two
 * rows a healthy showcase never produces (an empty slot, a wrong main stat), and that a requester's
 * own picture reaches the canvas.
 *
 * No network: every asset hash is null here, and a null hash is answered without a request.
 */
class AvaliacaoRendererTest {

    private val base = Avaliacao(
        nome = "Castorice",
        elemento = "Quântico",
        caminho = "A Recordação",
        nivel = 80,
        eidolon = 2,
        // 0–100, como sai do FribbelsScorer: o card é quem divide por 10 pra desenhar "8,44".
        nota = 84.4,
        rank = "SS",
        regua = "vel 0%",
        jogador = "tore",
        uid = "600281322",
        cone = Cone("O Adeus Mais Belo", sobreposicao = 2),
        conjuntos = listOf(Parte("Poetisa do Colapso Enlutada", pecas = 4)),
        stats = listOf(Stat("PV", "9088"), Stat("VEL", "87")),
        pecas = (1..6).map {
            Peca(
                it, "Peça $it", nivel = 15, main = "PV%", nota = 80.9, rank = "SS",
                // Cinco melhorias no total, como toda peça +15 que nasceu com quatro substats —
                // inclusive uma linha que nunca subiu, que o jogo desenha sem seta nenhuma.
                subs = listOf(
                    Sub("PV%", "17.2%", 3, util = true), Sub("CRIT", "5.8%", 1, util = true),
                    Sub("D.CRIT", "11.6%", 1, util = true), Sub("VEL", "2", 0, util = true),
                ),
            )
        },
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
        val img = AvaliacaoRenderer.render(base)
        assertEquals(1080, img.width)
        assertEquals(1080, img.height)
    }

    /**
     * A card built from Enka carries no finished stat panel (Enka computes none, mihomo does), so
     * the STATUS block simply isn't drawn. Everything that IS the verdict — the note, the relic
     * rows — has to survive that, since this is now the DEFAULT card, not an edge case.
     */
    @Test
    fun `renderiza sem o painel de status, como vem da enka`() {
        val semStatus = AvaliacaoRenderer.render(base.copy(stats = emptyList()))
        assertEquals(1080, semStatus.width)
        // Same canvas, different content: a dropped block must not silently blank the whole card.
        assertFalse(pixels(semStatus).all { it == pixels(semStatus).first() })
        assertFalse(pixels(semStatus).contentEquals(pixels(AvaliacaoRenderer.render(base))))
    }

    /** The rows a good build never produces — and the ones a player most needs to see. */
    @Test
    fun `slot vazio e main errado desenham diferente de uma peca normal`() {
        val vazio = base.copy(pecas = base.pecas.map { if (it.slot == 6) it.copy(vazio = true) else it })
        assertFalse(
            pixels(AvaliacaoRenderer.render(base)).contentEquals(pixels(AvaliacaoRenderer.render(vazio))),
            "o slot vazio não desenhou nada",
        )

        val errado = base.copy(
            pecas = base.pecas.map { if (it.slot == 5) it.copy(mainErrado = true, rank = "D", nota = 23.1) else it },
        )
        assertFalse(
            pixels(AvaliacaoRenderer.render(base)).contentEquals(pixels(AvaliacaoRenderer.render(errado))),
            "o main fora do ideal não desenhou nada",
        )
    }

    /**
     * A dead roll has to LOOK dead. It is the same string either way, so if the colour were dropped
     * the rows would still read as a full set of useful substats — which is exactly the reading the
     * greyed-out line exists to prevent.
     */
    @Test
    fun `um substat sem uso desenha diferente de um que conta`() {
        val morto = base.copy(
            pecas = base.pecas.map { p ->
                p.copy(subs = p.subs.mapIndexed { i, s -> if (i == 1) s.copy(util = false) else s })
            },
        )
        assertFalse(
            pixels(AvaliacaoRenderer.render(base)).contentEquals(pixels(AvaliacaoRenderer.render(morto))),
            "o substat sem uso saiu com a mesma cor de um útil",
        )
        // E as setas são conteúdo, não enfeite: uma melhoria a mais tem que mudar o card.
        val maisUma = base.copy(
            pecas = base.pecas.map { p -> p.copy(subs = p.subs.map { it.copy(melhorias = it.melhorias + 1) }) },
        )
        assertFalse(
            pixels(AvaliacaoRenderer.render(base)).contentEquals(pixels(AvaliacaoRenderer.render(maisUma))),
            "as setas de melhoria não foram desenhadas",
        )
    }

    @Test
    fun `a imagem enviada vai pro card, mesmo sem arte_foco guardado`() {
        // An upload has no curated box and must not need one: it is framed as the whole picture, or
        // the member's own art would be silently dropped for every character nobody has framed.
        assertFalse(
            pixels(AvaliacaoRenderer.render(base)).contentEquals(
                pixels(AvaliacaoRenderer.render(base, imagem(Color(0x30, 0x90, 0xC0)))),
            ),
            "a arte enviada não desenhou nada",
        )
    }

    /** Rank colours are the card's whole at-a-glance story: SS must not read like D, and the `+`
     *  half-step must not read like the letter under it. */
    @Test
    fun `cada rank tem sua cor`() {
        val cores = listOf("SSS+", "SSS", "SS+", "SS", "S+", "S", "A+", "A", "B+", "B", "C", "F")
            .map { AvaliacaoRenderer.corDoRank(it) }
        assertEquals(cores.size, cores.distinct().size, "dois ranks com a mesma cor")
    }

    /** `?` is a refusal to grade, not a bad grade, so it must not read as the red "farm this" — and
     *  `???` is the one band that is not a single colour at all. */
    @Test
    fun `a interrogação não é vermelha e ??? é multicromático`() {
        assertNotEquals(AvaliacaoRenderer.corDoRank("F"), AvaliacaoRenderer.corDoRank("?"))
        assertNotEquals(AvaliacaoRenderer.corDoRank("C"), AvaliacaoRenderer.corDoRank("?"))
        assertTrue(AvaliacaoRenderer.tintaDoRank("???", 0, 76) is LinearGradientPaint)
        assertTrue(AvaliacaoRenderer.tintaDoRank("SSS+", 0, 76) is Color)
        // Um número de largura zero não pode explodir o LinearGradientPaint (início == fim).
        assertTrue(AvaliacaoRenderer.tintaDoRank("???", 10, 0) is LinearGradientPaint)
    }
}
