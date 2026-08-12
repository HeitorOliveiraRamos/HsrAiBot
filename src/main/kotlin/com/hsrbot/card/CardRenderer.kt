package com.hsrbot.card

import org.slf4j.LoggerFactory
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.GradientPaint
import java.awt.RadialGradientPaint
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a build card: white frame, name on top of the splash, and a
 * right column of RASTROS / CONES DE LUZ / RELÍQUIAS + ORNAMENTOS / STATUS.
 *
 * The layout is band-based (see the `Y_*` constants): each band is a fixed slice of the card and
 * every string inside it goes through [fitText], which wraps and then shrinks the font until the
 * text fits its box. Nothing is ever ellipsised — gear names are the whole point of the card.
 *
 * Input is a fully resolved [Ficha] and nothing else, so the same code serves the curated preview
 * ([CardPreviewStandalone]) and user-written guides. Per-character art/icons are CDN content hashes
 * fetched by [asset] (cached forever on disk, since content-addressed assets are immutable);
 * element/path/logo art comes from `resources/guias_info/` via [res]. Every asset lookup fails
 * soft: a missing icon draws nothing rather than breaking the card.
 */
object CardRenderer {

    fun render(f: Ficha): BufferedImage {
        // ponytail: ImageIO plugin discovery is lazy and misses the webp reader in some launch modes.
        ImageIO.scanForPlugins()

        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

        // Chrome echoes the MEMBER's chosen art, not the element, when they uploaded one — borders,
        // header rules, stars and panel tint all come from the picture's own palette so card and art
        // read as one piece. Official (or near-greyscale) art keeps the element colour with the
        // white/gold it always had, so the 95 curated cards are unchanged.
        // ponytail: decodes the upload a second time (splash reads it again to draw); a guia render
        // is cached and this is one local PNG read, so not worth threading the image through.
        val pal = f.arte.enviada?.let { arquivo(it) }?.let { Paleta.daArte(it, f.elemento) } ?: Paleta.elemento(f.elemento)
        splash(g, f, pal.borda)
        nameBlock(g, f.nome.uppercase(), f.faccao)

        // Path + element badges hug the left edge, the way the reference card does it.
        res("caminhos", pathFile(f.caminho))?.let { badge(g, it, 40, 436, 66, pal.borda) }
        res("elementos", elementFile(f.elemento))?.let { badge(g, it, 40, 512, 66, pal.borda) }
        stars(g, f.raridade, 42, 616)
        synergies(g, f.sinergias, pal)

        traces(g, f.rastros, pal)
        lightCones(g, f.cones, pal)
        gear(g, f.reliquias, f.ornamentos, pal)
        status(g, f.status, f.metas, pal)

        frame(g)
        g.dispose()
        return img
    }

    fun png(f: Ficha): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(render(f), "png", out)
        out.toByteArray()
    }

    // -------------------- sections -------------------- //

    private fun splash(g: Graphics2D, f: Ficha, accent: Color) {
        // v2 wants the whole illustration with its scenery, which only works if we know where the
        // character is inside it. No focus box, no v2: fall back rather than guess. The author's own
        // upload wins over the official art — they chose a picture, that is the whole point of it.
        val enviada = f.arte.enviada?.let { arquivo(it) }
        val cena = if (!f.arte.podeV2) null else enviada ?: asset(f.arte.completa)
        if (f.arte.v2 && cena == null) {
            log.warn("v2 indisponível para '{}' — caindo para o layout v1", f.nome)
        }
        // v1 splash: `retrato` is the bust srs already framed on the character (95/95); the old
        // cut-out and the square art stay as fallbacks for anything it doesn't cover.
        val hash = f.arte.retrato ?: f.arte.figura ?: f.arte.completa
        val recortado = f.arte.retrato == null
        // Trim first, then fit: source framing varies enormously, so cropping the empty margin is
        // what makes every character occupy the same amount of the panel.
        val art = if (cena != null) null else asset(hash)?.let { trimAlpha(it) }
        if (art == null && cena == null) log.warn("sem arte para {}", f.nome)

        // The reference cards are washed in the character's own palette, not the element's — Blade's
        // is red, not Wind green. So the glow comes from the art, and the element only tints chrome.
        // ponytail: v1 is a flat wash, deliberately. Painting the splash's own scenery layer behind
        // the bust was tried and rejected — it reads as clutter under the right column.
        background(g, (cena ?: art)?.let { dominant(it) } ?: accent)
        if (cena != null) {
            drawScene(g, cena, f.arte.foco!!, livre = f.arte.enquadramentoLivre)
        } else {
            // A bust fills the panel WIDTH and hangs from the top — cropping it to fill 926px of
            // height would cut the shoulders and push the face out of frame. The chest running off
            // the bottom is fine: the badges, stars and SINERGIAS row are drawn over it.
            art?.let { if (recortado) drawSplash(g, it, 12, 120, 534, 926) else drawBust(g, it, 12, 120, 534, 926) }
        }

        // Scrim: the art runs under the right column, so fade it out before the text starts. v2 puts
        // a whole illustration back there, so it needs a heavier one to keep the panels legible.
        val scrim = if (cena != null) 215 else 165
        g.paint = GradientPaint(420f, 0f, Color(0, 0, 0, 0), 620f, 0f, Color(0, 0, 0, scrim))
        g.fillRect(420, 0, W - 420, H)
        // Then the band behind the name block. Full width, NOT the 560 the art panel is: the column
        // scrim above only reaches full strength at x=620, so stopping short left a lit strip
        // between the two edges — a white smear beside RASTROS with nothing in the art to explain it.
        g.paint = GradientPaint(0f, 0f, Color(0, 0, 0, 175), 0f, 240f, Color(0, 0, 0, 0))
        g.fillRect(0, 0, W, 240)

        // Only when THEIR picture is the one that got drawn: the official illustration is nobody's
        // to sign, and podeV2 can drop an upload back to the bust.
        if (enviada != null && cena != null) credito(g, f.arte.autor)
    }

    /**
     * The art credit, in the card's bottom-left corner — the one strip both card layouts leave
     * empty (the build card's left column ends with SINERGIAS, the ascension card's with the stars),
     * and the same corner `/tierlist` signs its lists in.
     *
     * Quiet on purpose: it says who the picture belongs to without competing with the character's
     * name. A name long enough to reach the right column is cut rather than wrapped — this is a
     * signature, not a line of the card.
     */
    internal fun credito(g: Graphics2D, autor: String?) {
        val nome = autor?.trim()?.takeIf { it.isNotEmpty() } ?: return
        g.font = din(15f, tracking = 0.14)
        g.color = alpha(Color.WHITE, 150)
        val texto = "PUBLICADO POR ${nome.uppercase()}"
        g.drawString(
            if (g.fontMetrics.stringWidth(texto) <= CREDITO_W) texto else corta(g, texto, CREDITO_W),
            46, H - 44,
        )
    }

    /** Longest prefix of [texto] that fits [max], with an ellipsis in place of what was cut. */
    private fun corta(g: Graphics2D, texto: String, max: Int): String {
        var fim = texto.length
        while (fim > 1 && g.fontMetrics.stringWidth(texto.take(fim) + "…") > max) fim--
        return texto.take(fim) + "…"
    }

    internal fun background(g: Graphics2D, glow: Color) {
        g.paint = GradientPaint(0f, 0f, mix(glow, Color(0x0B, 0x07, 0x0C), 0.74f), 0f, H.toFloat(), Color(0x07, 0x05, 0x09))
        g.fillRect(0, 0, W, H)
        g.paint = RadialGradientPaint(
            Point2D.Float(560f, 400f), 740f, floatArrayOf(0f, 1f),
            arrayOf(alpha(glow, 120), alpha(glow, 0)),
        )
        g.fillRect(0, 0, W, H)
        g.paint = RadialGradientPaint(
            Point2D.Float(540f, 540f), 800f, floatArrayOf(0.40f, 1f),
            arrayOf(Color(0, 0, 0, 0), Color(0, 0, 0, 200)),
        )
        g.fillRect(0, 0, W, H)
    }

    private fun nameBlock(g: Graphics2D, nome: String, faccao: String?) {
        faccao?.let {
            g.font = din(15f, tracking = 0.22)
            g.color = alpha(Color.WHITE, 170)
            g.drawString(it.uppercase(), 46, 62)
        }
        // The name owns everything left of the logo. One line is the goal — shrink to get there, and
        // only wrap (never cut) if even the floor is too wide.
        val (f, lines) = fitText(g, nome, 322, 1, 74f, 30f, display())
        var y = if (lines.size == 1) 124 else 106
        lines.forEach {
            g.font = f
            g.color = Color(0, 0, 0, 170)
            g.drawString(it, 48, y + 3)
            g.color = Color.WHITE
            g.drawString(it, 46, y)
            y += (f.size2D * 0.92f).roundToInt()
        }

        // Operator branding, both halves optional — an unconfigured bot leaves this corner empty
        // rather than drawing a placeholder. See [Marca].
        Marca.logo?.let { drawContain(g, it, 392, 20, 112, 112) }
        Marca.tag?.let {
            val tag = it.uppercase()
            g.font = din(11f, tracking = 0.16)
            g.color = alpha(Color.WHITE, 165)
            g.drawString(tag, 392 + (112 - g.fontMetrics.stringWidth(tag)) / 2, 148)
        }
    }

    private fun synergies(g: Graphics2D, sinergias: List<Sinergia>, pal: Paleta) {
        if (sinergias.isEmpty()) return
        // Same panel as every section on the right, for the same reason: the avatars sit straight on
        // the splash otherwise, and a face on a face is not a section. Its height is the FULL grid's,
        // never the filled rows' — a box that grows with the roster makes every card a different
        // shape, and the empty rows read as room left rather than as a gap.
        header(g, "SINERGIAS", SX, Y_SINERGIAS, SW, pal.regua)
        panel(g, SX, Y_SINERGIAS + 12, SW, 100 + (SINERGIA_LINHAS - 1) * AVATAR_PASSO, pal)
        // Four across the panel's inner width, so a full row reaches both insets instead of
        // huddling on the left half of a box that is as wide as the header.
        sinergias.take(SINERGIAS_NO_CARD).forEachIndexed { i, s ->
            val ax = SX + 12 + (i % SINERGIA_COLUNAS) * ((SW - 24 - AVATAR) / (SINERGIA_COLUNAS - 1))
            val ay = Y_SINERGIAS + 26 + (i / SINERGIA_COLUNAS) * AVATAR_PASSO
            asset(s.icone)?.let { avatar(g, it, ax, ay, AVATAR, pal.borda) }
        }
    }

    private fun traces(g: Graphics2D, rastros: List<Rastro>, pal: Paleta) {
        if (rastros.isEmpty()) return
        header(g, "RASTROS", RX, Y_TRACES, RW, pal.regua)
        panel(g, RX, Y_TRACES + 12, RW, 152, pal)

        val n = rastros.size
        val sep = 26
        val cell = (RW - 24 - sep * (n - 1)) / n
        val icon = min(84, cell)
        rastros.forEachIndexed { i, r ->
            val cx = RX + 12 + i * (cell + sep)
            asset(r.icone)?.let { drawContain(g, it, cx + (cell - icon) / 2, Y_TRACES + 28, icon, icon) }
            val (f, lines) = fitText(g, r.rotulo.uppercase(), cell, 2, 13f, 9f, din(13f, tracking = 0.06))
            drawLines(g, lines, f, cx, Y_TRACES + 42 + icon, DIM, centerW = cell)
            if (i < n - 1) {
                // Always ">": the row is a strict priority order, which is the only thing the form
                // asks for now.
                g.font = din(24f, bold = true)
                g.color = alpha(GOLD, 220)
                g.drawString(">", cx + cell + (sep - g.fontMetrics.stringWidth(">")) / 2, Y_TRACES + 28 + icon / 2 + 8)
            }
        }
    }

    private fun lightCones(g: Graphics2D, cones: List<Cone>, pal: Paleta) {
        if (cones.isEmpty()) return
        header(g, "CONES DE LUZ", RX, Y_CONES, RW, pal.regua)
        panel(g, RX, Y_CONES + 12, RW, 222, pal)

        val cell = (RW - 24) / cones.size
        cones.forEachIndexed { i, c ->
            val cx = RX + 12 + i * cell
            asset(c.icone)?.let { drawContain(g, it, cx + 6, Y_CONES + 26, cell - 12, 132) }
            c.sobreposicao?.let { superimposition(g, "S$it", cx + cell - 46, Y_CONES + 30) }
            val (f, lines) = fitText(g, c.nome.uppercase(), cell - 10, 3, 13f, 9f, din(13f))
            drawLines(g, lines, f, cx + 5, Y_CONES + 178, Color.WHITE, centerW = cell - 10)
        }
    }

    /** The S1..S5 chip over a cone's art — a cone at S1 and the same cone at S5 are different advice. */
    private fun superimposition(g: Graphics2D, text: String, x: Int, y: Int) {
        g.color = Color(0, 0, 0, 190)
        g.fillRoundRect(x, y, 40, 24, 10, 10)
        g.color = alpha(GOLD, 200)
        g.stroke = BasicStroke(1.2f)
        g.drawRoundRect(x, y, 40, 24, 10, 10)
        g.font = din(14f, bold = true, tracking = 0.04)
        g.color = GOLD
        g.drawString(text, x + (40 - g.fontMetrics.stringWidth(text)) / 2, y + 17)
    }

    private fun gear(g: Graphics2D, reliquias: List<Linha>, ornamentos: List<Linha>, pal: Paleta) {
        val rows = maxOf(gearRows(reliquias), gearRows(ornamentos), 1)
        val panelH = 20 + rows * 92
        header(g, "RELÍQUIAS", RX, Y_GEAR, COL_W, pal.regua)
        header(g, "ORNAMENTOS", RX2, Y_GEAR, COL_W, pal.regua)
        panel(g, RX, Y_GEAR + 12, COL_W, panelH, pal)
        panel(g, RX2, Y_GEAR + 12, COL_W, panelH, pal)

        gearColumn(g, reliquias, RX, Y_GEAR + 22)
        gearColumn(g, ornamentos, RX2, Y_GEAR + 22)
    }

    /** Rows a column actually draws: one per set, since a "2 + 2" line occupies both of its sets. */
    internal fun gearRows(linhas: List<Linha>): Int = min(MAX_GEAR_ROWS, linhas.sumOf { it.partes.size })

    private fun gearColumn(g: Graphics2D, linhas: List<Linha>, x: Int, y0: Int) {
        var row = 0
        for (linha in linhas) {
            linha.partes.forEachIndexed { i, parte ->
                if (row >= MAX_GEAR_ROWS) return
                val y = y0 + row * 92
                asset(parte.icone)?.let { drawContain(g, it, x + 6, y, 60, 60) }
                g.font = din(18f, bold = true, tracking = 0.06)
                g.color = GOLD
                g.drawString("${parte.pecas} PÇS", x + 72, y + 20)
                val (f, lines) = fitText(g, parte.nome, COL_W - 80, 3, 14f, 9f, din(14f))
                drawLines(g, lines, f, x + 72, y + 42, Color.WHITE)
                // A split line is two rows that must be read together: mark the seam between them.
                if (i < linha.partes.size - 1 && row + 1 < MAX_GEAR_ROWS) {
                    g.font = din(22f, bold = true)
                    g.color = alpha(GOLD, 210)
                    g.drawString("+", x + 36 - g.fontMetrics.stringWidth("+") / 2, y + 82)
                }
                row++
            }
        }
    }

    private fun status(g: Graphics2D, s: Status, metas: List<Meta>, pal: Paleta) {
        header(g, "STATUS", RX, Y_STATUS, RW, pal.regua)
        panel(g, RX, Y_STATUS + 12, RW, 170, pal)

        listOf("CORPO" to s.corpo, "PÉS" to s.pes, "ESFERA" to s.esfera, "CORDA" to s.corda)
            .forEachIndexed { i, (label, valor) ->
                val y = Y_STATUS + 50 + i * 36
                // The little piece icons: head/body from the relic set, sphere/rope from the
                // ornament set — same split the game uses.
                s.icones.getOrNull(i)?.let { asset(it) }?.let { drawContain(g, it, RX + 14, y - 26, 32, 32) }
                g.font = din(16f, tracking = 0.08)
                g.color = DIM
                g.drawString("$label:", RX + 52, y)
                val (f, lines) = fitText(g, valor ?: "—", 130, 1, 18f, 11f, din(18f, bold = true))
                drawLines(g, lines, f, RX + 142, y, Color.WHITE)
            }

        if (metas.isEmpty()) return
        // Right half of the panel: what the build is aiming at. Targets are bulleted (they are not
        // ranked); a bare substat list is a priority order, so that one keeps its numbers.
        val comAlvo = metas.any { it.alvo != null }
        g.color = alpha(Color.WHITE, 60)
        g.stroke = BasicStroke(1f)
        g.drawLine(RX + 292, Y_STATUS + 28, RX + 292, Y_STATUS + 164)
        g.font = din(12f, bold = true, tracking = 0.14)
        g.color = GOLD
        g.drawString(if (comAlvo) "METAS" else "SUBSTATUS", RX + 310, Y_STATUS + 44)
        metas.take(4).forEachIndexed { i, m ->
            val y = Y_STATUS + 72 + i * 26
            val marker = if (comAlvo) "•" else "${i + 1}"
            g.font = din(13f, bold = true)
            g.color = alpha(GOLD, 200)
            g.drawString(marker, RX + 310, y)
            // Only a line that also carries a number is short on room; a bare priority list keeps
            // the stat spelled out.
            val texto = m.alvo?.let { "${abreviar(m.stat)} $it" } ?: m.stat
            val (f, lines) = fitText(g, texto, RW - 348, 1, 14f, 10f, din(14f))
            drawLines(g, lines, f, RX + 330, y, Color.WHITE)
        }
    }

    /**
     * Stat names in the METAS column compete with a number for ~145px, and "Chance de Crit 100% em
     * combate" is what the guide team writes. Shorten the handful of long ones; anything unknown is
     * left exactly as typed.
     */
    internal fun abreviar(stat: String): String = META_ABREV[norm(stat)] ?: stat

    // -------------------- chrome -------------------- //

    /** The white double border, drawn last so nothing overlaps it. */
    internal fun frame(g: Graphics2D) {
        g.color = alpha(Color.WHITE, 235)
        g.stroke = BasicStroke(4f)
        g.draw(RoundRectangle2D.Float(18f, 18f, (W - 36).toFloat(), (H - 36).toFloat(), 34f, 34f))
        g.color = alpha(Color.WHITE, 95)
        g.stroke = BasicStroke(1.5f)
        g.draw(RoundRectangle2D.Float(29f, 29f, (W - 58).toFloat(), (H - 58).toFloat(), 24f, 24f))
    }

    /**
     * Centred section title with the rules fading out to both sides. The title stays white for
     * legibility; only the [regua] rules take a tint (the palette's secondary hue on an upload,
     * plain white otherwise).
     */
    internal fun header(g: Graphics2D, text: String, x: Int, y: Int, w: Int, regua: Color = Color.WHITE) {
        g.font = din(23f, bold = true, tracking = 0.12)
        val tw = g.fontMetrics.stringWidth(text)
        val cx = x + w / 2
        g.color = Color(0, 0, 0, 150)
        g.drawString(text, cx - tw / 2 + 2, y + 2)
        g.color = Color.WHITE
        g.drawString(text, cx - tw / 2, y)

        val ly = (y - 8).toFloat()
        g.stroke = BasicStroke(2f)
        val left = (cx - tw / 2 - 16).toFloat()
        val right = (cx + tw / 2 + 16).toFloat()
        g.paint = GradientPaint(x.toFloat(), 0f, alpha(regua, 0), left, 0f, alpha(regua, 190))
        g.drawLine(x, ly.toInt(), left.toInt(), ly.toInt())
        g.paint = GradientPaint(right, 0f, alpha(regua, 190), (x + w).toFloat(), 0f, alpha(regua, 0))
        g.drawLine(right.toInt(), ly.toInt(), x + w, ly.toInt())
    }

    internal fun panel(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, pal: Paleta) {
        g.color = pal.fundo
        g.fillRoundRect(x, y, w, h, 20, 20)
        g.color = alpha(pal.borda, 70)
        g.stroke = BasicStroke(1.5f)
        g.drawRoundRect(x, y, w, h, 20, 20)
    }

    internal fun badge(g: Graphics2D, icon: BufferedImage, x: Int, y: Int, d: Int, accent: Color) {
        g.color = Color(0, 0, 0, 160)
        g.fillOval(x, y, d, d)
        g.color = alpha(accent, 200)
        g.stroke = BasicStroke(2f)
        g.drawOval(x, y, d, d)
        val inset = (d * 0.20).roundToInt()
        drawContain(g, icon, x + inset, y + inset, d - inset * 2, d - inset * 2)
    }

    internal fun avatar(g: Graphics2D, img: BufferedImage, x: Int, y: Int, d: Int, accent: Color) {
        val clip = g.clip
        g.clip = Ellipse2D.Float(x.toFloat(), y.toFloat(), d.toFloat(), d.toFloat())
        g.color = Color(0, 0, 0, 170)
        g.fillOval(x, y, d, d)
        drawContain(g, img, x, y, d, d)
        g.clip = clip
        g.color = alpha(accent, 210)
        g.stroke = BasicStroke(2.5f)
        g.drawOval(x, y, d, d)
    }

    internal fun stars(g: Graphics2D, n: Int, x: Int, cy: Int) {
        // Coloured by RARITY the way the game does it — 5★ gold, 4★ violet — never by the art palette:
        // the rank is information, not decoration. The dark rim is derived from that colour so the
        // violet star still reads as struck rather than outlined.
        val cor = if (n >= 5) GOLD else ROXO_ESTRELA
        val rim = Color.RGBtoHSB(cor.red, cor.green, cor.blue, null).let { Color.getHSBColor(it[0], 0.62f, 0.34f) }
        repeat(n) { i ->
            val s = star(x + 22.0 + i * 50, cy.toDouble(), 22.0)
            g.color = alpha(cor, 70)
            g.fill(star(x + 22.0 + i * 50, cy.toDouble(), 27.0))
            g.color = cor
            g.fill(s)
            g.color = rim
            g.stroke = BasicStroke(1.2f)
            g.draw(s)
        }
    }

    private fun star(cx: Double, cy: Double, r: Double): Path2D {
        val p = Path2D.Double()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.44
            val a = -Math.PI / 2 + i * Math.PI / 5
            val x = cx + rr * cos(a)
            val y = cy + rr * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.closePath()
        return p
    }

    // -------------------- type -------------------- //

    internal fun din(size: Float, bold: Boolean = false, tracking: Double = 0.0): Font =
        font(if (bold) DIN_BOLD else DIN_REGULAR, size, tracking)

    /**
     * The display face. No style parameter: every caller wants it bold, and the bundled cut is
     * already the heavy one — a style argument here could only be a lie.
     */
    internal fun display(): Font = DIN_BOLD.deriveFont(60f)

    private fun font(base: Font, size: Float, tracking: Double): Font {
        val f = base.deriveFont(size)
        return if (tracking == 0.0) f else f.deriveFont(mapOf(TextAttribute.TRACKING to tracking))
    }

    /**
     * Largest size in [max]..[min] at which [text] wraps into at most [maxLines] lines inside [maxW].
     * If even [min] needs more lines it returns them anyway — the card would rather grow a line than
     * hide half a relic name.
     */
    internal fun fitText(
        g: Graphics2D,
        text: String,
        maxW: Int,
        maxLines: Int,
        max: Float,
        min: Float,
        base: Font,
    ): Pair<Font, List<String>> {
        var size = max
        var last = base.deriveFont(max) to wrapText(g.getFontMetrics(base.deriveFont(max)), text, maxW)
        while (size >= min) {
            val f = base.deriveFont(size)
            val lines = wrapText(g.getFontMetrics(f), text, maxW)
            last = f to lines
            if (lines.size <= maxLines) return last
            size -= 1f
        }
        return last
    }

    /** Draws [lines] from [baseline] down, optionally centred in a [centerW]-wide box at [x]. */
    internal fun drawLines(g: Graphics2D, lines: List<String>, f: Font, x: Int, baseline: Int, color: Color, centerW: Int? = null) {
        g.font = f
        g.color = color
        val lead = (f.size2D * 1.18f).roundToInt()
        var y = baseline
        for (line in lines) {
            val tx = if (centerW != null) x + (centerW - g.fontMetrics.stringWidth(line)) / 2 else x
            g.drawString(line, tx, y)
            y += lead
        }
    }

    // -------------------- assets -------------------- //

    /** Local art from `resources/guias_info/[dir]/[name].png`. */
    internal fun res(dir: String, name: String?): BufferedImage? {
        val n = name?.takeIf { it.isNotBlank() } ?: return null
        val path = if (dir.isEmpty()) "/guias_info/$n.png" else "/guias_info/$dir/$n.png"
        return javaClass.getResourceAsStream(path)?.use { runCatching { ImageIO.read(it) }.getOrNull() }
            ?: null.also { log.warn("ícone local ausente: {}", path) }
    }

    internal fun accentFor(elemento: String?): Color {
        val key = norm(elemento).let { ELEMENT_ALIAS[it] ?: it }
        return ACCENTS[key] ?: Color(0x9A, 0x7C, 0xC0)
    }

    internal fun pathFile(caminho: String?): String = norm(caminho).let { PATH_ALIAS[it] ?: it }

    internal fun elementFile(elemento: String?): String = norm(elemento).let { ELEMENT_ALIAS[it] ?: it }

    /**
     * A local image file — the author's uploaded art, materialised from `guia_arte`. Fails soft like
     * every other asset lookup: a card with a missing file falls back to the official art rather
     * than not rendering.
     */
    private fun arquivo(path: Path): BufferedImage? =
        runCatching { ImageIO.read(path.toFile()) }.getOrNull()
            ?: null.also { log.warn("arte enviada ilegível: {}", path.fileName) }

    /**
     * The URL to fetch an icon reference from, paired with the file it caches to. Pure half of
     * [asset]: an srs hash keeps its bare-hash filename so the existing cache stays warm, while a
     * nanoka URL is escaped WHOLE — its basename alone collides, since `avatarroundicon/1512.webp`
     * and `avatardrawcard/1512.webp` are both `1512.webp`.
     */
    internal fun assetRef(ref: String): Pair<String, String> =
        if (ref.startsWith("https://")) ref to ref.replace(NAO_ARQUIVO, "_")
        else "$ASSET_BASE/$ref.webp" to "$ref.webp"

    /**
     * Decoded asset for an icon reference, or null when absent/unfetchable (callers draw nothing —
     * a missing icon must never break a card).
     *
     * Two reference shapes, because two sources: a V20 starrailstation content hash (the default,
     * resolved against [ASSET_BASE]) or an absolute nanoka URL, which is how the beta characters
     * only nanoka publishes carry their art. Both are immutable — the hash IS the content, and
     * nanoka's `/assets/hsr/...` paths carry no version segment — so the on-disk copy is never
     * invalidated. The cache key keeps the bare hash for srs so the existing cache stays warm, and
     * escapes the whole URL for nanoka: basenames alone collide (`avatarroundicon/1512.webp` and
     * `avatardrawcard/1512.webp` are both `1512.webp`).
     */
    internal fun asset(ref: String?): BufferedImage? {
        val h = ref?.takeIf { it.isNotBlank() } ?: return null
        val (url, chave) = assetRef(h)
        val file = cacheDir.resolve(chave)
        // A hash has no '/', so this is its own prefix; a URL reduces to "1512.webp". Useful either way.
        val rotulo = h.substringAfterLast('/').take(24)
        fun fetch(): Boolean {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (compatible; HsrBot/1.0; +discord)")
                .GET().build()
            val resp = runCatching { http.send(req, HttpResponse.BodyHandlers.ofByteArray()) }.getOrNull()
            if (resp == null || resp.statusCode() !in 200..299) {
                log.warn("asset {} indisponível (HTTP {})", rotulo, resp?.statusCode())
                return false
            }
            Files.write(file, resp.body())
            return true
        }

        if (!Files.exists(file)) {
            if (!fetch()) return null
        }

        // Try to decode the cached file; if decoding fails, remove it and retry download once.
        var img = runCatching { ImageIO.read(file.toFile()) }.getOrNull()
        if (img == null) {
            log.warn("asset {} não decodificou (webp reader registrado?) — refetching", rotulo)
            try { Files.deleteIfExists(file) } catch (_: Exception) {}
            if (!fetch()) return null
            img = runCatching { ImageIO.read(file.toFile()) }.getOrNull()
            if (img == null) {
                log.warn("asset {} ainda não decodifica após refetch", rotulo)
                return null
            }
        }
        return img
    }

    /**
     * Splash fit. A cut-out (taller than the panel) is contained and bottom-anchored: whole figure,
     * feet at the base. The `arte_completa` fallback is landscape, and containing that leaves the
     * character floating in a letterbox — so those fill the panel height and get cropped at the sides.
     */
    internal fun drawSplash(g: Graphics2D, img: BufferedImage, x: Int, y: Int, w: Int, h: Int) {
        if (img.width.toDouble() / img.height <= w.toDouble() / h) {
            drawContain(g, img, x, y, w, h, bottom = true)
            return
        }
        val dw = (img.width * (h.toDouble() / img.height)).roundToInt()
        val clip = g.clip
        g.clipRect(x, y, w, h)
        g.drawImage(scaleHQ(img, dw, h), x + (w - dw) / 2, y, null)
        g.clip = clip
    }

    /**
     * v2 framing. Once you know where the character is, there is exactly one similarity transform
     * that puts them where the hand-made reference cards put them: scale until the figure is
     * [FIGURA_ALTURA] of the card's height, then translate so its centre-x lands at [FIGURA_CX] and
     * its top at [FIGURA_TOPO]. The scenery is not decided at all — it simply comes along at the
     * same scale, which is why "how much background to show" stops being a question.
     */
    internal fun drawScene(g: Graphics2D, img: BufferedImage, foco: Foco, livre: Boolean = false) {
        val boxH = (foco.h * img.height).coerceAtLeast(1.0)
        // Cover as a floor: a character who fills little of their own illustration must not zoom out
        // so far that the card's corners fall off the artwork.
        val scale = max(FIGURA_ALTURA * H / boxH, max(W.toDouble() / img.width, H.toDouble() / img.height))
        val dw = (img.width * scale).roundToInt()
        val dh = (img.height * scale).roundToInt()
        val ox = (FIGURA_CX * W - (foco.x + foco.w / 2) * img.width * scale).roundToInt()
        val oy = (FIGURA_TOPO * H - foco.y * img.height * scale).roundToInt()
        // A curated frame is measured to cover the card, so its offset is clamped to keep every corner
        // on the artwork. A MEMBER framing their own art is different: an image scaled to exactly cover
        // (every portrait upload hits `dw == W`) has ZERO room inside that clamp, so the arrows would
        // move nothing. Their pan is therefore free — the colour wash already painted behind shows in
        // any gap — and the clamp stays only for the official illustrations it was written for.
        val px = if (livre) ox else ox.coerceIn(min(W - dw, 0), 0)
        val py = if (livre) oy else oy.coerceIn(min(H - dh, 0), 0)
        // A freely-panned frame can pull an image edge onto the canvas, leaving the rest to fall on
        // the near-black wash — a hard, ugly seam. Fill behind it with a blurred, darkened cover of
        // the SAME art so that gap dissolves into the picture's own colours instead of a straight cut
        // to black, then feather the sharp art's own edges so its rectangle melts into that blur
        // rather than ending on a line. Only a livre frame can expose a gap; a clamped box covers.
        val nitida = scaleHQ(img, dw, dh)
        if (livre) {
            coberturaDesfocada(g, img)
            plumar(nitida)
        }
        g.drawImage(nitida, px, py, null)
    }

    /**
     * Fades [img]'s four edges to transparent over a [PLUMA]-wide band, so a freely-panned frame
     * blends into the blurred backdrop instead of ending on a hard rectangle. Same DstOut-gradient
     * dissolve [drawBust] uses on the bust's chin. A cut-out upload's edges are already transparent,
     * so this is a no-op there and only softens the opaque illustrations that actually leave a seam.
     */
    private fun plumar(img: BufferedImage) {
        val w = img.width
        val h = img.height
        val f = min(PLUMA, min(w, h) / 3)
        if (f <= 0) return
        val g = img.createGraphics()
        g.composite = AlphaComposite.DstOut
        val cheio = Color(0, 0, 0, 255)
        val vazio = Color(0, 0, 0, 0)
        g.paint = GradientPaint(0f, 0f, cheio, f.toFloat(), 0f, vazio); g.fillRect(0, 0, f, h)
        g.paint = GradientPaint((w - f).toFloat(), 0f, vazio, w.toFloat(), 0f, cheio); g.fillRect(w - f, 0, f, h)
        g.paint = GradientPaint(0f, 0f, cheio, 0f, f.toFloat(), vazio); g.fillRect(0, 0, w, f)
        g.paint = GradientPaint(0f, (h - f).toFloat(), vazio, 0f, h.toFloat(), cheio); g.fillRect(0, h - f, w, f)
        g.dispose()
    }

    /**
     * The whole canvas filled with a blurred, darkened cover of [img] — the backdrop a freely-panned
     * frame lands on wherever it doesn't reach an edge (and behind any transparent hole in a cut-out
     * upload). The blur is a hard downscale let back up by the graphics' own bilinear step, no
     * convolution; then knocked back so the sharp subject and the right-hand panels still read on top.
     */
    private fun coberturaDesfocada(g: Graphics2D, img: BufferedImage) {
        val escala = max(W.toDouble() / img.width, H.toDouble() / img.height)
        val dw = (img.width * escala).roundToInt()
        val dh = (img.height * escala).roundToInt()
        val sh = (DESFOQUE.toDouble() * img.height / img.width).roundToInt().coerceAtLeast(1)
        val pequena = blit(img, DESFOQUE, sh, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(pequena, (W - dw) / 2, (H - dh) / 2, dw, dh, null)
        g.color = Color(0, 0, 0, 130)
        g.fillRect(0, 0, W, H)
    }

    /**
     * The `retrato` bust: fit the panel WIDTH, anchored at the top. It is wider than the panel is
     * proportionally tall, so filling the height instead would crop the shoulders away and push the
     * face off the top — and the face is the whole reason this asset is worth using.
     */
    internal fun drawBust(g: Graphics2D, img: BufferedImage, x: Int, y: Int, w: Int, h: Int) {
        val dh = (img.height * (w.toDouble() / img.width)).roundToInt().coerceAtLeast(1)
        val visible = min(dh, h)
        // The bust stops in a straight horizontal line wherever the chest is still opaque, which
        // reads as a sticker pasted on: the cut lands on the flat wash with nothing to hide against.
        // Fade the last band to nothing so it dissolves instead.
        val faded = BufferedImage(w, visible, BufferedImage.TYPE_INT_ARGB_PRE)
        val fg = faded.createGraphics()
        fg.drawImage(scaleHQ(img, w, dh), 0, 0, null)
        val fade = min(BUST_FADE, visible)
        fg.composite = AlphaComposite.DstOut
        fg.paint = GradientPaint(
            0f, (visible - fade).toFloat(), Color(0, 0, 0, 0),
            0f, visible.toFloat(), Color(0, 0, 0, 255),
        )
        fg.fillRect(0, visible - fade, w, fade)
        fg.dispose()
        g.drawImage(faded, x, y, null)
    }

    /** Draws [img] scaled to fit inside the box, preserving aspect ratio and centring it. */
    internal fun drawContain(g: Graphics2D, img: BufferedImage, x: Int, y: Int, w: Int, h: Int, bottom: Boolean = false) {
        val scale = min(w.toDouble() / img.width, h.toDouble() / img.height)
        val dw = (img.width * scale).roundToInt().coerceAtLeast(1)
        val dh = (img.height * scale).roundToInt().coerceAtLeast(1)
        g.composite = AlphaComposite.SrcOver
        g.drawImage(scaleHQ(img, dw, dh), x + (w - dw) / 2, if (bottom) y + h - dh else y + (h - dh) / 2, null)
    }

    /**
     * High-quality downscale. Two things matter and Java2D gets both wrong by default:
     *
     *  1. **Premultiplied alpha.** These webps store junk RGB under their transparent pixels (visible
     *     as horizontal colour streaks if you open one). Interpolating straight ARGB averages that
     *     junk into every edge pixel, which is the dirty fringing that reads as "serration".
     *  2. **One-step reduction aliases.** Bilinear samples a 2x2 neighbourhood, so going straight
     *     from 1560px to 545px throws away ~92% of the pixels unsampled. Halving repeatedly until
     *     we're within 2x keeps every source pixel contributing, then one bicubic step lands the
     *     exact size.
     */
    internal fun scaleHQ(src: BufferedImage, tw: Int, th: Int): BufferedImage {
        if (src.width == tw && src.height == th && src.type == BufferedImage.TYPE_INT_ARGB_PRE) return src
        var img = src
        var w = src.width
        var h = src.height
        while (w > tw * 2 && h > th * 2) {
            w /= 2
            h /= 2
            img = blit(img, w, h, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        }
        return blit(img, tw, th, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    }

    private fun blit(src: BufferedImage, w: Int, h: Int, interpolation: Any): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    /**
     * Crops the transparent margin off a splash so the character fills its slot instead of floating
     * in whatever padding the source happened to ship (figPath framings vary wildly between
     * characters). [threshold] is deliberately above zero: the near-transparent junk described in
     * [scaleHQ] would otherwise count as content and defeat the crop. Icons are NOT trimmed — their
     * padding is designed.
     */
    internal fun trimAlpha(img: BufferedImage, threshold: Int = 24): BufferedImage {
        val b = alphaBounds(img, threshold) ?: return img // fully transparent — leave it alone
        return img.getSubimage(b.x, b.y, b.width, b.height)
    }

    /**
     * The tightest rectangle around everything more opaque than [threshold], or null when the image
     * is effectively empty. The pixel-space half of [trimAlpha], split out so [Enquadramento.auto]
     * can frame an upload to its subject without also cropping the bytes.
     */
    internal fun alphaBounds(img: BufferedImage, threshold: Int = 24): Rectangle? {
        var minX = img.width
        var minY = img.height
        var maxX = -1
        var maxY = -1
        val row = IntArray(img.width)
        for (y in 0 until img.height) {
            img.getRGB(0, y, img.width, 1, row, 0, img.width)
            for (x in 0 until img.width) {
                if ((row[x] ushr 24) <= threshold) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return null
        return Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /**
     * The character's own colour, for the background wash: an average weighted by saturation squared,
     * so the greys and the skin tones lose to whatever actually reads as "their" colour, then pushed
     * to a fixed saturation/brightness so a pale character still produces a usable glow.
     */
    internal fun dominant(img: BufferedImage): Color? {
        val s = blit(img, 64, 64, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        var wr = 0.0
        var wg = 0.0
        var wb = 0.0
        var tot = 0.0
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                val px = s.getRGB(x, y)
                if ((px ushr 24) < 200) continue
                val r = (px shr 16) and 0xFF
                val gr = (px shr 8) and 0xFF
                val bl = px and 0xFF
                val sat = (maxOf(r, gr, bl) - minOf(r, gr, bl)) / 255.0
                val w = sat * sat
                wr += r * w
                wg += gr * w
                wb += bl * w
                tot += w
            }
        }
        if (tot < 1.0) return null
        val hsb = Color.RGBtoHSB((wr / tot).roundToInt(), (wg / tot).roundToInt(), (wb / tot).roundToInt(), null)
        return Color.getHSBColor(hsb[0], (hsb[1] * 1.5f).coerceIn(0.50f, 0.92f), 0.58f)
    }

    /**
     * Up to [n] distinct accent colours pulled from [img], most-prominent first — the trio that lets
     * a card echo a whole palette (Castorice → lavender, cyan, pink) instead of one averaged hue.
     *
     * Pixels are binned by hue and weighted by saturation² (the same bias [dominant] uses, so greys,
     * skin and the white dress lose to whatever actually reads as colour), then the heaviest bins are
     * taken greedily, skipping any within [SEPARACAO_MATIZ] of one already chosen so the result is
     * three *different* colours and not three lavenders. Each is normalised to the legible S/B band.
     * Empty when the art is effectively greyscale — the caller falls back to the element colour.
     */
    internal fun palette(img: BufferedImage, n: Int): List<Color> {
        val s = blit(img, 64, 64, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val peso = DoubleArray(MATIZ_BINS)
        val matizPeso = DoubleArray(MATIZ_BINS) // Σ(hue·w) per bin → its weighted mean hue.
        val hsb = FloatArray(3)
        for (y in 0 until s.height) {
            for (x in 0 until s.width) {
                val px = s.getRGB(x, y)
                if ((px ushr 24) < 200) continue
                val r = (px shr 16) and 0xFF
                val gr = (px shr 8) and 0xFF
                val bl = px and 0xFF
                val sat = (maxOf(r, gr, bl) - minOf(r, gr, bl)) / 255.0
                if (sat < 0.15) continue // near-grey: no hue worth binning
                val w = sat * sat
                Color.RGBtoHSB(r, gr, bl, hsb)
                val bin = (hsb[0] * MATIZ_BINS).toInt().coerceIn(0, MATIZ_BINS - 1)
                peso[bin] += w
                matizPeso[bin] += hsb[0] * w
            }
        }
        if (peso.sum() < 1.0) return emptyList()
        fun matiz(bin: Int) = matizPeso[bin] / peso[bin]
        val escolhidos = mutableListOf<Int>()
        for (bin in (0 until MATIZ_BINS).sortedByDescending { peso[it] }) {
            if (peso[bin] <= 0.0) break
            if (escolhidos.none { distanciaMatiz(matiz(it), matiz(bin)) < SEPARACAO_MATIZ }) escolhidos += bin
            if (escolhidos.size == n) break
        }
        return escolhidos.map { Color.getHSBColor(matiz(it).toFloat(), 0.68f, 0.62f) }
    }

    /** Circular distance between two hues on the [0,1) wheel — red-ish 0.98 and 0.02 are close. */
    private fun distanciaMatiz(a: Double, b: Double): Double {
        val d = kotlin.math.abs(a - b)
        return min(d, 1.0 - d)
    }

    internal fun alpha(c: Color, a: Int) = Color(c.red, c.green, c.blue, a)

    private fun mix(a: Color, b: Color, t: Float) = Color(
        (a.red * (1 - t) + b.red * t).roundToInt(),
        (a.green * (1 - t) + b.green * t).roundToInt(),
        (a.blue * (1 - t) + b.blue * t).roundToInt(),
    )
}

private val log = LoggerFactory.getLogger("CardRenderer")

private const val W = 1080
private const val H = 1080
private const val ASSET_BASE = "https://cdn.starrailstation.com/assets"

/** Everything a cache filename may not contain — an absolute URL is escaped through this. */
private val NAO_ARQUIVO = Regex("""[^A-Za-z0-9._-]""")

private const val RX = 548 // right column left edge
private const val RW = W - 40 - RX // right column width (40 = content inset)
private const val COL_W = (RW - 16) / 2 // half column (relíquias | ornamentos)
private const val RX2 = RX + COL_W + 16 // right half column left edge

/** How much room the art credit gets before it is cut: the left column, clear of the right one. */
private const val CREDITO_W = 380

private const val SX = 40 // left column (SINERGIAS) left edge
private const val SW = 390 // left column width

// Vertical bands. Header baseline, then the panel the section's content lives in.
private const val Y_TRACES = 66
private const val Y_CONES = 256
private const val Y_GEAR = 518
private const val Y_STATUS = 858
private const val Y_SINERGIAS = 706 // left column, under the stars

private const val AVATAR = 72
private const val AVATAR_PASSO = 82
private const val SINERGIA_COLUNAS = 4
private const val SINERGIA_LINHAS = 3

/**
 * Synergy avatars the card has room for. The form must never accept more than this — an extra name
 * would be typed, stored, hashed and then silently not drawn — so `GuiaFormulario.MAX_SINERGIAS`
 * reads it from here rather than keeping its own copy of the number.
 */
internal const val SINERGIAS_NO_CARD = SINERGIA_COLUNAS * SINERGIA_LINHAS

/** Rows that fit between the gear panel's top and the STATUS band below it. */
private const val MAX_GEAR_ROWS = 3

// ponytail: 120px of dissolve at the bust's bottom edge — the knob to turn if it eats too much chest.
private const val BUST_FADE = 120

// v2 framing, measured off the hand-made reference cards: the figure stands ~82% of the card tall,
// centred a quarter of the way across, head starting just below the title. These three numbers are
// the entire "how much zoom, how much background" decision — everything else follows from the box.
private const val FIGURA_ALTURA = 0.82
private const val FIGURA_CX = 0.25
// ponytail: 0.12, not 0.07 — the name block owns y 40..130, and at 0.07 a character whose head sits
// at the centre of their own box (Argenti) lands their face right under the lettering.
private const val FIGURA_TOPO = 0.12

// ponytail: width the blurred gap-fill is shrunk to before being let back up — the blur "radius" in
// disguise. Lower = softer/cheaper; raise if the backdrop looks too smeared to read as the same art.
private const val DESFOQUE = 48

// ponytail: how wide the sharp art's edges fade into the blur backdrop. Wider = softer blend; too
// wide eats into the character near a panned edge.
private const val PLUMA = 72

private val GOLD = Color(0xF2, 0xC9, 0x6B)
private val DIM = Color(0xD5, 0xCB, 0xD8)

/** The 4★ star colour — the game's violet, the counterpart to [GOLD] for a 5★. */
private val ROXO_ESTRELA = Color(0xBC, 0x8A, 0xE8)

/** The neutral panel fill every card used before palettes existed — an element card keeps it. */
private val PAINEL_PADRAO = Color(0, 0, 0, 110)

/** Hue buckets [palette] sorts pixels into, and how far apart two picked hues must be to both count. */
private const val MATIZ_BINS = 24
private const val SEPARACAO_MATIZ = 0.06 // ~22° — cyan, pink and purple stay distinct; near-shades merge

/**
 * The colours a card's chrome is drawn in: [borda] outlines panels and badges, [regua] tints the
 * ruled header underlines, [fundo] the panel fill. An official card uses the element colour with the
 * white it always had (so the 95 curated cards are untouched); a member's upload drives these from
 * the picture's own palette, so card and art read as one piece. Stars are NOT here — they are
 * coloured by rarity (5★ gold, 4★ violet), which is rank information, not part of the art palette.
 */
data class Paleta(val borda: Color, val regua: Color, val fundo: Color) {
    companion object {
        fun elemento(elemento: String?): Paleta =
            Paleta(CardRenderer.accentFor(elemento), Color.WHITE, PAINEL_PADRAO)

        /** From an upload's palette; falls back to the element colour when the art is near-greyscale. */
        fun daArte(img: BufferedImage, elemento: String?): Paleta {
            val p = CardRenderer.palette(img, 2)
            val primaria = p.getOrNull(0) ?: return elemento(elemento)
            return Paleta(primaria, p.getOrNull(1) ?: primaria, fundoDe(primaria))
        }

        /** A very dark, tinted panel fill so the dark theme belongs to the palette, not to neutral black. */
        private fun fundoDe(c: Color): Color {
            val hsb = Color.RGBtoHSB(c.red, c.green, c.blue, null)
            return Color.getHSBColor(hsb[0], 0.55f, 0.09f).let { Color(it.red, it.green, it.blue, 150) }
        }
    }
}

private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
private val cacheDir: Path = Path.of("target", "asset-cache").also { Files.createDirectories(it) }

/**
 * The card's type, loaded from the JAR instead of from whatever the host happens to have installed.
 *
 * The old lookup walked "DIN" → "Avenir Next Condensed" → "Futura" → SANS_SERIF against the machine's
 * font list. On this Mac that found the real DIN; on a Linux host it would have found none of them and
 * landed on generic sans — no error, no log line, just every card silently rendering in a different
 * typeface. Bundling the faces makes the render byte-identical on any host and deletes the fallback
 * chain that hid the problem.
 *
 * The display face was macOS's system "DIN Alternate Bold", which is Apple's file and cannot travel
 * with the app. DIN-Bold replaces it: of the faces we own it is the closest in width (501 vs 470 at
 * 48pt) and the only alternate-cut candidate, DIN-BlackAlternate, is unusable — it reports
 * canDisplay('•') == true but maps U+2022 to the wrong outline, drawing "ROBIN ï SUMMERETTO".
 * Verify a font by rendering it, not by asking it.
 */
private fun bundledFont(name: String): Font =
    CardRenderer::class.java.getResourceAsStream("/fonts/$name")
        ?.use { Font.createFont(Font.TRUETYPE_FONT, it) }
        ?: error("Missing bundled font /fonts/$name — it must be on the classpath, not the host")

private val DIN_REGULAR: Font = bundledFont("DIN-Regular.ttf")
private val DIN_BOLD: Font = bundledFont("DIN-Bold.ttf")

private val ELEMENT_ALIAS = mapOf(
    "wind" to "vento", "quantum" to "quantico", "fire" to "fogo", "ice" to "gelo",
    "lightning" to "raio", "imaginary" to "imaginario", "physical" to "fisico",
)

// Only EN→PT: every file in `caminhos/` is named after the normalized PT path, so a PT key here is
// an entry that can go stale under a rename and take the icon with it (which "erudicao" did).
private val PATH_ALIAS = mapOf(
    "erudition" to "erudicao",
    "destruction" to "destruicao", "hunt" to "caca", "abundance" to "abundancia",
    "harmony" to "harmonia", "nihility" to "inexistencia", "preservation" to "preservacao",
    "remembrance" to "recordacao", "elation" to "euforia",
)

private val ACCENTS = mapOf(
    "fogo" to Color(0xE8, 0x54, 0x3A), "gelo" to Color(0x4F, 0xB4, 0xE8),
    "vento" to Color(0x4F, 0xC7, 0x9C), "raio" to Color(0xB0, 0x7B, 0xE8),
    "quantico" to Color(0x6C, 0x6C, 0xE0), "imaginario" to Color(0xE0, 0xC2, 0x5A),
    "fisico" to Color(0xB6, 0xB9, 0xC2),
)

// Keyed on norm(), so these are the game's own labels ("Chance Crít.") with the spellings people
// type kept alongside them.
private val META_ABREV = mapOf(
    "chancecrit" to "CC", "chancedecrit" to "CC",
    "danocrit" to "DC", "danodecrit" to "DC",
    "velocidade" to "VEL",
    "regendeenergia" to "ERR", "eficienciaderecarga" to "ERR",
    "efeitodequebra" to "Quebra",
    "acertodeefeito" to "Acerto", "resdeefeito" to "RES Efeito",
    "aumentodecura" to "Cura",
)

/** Accent-free, article-free, alphanumeric key: "A Erudição" -> "erudicao". */
internal fun norm(s: String?): String = Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFKD)
    .replace("\\p{M}+".toRegex(), "")
    .lowercase()
    .replace("^(a|o|as|os|the)\\s+".toRegex(), "")
    .replace("[^\\p{L}\\p{N}]".toRegex(), "")

/** Greedy word wrap; a single word wider than the box is hard-broken, never clipped. */
internal fun wrapText(fm: FontMetrics, text: String, maxW: Int): List<String> {
    val out = mutableListOf<String>()
    var line = ""
    for (word in text.split(" ").filter { it.isNotEmpty() }) {
        val candidate = if (line.isEmpty()) word else "$line $word"
        if (fm.stringWidth(candidate) <= maxW) {
            line = candidate
            continue
        }
        if (line.isNotEmpty()) {
            out += line
            line = ""
        }
        var rest = word
        while (fm.stringWidth(rest) > maxW && rest.length > 1) {
            var cut = rest.length
            while (cut > 1 && fm.stringWidth(rest.substring(0, cut)) > maxW) cut--
            out += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        line = rest
    }
    if (line.isNotEmpty()) out += line
    return out.ifEmpty { listOf("") }
}

internal fun str(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }

/** Longest side an uploaded image is kept at. [CardRenderer.drawScene] scales the illustration to
 * ~82% of a 1080px canvas, so anything past this is decoded, stored and thrown away. */
private const val ARTE_LADO_MAX = 1280

/**
 * Pixels an upload may claim before we refuse to decode it. Bounds the transient raster (~4 bytes a
 * pixel), which is the only thing standing between the bot and a crafted PNG — see [normalizarArte].
 */
private const val ARTE_MAX_PIXELS = 24_000_000L

/**
 * An uploaded image, made safe and small enough to store: capped, decoded, downscaled and re-encoded
 * as a plain ARGB PNG. Null when the bytes are not an image this JVM can read, or are too big to be
 * one worth reading.
 *
 * The DIMENSIONS are read from the header before a single pixel is decoded, and that ordering is the
 * point. A PNG of flat colour compresses to almost nothing, so a few hundred harmless-looking
 * kilobytes expand into a 20000x20000 raster and take the bot down with them — the upload size limit
 * Discord enforces says nothing at all about that.
 *
 * Re-encoding rather than storing the original is what makes the render path safe: the bytes that
 * come back out of `guia_arte` are ones this JVM has already decoded once, in a format with no
 * surprises left in it (indexed palettes, CMYK JPEGs, premultiplied alpha all collapse here).
 */
internal fun normalizarArte(bytes: ByteArray): ByteArray? {
    ImageIO.scanForPlugins()
    val pixels = runCatching {
        ImageIO.createImageInputStream(java.io.ByteArrayInputStream(bytes))?.use { entrada ->
            val leitor = ImageIO.getImageReaders(entrada).asSequence().firstOrNull() ?: return@use null
            try {
                leitor.input = entrada
                leitor.getWidth(0).toLong() * leitor.getHeight(0).toLong()
            } finally {
                leitor.dispose()
            }
        }
    }.getOrNull() ?: return null
    if (pixels <= 0L || pixels > ARTE_MAX_PIXELS) {
        log.warn("arte enviada recusada: {} pixels", pixels)
        return null
    }

    val lida = runCatching { ImageIO.read(java.io.ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
    val escala = min(1.0, ARTE_LADO_MAX.toDouble() / max(lida.width, lida.height))
    val w = (lida.width * escala).roundToInt().coerceAtLeast(1)
    val h = (lida.height * escala).roundToInt().coerceAtLeast(1)
    // Always through a plain ARGB copy, never straight to the writer: scaleHQ hands back
    // PREMULTIPLIED alpha and ImageIO's PNG writer stores those samples verbatim, which reads back
    // as darkened haloes on every semi-transparent edge.
    val saida = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = saida.createGraphics()
    g.drawImage(CardRenderer.scaleHQ(lida, w, h), 0, 0, null)
    g.dispose()
    return ByteArrayOutputStream().use {
        ImageIO.write(saida, "png", it)
        it.toByteArray()
    }
}
