package com.hsrbot.hsr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * One gazetteer entry: the shared numeric game id ([id], the `personagem_hsr.character_id`
 * nanoka/mihomo/`/build` all use) plus both display names ([namePt] from `nome`, [nameEn] from
 * `nome_en`). [namePt] is null for betas srs hasn't localized yet. The field names stay
 * `nameEn`/`namePt` so the name-matching callers don't churn. The rich kit/lore now lives in the
 * V17 tables ([PersonagemHsr]); fribbels build metadata in its own table ([FribbelsMeta]).
 *
 * [caminho]/[elemento] are carried because a NAME DOES NOT IDENTIFY A CHARACTER: `nome` is
 * ambiguous for 14 of the 97 rows (both 7 de Março forms, five Desbravador + five Desbravadora
 * paths), and `(nome, caminho)` is what's actually unique. They're stored raw — canonicalize
 * through [HsrTaxonomy] before comparing, since beta rows carry the English spellings.
 */
data class HsrCharacter(
    val id: String,
    val nameEn: String? = null,
    val namePt: String? = null,
    val caminho: String? = null,
    val elemento: String? = null,
    val raridade: Int? = null,
    val faccao: String? = null,
) {
    /** All known names, for accent-insensitive matching across the languages users mix. */
    val names: List<String> get() = listOfNotNull(nameEn, namePt)

    /** Preferred bare name (PT first), before any ambiguity suffix — see [HsrCharacterService.displayName]. */
    val baseName: String get() = namePt ?: nameEn ?: id
}

/**
 * Build metadata harvested from a fribbels/hsr-optimizer per-character config
 * (`src/lib/conditionals/character/<n>/<Name>.ts`):
 *
 *  - [subWeights]: substat → weight 0..1, from `scoring().stats` — THE ruler `/build` scores on,
 *    so that [FribbelsScorer] reproduces fribbels' own numbers rather than a second opinion;
 *  - [mainStats]: slot (3–6) → ideal main stats, from `scoring().parts`. A slot the config leaves
 *    empty is absent here and means "any main is fine" — [FribbelsScorer.prepare] fills it, the
 *    same way fribbels' own metadata loader does;
 *  - [flatMainstatBoost]: the one stat this character converts directly (a sustain's flat DEF or
 *    HP), from `scoring().flatMainstatBoost`. Declared by 12 of the ~100 configs; on a relic whose
 *    main is that stat's percent form it lifts the flat substat off the usual 40% discount;
 *  - [relicSets]/[ornamentSets]: the sets fribbels' DPS benchmark treats as INTERCHANGEABLE, from
 *    `simulation()` (PT-BR names when StarRailRes could map them); empty for characters without sim
 *    metadata. Not a ranking and not advice: their `getSimulationSets` uses the list to keep the
 *    player's own sets in the benchmark, and upstream puts a set in it precisely when the sim can't
 *    model its value. Absence means "unmodelled", never "wrong" — see
 *    [com.hsrbot.hsr.BuildAnalyzer.alertas];
 *  - [substatPriority]: ordered substat priority from `simulation().substats`;
 *  - [breakpoints]: the stat gates from `simulation().hardBreakpoints/softBreakpoints` — the
 *    numbers below which the kit stops working (Hysilens' 120% de Acerto de Efeito, Cyrene's
 *    180 de Velocidade). Only 9 of the ~100 configs declare any.
 *
 * Stat keys everywhere are the game property keys ("CriticalChanceBase", "SpeedDelta"…)
 * that mihomo and Enka affixes already use, so everything joins for free.
 */
data class FribbelsMeta(
    val subWeights: Map<String, Double>,
    val mainStats: Map<Int, List<String>>,
    val relicSets: List<List<String>>,
    val ornamentSets: List<String>,
    val substatPriority: List<String>,
    val breakpoints: List<Breakpoint> = emptyList(),
    val flatMainstatBoost: String? = null,
) {

    /**
     * One stat gate. [threshold] is in the panel's own units — a fraction for percentages
     * (0.75 = 75% de Acerto de Efeito), the flat number for Velocidade/ATQ.
     *
     * fribbels splits these into `hard` (missing it zeroes their whole sim score) and `soft`
     * (proportional penalty); we keep neither flag, because [BuildAnalyzer] applies the same
     * bounded penalty to both — see its `penalidades`.
     */
    data class Breakpoint(val stat: String, val threshold: Double)

    fun toJson(mapper: ObjectMapper): String = mapper.writeValueAsString(
        mapOf(
            "subWeights" to subWeights,
            "mainStats" to mainStats.mapKeys { it.key.toString() },
            "relicSets" to relicSets,
            "ornamentSets" to ornamentSets,
            "substatPriority" to substatPriority,
            "breakpoints" to breakpoints.map { mapOf("stat" to it.stat, "threshold" to it.threshold) },
            "flatMainstatBoost" to flatMainstatBoost,
        ),
    )

    companion object {

        /**
         * Overlays the hand-written `hsr_build_meta.ajuste` patch on the harvested `fribbels` blob
         * (see V37). DEEP on objects, replacing on everything else: a patch naming one substat
         * weight or one slot's ideal main changes only that entry, while a list — `relicSets`,
         * `substatPriority`, `breakpoints` — is swapped whole, since half a list is never what
         * anyone means. That is what makes disagreeing with one fribbels number a one-line UPDATE
         * instead of a full re-statement of the ruler that then goes stale on its own.
         *
         * Mutates [base], which is always a freshly parsed row here. A null [patch] is the common
         * case and returns [base] untouched.
         */
        internal fun merge(base: JsonNode, patch: JsonNode?): JsonNode {
            if (patch == null) return base
            if (base !is ObjectNode || patch !is ObjectNode) return patch
            patch.fields().forEach { (k, v) ->
                val atual = base.get(k)
                base.set<JsonNode>(k, if (atual == null) v else merge(atual, v))
            }
            return base
        }

        /** JsonNode hand-parse (no jackson-kotlin in the project). Pure, fixture-testable. */
        fun fromJson(root: JsonNode): FribbelsMeta = FribbelsMeta(
            subWeights = buildMap {
                root.path("subWeights").fields().forEach { (k, v) -> put(k, v.asDouble(0.0)) }
            },
            mainStats = buildMap {
                root.path("mainStats").fields().forEach { (slot, stats) ->
                    slot.toIntOrNull()?.let { put(it, stats.map { s -> s.asText() }) }
                }
            },
            relicSets = root.path("relicSets").map { pair -> pair.map { it.asText() } },
            ornamentSets = root.path("ornamentSets").map { it.asText() },
            substatPriority = root.path("substatPriority").map { it.asText() },
            // Absent on every row harvested before this field existed — an empty list is exactly
            // the old behaviour (no gate, no penalty), so no migration and no backfill.
            breakpoints = root.path("breakpoints").mapNotNull { bp ->
                bp.path("stat").asText("").takeIf { it.isNotBlank() }
                    ?.let { Breakpoint(it, bp.path("threshold").asDouble(0.0)) }
            },
            flatMainstatBoost = root.path("flatMainstatBoost").asText("").takeIf { it.isNotBlank() },
        )
    }
}
