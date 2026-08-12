package com.hsrbot.card

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the ascension guide card: the splash and the character's name down the left, and two
 * material grids down the right — MELHORIA DE PERSONAGEM (6 cells) over MELHORIA DE RASTROS (9).
 *
 * That structure is the guide team's OLD hand-made card, kept as-is: same left/right split, same two
 * grids, name and stars at the foot of the splash rather than over its head. What changes is the
 * style — the wash, the white double frame, the ruled headers, the panels and the gold chips all
 * come from [CardRenderer], reused rather than restated, so the three cards a guide can produce
 * (build, tier list, ascension) stay one visual family. That reuse is why a handful of its drawing
 * primitives are `internal` instead of `private`.
 *
 * Input is a resolved [Ascensao] and nothing else: no database, no Spring. Icons are CDN content
 * hashes fetched by [CardRenderer.asset] and every lookup fails soft — a cell with no icon draws its
 * empty socket, which is exactly what the whole grid does until `materiais` is populated.
 */
object AscensaoRenderer {

    /**
     * [enviada] is art the requester uploaded with the command, already normalised. It wins over the
     * official illustration — they picked a picture on purpose — and, unlike a guide's upload, it is
     * never stored: this card has no draft and no cache to hang it on, so it lives exactly as long
     * as the render does.
     *
     * [focoEnviada] frames THAT picture and nothing else. It travels beside the image instead of on
     * `a.arte.foco` because the curated box measures the official illustration — applied to someone
     * else's it zooms in on empty background — so an upload starts from the whole image and the
     * member nudges it from there.
     */
    fun render(a: Ascensao, enviada: BufferedImage? = null, focoEnviada: Foco = INTEIRO): BufferedImage {
        // ponytail: same reason CardRenderer does it — plugin discovery is lazy and misses webp.
        ImageIO.scanForPlugins()

        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

        // An upload's own palette drives the chrome (borders, header rules, stars, panel tint), so the
        // card echoes the picture the member chose; official/greyscale art stays element-coloured with
        // the white/gold it always had. Same rule as the guide card.
        val pal = enviada?.let { Paleta.daArte(it, a.elemento) } ?: Paleta.elemento(a.elemento)
        Retrato.coluna(g, a, pal, enviada, focoEnviada)

        val fim = secao(g, "MELHORIA DE PERSONAGEM", a.personagem, Y_TOPO, pal)
        secao(g, "MELHORIA DE RASTROS", a.rastros, fim + GAP_SECAO, pal)

        CardRenderer.frame(g)
        g.dispose()
        return img
    }

    fun png(a: Ascensao, enviada: BufferedImage? = null, focoEnviada: Foco = INTEIRO): ByteArray =
        ByteArrayOutputStream().use { out ->
            ImageIO.write(render(a, enviada, focoEnviada), "png", out)
            out.toByteArray()
        }

    // -------------------- direita -------------------- //

    /**
     * One titled grid. Returns the panel's bottom edge, so the second section is placed off the
     * first instead of off a constant that has to be kept in sync with it.
     *
     * The row count comes from the cell count (6 → 2 rows, 9 → 3) rather than being fixed: the two
     * grids differ, and the card should not care which one it is drawing.
     */
    private fun secao(g: Graphics2D, titulo: String, custos: List<Custo>, y: Int, pal: Paleta): Int {
        val linhas = max(1, ceil(custos.size / COLUNAS.toDouble()).toInt())
        val altura = PAD * 2 + linhas * CELULA_H + (linhas - 1) * GAP
        CardRenderer.header(g, titulo, RX, y, RW, pal.regua)
        CardRenderer.panel(g, RX, y + 12, RW, altura, pal)

        custos.forEachIndexed { i, c ->
            celula(
                g, c,
                RX + PAD + (i % COLUNAS) * (CELULA_W + GAP),
                y + 12 + PAD + (i / COLUNAS) * (CELULA_H + GAP),
                pal,
            )
        }
        return y + 12 + altura
    }

    /** One material: its icon in a socket, with the amount on a chip under it. */
    private fun celula(g: Graphics2D, c: Custo, x: Int, y: Int, pal: Paleta) {
        val icone = CardRenderer.asset(c.icone)
        // The socket is drawn whether or not there is an icon — an empty cell must read as a slot
        // waiting for art, not as a gap in the grid.
        g.color = Color(0xFF, 0xFF, 0xFF, if (icone == null) 10 else 16)
        g.fill(RoundRectangle2D.Float((x).toFloat(), y.toFloat(), CELULA_W.toFloat(), SOQUETE_H.toFloat(), 18f, 18f))
        g.color = CardRenderer.alpha(pal.borda, if (icone == null) 60 else 90)
        g.stroke = BasicStroke(1.5f)
        g.draw(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), CELULA_W.toFloat(), SOQUETE_H.toFloat(), 18f, 18f))
        icone?.let { CardRenderer.drawContain(g, it, x + 10, y + 8, CELULA_W - 20, SOQUETE_H - 16) }

        chip(g, "x" + abreviar(c.qtd), x, y + SOQUETE_H + 8)
    }

    /** The amount chip, centred under a cell — the same black/gold pill the build card marks S1..S5 with. */
    private fun chip(g: Graphics2D, texto: String, x: Int, y: Int) {
        g.font = CardRenderer.din(21f, bold = true, tracking = 0.04)
        val w = max(64, g.fontMetrics.stringWidth(texto) + 28)
        val cx = x + (CELULA_W - w) / 2
        g.color = Color(0, 0, 0, 190)
        g.fill(RoundRectangle2D.Float(cx.toFloat(), y.toFloat(), w.toFloat(), CHIP_H.toFloat(), 14f, 14f))
        g.color = CardRenderer.alpha(GOLD, 200)
        g.stroke = BasicStroke(1.2f)
        g.draw(RoundRectangle2D.Float(cx.toFloat(), y.toFloat(), w.toFloat(), CHIP_H.toFloat(), 14f, 14f))
        g.color = GOLD
        g.drawString(texto, cx + (w - g.fontMetrics.stringWidth(texto)) / 2, y + 22)
    }

    /**
     * Amounts the way the guides write them: `308000` → `308K`, `3000000` → `3M`, `15` → `15`.
     * Anything under ten thousand is spelled out — the trace and ascension counts are the numbers a
     * player actually farms against, and rounding those to "0K" would be worse than useless.
     */
    internal fun abreviar(qtd: Long): String = when {
        qtd >= 1_000_000 -> String.format(Locale.ROOT, "%.1f", qtd / 1_000_000.0).removeSuffix(".0") + "M"
        qtd >= 10_000 -> "${(qtd / 1000.0).roundToInt()}K"
        else -> qtd.toString()
    }

    // -------------------- constantes -------------------- //

    private const val W = Retrato.W
    private const val H = Retrato.H

    /** Right column, matching the build card's so the two read as the same grid. */
    private const val RX = 548
    private const val RW = W - 40 - RX
    private const val PAD = 12
    private const val COLUNAS = 3
    private const val GAP = 14

    private const val CELULA_W = (RW - PAD * 2 - GAP * (COLUNAS - 1)) / COLUNAS
    private const val SOQUETE_H = 98
    private const val CHIP_H = 30
    private const val CELULA_H = SOQUETE_H + 8 + CHIP_H + 8

    /** Header baseline of the first section; the second is placed off the first's panel. */
    private const val Y_TOPO = 92
    private const val GAP_SECAO = 52

    private val GOLD = Color(0xF2, 0xC9, 0x6B)

    private val INTEIRO = Foco.INTEIRO
}
