package com.hsrbot.card

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * What a card needs to draw the character themselves, as opposed to whatever the card is about.
 * Two cards want exactly this and nothing more — [Ascensao] and [Avaliacao] — which is why it is an
 * interface rather than a fourth copy of the same five fields.
 */
interface Identidade {
    val nome: String
    val elemento: String?
    val caminho: String?
    val raridade: Int
    val arte: Arte
}

/**
 * The left column of the square cards: the illustration, the logo, and the name/element/path/stars
 * signature at its foot.
 *
 * Lifted out of [AscensaoRenderer] when `/build` grew a card, because the two are the same column —
 * same art rules, same anchors, same scrims. The right half is what differs between them, so that is
 * all each renderer now owns. [CardRenderer]'s own splash is deliberately NOT this one: the guide
 * card puts the name at the top, which is a different column, not a parameter of this one.
 */
internal object Retrato {

    /**
     * [enviada] is art the requester uploaded, already normalised, and it wins over the official
     * illustration — they picked a picture on purpose. [focoEnviada] frames THAT picture and nothing
     * else: the curated box measures the official art, so applied to someone else's it zooms in on
     * empty background. An upload therefore starts from the whole image and gets nudged from there.
     */
    fun coluna(
        g: Graphics2D,
        id: Identidade,
        pal: Paleta,
        enviada: BufferedImage? = null,
        focoEnviada: Foco = Foco.INTEIRO,
        logo: BufferedImage? = null,
        tag: String? = null,
    ) {
        splash(g, id, pal.borda, enviada, focoEnviada)
        marca(g, logo, tag, pal.borda)
        assinatura(g, id, pal)
    }

    /**
     * The splash, washed in the character's own palette the way the build card is — and framed by
     * the same [CardRenderer.drawScene] rule, so the whole illustration and its scenery reach this
     * card too rather than a bust in a left-hand slot. The bust stays as the fallback for a
     * character with no focus box: without one there is no way to know where in the picture they
     * are, and guessing is exactly what the curated boxes exist to avoid.
     */
    private fun splash(g: Graphics2D, id: Identidade, accent: Color, enviada: BufferedImage?, focoEnviada: Foco) {
        val foco = if (enviada != null) focoEnviada else id.arte.foco
        val cena = (enviada ?: id.arte.completa?.let { CardRenderer.asset(it) })?.takeIf { foco != null }

        // Trim first, then fit: source framing varies enormously, so cropping the empty margin is
        // what makes every character occupy the same amount of the panel.
        val hash = id.arte.retrato ?: id.arte.figura ?: id.arte.completa
        val art = if (cena != null) null else CardRenderer.asset(hash)?.let { CardRenderer.trimAlpha(it) }
        CardRenderer.background(g, (cena ?: art)?.let { CardRenderer.dominant(it) } ?: accent)
        if (cena != null) {
            // An upload is the member's own frame — pan it freely; the curated official box stays clamped.
            CardRenderer.drawScene(g, cena, foco!!, livre = enviada != null)
        } else {
            art?.let {
                if (id.arte.retrato == null) CardRenderer.drawSplash(g, it, 12, ARTE_Y, ARTE_W, ARTE_H)
                else CardRenderer.drawBust(g, it, 12, ARTE_Y, ARTE_W, ARTE_H)
            }
        }

        // The art runs under the right column: fade it out before the panels start. A whole
        // illustration back there needs a heavier scrim to keep them legible, same as v2.
        val scrim = if (cena != null) 215 else 165
        g.paint = GradientPaint(420f, 0f, Color(0, 0, 0, 0), 620f, 0f, Color(0, 0, 0, scrim))
        g.fillRect(420, 0, W - 420, H)
        // And a band under the name block. Full width for the reason CardRenderer's top band is:
        // stopping at the art panel's edge leaves a lit strip where the two scrims meet.
        val banda = if (cena != null) 215 else 185
        g.paint = GradientPaint(0f, (H - 330).toFloat(), Color(0, 0, 0, 0), 0f, H.toFloat(), Color(0, 0, 0, banda))
        g.fillRect(0, H - 330, W, 330)

        // Only when THEIR picture is the one that got drawn — the official art is nobody's to sign.
        if (enviada != null && cena != null) CardRenderer.credito(g, id.arte.autor)
    }

    /**
     * Logo and server tag, side by side in the top-left corner.
     *
     * [logo] and [tag] are the pair for a card that belongs to a specific server: `/rank` draws the
     * guild's own icon and name there. A guild icon is a photo and not a cut-out logo, so it gets
     * the circular treatment Discord itself gives it — a square screenshot dropped in that corner
     * reads as a rendering bug.
     *
     * Without them the card falls back to the operator's [Marca], which is empty unless configured:
     * an unbranded bot simply draws neither, and the corner stays clean.
     */
    private fun marca(g: Graphics2D, logo: BufferedImage?, tag: String?, accent: Color) {
        val proprio = logo != null
        if (proprio) CardRenderer.avatar(g, logo!!, 44, 38, 84, accent)
        else Marca.logo?.let { CardRenderer.drawContain(g, it, 44, 38, 84, 84) }

        val texto = (tag ?: Marca.tag)?.uppercase() ?: return
        // Sits beside the logo when there is one, and takes its place when there isn't — a tag
        // indented past an empty square reads as a missing image rather than as a choice.
        val x = if (proprio || Marca.logo != null) 140 else MARGEM
        // A server name can be anything, so it shrinks to fit the strip left of the right column
        // instead of running under the panels.
        val (fonte, linhas) = CardRenderer.fitText(g, texto, 300, 1, 16f, 10f, CardRenderer.din(16f, tracking = 0.18))
        CardRenderer.drawLines(g, linhas.take(1), fonte, x, 88, CardRenderer.alpha(Color.WHITE, 170))
    }

    /**
     * The foot of the splash: name over the element badge, then the path badge and the rarity stars.
     * Everything is anchored UP from [Y_NOME] so a two-line name grows into the art instead of
     * pushing the stars off the card.
     */
    private fun assinatura(g: Graphics2D, id: Identidade, pal: Paleta) {
        val (fonte, linhas) = CardRenderer.fitText(
            g, id.nome.uppercase(), NOME_W, 2, 62f, 26f, CardRenderer.display(),
        )
        val lead = (fonte.size2D * 0.92f).roundToInt()
        val primeira = Y_NOME - (linhas.size - 1) * lead

        // A short rule over the name, the one piece of chrome the old card opens its title with.
        g.paint = GradientPaint(
            NOME_X.toFloat(), 0f, CardRenderer.alpha(Color.WHITE, 200),
            (NOME_X + 210).toFloat(), 0f, CardRenderer.alpha(Color.WHITE, 0),
        )
        // Clear of the cap height, which at 62pt is ~46px: any tighter and the rule draws INSIDE the
        // letters instead of over them.
        val regua = primeira - (fonte.size2D * 1.06f).roundToInt()
        g.stroke = BasicStroke(2f)
        g.drawLine(NOME_X, regua, NOME_X + 210, regua)

        var y = primeira
        for (linha in linhas) {
            g.font = fonte
            g.color = Color(0, 0, 0, 175)
            g.drawString(linha, NOME_X + 3, y + 3)
            g.color = Color.WHITE
            g.drawString(linha, NOME_X, y)
            y += lead
        }

        // The element sits beside the LAST line of the name, the path leads the stars below it.
        CardRenderer.res("elementos", CardRenderer.elementFile(id.elemento))
            ?.let { CardRenderer.badge(g, it, MARGEM, Y_NOME - 46, 54, pal.borda) }
        CardRenderer.res("caminhos", CardRenderer.pathFile(id.caminho))
            ?.let { CardRenderer.badge(g, it, MARGEM, Y_ESTRELAS - 24, 48, pal.borda) }
        CardRenderer.stars(g, id.raridade, MARGEM + 62, Y_ESTRELAS)
    }

    const val W = 1080
    const val H = 1080
    const val MARGEM = 46

    private const val ARTE_Y = 100
    private const val ARTE_W = 540
    private const val ARTE_H = 880

    /** Baseline of the name's LAST line, and the stars' centre — the block grows upward from here. */
    const val Y_NOME = 934
    const val Y_ESTRELAS = 992
    private const val NOME_X = 116
    private const val NOME_W = 400
}
