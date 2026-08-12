package com.hsrbot.guia

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * A filled-in guide form: what the author CHOSE, and nothing that can be looked up. It is stored as
 * `guia.spec` and hashed to decide whether a card has already been made.
 *
 * Deliberately separate from [com.hsrbot.card.Ficha], which is the resolved thing the renderer
 * draws. Two reasons: icons and canonical names must be re-resolved on every render (an asset hash
 * changes when the artwork does), and the hash input has to stay stable — adding a field to the
 * render model must never change what an existing guide hashes to.
 *
 * Everything defaults to empty so a half-filled draft round-trips through the wizard, and unknown
 * properties are ignored so a spec written by an older version still loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class GuiaSpec(
    /** `personagem_hsr.character_id` — the game's own id, stable across repopulates. */
    val personagemId: Int,
    val rastros: List<RastroSpec> = emptyList(),
    val cones: List<ConeSpec> = emptyList(),
    val reliquias: List<LinhaSpec> = emptyList(),
    val ornamentos: List<LinhaSpec> = emptyList(),
    val corpo: String? = null,
    val pes: String? = null,
    val esfera: String? = null,
    val corda: String? = null,
    val metas: List<MetaSpec> = emptyList(),
    /** Canonical character names; the wizard resolves whatever was typed before saving. */
    val sinergias: List<String> = emptyList(),
    /** The author's own framing for THIS guide. Null = whatever `personagem_hsr` was curated with. */
    val foco: FocoSpec? = null,
    /**
     * sha256 of an image the author uploaded, stored in `guia_arte`. The hash and not the bytes:
     * this object is serialised into the guide's own sha256 and read back on every wizard click.
     * Set = the card draws THIS picture instead of the official art, framed by [foco].
     */
    val arte: String? = null,
    /**
     * Who uploaded [arte], credited in the card's corner. In the SPEC and not read off `guia.autor_id`
     * at render time because the PNG cache is keyed by this object's hash: two authors who upload the
     * same picture must get two cards with two names on them, not one cached card with whoever
     * rendered first written on it.
     */
    val arteAutor: String? = null,
) {
    /** Enough answers to be worth a card. Everything else the renderer simply leaves out. */
    val completa: Boolean get() = cones.isNotEmpty() || reliquias.isNotEmpty() || ornamentos.isNotEmpty()
}

/**
 * One trace in the priority row. [rotulo] is a key of [com.hsrbot.card.RASTRO_ICONE], and the
 * position in [GuiaSpec.rastros] IS the priority.
 *
 * It used to carry an `operador` (`>` or `=`). Dropped: the form now asks for the order with four
 * ordered selects instead of a typed line, and `=` was a piece of syntax to learn. A stored spec
 * that still has one loads fine — unknown properties are ignored — and simply renders as `>`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RastroSpec(val rotulo: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ConeSpec(val nome: String, val sobreposicao: Int? = null)

/** One recommendation line: one set at 4 pieces, or the "2 de X + 2 de Y" split. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LinhaSpec(val partes: List<ParteSpec>)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ParteSpec(val nome: String, val pecas: Int)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaSpec(val stat: String, val alvo: String? = null)

/**
 * Where the character sits inside `arte_completa`, as fractions of the image — the same box the
 * curated `arte_foco_*` columns hold, written by the author for their own guide instead.
 *
 * Its own type rather than [com.hsrbot.card.Foco] for the reason the whole spec is separate: this
 * one is hashed and stored forever, so it must not move when the render model does.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FocoSpec(val x: Double, val y: Double, val largura: Double, val altura: Double)
