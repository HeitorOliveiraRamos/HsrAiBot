package com.hsrbot.tier

/**
 * The twenty cells of a tier list: four papéis, five tiers each, holding
 * `personagem_hsr.character_id` strings.
 *
 * A plain nested map rather than a data class with twenty fields, because every caller here
 * iterates papéis and tiers — the wizard builds one page per cell, the renderer draws one column per
 * papel, the diff walks all twenty. Named fields would turn each of those into a `when` with twenty
 * branches.
 *
 * Ids, never names: see V28. The only place a name exists is at render time.
 */
typealias Grade = Map<String, List<List<String>>>

/**
 * A column of the card, and the `dp_`/`ds_`/`sup_`/`def_` key it is stored under.
 *
 * The papel is the author's answer and is never derived from the character's Path. That was the
 * first design and it is wrong: HSR roles do not partition by Path, and the exception list needed to
 * patch it up would be longer than the roster. A character genuinely carrying two roles is placed in
 * both — nothing here stops that, only a second tier within the SAME papel is refused.
 */
enum class Papel(val chave: String, val rotulo: String) {
    DP("dp", "Dano Principal"),
    DS("ds", "Dano Suportivo"),
    SUP("sup", "Suporte"),
    DEF("def", "Protetor"),
}

/** Tier letters, best first. The INDEX is the tier — 0 is S — so "went up" is a smaller index. */
val TIERS = listOf("S", "A", "B", "C", "D")

/** Cell → the ids in it. Absent papéis and short lists read as empty rather than throwing. */
fun Grade.celula(papel: Papel, tier: Int): List<String> =
    this[papel.chave]?.getOrNull(tier).orEmpty()

/** The same grade with one cell replaced. Missing papéis are materialised as five empty tiers. */
fun Grade.comCelula(papel: Papel, tier: Int, ids: List<String>): Grade {
    val atual = this[papel.chave].orEmpty()
    val cinco = List(TIERS.size) { atual.getOrNull(it).orEmpty() }
    return this + (papel.chave to cinco.mapIndexed { i, antigos -> if (i == tier) ids else antigos })
}

/** Which tier this character sits in for this papel, or null when it is not in the column at all. */
fun Grade.tierDe(papel: Papel, id: String): Int? =
    (0 until TIERS.size).firstOrNull { id in celula(papel, it) }

/** Every character placed anywhere in the list. */
fun Grade.todos(): Set<String> = Papel.entries
    .flatMap { p -> (0 until TIERS.size).flatMap { celula(p, it) } }
    .toSet()

/** How many characters the list holds in total, counting a two-role character once per role. */
fun Grade.tamanho(): Int = Papel.entries.sumOf { p -> (0 until TIERS.size).sumOf { celula(p, it).size } }

/** A cell as a single 0..19 number, which is what a component id and the navigation select carry. */
fun celulaDe(papel: Papel, tier: Int): Int = papel.ordinal * TIERS.size + tier

fun papelDaCelula(celula: Int): Papel = Papel.entries[celula / TIERS.size]

fun tierDaCelula(celula: Int): Int = celula % TIERS.size

/** `"Dano Principal · S"` — the label of a cell wherever one is named to the author. */
fun rotuloDaCelula(celula: Int): String =
    "${papelDaCelula(celula).rotulo} · ${TIERS[tierDaCelula(celula)]}"
