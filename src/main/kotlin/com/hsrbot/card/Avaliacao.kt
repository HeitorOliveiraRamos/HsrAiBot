package com.hsrbot.card

/**
 * Everything the `/build` card draws, already resolved — the [Ficha] of [AvaliacaoRenderer], and the
 * same contract: display strings and CDN asset hashes, no database and no Spring, so the card is a
 * pure function of this object.
 *
 * What makes it different from the other two cards is where the numbers come from. A guide is what
 * someone *recommends* and an ascension is what the game *costs*; this one is what the player
 * actually equipped, read off their showcase — so every number here traces back to
 * [com.hsrbot.hsr.BuildAnalyzer] over live showcase data, and none of it is curated or stored. The
 * scores are fribbels/hsr-optimizer's own Perfection, so they can be checked against their site with
 * the same UID — their 84,4% is the 8,44 drawn here.
 */
data class Avaliacao(
    override val nome: String,
    override val elemento: String? = null,
    override val caminho: String? = null,
    override val raridade: Int = 5,
    override val arte: Arte = Arte(),
    val nivel: Int = 0,
    val eidolon: Int = 0,
    /** fribbels' Perfection, still 0–100 here — [com.hsrbot.hsr.BuildAnalyzer.nota] draws it as 0–10. */
    val nota: Double = 0.0,
    val rank: String = "",
    /**
     * A ruler note for the footer, empty on the default ruler — só aparece quando a régua saiu do
     * padrão (hoje, o peso da Velocidade), que é o caso em que a nota não bate com a de mais ninguém.
     */
    val regua: String = "",
    val jogador: String? = null,
    val uid: String? = null,
    val cone: Cone? = null,
    val conjuntos: List<Parte> = emptyList(),
    val stats: List<Stat> = emptyList(),
    /** The six slots in order, empty ones included — a missing piece is a real deficiency. */
    val pecas: List<Peca> = emptyList(),
) : Identidade

/** One finished combat stat, already formatted: `VEL` / `134`. */
data class Stat(val rotulo: String, val valor: String)

/**
 * One relic slot on the card. [vazio] is the slot the player left empty — it still gets its row,
 * because "you have no rope" is the single most useful thing the card can tell someone.
 *
 * [mainErrado] is only ever true for slots 3–6: head and hands have fixed main stats, so there is
 * no wrong choice to flag there.
 */
data class Peca(
    val slot: Int,
    val nome: String,
    val nivel: Int = 0,
    val main: String = "—",
    val nota: Double = 0.0,
    val rank: String = "",
    val icone: String? = null,
    val mainErrado: Boolean = false,
    /** The four substats as rolled, in showcase order — the row's explanation of its own score. */
    val subs: List<Sub> = emptyList(),
    val vazio: Boolean = false,
)

/**
 * One substat on a relic row: `D.CRIT 12.3%` with [melhorias] dots under it. [melhorias] is how many
 * times the line was ENHANCED — the base roll is the line existing, so it gets no dot, and a +15
 * relic's dots therefore sum to 5 across the row (born with four substats) or 4 (born with three).
 *
 * The dots sit under the text rather than in it because the value is the thing being read; the roll
 * count is how the value got there, which is a footnote to it.
 *
 * [util] decides the colour and nothing else — white for a roll the character's ruler pays for,
 * faded for one it does not, so the reader can see a low score is wasted rolls rather than bad luck
 * without a word of explanation.
 */
data class Sub(val rotulo: String, val valor: String, val melhorias: Int, val util: Boolean)
