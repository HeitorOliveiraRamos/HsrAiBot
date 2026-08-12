package com.hsrbot.card

import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import java.awt.image.BufferedImage

/**
 * Reframing a card's art with buttons instead of four typed fractions.
 *
 * The one home for the framing controls, shared by every card that lets a member move the art —
 * `/ascensao`, `/build` and `/guia`. Each surface keeps its own idea of WHERE a frame is stored (an
 * in-memory preview, a saved draft) and HOW it redraws afterwards; what none of them may reinvent is
 * the arithmetic that turns a click into a new [Foco], the rows of buttons, or the opening frame an
 * upload deserves. That lives here, so "the framing works like this" is true in exactly one place.
 *
 * A [Foco] is (x, y, w, h) as fractions of the image, read by [CardRenderer.drawScene]: the crop's
 * centre is x + w/2 and its height drives the zoom. So a character moves RIGHT on the card when the
 * crop slides LEFT, and zooming IN means a SMALLER box. The [Dir]s are named for what the member
 * sees; the inversion lives once, in [aplicar].
 */
object Enquadramento {

    /** A single press, named for how the character moves on the card — never how the crop moves. */
    enum class Dir { ESQUERDA, DIREITA, CIMA, BAIXO, MAIS_ZOOM, MENOS_ZOOM }

    /** One nudge: the frame in play plus a direction, a new frame clamped to what still draws. */
    fun aplicar(f: Foco, dir: Dir): Foco = when (dir) {
        Dir.DIREITA -> f.copy(x = f.x - PASSO)
        Dir.ESQUERDA -> f.copy(x = f.x + PASSO)
        Dir.CIMA -> f.copy(y = f.y + PASSO)
        Dir.BAIXO -> f.copy(y = f.y - PASSO)
        Dir.MAIS_ZOOM -> zoom(f, ZOOM)
        Dir.MENOS_ZOOM -> zoom(f, 1.0 / ZOOM)
    }.saneado()

    /** Scales the box around its own centre, so a zoom keeps the character where they already are. */
    private fun zoom(f: Foco, fator: Double): Foco {
        val w = f.w * fator
        val h = f.h * fator
        return Foco(f.x + (f.w - w) / 2, f.y + (f.h - h) / 2, w, h)
    }

    // A [0,1] clamp walls an edge frame: a flat upload opens at the whole image (anchor at its
    // centre) and one arrow is instantly floored back — press ▶ on it and nothing moves. A member's
    // frame is not storage-bound (it lives in `guia.spec` jsonb or in memory, never the V23-checked
    // arte_foco_* columns), so let the anchor pan a full image-width past either edge; the colour
    // wash fills any gap and Recomeçar is one press away if they lose the character off-screen.
    private fun Foco.saneado() = Foco(
        x.coerceIn(-PAN_MAX, 1.0 + PAN_MAX),
        y.coerceIn(-PAN_MAX, 1.0 + PAN_MAX),
        w.coerceIn(MIN_BOX, 1.0),
        h.coerceIn(MIN_BOX, 1.0),
    )

    /**
     * The frame an upload opens on: the character's own bounds, not the whole canvas.
     *
     * Most uploaded art is a render on transparency, so the opaque bounding box IS the character, and
     * framing to it is the same trim the curated boxes give the official art — one alpha scan. A flat
     * image has no bounds to find and falls back to the whole picture, which is where an upload's
     * framing started before this existed, so nobody's card gets worse.
     */
    fun auto(img: BufferedImage): Foco {
        val b = CardRenderer.alphaBounds(img) ?: return Foco.INTEIRO
        return Foco(
            b.x.toDouble() / img.width,
            b.y.toDouble() / img.height,
            b.width.toDouble() / img.width,
            b.height.toDouble() / img.height,
        )
    }

    /**
     * The framing controls as rows, keyed by whatever id scheme the caller uses — a 3-part
     * `cartao:…` id for a preview, a 4-part `g:…` id for a draft. The caller maps a campo (a [Dir]
     * token, or [RESET]) to its own component id; nothing here knows which card is being framed.
     */
    fun linhas(id: (String) -> String): List<ActionRow> = listOf(
        ActionRow.of(
            Button.secondary(id(token(Dir.ESQUERDA)), "◀"),
            Button.secondary(id(token(Dir.CIMA)), "▲"),
            Button.secondary(id(token(Dir.BAIXO)), "▼"),
            Button.secondary(id(token(Dir.DIREITA)), "▶"),
        ),
        ActionRow.of(
            Button.secondary(id(token(Dir.MAIS_ZOOM)), "Aproximar 🔍"),
            Button.secondary(id(token(Dir.MENOS_ZOOM)), "Afastar"),
            Button.secondary(id(RESET), "Recomeçar ↺"),
        ),
    )

    /** The campo a click carries → its [Dir], or null when it is not one of the arrows. */
    fun dir(campo: String): Dir? =
        if (campo.startsWith(PREFIXO)) Dir.entries.firstOrNull { token(it) == campo } else null

    private fun token(dir: Dir) = PREFIXO + dir.name.lowercase()

    /** Back to the opening frame. Its own token so [dir] never mistakes it for an arrow. */
    const val RESET = "nd_reset"

    private const val PREFIXO = "nd_"

    /** How far one arrow slides the character — a fraction of the image. */
    private const val PASSO = 0.05

    /** How much one step tightens (or, inverted, loosens) the crop. */
    private const val ZOOM = 0.88

    /** The tightest crop allowed: past this there is nothing on screen but magnified pixels. */
    private const val MIN_BOX = 0.08

    /** How far past an image edge the anchor may pan — one full image-width, enough to reach either side. */
    private const val PAN_MAX = 1.0
}
