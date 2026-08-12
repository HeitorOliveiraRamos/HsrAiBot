package com.hsrbot.card

import com.hsrbot.hsr.BuildAnalyzer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Paint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Draws the `/build` card: the character down the left ([Retrato], shared with `/ascensao`), and the
 * verdict on their showcased build down the right — the score, the finished stats, and one row per
 * relic slot.
 *
 * The rows are the point. A score on its own is a number to argue with; a score beside the piece
 * that earned it is advice, so every row carries what the piece IS (main stat, level) next to what
 * it scored, an empty slot gets a row of its own, and a main stat this character has no use for is
 * marked right there rather than in a footnote.
 *
 * Input is a resolved [Avaliacao] and nothing else: no database, no Spring, no mihomo. Icons are CDN
 * content hashes fetched by [CardRenderer.asset] and every lookup fails soft — a missing icon costs
 * a socket, never a row.
 */
object AvaliacaoRenderer {

    fun render(a: Avaliacao, enviada: BufferedImage? = null, focoEnviada: Foco = Foco.INTEIRO): BufferedImage {
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
        nivel(g, a, pal)

        nota(g, a, pal)
        status(g, a.stats, pal)
        pecas(g, a.pecas, pal)
        rodape(g, a)

        CardRenderer.frame(g)
        g.dispose()
        return img
    }

    fun png(a: Avaliacao, enviada: BufferedImage? = null, focoEnviada: Foco = Foco.INTEIRO): ByteArray =
        ByteArrayOutputStream().use { out ->
            ImageIO.write(render(a, enviada, focoEnviada), "png", out)
            out.toByteArray()
        }

    /**
     * Level and eidolon, on the stars' line and to the right of them — the two numbers that decide
     * how much of this build is investment and how much is luck, so they belong with the identity
     * rather than in the verdict.
     */
    private fun nivel(g: Graphics2D, a: Avaliacao, pal: Paleta) {
        val texto = "NV. ${a.nivel}  ·  E${a.eidolon}"
        g.font = CardRenderer.din(19f, bold = true, tracking = 0.06)
        val w = g.fontMetrics.stringWidth(texto) + 34
        val x = Retrato.MARGEM + 326
        g.color = Color(0, 0, 0, 175)
        g.fill(RoundRectangle2D.Float(x.toFloat(), (Retrato.Y_ESTRELAS - 21).toFloat(), w.toFloat(), 42f, 16f, 16f))
        g.color = CardRenderer.alpha(pal.borda, 190)
        g.stroke = BasicStroke(1.5f)
        g.draw(RoundRectangle2D.Float(x.toFloat(), (Retrato.Y_ESTRELAS - 21).toFloat(), w.toFloat(), 42f, 16f, 16f))
        g.color = Color.WHITE
        g.drawString(texto, x + 17, Retrato.Y_ESTRELAS + 7)
    }

    /** The verdict, and the light cone beside it — the one piece of gear that is not a relic. */
    private fun nota(g: Graphics2D, a: Avaliacao, pal: Paleta) {
        CardRenderer.header(g, "NOTA DA BUILD", RX, Y_NOTA, RW, pal.regua)
        CardRenderer.panel(g, RX, Y_NOTA + 12, RW, NOTA_H, pal)
        // The number is the loudest thing on the right half of the card, on purpose.
        val texto = fmt(a.nota)
        g.font = CardRenderer.display().deriveFont(74f)
        val largura = g.fontMetrics.stringWidth(texto)
        g.color = Color(0, 0, 0, 170)
        g.drawString(texto, RX + 27, Y_NOTA + 106)
        g.paint = tintaDoRank(a.rank, RX + 24, largura)
        g.drawString(texto, RX + 24, Y_NOTA + 103)
        g.font = CardRenderer.din(24f, tracking = 0.04)
        g.color = CardRenderer.alpha(Color.WHITE, 150)
        g.drawString("/10", RX + 30 + largura, Y_NOTA + 103)

        // 76 px, not 66: the widest letter grade went from "SSS" to "SSS+".
        chip(g, a.rank, RX + 24, Y_NOTA + 118, 76, tintaDoRank(a.rank, RX + 24, 76))

        val cone = a.cone ?: return
        // The cone hangs off the panel's right half: a build is its relics AND its cone, and one at
        // S1 is different advice from the same one at S5.
        CardRenderer.asset(cone.icone)?.let { CardRenderer.drawContain(g, it, RX + 232, Y_NOTA + 26, 96, 118) }
        cone.sobreposicao?.let { chip(g, "S$it", RX + 232, Y_NOTA + 26, 46, GOLD, 26) }
        val (f, linhas) = CardRenderer.fitText(g, cone.nome.uppercase(), RW - 358, 3, 15f, 10f, CardRenderer.din(15f))
        CardRenderer.drawLines(g, linhas, f, RX + 344, Y_NOTA + 62, Color.WHITE)
    }

    /** The finished combat stats, two columns of four — the game's own detail panel, condensed. */
    private fun status(g: Graphics2D, stats: List<Stat>, pal: Paleta) {
        CardRenderer.header(g, "STATUS", RX, Y_STATUS, RW, pal.regua)
        CardRenderer.panel(g, RX, Y_STATUS + 12, RW, STATUS_H, pal)
        if (stats.isEmpty()) return

        val colW = (RW - 32) / 2
        stats.take(8).forEachIndexed { i, s ->
            val x = RX + 16 + (i % 2) * colW
            val y = Y_STATUS + 52 + (i / 2) * 38
            g.font = CardRenderer.din(15f, tracking = 0.08)
            g.color = DIM
            g.drawString(s.rotulo, x, y)
            // Values are right-aligned to the column, so the eye reads a stat block and not a
            // ragged list — the numbers vary from "87" to "129.9%".
            g.font = CardRenderer.din(20f, bold = true)
            g.color = Color.WHITE
            g.drawString(s.valor, x + colW - 26 - g.fontMetrics.stringWidth(s.valor), y)
        }
    }

    /** One row per slot, in game order, empty ones included. */
    private fun pecas(g: Graphics2D, pecas: List<Peca>, pal: Paleta) {
        CardRenderer.header(g, "RELÍQUIAS", RX, Y_PECAS, RW, pal.regua)
        CardRenderer.panel(g, RX, Y_PECAS + 12, RW, PECAS_H, pal)

        // The substat lines share ONE size, the smallest any of them needs: six rows of the same
        // shape shrunk independently read as a mistake even when each is legible on its own.
        val fSubs = pecas.take(6)
            .filterNot { it.vazio || it.mainErrado || it.subs.isEmpty() }
            .minOfWithOrNull(compareBy { it.size2D }) {
                CardRenderer.fitText(g, textoSubs(it.subs).joinToString(SEP), SUBS_W, 1, 13f, 8f, CardRenderer.din(13f)).first
            }

        pecas.take(6).forEachIndexed { i, p ->
            val y = Y_PECAS + 22 + i * LINHA_H
            // The socket is drawn whether or not there is an icon, and an empty slot gets a dimmer
            // one: the row must read as a slot waiting for a piece, never as a gap in the list.
            val icone = if (p.vazio) null else CardRenderer.asset(p.icone)
            g.color = Color(0xFF, 0xFF, 0xFF, if (icone == null) 8 else 14)
            g.fill(RoundRectangle2D.Float((RX + 14).toFloat(), y.toFloat(), 52f, 52f, 14f, 14f))
            g.color = CardRenderer.alpha(if (p.vazio) DIM else pal.borda, if (icone == null) 55 else 90)
            g.stroke = BasicStroke(1.4f)
            g.draw(RoundRectangle2D.Float((RX + 14).toFloat(), y.toFloat(), 52f, 52f, 14f, 14f))
            icone?.let { CardRenderer.drawContain(g, it, RX + 20, y + 5, 40, 42) }

            if (p.vazio) {
                g.font = CardRenderer.din(19f, bold = true, tracking = 0.04)
                g.color = CardRenderer.alpha(VERMELHO, 230)
                g.drawString("${p.nome.uppercase()} — VAZIO", RX + 80, y + 32)
                return@forEachIndexed
            }

            // Main stat and level on the top line: what the piece IS, before what it scored. The
            // level rides beside the main rather than under it, because the line below now belongs
            // to the substats and they need every pixel of it.
            val (f, linhas) = CardRenderer.fitText(
                g, p.main.uppercase(), TEXTO_W - 46, 1, 19f, 12f, CardRenderer.din(19f, bold = true),
            )
            CardRenderer.drawLines(g, linhas, f, RX + 80, y + 24, if (p.mainErrado) VERMELHO else Color.WHITE)
            g.font = f
            val mainW = g.fontMetrics.stringWidth(linhas.firstOrNull().orEmpty())
            g.font = CardRenderer.din(14f, tracking = 0.06)
            g.color = CardRenderer.alpha(DIM, 175)
            g.drawString("+${p.nivel}", RX + 90 + mainW, y + 24)

            // Then the substats — except on a piece whose main is wrong, which is the piece to
            // REPLACE and not the piece to refarm, so its rolls are beside the point. Red is
            // reserved for exactly that case.
            if (p.mainErrado) {
                val (af, alinhas) = CardRenderer.fitText(g, "MAIN FORA DO IDEAL", TEXTO_W, 1, 13f, 9f, CardRenderer.din(13f))
                CardRenderer.drawLines(g, alinhas, af, RX + 80, y + 48, CardRenderer.alpha(VERMELHO, 205))
            } else if (fSubs != null) {
                substats(g, p.subs, fSubs, RX + 80, y + 48)
            }

            // Score and grade ride the MAIN's line, not the row's middle: that hands the line below
            // to the substats whole, which is the only way four `LABEL 21.1%` pairs fit at a size
            // anyone can read.
            g.font = CardRenderer.din(26f, bold = true)
            val nota = fmt(p.nota)
            val nw = g.fontMetrics.stringWidth(nota)
            g.paint = tintaDoRank(p.rank, RX + RW - 84 - nw, nw)
            g.drawString(nota, RX + RW - 84 - nw, y + 28)
            chip(g, p.rank, RX + RW - 70, y + 4, 56, tintaDoRank(p.rank, RX + RW - 70, 56), 30)
        }
    }

    /**
     * The piece's substats as the game states them — `PV% 21.1% · CRIT 2.5%` — with a dot under each
     * for every time that line was enhanced. White is a roll the character's ruler pays for, faded
     * is one it does not, which turns every relic score on the card into something the reader can
     * check with their eyes: a 5,23 with two greyed lines rolled into nothing, while the same score
     * with four white ones just rolled low, and that is a different piece to farm.
     *
     * A substat with no dots never came up again after the roll that put it there, exactly as the
     * game draws it, and the dots across a row add up to the relic's five (or four) enhancements —
     * the number a player is actually counting.
     *
     * Drawn segment by segment because each carries its own colour, so [CardRenderer.drawLines] is
     * no use; [f] is the size [pecas] settled on for the whole stack.
     */
    private fun substats(g: Graphics2D, subs: List<Sub>, f: Font, x: Int, y: Int) {
        val partes = textoSubs(subs)
        g.font = f
        var cx = x
        partes.forEachIndexed { i, texto ->
            if (i > 0) {
                g.color = CardRenderer.alpha(DIM, 70)
                g.drawString(SEP, cx, y)
                cx += g.fontMetrics.stringWidth(SEP)
            }
            val cor = if (subs[i].util) Color.WHITE else CardRenderer.alpha(DIM, 90)
            g.color = cor
            g.drawString(texto, cx, y)
            val w = g.fontMetrics.stringWidth(texto)
            pontos(g, subs[i].melhorias, cx, w, y + PONTO_DY, cor)
            cx += w
        }
    }

    /** The enhancement marks, centred under their substat and in its own colour — a dead line's
     *  rolls were still spent, so they are drawn, just as faded as the stat they went into. */
    private fun pontos(g: Graphics2D, n: Int, x: Int, w: Int, y: Int, cor: Color) {
        if (n <= 0) return
        g.color = cor
        var px = x + (w - (n * PONTO + (n - 1) * PONTO_GAP)) / 2f
        repeat(n) {
            g.fill(Ellipse2D.Float(px, y.toFloat(), PONTO, PONTO))
            px += PONTO + PONTO_GAP
        }
    }

    private fun textoSubs(subs: List<Sub>): List<String> =
        subs.map { "${it.rotulo.uppercase()} ${it.valor}" }

    /**
     * Whose showcase this is and what they are wearing, plus [Avaliacao.regua] when the scoring left
     * the default ruler. The sets go here rather than in a section of their own: the rows above
     * already picture them piece by piece, so what is left to say is the 2/4 split, which is one
     * line.
     */
    private fun rodape(g: Graphics2D, a: Avaliacao) {
        val conjuntos = a.conjuntos.filter { it.nome.isNotBlank() }
        if (conjuntos.isNotEmpty()) {
            val texto = conjuntos.joinToString("   ·   ") { "${it.pecas}PÇ ${it.nome.uppercase()}" }
            val (f, linhas) = CardRenderer.fitText(g, texto, RW, 1, 14f, 9f, CardRenderer.din(14f, tracking = 0.04))
            CardRenderer.drawLines(g, linhas.take(1), f, RX, Y_RODAPE, CardRenderer.alpha(GOLD, 205))
        }

        val creditos = listOfNotNull(
            a.jogador?.takeIf { it.isNotBlank() }?.let { "VITRINE DE ${it.uppercase()}" },
            a.uid?.takeIf { it.isNotBlank() }?.let { "UID $it" },
            a.regua.takeIf { it.isNotBlank() }?.uppercase(),
        ).joinToString("  ·  ")
        val (f, linhas) = CardRenderer.fitText(g, creditos, RW, 1, 13f, 9f, CardRenderer.din(13f, tracking = 0.1))
        CardRenderer.drawLines(g, linhas.take(1), f, RX, Y_RODAPE + 28, CardRenderer.alpha(Color.WHITE, 135))
    }

    /** The black/gold pill the other cards mark ranks and superimpositions with. */
    internal fun chip(g: Graphics2D, texto: String, x: Int, y: Int, w: Int, tinta: Paint, h: Int = 36) {
        g.color = Color(0, 0, 0, 195)
        g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), 14f, 14f))
        // A gradient carries its own alpha in its stops, so only a flat tint gets softened here.
        g.paint = if (tinta is Color) CardRenderer.alpha(tinta, 210) else tinta
        g.stroke = BasicStroke(1.4f)
        g.draw(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), 14f, 14f))
        g.font = CardRenderer.din(h * 0.56f, bold = true, tracking = 0.04)
        g.paint = tinta
        g.drawString(texto, x + (w - g.fontMetrics.stringWidth(texto)) / 2, y + (h * 0.72f).toInt())
    }

    /**
     * Rank → colour. The letters are [com.hsrbot.hsr.FribbelsScorer.rating]'s nineteen bands, and
     * the point of tinting them is that a card is read at a glance: gold is "leave it alone", red
     * is "this is the piece to farm". The `+` half-step is the full tint and the bare letter below
     * it the same hue dimmed, so a pair reads as one grade with a step inside it, and the ladder
     * descends gold → pale gold → fading yellow → purple → blue → grey → red.
     *
     * `C` through `E+` share one grey — six mid tints nobody can tell apart would just be noise.
     * `?` is not a bad grade, it is fribbels declining to grade a piece at all (a wrong main, or a
     * relic below 5★), so it gets the neutral tint rather than the red one.
     */
    internal fun corDoRank(rank: String): Color = when (rank) {
        // ponytail: flat stand-in — the real `???` is [tintaDoRank]'s rainbow, this is what alpha()
        // and any Color-only caller falls back to.
        "???", "SSS+" -> GOLD
        "SSS" -> Color(0xC9, 0xA2, 0x4E)
        "SS+" -> Color(0xF0, 0xDD, 0xA8)
        "SS" -> Color(0xC8, 0xB6, 0x81)
        "S+" -> Color(0xEF, 0xEF, 0xB0)
        "S" -> Color(0xC6, 0xC6, 0x8B)
        "A+" -> Color(0xB9, 0x8F, 0xE0)
        "A" -> Color(0x93, 0x70, 0xB4)
        "B+" -> Color(0x8C, 0xC0, 0xE8)
        "B" -> Color(0x6E, 0x97, 0xB8)
        "F", "F+" -> VERMELHO
        "?" -> NEUTRO
        else -> CINZA
    }

    /**
     * The same ladder as a [Paint], because the top band is not a single colour: `???` sweeps the
     * spectrum across the [w] px starting at [x], every other rank is its flat tint. Callers pass
     * the box they are about to paint so the sweep spans that element and not the whole card.
     */
    internal fun tintaDoRank(rank: String, x: Int, w: Int): Paint =
        if (rank != "???") corDoRank(rank) else LinearGradientPaint(
            Point2D.Float(x.toFloat(), 0f),
            Point2D.Float((x + w.coerceAtLeast(1)).toFloat(), 0f),
            ARCO_PARADAS,
            ARCO,
        )

    private fun fmt(nota: Double): String = BuildAnalyzer.nota(nota)

    // -------------------- constantes -------------------- //

    private const val W = Retrato.W
    private const val H = Retrato.H

    /** Right column, shared with the other two square cards so all three read as the same grid. */
    private const val RX = 548
    private const val RW = W - 40 - RX

    private const val Y_NOTA = 92
    private const val NOTA_H = 156

    private const val Y_STATUS = 316
    private const val STATUS_H = 176

    /** The block starts higher and its rows are taller than they were: the substat line grew a strip
     *  of enhancement dots under it, and 62 px left those sitting on the next row's icon. */
    private const val Y_PECAS = 540
    private const val LINHA_H = 66
    private const val PECAS_H = 20 + 6 * LINHA_H

    /** Room for a piece's main stat between its icon and its score. The score reserve went from
     *  100 to 160 px when the scale moved from `9.7` to fribbels' `97.3`. */
    private const val TEXTO_W = RW - 80 - 160

    /** The substat line owns the row's full width, since the score moved up to the main's line. */
    private const val SUBS_W = RW - 80 - 14

    /** Between two substats. Thin on purpose — the gap is punctuation, not content. */
    private const val SEP = " · "

    /** The enhancement dots: diameter, gap, and how far under the substat's baseline they sit. */
    private const val PONTO = 3f
    private const val PONTO_GAP = 3f
    private const val PONTO_DY = 8

    private const val Y_RODAPE = 984

    private val GOLD = Color(0xF2, 0xC9, 0x6B)
    private val DIM = Color(0xD5, 0xCB, 0xD8)
    private val VERMELHO = Color(0xE8, 0x8E, 0x8E)

    /** C…E+ (a grade with nothing to say) and `?` (no grade at all) — two greys, not one. */
    private val CINZA = Color(0xA9, 0xA2, 0xB0)
    private val NEUTRO = Color(0xC9, 0xC1, 0xCE)

    /** `???`'s sweep. Pastel stops, not primaries: this is drawn on a dark panel at 74 px bold. */
    private val ARCO_PARADAS = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
    private val ARCO = arrayOf(
        Color(0xFF, 0x9E, 0x9E), Color(0xFF, 0xC9, 0x6B), Color(0xF2, 0xEE, 0x8A),
        Color(0x8F, 0xD9, 0x9B), Color(0x8F, 0xCF, 0xE8), Color(0xC9, 0x9B, 0xF0),
    )
}
