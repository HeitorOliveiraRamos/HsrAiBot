package com.hsrbot.tier

import com.hsrbot.card.Mudanca
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Who moved, per papel — the author's answer where they gave one, and the automatic diff everywhere
 * else.
 *
 * Keyed by [Papel.chave]. **A key's PRESENCE is the switch**, never whether its lists have anything
 * in them: a papel with an entry is drawn from that entry even when all three lists are empty, and a
 * papel with no entry is drawn from the diff against the author's previous list. That is what makes
 * the two coexist column by column, which is the whole requirement — correct Dano Principal by hand,
 * leave Suporte to work itself out.
 */
typealias Ajustes = Map<String, MudancasSpec>

/**
 * One papel's markers, as `personagem_hsr.character_id`.
 *
 * Ids and not the names the author typed: a name is resolved once, when the form is submitted, and
 * what gets stored is who they meant. Storing the typed string would re-run the ambiguity every
 * render and leave "Desbravadora" naming five people forever.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class MudancasSpec(
    val novas: List<String> = emptyList(),
    val subiram: List<String> = emptyList(),
    val desceram: List<String> = emptyList(),
) {
    /**
     * The marker for one character, or [Mudanca.NENHUMA].
     *
     * Precedence when the author lists someone twice — which the form has no reason to refuse, since
     * refusing it would mean rejecting the whole box over one name: NOVA, then SUBIU, then DESCEU.
     * "New" wins because it is a statement about the character rather than about their rank.
     */
    fun de(id: String): Mudanca = when (id) {
        in novas -> Mudanca.NOVA
        in subiram -> Mudanca.SUBIU
        in desceram -> Mudanca.DESCEU
        else -> Mudanca.NENHUMA
    }

    /** Only markers for characters actually in the column. See [semRestos]. */
    fun restritoA(ids: Collection<String>): MudancasSpec = MudancasSpec(
        novas = novas.filter { it in ids },
        subiram = subiram.filter { it in ids },
        desceram = desceram.filter { it in ids },
    )

    val vazio: Boolean get() = novas.isEmpty() && subiram.isEmpty() && desceram.isEmpty()
}

/**
 * The stored adjustments with anything that no longer names a character in that column dropped.
 *
 * A manual marker is written against the list as it stood; moving that character out of the column
 * afterwards leaves a marker with nothing to draw on. Cleaning at read time rather than chasing
 * every edit keeps the invariant in one place — nothing else has to remember that adjustments exist.
 */
fun Ajustes.semRestos(grade: Grade): Ajustes = mapValues { (chave, m) ->
    val papel = Papel.entries.firstOrNull { it.chave == chave } ?: return@mapValues m
    m.restritoA((0 until TIERS.size).flatMap { grade.celula(papel, it) }.toSet())
}

/**
 * The computed markers for one column: this list against the author's previous one.
 *
 * Up and down are compared WITHIN the papel — a character who swapped roles did not go up or down,
 * and an arrow on them would be a claim the list does not make. New is "was not in the previous list
 * at all", which covers both a character released since it and one the author had simply not placed.
 *
 * A pure function and not a method on the service, because it is the rule the whole feature argues
 * about and it should be testable without a database behind it.
 */
fun automatico(anterior: Grade?, grade: Grade, papel: Papel): MudancasSpec {
    if (anterior == null) return MudancasSpec()
    val jaVistos = anterior.todos()
    val novas = mutableListOf<String>()
    val subiram = mutableListOf<String>()
    val desceram = mutableListOf<String>()
    (0 until TIERS.size).forEach { tier ->
        grade.celula(papel, tier).forEach { id ->
            val antes = anterior.tierDe(papel, id)
            when {
                id !in jaVistos -> novas += id
                antes == null -> Unit
                tier < antes -> subiram += id
                tier > antes -> desceram += id
            }
        }
    }
    return MudancasSpec(novas, subiram, desceram)
}
