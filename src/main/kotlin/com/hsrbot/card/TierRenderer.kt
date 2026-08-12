package com.hsrbot.card

import org.slf4j.LoggerFactory
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Point2D
import java.awt.GradientPaint
import java.awt.RadialGradientPaint
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws a 1080×1080 tier list: four papel columns across, five tier rows down, avatars flowing
 * inside each cell.
 *
 * The one thing that makes this different from [CardRenderer] is that **nothing is a fixed band**.
 * A tier holds anywhere from nothing to the entire roster, so the row heights and the avatar size
 * are both computed from the contents by [layout] before a pixel is drawn — see it for the sizing
 * rule and the ceiling.
 *
 * Input is a resolved [TierList] and nothing else: no database, no Spring. Avatars are CDN content
 * hashes fetched by [CardRenderer.asset] (cached on disk), and every lookup fails soft — a missing
 * avatar draws its empty socket rather than breaking the image.
 */
object TierRenderer {

    fun render(t: TierList): BufferedImage {
        // ponytail: same reason CardRenderer does it — plugin discovery is lazy and misses webp.
        ImageIO.scanForPlugins()

        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

        fundo(g)
        cabecalho(g, t)
        rotulosDeColuna(g, t)
        tiers(g, t)
        rodape(g, t)

        g.dispose()
        return img
    }

    fun png(t: TierList): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(render(t), "png", out)
        out.toByteArray()
    }

    // -------------------- seções -------------------- //

    private fun fundo(g: Graphics2D) {
        g.paint = GradientPaint(0f, 0f, Color(0x16, 0x0E, 0x1B), 0f, H.toFloat(), Color(0x07, 0x05, 0x0A))
        g.fillRect(0, 0, W, H)
        // One wash behind the title, so the header reads as a band without needing a box around it.
        //
        // Filled over the WHOLE canvas, not over the top 320px it is visible in: a radial gradient
        // clipped by a fillRect ends at the rectangle's edge, and since the gradient is still well
        // above zero alpha there, that edge draws as a hard purple band straight across the image.
        // The gradient reaches zero on its own at the radius — let it.
        g.paint = RadialGradientPaint(
            Point2D.Float(W / 2f, 60f), 820f, floatArrayOf(0f, 1f),
            arrayOf(Color(0x7B, 0x4B, 0xC9, 90), Color(0x7B, 0x4B, 0xC9, 0)),
        )
        g.fillRect(0, 0, W, H)
    }

    private fun cabecalho(g: Graphics2D, t: TierList) {
        val logo = Marca.logo
        if (logo != null) {
            CardRenderer.drawContain(g, logo, MARGEM, 38, 68, 68)
        } else {
            g.color = CardRenderer.alpha(GOLD, 60)
            g.stroke = BasicStroke(2f)
            g.drawOval(MARGEM, 38, 68, 68)
        }

        g.font = CardRenderer.din(19f, tracking = 0.32)
        g.color = GOLD
        g.drawString("TIER LIST", TX, 60)

        // The version pill is drawn first because the title has to know how much room is left: a
        // long mode name ("SOMBRA APOCALÍPTICA") must shrink rather than run under it.
        val pilulaW = t.versao?.let { pilula(g, it) } ?: 0
        val limite = W - MARGEM - pilulaW - (if (pilulaW > 0) 20 else 0) - TX
        val titulo = t.modo.uppercase()
        var tamanho = 41f
        while (tamanho > 20f) {
            g.font = CardRenderer.din(tamanho, bold = true, tracking = 0.04)
            if (g.fontMetrics.stringWidth(titulo) <= limite) break
            tamanho -= 1f
        }
        g.color = Color.WHITE
        g.drawString(titulo, TX, 99)
    }

    /** The version pill, right-aligned in the header. Returns the width it took. */
    private fun pilula(g: Graphics2D, versao: String): Int {
        g.font = CardRenderer.din(20f, tracking = 0.06)
        val w = max(88, g.fontMetrics.stringWidth(versao) + 40)
        val x = W - MARGEM - w
        g.color = Color(0xFF, 0xFF, 0xFF, 16)
        g.fill(RoundRectangle2D.Float(x.toFloat(), 50f, w.toFloat(), 40f, 20f, 20f))
        g.color = Color(0xFF, 0xFF, 0xFF, 36)
        g.stroke = BasicStroke(1f)
        g.draw(RoundRectangle2D.Float(x.toFloat(), 50f, w.toFloat(), 40f, 20f, 20f))
        g.color = DIM
        g.drawString(versao, x + (w - g.fontMetrics.stringWidth(versao)) / 2, 77)
        return w
    }

    /**
     * The four papel names as letter-spaced labels over a single hairline — not as boxed table
     * cells. The boxes are what make the reference layout read as a spreadsheet.
     */
    /**
     * The four papel names, over a hairline with a gold tab under each.
     *
     * They carry as much of the image's meaning as the tier letters do — a column is half of what
     * placing a character says — so they are drawn near-white and bold rather than as the muted
     * caption they started as. The gold tab is what ties a label to its column without putting a box
     * back around it.
     */
    private fun rotulosDeColuna(g: Graphics2D, t: TierList) {
        g.color = Color(0xFF, 0xFF, 0xFF, 26)
        g.fillRect(CX0, Y_ROTULOS + 13, COL_W * COLUNAS, 1)

        g.font = CardRenderer.din(19f, bold = true, tracking = 0.20)
        t.colunas.forEachIndexed { i, c ->
            val rotulo = c.rotulo.uppercase()
            val w = g.fontMetrics.stringWidth(rotulo)
            val cx = CX0 + i * COL_W + COL_W / 2
            g.color = Color(0xEF, 0xE7, 0xF2)
            g.drawString(rotulo, cx - w / 2, Y_ROTULOS)
            g.color = CardRenderer.alpha(GOLD, 170)
            g.fillRect(cx - w / 2, Y_ROTULOS + 12, w, 3)
        }
    }

    private fun tiers(g: Graphics2D, t: TierList) {
        val contagens = (0 until TIERS).map { tier ->
            (0 until COLUNAS).map { col -> t.colunas.getOrNull(col)?.tiers?.getOrNull(tier)?.size ?: 0 }
        }
        val l = layout(contagens)

        var y = Y_TIERS
        (0 until TIERS).forEach { tier ->
            val h = l.alturas[tier]
            val cor = CORES_TIER[tier]

            g.color = Color(0xFF, 0xFF, 0xFF, 9)
            g.fill(RoundRectangle2D.Float(CX0.toFloat(), y.toFloat(), (COL_W * COLUNAS).toFloat(), h.toFloat(), 14f, 14f))

            // The tier chip runs the row's FULL height, so a tall row reads as one block instead of
            // a letter floating beside a wall of avatars.
            g.paint = GradientPaint(0f, y.toFloat(), cor, 0f, (y + h).toFloat(), CardRenderer.alpha(cor, 140))
            g.fill(RoundRectangle2D.Float(CHIP_X.toFloat(), y.toFloat(), CHIP_W.toFloat(), h.toFloat(), 14f, 14f))
            g.font = CardRenderer.din(42f, bold = true)
            g.color = Color(0x12, 0x0C, 0x16)
            val letra = LETRAS[tier]
            g.drawString(letra, CHIP_X + (CHIP_W - g.fontMetrics.stringWidth(letra)) / 2, y + h / 2 + 15)

            (0 until COLUNAS).forEach { col ->
                if (col > 0) {
                    g.color = Color(0xFF, 0xFF, 0xFF, 18)
                    g.fillRect(CX0 + col * COL_W, y + 10, 1, h - 20)
                }
                celula(g, t.colunas.getOrNull(col)?.tiers?.getOrNull(tier).orEmpty(), CX0 + col * COL_W, y, l, l.linhas[tier])
            }
            y += h + GAP_TIER
        }
    }

    /**
     * One cell's avatars, wrapped to [Layout.porLinha] across and clipped to [linhas] rows. The clip
     * only ever fires in the pathological case [layout] documents; when it does, the last drawn slot
     * becomes a `+N` chip rather than the list simply ending without saying so.
     */
    private fun celula(g: Graphics2D, avatares: List<Avatar>, x: Int, y: Int, l: Layout, linhas: Int) {
        if (avatares.isEmpty()) return
        val cabem = l.porLinha * linhas
        val desenhados = if (avatares.size <= cabem) avatares.size else cabem - 1
        // Spread across the column's full width rather than packed at the minimum gap: a full row
        // then reaches both insets, which is what puts the avatars under the centred column label
        // instead of huddled on its left half. Never tighter than the packed step, since porLinha
        // was derived from that same width.
        val passoX = if (l.porLinha > 1) (USAVEL - l.d) / (l.porLinha - 1) else 0
        val passoY = l.d + GAP

        fun px(i: Int) = x + PAD + (i % l.porLinha) * passoX
        fun py(i: Int) = y + PAD_TIER + (i / l.porLinha) * passoY

        repeat(desenhados) { i -> avatar(g, avatares[i], px(i), py(i), l.d) }
        if (desenhados < avatares.size) {
            mais(g, avatares.size - desenhados, px(desenhados), py(desenhados), l.d)
        }
    }

    /**
     * One character: the portrait in a circle, ringed in their ELEMENT's colour.
     *
     * The ring is what makes a 44px avatar readable at all — at this size the portraits are a wash
     * of similar tones, and the element is the one attribute a tier list reader actually scans for.
     */
    private fun avatar(g: Graphics2D, a: Avatar, x: Int, y: Int, d: Int) {
        val accent = CardRenderer.accentFor(a.elemento)
        val clip = g.clip
        g.clip = Ellipse2D.Float(x.toFloat(), y.toFloat(), d.toFloat(), d.toFloat())
        g.color = Color(0x2A, 0x20, 0x33)
        g.fillOval(x, y, d, d)
        CardRenderer.asset(a.icone)?.let { CardRenderer.drawContain(g, it, x, y, d, d) }
        g.clip = clip

        g.color = CardRenderer.alpha(accent, 220)
        g.stroke = BasicStroke(max(2f, d / 18f))
        g.drawOval(x, y, d, d)
        if (a.mudanca != Mudanca.NENHUMA) marcador(g, a.mudanca, x, y, d)
    }

    /** The `+N` that closes a cell too tall to draw. See [layout]. */
    private fun mais(g: Graphics2D, n: Int, x: Int, y: Int, d: Int) {
        g.color = Color(0x2A, 0x20, 0x33)
        g.fillOval(x, y, d, d)
        g.color = CardRenderer.alpha(GOLD, 150)
        g.stroke = BasicStroke(max(2f, d / 18f))
        g.drawOval(x, y, d, d)
        g.font = CardRenderer.din(d * 0.36f, bold = true)
        g.color = GOLD
        val s = "+$n"
        g.drawString(s, x + (d - g.fontMetrics.stringWidth(s)) / 2, y + d / 2 + (d * 0.13f).roundToInt())
    }

    /** Up/down/new, as a small badge on the avatar's bottom-right corner. */
    private fun marcador(g: Graphics2D, m: Mudanca, x: Int, y: Int, d: Int) {
        val s = max(14, (d * 0.40).roundToInt())
        val bx = x + d - s
        val by = y + d - s
        g.color = Color(0x10, 0x0B, 0x14)
        g.fillOval(bx, by, s, s)
        g.color = corDe(m)
        g.stroke = BasicStroke(1.5f)
        g.drawOval(bx, by, s, s)
        g.fill(glifo(m, bx + s / 2.0, by + s / 2.0, s * 0.26))
    }

    private fun corDe(m: Mudanca) = when (m) {
        Mudanca.SUBIU -> SUBIU
        Mudanca.DESCEU -> DESCEU
        else -> GOLD
    }

    /**
     * The badge glyphs, drawn as shapes rather than as "▲▼✦" text: at 18px the arrow characters are
     * hinted into mush and the star is missing from most of the fallback families.
     */
    private fun glifo(m: Mudanca, cx: Double, cy: Double, r: Double): GeneralPath {
        val p = GeneralPath()
        when (m) {
            Mudanca.SUBIU -> {
                p.moveTo(cx, cy - r); p.lineTo(cx + r, cy + r * 0.8); p.lineTo(cx - r, cy + r * 0.8)
            }
            Mudanca.DESCEU -> {
                p.moveTo(cx, cy + r); p.lineTo(cx + r, cy - r * 0.8); p.lineTo(cx - r, cy - r * 0.8)
            }
            // A four-point diamond for "nova" — it has to read as neither direction.
            else -> {
                p.moveTo(cx, cy - r * 1.2); p.lineTo(cx + r * 0.8, cy)
                p.lineTo(cx, cy + r * 1.2); p.lineTo(cx - r * 0.8, cy)
            }
        }
        p.closePath()
        return p
    }

    private fun rodape(g: Graphics2D, t: TierList) {
        g.color = Color(0xFF, 0xFF, 0xFF, 20)
        g.fillRect(MARGEM, Y_RODAPE, W - MARGEM * 2, 1)

        val baseCredito = if (t.temMudancas) H - 24 else H - 36
        if (t.temMudancas) {
            g.font = CardRenderer.din(15f, tracking = 0.06)
            var x = MARGEM
            listOf(Mudanca.SUBIU to "subiu", Mudanca.DESCEU to "desceu", Mudanca.NOVA to "nova")
                .forEach { (m, rotulo) ->
                    g.color = corDe(m)
                    g.fill(glifo(m, x + 6.0, (H - 57).toDouble(), 5.5))
                    g.color = Color(0x9C, 0x93, 0xA5)
                    g.drawString(rotulo, x + 18, H - 52)
                    x += 18 + g.fontMetrics.stringWidth(rotulo) + 22
                }
        }

        g.font = CardRenderer.din(17f, tracking = 0.06)
        g.color = Color(0x9C, 0x93, 0xA5)
        // The author's name is what the anonymous option removes; the server tag only shows when
        // the operator configured one, so an unbranded bot draws just "por Fulano" (or nothing).
        val credito = listOfNotNull(t.autor?.let { "por $it" }, Marca.tag).joinToString(" · ")
        g.drawString(credito, MARGEM, baseCredito)
        val jogo = "HONKAI: STAR RAIL"
        g.drawString(jogo, W - MARGEM - g.fontMetrics.stringWidth(jogo), baseCredito)
    }

    // -------------------- layout -------------------- //

    internal data class Layout(
        /** Avatar diameter, the same in every cell — a per-cell size would read as a bug. */
        val d: Int,
        val porLinha: Int,
        /** Rows each tier row is drawn at, in S→D order. */
        val linhas: List<Int>,
        val alturas: List<Int>,
    )

    /**
     * How big the avatars are and how tall each tier row is, from the cell counts alone.
     *
     * A tier row is as tall as its FULLEST column, floored at one row so an empty tier still shows
     * its letter. That is the whole "tiers adjust to the quantity" rule: an empty column costs
     * nothing, and the D row of a list nobody put anything in is a thin strip rather than the
     * reference layout's grey void.
     *
     * The avatar size is then the largest of [DIAMETROS] whose total fits the space between the
     * column labels and the footer — one descending scan, ~10 iterations, no layout engine. In
     * practice it never leaves the first two entries: 97 characters across 20 cells is roughly 15
     * rows, and the budget holds 15 at 44px.
     *
     * ponytail: past what even the smallest diameter fits — a list with several hundred placements,
     * which needs the same character in most of the twenty cells — the rows are scaled down
     * proportionally and each over-full cell ends in a `+N` chip. That is a legibility floor, not a
     * data limit: nothing refuses the picks, the image just stops promising to show all of them.
     */
    internal fun layout(contagens: List<List<Int>>): Layout {
        fun linhasDe(porLinha: Int) = contagens.map { tier ->
            max(1, tier.maxOfOrNull { ceil(it / porLinha.toDouble()).toInt() } ?: 1)
        }

        val d = DIAMETROS.firstOrNull { diametro ->
            val porLinha = porLinha(diametro)
            BASE + linhasDe(porLinha).sum() * (diametro + GAP) <= DISPONIVEL
        } ?: DIAMETROS.last()

        val porLinha = porLinha(d)
        val pedido = linhasDe(porLinha)
        val cabem = (DISPONIVEL - BASE) / (d + GAP)
        // Only ever below 1.0 in the pathological case; at 1.0 or above this is the identity.
        val fator = if (pedido.sum() <= cabem) 1.0 else cabem.toDouble() / pedido.sum()
        val linhas = pedido.map { max(1, (it * fator).toInt()) }
        return Layout(d, porLinha, linhas, linhas.map { PAD_TIER * 2 + it * (d + GAP) - GAP })
    }

    private fun porLinha(d: Int) = max(1, USAVEL / (d + GAP))

    private val log = LoggerFactory.getLogger(javaClass)

    // -------------------- constantes -------------------- //

    private const val W = 1080
    private const val H = 1080
    private const val MARGEM = 32

    private const val COLUNAS = 4
    private const val TIERS = 5

    /** The tier chip's column, and where the four papel columns start after it. */
    private const val CHIP_X = MARGEM
    private const val CHIP_W = 60
    private const val CX0 = 104
    private const val COL_W = (W - MARGEM - CX0) / COLUNAS
    private const val PAD = 8
    private const val USAVEL = COL_W - PAD * 2

    /** Left edge of the header text, clearing the logo. */
    private const val TX = 118
    private const val Y_ROTULOS = 152
    private const val Y_TIERS = 172
    private const val Y_RODAPE = 1010

    private const val GAP = 6
    private const val GAP_TIER = 12
    private const val PAD_TIER = 14

    /** Vertical room the five tier rows share, gaps between them already taken out. */
    private const val DISPONIVEL = Y_RODAPE - 6 - Y_TIERS - (TIERS - 1) * GAP_TIER

    /** The part of a row's height that is padding rather than avatars, summed over the five rows. */
    private const val BASE = TIERS * (PAD_TIER * 2 - GAP)

    /**
     * Avatar sizes to try, largest first. Nothing below 24 is worth drawing — a portrait stops being
     * a face — so that is where [layout] stops shrinking and starts clipping.
     */
    private val DIAMETROS = listOf(64, 58, 52, 48, 44, 40, 36, 32, 28, 24)

    /**
     * Tier letters. Duplicated from `com.hsrbot.tier.TIERS` on purpose: the renderer knows nothing
     * about the stored grade, the same way [CardRenderer] knows nothing about a guide's spec.
     */
    private val LETRAS = listOf("S", "A", "B", "C", "D")

    private val CORES_TIER = listOf(
        Color(0xF2, 0xC9, 0x6B),
        Color(0xB9, 0x8C, 0xF7),
        Color(0x6B, 0xA8, 0xF2),
        Color(0x55, 0xC7, 0x9A),
        Color(0xE0, 0x77, 0x6B),
    )

    private val GOLD = Color(0xF2, 0xC9, 0x6B)
    private val DIM = Color(0xD5, 0xCB, 0xD8)
    private val SUBIU = Color(0x6B, 0xD9, 0xA8)
    private val DESCEU = Color(0xE0, 0x77, 0x6B)

}
