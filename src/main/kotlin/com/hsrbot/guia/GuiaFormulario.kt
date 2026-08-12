package com.hsrbot.guia

import com.hsrbot.card.SINERGIAS_NO_CARD
import com.hsrbot.card.norm

/**
 * The typed half of the wizard: what the modal boxes hold, in and out.
 *
 * Everything with a closed list of answers is a select instead — including the status targets,
 * whose STATS live here but are picked from a list, never typed, and the trace priority, which is
 * four ordered selects. What is left is the genuinely open-ended (a synergy roster, a target like
 * "100% em combate") plus ORDER, which a select cannot express at all: Discord returns a select's
 * values in option order, never click order, so sequence has to be asked as lines of text.
 *
 * Parsing NEVER throws away the whole field over one bad line: what parsed is kept, what didn't is
 * reported back. Discord discards a modal's contents the moment it is answered, so rejecting the
 * submission outright would mean retyping everything because of one typo.
 */
object GuiaFormulario {

    /**
     * What a relic or ornament roll can contribute, which is what a status target is set on — the
     * options of the metas select. These are the game's own PT labels, the same strings the curated
     * `builds` rows carry, so a guide and a harvested recommendation never disagree about what a
     * stat is called and a prefilled meta always matches an option.
     */
    val STATS = listOf(
        "PV", "PV%", "ATQ", "ATQ%", "DEF", "DEF%", "Velocidade", "Chance Crít.", "Dano Crít.",
        "Efeito de Quebra", "Acerto de Efeito", "RES de Efeito", "Regen. de Energia",
    )

    /** The card shows four target lines; a fifth would be drawn outside its panel. */
    const val MAX_METAS = 4

    /** How many cones, relic sets and ornament sets one card holds — the cap in every box here. */
    const val MAX_CONJUNTOS = 3

    /**
     * What a cone starts at when nobody has said otherwise: a 4★ is the one everyone owns copies
     * of, so S5 is the realistic assumption, while a 5★ is a single pull and S1 is the honest one.
     * The author overrides either in the Detalhes box.
     */
    fun sobreposicaoPadrao(raridade: Int?): Int = if (raridade == 4) 5 else 1

    // -------------------- sobreposições -------------------- //

    fun formatSobreposicoes(cones: List<ConeSpec>): String =
        cones.joinToString("\n") { "${it.nome}: S${it.sobreposicao ?: 1}" }

    /**
     * `"Um Voto Secreto: S5"`, one cone per line — this box says WHICH cones, in WHAT order, at
     * WHAT rank. The box is prefilled with the current answer, so what it holds when it comes back
     * is the whole answer: a line swapped for another cone of the character's path swaps the cone
     * itself, a line deleted removes it, and the order of the lines is the order the card draws.
     *
     * Order can only be asked here, never in the select: Discord hands a select's values back in
     * option order and never in click order.
     *
     * Matching is by name against [catalogo] (this character's usable cones) plus whatever is
     * already chosen, so a cone whose Path was patched away can still be typed back. The rank is
     * optional — a bare name keeps the rank it had, or takes [sobreposicaoPadrao] when it is new. A
     * whole line is tried as a name BEFORE splitting on the last colon, since cone names carry
     * colons of their own ("Registro Ninja: Caça ao Som") and that has to work either way.
     *
     * A box nobody could read a single name out of leaves the choice alone — one typo must not be
     * the same gesture as "remove everything".
     */
    fun parseSobreposicoes(
        texto: String,
        cones: List<ConeSpec>,
        catalogo: Map<String, Int>,
        max: Int = MAX_CONJUNTOS,
    ): Resultado<ConeSpec> {
        if (texto.isBlank()) return Resultado(cones, emptyList())
        val raridades = catalogo.mapKeys { norm(it.key) }
        val atual = cones.associateBy { norm(it.nome) }
        // The catalogue's spelling wins, so a name typed with different accents is stored the way
        // the database spells it and still resolves to an icon at render time.
        val porNome = (cones.map { it.nome } + catalogo.keys).associateBy { norm(it) }
        val escolhidos = LinkedHashMap<String, ConeSpec>()
        val erros = mutableListOf<String>()
        texto.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { linha ->
            val semRank = porNome[norm(linha)]
            val nome = semRank ?: porNome[norm(linha.substringBeforeLast(":").trim())]
            if (nome == null) {
                erros += "Não achei o cone “${linha.substringBeforeLast(":").trim()}” — ele tem que ser " +
                    "do caminho da personagem e 4★ ou 5★."
                return@forEach
            }
            val chave = norm(nome)
            val anterior = atual[chave]?.sobreposicao
            val rank = if (semRank != null) null else {
                val n = linha.substringAfterLast(":", "").trim().trimStart('s', 'S').toIntOrNull()
                if (n == null || n !in 1..5) {
                    erros += "Sobreposição inválida em “$nome” — use de S1 a S5."
                    null
                } else n
            }
            escolhidos[chave] = ConeSpec(nome, rank ?: anterior ?: sobreposicaoPadrao(raridades[chave]))
        }
        if (escolhidos.isEmpty()) return Resultado(cones, erros)
        return Resultado(escolhidos.values.take(max), erros + excedente(escolhidos.size, max, "cones"))
    }

    // -------------------- conjuntos -------------------- //

    /** One column, one set name per line, in the order the card draws them. */
    fun formatOrdem(linhas: List<LinhaSpec>): String =
        linhas.flatMap { l -> l.partes.map { it.nome } }.joinToString("\n")

    /**
     * One column's box: which sets, in what order. Same contract as [parseSobreposicoes] — the box
     * arrives prefilled, so what comes back IS the answer, and a name swapped for another set that
     * really exists swaps the set.
     *
     * That order matters beyond taste: the first relic line is what decides the corpo/pés icons.
     * The caller rebuilds the lines from these names, which is also what keeps a "2 + 2" together —
     * a split is two names in one line and can only be reassembled by whoever knows about it.
     */
    fun parseConjuntos(
        texto: String,
        atuais: List<String>,
        catalogo: Collection<String>,
        max: Int = MAX_CONJUNTOS,
    ): Resultado<String> {
        if (texto.isBlank()) return Resultado(atuais, emptyList())
        val porNome = (atuais + catalogo).associateBy { norm(it) }
        val escolhidos = LinkedHashSet<String>()
        val erros = mutableListOf<String>()
        texto.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { linha ->
            val nome = porNome[norm(linha)]
            if (nome == null) erros += "Não achei o conjunto “$linha”."
            else escolhidos += nome
        }
        if (escolhidos.isEmpty()) return Resultado(atuais, erros)
        return Resultado(escolhidos.take(max), erros + excedente(escolhidos.size, max, "conjuntos"))
    }

    /** Said once, where the box is cut, rather than silently dropping the tail. */
    private fun excedente(quantos: Int, max: Int, oque: String): List<String> =
        if (quantos <= max) emptyList() else listOf("Só cabem $max $oque no card — fiquei com os $max primeiros.")

    // -------------------- sinergias -------------------- //

    fun formatSinergias(sinergias: List<String>): String = sinergias.joinToString(", ")

    /**
     * Splits the typed list. Resolving each name to a real character is the caller's job — that
     * needs the gazetteer — so the splitting rule stays testable on its own.
     */
    fun parseSinergias(texto: String): List<String> =
        texto.split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_SINERGIAS)

    /** However many avatars the card's grid holds — see [SINERGIAS_NO_CARD]. */
    const val MAX_SINERGIAS = SINERGIAS_NO_CARD

    /** What parsed, and what to tell the author about the rest. */
    data class Resultado<T>(val valores: List<T>, val erros: List<String>)
}
