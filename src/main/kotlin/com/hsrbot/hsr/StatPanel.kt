package com.hsrbot.hsr

import java.util.Locale
import kotlin.math.truncate

/**
 * The combat stat panel Enka does not compute — the block mihomo used to hand us finished.
 *
 * Nobody gets this precomputed. enka.network's own site and fribbels' optimizer both do exactly what
 * happens here: take the raw showcase and add it up against the static game tables. The tables all
 * live in StarRailRes, which [StarRailResNames] already pulls daily, and they speak the SAME property
 * vocabulary as the scorer (`CriticalChanceBase`, `QuantumAddedRatio`), so nothing new has to be
 * translated.
 *
 * The sum, in game order:
 *
 *  - character base, `base + step × (level − 1)` off `character_promotions` at the ascension;
 *  - the light cone's base HP/ATK/DEF, which Enka DOES ship, in `equipment._flat.props`;
 *  - the light cone's PASSIVE grant at its superimposition, from `light_cone_ranks.properties` —
 *    a separate thing from its base stats, and on a signature cone most of the character's HP;
 *  - unlocked trace nodes, from `character_skill_trees`;
 *  - every relic prop, main and substat alike — Enka ships those already valued;
 *  - active set bonuses, from `relic_sets.properties`.
 *
 * Eidolons are deliberately absent: no rank in `character_ranks.json` carries a `properties` block,
 * so none of them moves the panel.
 *
 * A CONDITIONAL bonus is an empty property list in those tables — Watchmaker's post-ultimate 4pc,
 * Arcadia's team-size scaling — and the game's own panel excludes them too, so they need no case.
 *
 * Percentages multiply the base (character + cone) and flats are added after, which is the game's own
 * order and the reason a 2000-HP relic and a 20% one are not interchangeable.
 *
 * Output is [ShowcaseStat] in mihomo's field vocabulary (`hp`, `crit_rate`, `quantum_dmg`) so
 * [com.hsrbot.card.AvaliacaoService] keeps its existing `STAT_BASE`/`STAT_PT` tables untouched and
 * cannot tell a computed panel from a fetched one.
 */
internal object StatPanel {

    /** One `{type, value}` pair — a trace bonus, a relic roll or a set bonus; all the same shape. */
    data class Prop(val type: String, val value: Double)

    /** A promotion row's growth for one stat: the value at level 1, plus the per-level step. */
    data class Crescimento(val base: Double, val step: Double)

    fun compute(
        nivel: Int,
        promocao: Int,
        promocoes: List<Map<String, Crescimento>>,
        tracos: List<Prop>,
        coneBase: List<Prop>,
        reliquias: List<Prop>,
        conjuntos: List<Prop>,
        conePassivo: List<Prop> = emptyList(),
    ): List<ShowcaseStat> {
        // A character the promotions table hasn't caught up with gets no panel rather than a wrong
        // one: the renderer already handles an empty list, and a fabricated HP total is worse than
        // a missing block.
        val cresc = promocoes.getOrNull(promocao) ?: return emptyList()

        fun base(stat: String) = cresc[stat]?.let { it.base + it.step * (nivel - 1) } ?: 0.0

        val bonus = (tracos + reliquias + conjuntos + conePassivo)
            .groupingBy { it.type }
            .fold(0.0) { acc, p -> acc + p.value }

        fun b(tipo: String) = bonus[tipo] ?: 0.0

        // The cone contributes to the BASE, so a percentage relic scales it too — dropping it into
        // the flat pile instead would quietly undercount every build that runs a 5-star cone.
        //
        // `b(prop)` is the second door into the same base: a passive that reads "increases the
        // wearer's BASE SPD by 12" arrives as a `BaseSpeed` prop in the bonus pile, not in the cone's
        // `_flat.props`, and it must land on the base rather than after the percentage — which is
        // also why every base stat asks for it, not just speed.
        fun comBase(stat: String, prop: String) =
            base(stat) + coneBase.filter { it.type == prop }.sumOf { it.value } + b(prop)

        val total = buildMap {
            put("hp", comBase("hp", "BaseHP") * (1 + b("HPAddedRatio")) + b("HPDelta"))
            put("atk", comBase("atk", "BaseAttack") * (1 + b("AttackAddedRatio")) + b("AttackDelta"))
            put("def", comBase("def", "BaseDefence") * (1 + b("DefenceAddedRatio")) + b("DefenceDelta"))
            put("spd", comBase("spd", "BaseSpeed") * (1 + b("SpeedAddedRatio")) + b("SpeedDelta"))
            // Crit's 5%/50% floor is in the promotions table, not hardcoded — it is a per-character
            // value in principle and reading it costs nothing.
            put("crit_rate", base("crit_rate") + b("CriticalChanceBase"))
            put("crit_dmg", base("crit_dmg") + b("CriticalDamageBase"))
        }

        val extras = EXTRA_FIELDS.mapNotNull { (prop, campo) ->
            // Only what this build actually has: the game omits a zeroed bonus row too, and the card
            // has room for two of them.
            b(prop).takeIf { it != 0.0 }?.let { campo to it + BASE_EXTRA.getOrDefault(campo, 0.0) }
        }

        return total.map { (campo, valor) -> ShowcaseStat(campo, campo, display(campo, valor)) } +
            extras.map { (campo, valor) -> ShowcaseStat(campo, campo, display(campo, valor)) }
    }

    /**
     * The game TRUNCATES, it does not round: 240.852% CRIT DMG shows as 240.8, and enka.network's
     * own card agrees. Rounding put us one digit off on half the percentage rows, which reads as a
     * miscalculation even when the arithmetic underneath is right.
     */
    private fun display(campo: String, valor: Double): String =
        if (campo in PLANOS) valor.toInt().toString()
        else String.format(Locale.ROOT, "%.1f%%", truncate(valor * 1000) / 10)

    private val PLANOS = setOf("hp", "atk", "def", "spd")

    /**
     * The bonus-only rows, in the order the game's panel lists them — elemental damage first,
     * because for most builds it is the one that separates two copies of the same character.
     *
     * Three property types in the tables are deliberately absent, because the game's own panel has no
     * row for them: `AllDamageTypeAddedRatio` ("increases DMG dealt by X%", a separate multiplier,
     * not an elemental bonus), `HealTakenRatio`, and `ElationDamageAddedRatioBase` — Elation is a
     * PATH, not an element, so it is a skill-damage bonus and not one of the seven element rows.
     */
    // internal: [BuildAnalyzer] reverses this map to read a breakpoint's stat off the panel.
    internal val EXTRA_FIELDS = listOf(
        "PhysicalAddedRatio" to "physical_dmg", "FireAddedRatio" to "fire_dmg",
        "IceAddedRatio" to "ice_dmg", "ThunderAddedRatio" to "lightning_dmg",
        "WindAddedRatio" to "wind_dmg", "QuantumAddedRatio" to "quantum_dmg",
        "ImaginaryAddedRatio" to "imaginary_dmg",
        "BreakDamageAddedRatioBase" to "break_dmg", "StatusProbabilityBase" to "effect_hit",
        "StatusResistanceBase" to "effect_res", "SPRatioBase" to "sp_rate",
        "HealRatioBase" to "heal_ratio",
    )

    /** Energy regen is a multiplier on 100%, not a bonus from zero; the rest start at nothing. */
    private val BASE_EXTRA = mapOf("sp_rate" to 1.0)
}
