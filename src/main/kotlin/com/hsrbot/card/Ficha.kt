package com.hsrbot.card

/**
 * Everything a build card draws, already resolved: display names, CDN asset hashes and the handful
 * of numbers the layout needs. [CardRenderer] takes one of these and touches nothing else — no
 * database, no Spring — so a card is a pure function of this object.
 *
 * This is NOT the user's form input. A guide's stored spec holds *choices* (set names, ranks,
 * targets); resolving those against `personagem_hsr`/`reliquias`/`ornamentos_planos`/`cones_de_luz`
 * to get icons and canonical names is the caller's job, and it is what makes a stored guide
 * re-render correctly after an asset changes.
 *
 * Every field is optional in the sense that the renderer skips a section it has no data for: a
 * ficha with only a name still produces a card.
 */
data class Ficha(
    val nome: String,
    val faccao: String? = null,
    val elemento: String? = null,
    val caminho: String? = null,
    val raridade: Int = 5,
    val arte: Arte = Arte(),
    /** RASTROS row, already in priority order; the renderer draws a `>` between each pair. */
    val rastros: List<Rastro> = emptyList(),
    val cones: List<Cone> = emptyList(),
    val reliquias: List<Linha> = emptyList(),
    val ornamentos: List<Linha> = emptyList(),
    val status: Status = Status(),
    val metas: List<Meta> = emptyList(),
    val sinergias: List<Sinergia> = emptyList(),
)

/**
 * The splash sources. v2 draws a whole illustration and needs [foco] to know where the character is
 * inside it; v1 draws the `retrato` bust over a colour wash. [podeV2] is the single place that
 * decides, so the renderer and its callers can never disagree about which layout a card ended up
 * using.
 *
 * [enviada] is art the guide's author uploaded, already on disk, and it WINS over [completa] — they
 * picked a picture on purpose. A Path rather than the bytes so this stays a well-behaved data class
 * (a BufferedImage or ByteArray field would make `equals` compare references, the trap [Cartao]
 * documents); the file is a cache of the `guia_arte` row and the caller materialises it.
 *
 * [autor] is who uploaded it, credited on the card the way `/tierlist` credits whoever made a list.
 * It is only ever drawn when their picture is the one on the canvas: it credits the art, not the
 * card, and the official illustration is nobody here's to sign.
 */
data class Arte(
    val completa: String? = null,
    val retrato: String? = null,
    val figura: String? = null,
    val enviada: java.nio.file.Path? = null,
    val autor: String? = null,
    val foco: Foco? = null,
    /**
     * The frame is the MEMBER's to move, not a curated column — an upload, or an official
     * illustration they nudged. [CardRenderer.drawScene] lets such a frame pan past the strict cover
     * (the wash fills any gap); a curated frame stays clamped so its corners keep the artwork.
     */
    val enquadramentoLivre: Boolean = false,
    /** v2 requested. Silently ignored when there is no focus box to frame with — see [podeV2]. */
    val v2: Boolean = false,
) {
    val podeV2: Boolean get() = v2 && foco != null && (completa != null || enviada != null)
}

/** Where the character sits inside `arte_completa`, as fractions of the image (the V23 columns). */
data class Foco(val x: Double, val y: Double, val w: Double, val h: Double) {
    companion object {
        /**
         * The framing an upload starts from: the whole picture. Anything else would be a guess about
         * where in a stranger's image the character is, which is the judgement the curated boxes
         * exist precisely to avoid making automatically.
         */
        val INTEIRO = Foco(0.0, 0.0, 1.0, 1.0)
    }
}

/**
 * One ability in the trace-priority row. Position IS the priority — the row is drawn strictly
 * descending, joined by `>`. There used to be an `=` for "these two matter about the same" and the
 * form no longer asks for it: it made the author learn an operator to answer a question that is
 * really just "put them in order".
 */
data class Rastro(val rotulo: String, val icone: String? = null)

data class Cone(val nome: String, val icone: String? = null, val sobreposicao: Int? = null)

/**
 * One recommendation line in the RELÍQUIAS/ORNAMENTOS column. Usually a single set worn at 4 (relic)
 * or 2 (ornament) pieces, but the split builds the guide team actually writes — "2 de X + 2 de Y" —
 * are two [Parte]s in one line, drawn as adjacent rows joined by a `+`.
 */
data class Linha(val partes: List<Parte>) {
    constructor(vararg partes: Parte) : this(partes.toList())
}

data class Parte(val nome: String, val icone: String? = null, val pecas: Int = 4)

/** Main stats, one per slot, plus the piece icons shown beside them (corpo→pés→esfera→corda). */
data class Status(
    val corpo: String? = null,
    val pes: String? = null,
    val esfera: String? = null,
    val corda: String? = null,
    val icones: List<String?> = emptyList(),
)

/**
 * A line of the METAS column. [alvo] is the number the build is aiming at ("100% em combate");
 * when it is null the line is a bare substat priority instead and gets numbered, which is how the
 * curated `builds.substatus_recomendados` rows still render.
 */
data class Meta(val stat: String, val alvo: String? = null)

data class Sinergia(val nome: String, val icone: String? = null)
