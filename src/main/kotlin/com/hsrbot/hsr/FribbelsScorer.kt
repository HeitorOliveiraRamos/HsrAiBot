package com.hsrbot.hsr

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * fribbels/hsr-optimizer's relic scorer, measured against our own reference relic.
 *
 * The mechanism is theirs port for port — same weights, same substat pricing, same divisor shape.
 * What differs is the piece that divisor describes. Theirs is a relic where every roll landed on a
 * wanted stat AND came out at the maximum tier: 200k simulated pieces put the best one at 90.9%, and
 * on their scale the best build ever stored here sat at 87.9. Ours keeps the perfect direction and
 * drops every roll to its MINIMUM tier (see [MIN_ROLL]) — a reference a member can picture, and beat.
 *
 * Every number here is therefore exactly 1.25× the one their site prints for the same build, and
 * nothing in this file caps it: passing 100 is the achievement the top of the ladder names, [rating]
 * reads the uncapped figure so `@$%!` marks it, and the ranking sorts on it. The cap to 10,00 lives
 * in exactly one place, [BuildAnalyzer.nota], and never reaches the database.
 *
 * Upstream files, all under `src/lib/relics/scoring/`:
 *
 *  - `scoringMetadata.ts` → [prepare]: weights clamped to 0..1, flat HP/ATK/DEF rewritten to 40% of
 *    their percent counterpart, sorted descending, and the per-stat contribution/high-roll tables
 *    precomputed;
 *  - `optimalScore.ts` → [computeOptimalScore] / [resolveOptimalMainstat]: the divisor, i.e. the
 *    best relic this slot could hold;
 *  - `currentScore.ts` → [score]'s `percent`, and `futureScore.ts`'s `current` → its [Score.perfection];
 *  - `scoreFormatting.ts` → [rating], nineteen 5%-wide bands — their thresholds, our letters.
 *
 * The formula in one line: `Σ substatValue × weight × (6.48 / highRoll) / idealScore × 100`. A
 * substat is priced by its VALUE, not by how many times it rolled, and `6.48` (a max crit-damage
 * roll) is the unit every stat is converted into. The main stat never adds to the numerator; it
 * moves the divisor, because a relic cannot roll its own main as a substat, and on slots 3–6 a main
 * the character does not want subtracts a flat ten max-rolls' worth from [Score.perfection].
 *
 * What is NOT ported: `futureScore`'s best/average/worst projections and the reroll estimates, since
 * nothing in `/build` shows them. [Score.perfection] takes only the `current` branch of that file.
 *
 * Everything is keyed by the game property strings ("CriticalDamageBase", "SpeedDelta") the
 * showcases and [FribbelsMeta] already speak, rather than fribbels' own `Stats.*` enum.
 */
object FribbelsScorer {

    /**
     * One substat's roll tiers at one relic rarity, in display units (percentages as `4.32`, flat
     * stats as `42.33`).
     *
     * [base] and [step] are the GAME's numbers (`relic_sub_affixes.json`) and produce the substat
     * value the same way fribbels' importer does: `base × count + step × step`. [high] is fribbels'
     * own `SubStatValues` top tier and is what the scoring side normalizes against. The two disagree
     * in the fifth decimal on the flat stats (their `low` is 33.87 where the game stores 33.87004);
     * both are reproduced as-is rather than reconciled, because matching their output is the point.
     */
    data class Tier(val base: Double, val step: Double, val high: Double)

    /** The 12 stats a relic can roll as a SUBstat, in fribbels' `Constants.SubStats` order — which
     *  is the order [prepare]'s stable sort falls back on when two weights tie. */
    val SUBSTATS: List<String> = listOf(
        "AttackAddedRatio", "AttackDelta", "HPAddedRatio", "HPDelta",
        "DefenceAddedRatio", "DefenceDelta", "SpeedDelta",
        "CriticalChanceBase", "CriticalDamageBase",
        "StatusProbabilityBase", "StatusResistanceBase", "BreakDamageAddedRatioBase",
    )

    /** Every main stat each slot can roll (`PartsMainStats`); slots 1–2 are fixed. */
    val SLOT_MAIN_STATS: Map<Int, List<String>> = mapOf(
        1 to listOf("HPDelta"),
        2 to listOf("AttackDelta"),
        3 to listOf(
            "HPAddedRatio", "AttackAddedRatio", "DefenceAddedRatio",
            "CriticalChanceBase", "CriticalDamageBase", "HealRatioBase", "StatusProbabilityBase",
        ),
        4 to listOf("HPAddedRatio", "AttackAddedRatio", "DefenceAddedRatio", "SpeedDelta"),
        5 to listOf(
            "HPAddedRatio", "AttackAddedRatio", "DefenceAddedRatio",
            "PhysicalAddedRatio", "FireAddedRatio", "IceAddedRatio", "ThunderAddedRatio",
            "WindAddedRatio", "QuantumAddedRatio", "ImaginaryAddedRatio",
        ),
        6 to listOf(
            "HPAddedRatio", "AttackAddedRatio", "DefenceAddedRatio",
            "BreakDamageAddedRatioBase", "SPRatioBase",
        ),
    )

    /** Percent stat → its flat counterpart, for the 40% rule and the flat/percent-pair divisor. */
    val PERCENT_TO_FLAT: Map<String, String> = mapOf(
        "HPAddedRatio" to "HPDelta",
        "AttackAddedRatio" to "AttackDelta",
        "DefenceAddedRatio" to "DefenceDelta",
    )

    /** Slots whose main stat the player chose, and so can get wrong (`hasMainStat`). */
    private val SELECTABLE_MAIN = 3..6

    /** `AllStats`, the order [resolveOptimalMainstat] walks when several mains tie on weight. */
    private val ALL_STATS: List<String> = listOf(
        "AttackAddedRatio", "AttackDelta", "BreakDamageAddedRatioBase", "CriticalDamageBase",
        "CriticalChanceBase", "DefenceAddedRatio", "DefenceDelta", "StatusProbabilityBase",
        "SPRatioBase", "FireAddedRatio", "HPAddedRatio", "HPDelta", "IceAddedRatio",
        "ImaginaryAddedRatio", "ThunderAddedRatio", "HealRatioBase", "PhysicalAddedRatio",
        "QuantumAddedRatio", "StatusResistanceBase", "SpeedAddedRatio", "SpeedDelta",
        "WindAddedRatio",
    )

    /** The unit every stat is converted into: one max crit-damage roll on a 5★ relic. */
    private const val POTENTIAL_UNIT = 6.48

    /**
     * The roll quality the DIVISOR is built from: a minimum roll instead of a maximum one. The
     * numerator is untouched — a relic is still priced by what it actually rolled.
     *
     * `low / high` is exactly 0.8 for eleven of the twelve substats (CD 5.184/6.48, ATQ% 3.456/4.32,
     * PV plano 33.87004/42.33751…). Velocidade is the lone exception at 2.0/2.6 = 0.769, and it is
     * deliberately NOT honoured per-stat: measured across all 97 harvested rulers, an honest ratio
     * inflates speed characters up to 1.2942× against 1.2500× for everyone else, and `/rank`'s geral
     * board ranks characters against each other — that 3.5% skew would hand Asta, Hanya and Tingyun
     * free positions over a Castorice with the same relics. A flat constant also keeps the whole
     * change a single multiplication, which is what let `V36` rescale every stored nota without
     * re-fetching a showcase.
     */
    private const val MIN_ROLL = 0.8

    /** `GRADE_CONFIG.maxMainstat` — a maxed main stat in [POTENTIAL_UNIT]s, ten of them at 5★. */
    private val MAX_MAINSTAT = mapOf(2 to 12.8562, 3 to 25.8165, 4 to 43.1304, 5 to 64.8)

    /** `FLAT_STAT_SCALING`: a flat roll is worth 40% of the percent roll it stands in for. */
    private const val FLAT_SCALING = 0.4

    private val TIERS: Map<String, Map<Int, Tier>> = mapOf(
        "AttackAddedRatio" to mapOf(
            5 to Tier(3.4560002, 0.43200003, 4.32),
            4 to Tier(2.7648, 0.34560005, 3.456),
            3 to Tier(2.0736001, 0.25920009, 2.592),
            2 to Tier(1.3824001, 0.17280006, 1.728),
        ),
        "AttackDelta" to mapOf(
            5 to Tier(16.935019, 2.116877, 21.168754),
            4 to Tier(13.548016, 1.693502, 16.935),
            3 to Tier(10.161012, 1.270126, 12.701252),
            2 to Tier(6.774008, 0.846751, 8.4675),
        ),
        "HPAddedRatio" to mapOf(
            5 to Tier(3.4560002, 0.43200003, 4.32),
            4 to Tier(2.7648, 0.34560005, 3.456),
            3 to Tier(2.0736001, 0.25920009, 2.592),
            2 to Tier(1.3824001, 0.17280006, 1.728),
        ),
        "HPDelta" to mapOf(
            5 to Tier(33.87004, 4.233755, 42.33751),
            4 to Tier(27.096031, 3.387004, 33.87),
            3 to Tier(20.322023, 2.540253, 25.402506),
            2 to Tier(13.548016, 1.693502, 16.935),
        ),
        "DefenceAddedRatio" to mapOf(
            5 to Tier(4.32, 0.54, 5.4),
            4 to Tier(3.4560002, 0.43200003, 4.32),
            3 to Tier(2.592, 0.32400002, 3.24),
            2 to Tier(1.7280001, 0.21600004, 2.16),
        ),
        "DefenceDelta" to mapOf(
            5 to Tier(16.935019, 2.116877, 21.168754),
            4 to Tier(13.548016, 1.693502, 16.935),
            3 to Tier(10.161012, 1.270126, 12.701252),
            2 to Tier(6.774008, 0.846751, 8.4675),
        ),
        "SpeedDelta" to mapOf(
            5 to Tier(2.0, 0.3, 2.6),
            4 to Tier(1.6, 0.2, 2.0),
            3 to Tier(1.2, 0.1, 1.4),
            2 to Tier(1.0, 0.1, 1.2),
        ),
        "CriticalChanceBase" to mapOf(
            5 to Tier(2.592, 0.32400002, 3.24),
            4 to Tier(2.0736001, 0.25920009, 2.592),
            3 to Tier(1.5552, 0.19440008, 1.944),
            2 to Tier(1.0368001, 0.12960008, 1.296),
        ),
        "CriticalDamageBase" to mapOf(
            5 to Tier(5.184, 0.64800004, 6.48),
            4 to Tier(4.1472, 0.51840004, 5.184),
            3 to Tier(3.1104, 0.3888001, 3.888),
            2 to Tier(2.0736001, 0.25920009, 2.592),
        ),
        "StatusProbabilityBase" to mapOf(
            5 to Tier(3.4560002, 0.43200003, 4.32),
            4 to Tier(2.7648, 0.34560005, 3.456),
            3 to Tier(2.0736001, 0.25920009, 2.592),
            2 to Tier(1.3824001, 0.17280006, 1.728),
        ),
        "StatusResistanceBase" to mapOf(
            5 to Tier(3.4560002, 0.43200003, 4.32),
            4 to Tier(2.7648, 0.34560005, 3.456),
            3 to Tier(2.0736001, 0.25920009, 2.592),
            2 to Tier(1.3824001, 0.17280006, 1.728),
        ),
        "BreakDamageAddedRatioBase" to mapOf(
            5 to Tier(5.184, 0.64800004, 6.48),
            4 to Tier(4.1472, 0.51840004, 5.184),
            3 to Tier(3.1104, 0.3888001, 3.888),
            2 to Tier(2.0736001, 0.25920009, 2.592),
        ),
    )

    /** Rarities [TIERS] covers; anything else is scored on 5★ tiers, as fribbels does. */
    private fun tier(stat: String, grade: Int): Tier? =
        TIERS[stat]?.let { it[grade] ?: it.getValue(5) }

    /**
     * The prepared ruler for one character — fribbels' `ScorerMetadata`, minus the fields only their
     * projections use. Built once per character by [prepare] and reused across its six relics.
     */
    data class Meta(
        /** Substat → weight, clamped to 0..1 and with the flat stats already scaled to 40%. */
        val stats: Map<String, Double>,
        /** Slot 3–6 → the mains the character actually wants; never empty (see [prepare]). */
        val parts: Map<Int, List<String>>,
        /** [stats] sorted by weight, heaviest first — the order the divisor is built from. */
        val sorted: List<Pair<String, Double>>,
        /** Substat → `weight × 6.48 / highRoll`: what one point of that stat is worth. */
        val contributions: Map<String, Double>,
        /** Substat → what a single maxed roll of it is worth. */
        val highRoll: Map<String, Double>,
        /** The one character-specific exception: see [boost]. */
        val flatMainstatBoost: String?,
    )

    /**
     * `prepareScoringMetadata`. Returns null when nothing this character can roll is worth anything
     * — their `ScoringCase.NONE`, which divides by `Infinity` and scores every relic 0. Refusing to
     * judge reads better on a card than six zeroes.
     *
     * An EMPTY ideal-main list means "any main is fine" and is filled with every main the slot can
     * roll, exactly as their `applyCharacterConfig` does when it loads the config — 35 of the ~100
     * configs leave at least one slot open that way, and treating those as "no acceptable main"
     * would flag a correct body as wrong on every one of them.
     */
    fun prepare(meta: FribbelsMeta): Meta? {
        // LinkedHashMap in SUBSTATS order: `sorted` is a STABLE sort, so this order is what decides
        // ties, and a tie between HP% and SPD is the difference between two different divisors.
        val stats = LinkedHashMap<String, Double>(SUBSTATS.size)
        SUBSTATS.forEach { stats[it] = (meta.subWeights[it] ?: 0.0).coerceIn(0.0, 1.0) }
        // `applyFlatStatScaling`, unconditional: a config that weights flat ATK without ATK% has its
        // flat weight overwritten with 0. That is upstream behaviour, not an oversight of ours.
        PERCENT_TO_FLAT.forEach { (pct, flat) -> stats[flat] = (stats[pct] ?: 0.0) * FLAT_SCALING }
        if (stats.values.none { it > 0.0 }) return null

        val sorted = stats.entries.sortedByDescending { it.value }.map { it.key to it.value }
        return Meta(
            stats = stats,
            parts = SELECTABLE_MAIN.associateWith { slot ->
                meta.mainStats[slot]?.takeIf { it.isNotEmpty() } ?: SLOT_MAIN_STATS.getValue(slot)
            },
            sorted = sorted,
            contributions = sorted.associate { (stat, w) -> stat to w * scale(stat) },
            highRoll = sorted.associate { (stat, w) -> stat to w * potentialUnits(stat) },
            flatMainstatBoost = meta.flatMainstatBoost,
        )
    }

    /** `substatPotentialScale`: how many [POTENTIAL_UNIT]s one point of this stat is worth. */
    private fun scale(stat: String): Double = POTENTIAL_UNIT / TIERS.getValue(stat)[5]!!.high

    /**
     * `substatPotentialUnits` of one maxed 5★ roll. Algebraically [POTENTIAL_UNIT], but written the
     * way upstream writes it so the last bit of the double agrees with theirs.
     */
    private fun potentialUnits(stat: String): Double = TIERS.getValue(stat)[5]!!.high * scale(stat)

    /**
     * The substat's value in display units, from the two integers every showcase reports:
     * `base × count + step × step`, the same arithmetic fribbels' importer runs — [count] is how many
     * times the stat rolled and [step] the total upgrade increments across those rolls — rounded to
     * five decimals there and here, which is what erases the game's float32 noise.
     */
    fun substatValue(stat: String, count: Int, step: Int, grade: Int): Double {
        val t = tier(stat, grade) ?: return 0.0
        return precisionRound(t.base * count + t.step * step)
    }

    /**
     * `getFlatMainstatBoost` — the eleven configs (Aventurine, Gepard, Bailu, Robin, both
     * Desbravadores da Preservação…) that name a flat stat here. On a relic whose MAIN is that
     * stat's percent form, the flat substat stops being worth 40% and counts at the percent weight:
     * those characters convert flat DEF/HP into damage or shielding directly, and the piece is
     * already multiplying it. Returns null everywhere else.
     *
     * Eleven and not twelve: `HuohuoB1.ts` declares one and `Huohuo.ts` does not, and the B1 rerun
     * configs are skipped by the harvest because no showcase reports their id. That is upstream's
     * own split, so leaving base Huohuo unboosted is what keeps us equal to their site.
     */
    private fun boost(meta: Meta, mainStat: String?): Pair<String, Double>? {
        val flat = PERCENT_TO_FLAT[mainStat] ?: return null
        if (meta.flatMainstatBoost != flat) return null
        val weight = meta.stats[mainStat] ?: 0.0
        if (weight <= 0.0) return null
        return flat to weight
    }

    private fun contribution(meta: Meta, mainStat: String?, stat: String): Double {
        val b = boost(meta, mainStat)
        return if (b != null && b.first == stat) b.second * scale(stat) else meta.contributions[stat] ?: 0.0
    }

    /**
     * What one roll of this stat is worth TO THE DIVISOR: [MIN_ROLL] of a maxed one, because the
     * reference relic's rolls all came out at the bottom tier. Every branch of [computeOptimalScore]
     * goes through here and nothing else does, which is why the rescale lives in this one function.
     */
    private fun referenceRoll(meta: Meta, mainStat: String?, stat: String): Double {
        val b = boost(meta, mainStat)
        val max = if (b != null && b.first == stat) b.second * potentialUnits(stat) else meta.highRoll[stat] ?: 0.0
        return MIN_ROLL * max
    }

    /**
     * `computeOptimalScore`: the reference relic for this slot — nine rolls, six of them into the
     * heaviest substat left, then one each into the next three, every one at its MINIMUM tier
     * ([MIN_ROLL]). The main stat is removed from the pool first, because a relic cannot roll its own
     * main as a substat: that is what stops a crit-damage body from being measured against an ideal
     * full of crit-damage rolls it was never allowed to have.
     *
     * A character with one useful substat — or two that are the flat and percent halves of the same
     * stat, which cannot fill four lines between them — is measured against only those. Returns
     * [Double.POSITIVE_INFINITY] (their `Infinity`) when the main has taken the only stat that
     * counted, which the caller reads as a 0.
     */
    fun computeOptimalScore(slot: Int, mainStat: String?, meta: Meta): Double {
        val subs = meta.sorted
        if (subs[0].second == 0.0) return Double.POSITIVE_INFINITY

        // SINGLE_STAT / HP / ATK / DEF: nothing to put on the other lines.
        if (subs[1].second == 0.0) {
            val stat = subs[0].first
            return if (stat == mainStat) Double.POSITIVE_INFINITY else 6 * referenceRoll(meta, mainStat, stat)
        }
        if (subs[2].second == 0.0 && flatPercentPair(subs[0].first, subs[1].first)) {
            val (s1, s2) = subs[0].first to subs[1].first
            return when {
                s1 == mainStat && s2 == mainStat -> Double.POSITIVE_INFINITY
                s1 == mainStat -> 6 * referenceRoll(meta, mainStat, s2)
                s2 == mainStat -> 6 * referenceRoll(meta, mainStat, s1)
                else -> 6 * referenceRoll(meta, mainStat, s1) + referenceRoll(meta, mainStat, s2)
            }
        }

        val optimalMain = resolveOptimalMainstat(slot, mainStat, meta)
        val effective = subs.filter { it.first != optimalMain }
            .map { referenceRoll(meta, optimalMain, it.first) }
            .sortedDescending()
        return 6 * effective[0] + effective[1] + effective[2] + effective[3]
    }

    private fun flatPercentPair(a: String, b: String): Boolean =
        PERCENT_TO_FLAT[a] == b || PERCENT_TO_FLAT[b] == a

    /**
     * `resolveOptimalMainstat`: which main the perfect relic for this slot would carry, and so the
     * one stat it could not also roll. An already-ideal main (or one the character maxes anyway) is
     * kept; otherwise the ideal is rebuilt around the best main the slot can take, so a wrong main
     * never inflates the divisor into an excuse for itself.
     *
     * Among mains tied on weight the upstream walk prefers, in order, the ideal ones and the ones
     * that cannot be substats — picking a main the character could not have rolled anyway costs the
     * substat pool nothing.
     */
    fun resolveOptimalMainstat(slot: Int, mainStat: String?, meta: Meta): String? {
        val ideal = meta.parts[slot].orEmpty()
        if (mainStat == null || mainStat in ideal || meta.stats[mainStat] == 1.0 || slot !in SELECTABLE_MAIN) {
            return mainStat
        }
        val entries = ALL_STATS
            .map { stat ->
                stat to if (stat in ideal || meta.stats[stat] == 1.0) 1.0 else (meta.stats[stat] ?: 0.0)
            }
            .sortedByDescending { it.second }
        val possible = SLOT_MAIN_STATS[slot].orEmpty()

        val start = entries.indexOfFirst { it.first in possible }
        if (start < 0) return mainStat
        val topWeight = entries[start].second
        var best = entries[start].first
        // Deliberately wrong seeds: the first matching iteration always overwrites them, which is
        // how the upstream loop gets its "any real candidate beats the placeholder" behaviour.
        var bestIsIdeal = false
        var bestIsSubstat = true

        for (i in start until entries.size) {
            val (stat, weight) = entries[i]
            if (weight != topWeight) break
            if (stat !in possible) continue
            val isIdeal = stat in ideal
            val isSubstat = stat in SUBSTATS
            if (bestIsIdeal && !isIdeal) continue
            if (!bestIsSubstat && isSubstat) continue
            if (bestIsIdeal == isIdeal && bestIsSubstat == isSubstat) continue
            best = stat
            bestIsIdeal = isIdeal
            bestIsSubstat = isSubstat
        }
        return best
    }

    /**
     * One relic's verdict.
     *
     * [percent] is `currentScore.ts`'s `percentScore` — the substats alone, the figure the showcase
     * labels "Score". [perfection] is `futureScore.ts`'s `current`, the same number minus a whole
     * maxed main stat when slots 3–6 carry one the character does not want; the two are equal
     * whenever the main is right. Both come out 1.25× their site's, on [MIN_ROLL]'s divisor, and
     * neither is clamped at 100.
     */
    data class Score(val percent: Double, val perfection: Double, val rating: String)

    /** `scoreCurrentRelic` + `computeFutureScores`' `current`, for one relic against one ruler. */
    fun score(relic: ShowcaseRelic, meta: Meta): Score {
        val mainStat = relic.mainAffix?.type
        val raw = relic.subAffixes.sumOf {
            substatValue(it.type, it.count, it.step, relic.grade) * contribution(meta, mainStat, it.type)
        }
        val ideal = computeOptimalScore(relic.slot, mainStat, meta)
        val normFactor = if (ideal.isInfinite()) 0.0 else 100.0 / ideal

        val mainWeight = mainStatWeight(relic.slot, mainStat, meta)
        val deduction = if (relic.slot in SELECTABLE_MAIN) {
            (mainWeight - 1) * (MAX_MAINSTAT[relic.grade] ?: MAX_MAINSTAT.getValue(5))
        } else {
            0.0
        }

        val percent = if (ideal.isInfinite()) 0.0 else truncate10ths(precisionRound(raw / ideal * 100))
        val perfection = truncate10ths(precisionRound(max(0.0, (raw + deduction) * normFactor)))
        return Score(
            percent = percent,
            perfection = perfection,
            rating = rating(percent, relic.grade, relic.slot, mainWeight > 0.0),
        )
    }

    /**
     * `mainStatWeight`: 1 when the slot's main is one the character asked for, its plain substat
     * weight when it is merely useful, 0 when it is neither. Always 0 on head and hands, whose main
     * is fixed and so carries no information.
     */
    fun mainStatWeight(slot: Int, mainStat: String?, meta: Meta): Double = when {
        slot !in SELECTABLE_MAIN || mainStat == null -> 0.0
        mainStat in meta.parts[slot].orEmpty() -> 1.0
        else -> meta.stats[mainStat] ?: 0.0
    }

    /**
     * `pctToRating`: twenty bands, one every 5% up to `???` and then `@$%!` at 100. A relic that
     * isn't 5★, or whose selectable main the character has no use for, gets `?` rather than a letter
     * — the score of a piece that is going to be replaced anyway says nothing worth grading.
     *
     * Upstream's `aeonEligible` guard, which downgrades the top band on a customized ruler, has
     * nothing to do here: we never let a player retune the weights.
     */
    fun rating(pct: Double, grade: Int = 5, slot: Int? = null, correctMain: Boolean = true): String {
        if (grade != 5) return "?"
        if (pct <= 0.0) return "?"
        if (slot != null && slot in SELECTABLE_MAIN && !correctMain) return "?"
        return RATING_TIERS.last { pct >= it.first }.second
    }

    /**
     * Nineteen bands at fribbels' thresholds with OUR labels, plus one rung of our own on top. Theirs
     * run F, F+, D, D+, C… WTF, WTF+, AEON, which skips E entirely and ends in two jokes; this ladder
     * is the plain F→SSS one a player already reads on every other tier list.
     *
     * The thresholds no longer line up with theirs. [MIN_ROLL] made every percentage 1.25× the one
     * their site prints, so a build they call A reads three bands higher here — that inflation IS the
     * feature, and `@$%!` at 100 is the rung it opens up: a build that beat the reference relic
     * outright. Exactly one of the 610 builds stored when V36 ran did, at 109.8; the next one down is
     * 99.7. Individual PIECES clear it far more often, which is the intended flex.
     */
    private val RATING_TIERS: List<Pair<Double, String>> = listOf(
        0.0 to "F", 5.0 to "F+", 10.0 to "E", 15.0 to "E+", 20.0 to "D", 25.0 to "D+",
        30.0 to "C", 35.0 to "C+", 40.0 to "B", 45.0 to "B+", 50.0 to "A", 55.0 to "A+",
        60.0 to "S", 65.0 to "S+", 70.0 to "SS", 75.0 to "SS+", 80.0 to "SSS",
        85.0 to "SSS+", 90.0 to "???", 100.0 to "@\$%!",
    )

    /** `mathUtils.precisionRound` at its default 5 decimals — kills the float noise before [truncate10ths]. */
    internal fun precisionRound(x: Double): Double = (x * 1e5).roundToLong() / 1e5

    /** `mathUtils.truncate10ths`. Upstream truncates, so 84.49 prints 84.4, not 84.5. */
    internal fun truncate10ths(x: Double): Double = floor(x * 10) / 10
}
