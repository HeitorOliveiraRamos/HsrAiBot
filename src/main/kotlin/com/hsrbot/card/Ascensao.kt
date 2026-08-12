package com.hsrbot.card

/**
 * Everything an ascension guide card draws, already resolved — the [Ficha] of [AscensaoRenderer].
 * Same contract: display names and CDN asset hashes, no database and no Spring, so the card is a
 * pure function of this object and a card with only a name still renders.
 *
 * The two grids come straight from `personagem_hsr.custos_melhoria` (V30), already in draw order:
 * [personagem] is the six-cell MELHORIA DE PERSONAGEM grid and [rastros] the nine-cell MELHORIA DE
 * RASTROS one. Resolving each cell's material id to an icon against `materiais` is the caller's job,
 * the same way a guide's relic names are resolved before a [Ficha] reaches [CardRenderer].
 */
data class Ascensao(
    override val nome: String,
    override val elemento: String? = null,
    override val caminho: String? = null,
    override val raridade: Int = 5,
    override val arte: Arte = Arte(),
    val personagem: List<Custo> = emptyList(),
    val rastros: List<Custo> = emptyList(),
) : Identidade

/**
 * One cell of a grid: how much of a material, and the icon to draw above the number. [icone] is null
 * until `materiais` is populated (and stays fail-soft afterwards) — the cell then draws its empty
 * socket, never nothing, so a missing icon reads as a slot rather than as a hole in the layout.
 */
data class Custo(val qtd: Long, val icone: String? = null)
