package com.hsrbot.card

/**
 * Everything a tier list image draws, already resolved: display names, CDN avatar hashes and the
 * per-character change marker. [TierRenderer] takes one of these and touches nothing else — no
 * database, no Spring — the same separation [Ficha] has from the guide's stored spec.
 *
 * [colunas] is exactly four and each column's [Coluna.tiers] is exactly five, in S→D order. The
 * renderer trusts that: a short list would silently drop a tier row rather than fail, so the
 * resolver builds them fixed-size.
 */
data class TierList(
    /** The endgame the list is about, drawn as the title. */
    val modo: String,
    val versao: String? = null,
    /** Author's display name, or null for an anonymous list. The server line is drawn either way. */
    val autor: String? = null,
    val colunas: List<Coluna> = emptyList(),
) {
    /** A legend is only drawn when there is something to explain — i.e. when a previous list existed. */
    val temMudancas: Boolean
        get() = colunas.any { c -> c.tiers.any { t -> t.any { it.mudanca != Mudanca.NENHUMA } } }
}

data class Coluna(val rotulo: String, val tiers: List<List<Avatar>>)

data class Avatar(
    val nome: String,
    val icone: String? = null,
    val elemento: String? = null,
    val mudanca: Mudanca = Mudanca.NENHUMA,
)

/**
 * How this character moved since the author's previous list of the same mode.
 *
 * [SUBIU]/[DESCEU] compare the tier WITHIN this papel, so a character who changed role rather than
 * rank carries no marker — it did not go up or down, it moved sideways, and an arrow would be a
 * lie. [NOVA] is "was not in the previous list at all", which covers both a character released since
 * it and one the author simply had not placed.
 */
enum class Mudanca { NENHUMA, SUBIU, DESCEU, NOVA }
