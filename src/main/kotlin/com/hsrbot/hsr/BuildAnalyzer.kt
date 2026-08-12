package com.hsrbot.hsr

import java.util.Locale

/**
 * The `/build` report: [FribbelsScorer]'s numbers plus the judgment a card needs around them — what
 * is wrong with the build, and what to farm next. No LLM anywhere.
 *
 * The scoring itself lives entirely in [FribbelsScorer], which is a port of fribbels/hsr-optimizer
 * running on our own reference relic — a flat 1.25× their Perfection, uncapped. Whatever the scale,
 * the number has to stay one a member can re-derive: the missed stat gates below produce WORDS and
 * never a multiplier on the nota, because our [StatPanel] has no team buffs in it and a penalty
 * computed off that would be a number nobody could check.
 *
 * Everything else stays code and stays deterministic: a piece is told which wanted stat it is still
 * missing, the lowest-scoring piece is the farming suggestion, an empty slot averages in as a zero.
 * The model never gets to invent any of it — `/build` renders this report directly.
 */
object BuildAnalyzer {

    data class RelicScore(
        val slot: Int,
        val level: Int,
        val setName: String,
        val mainName: String,
        /** 1.0 when the main is one the character asked for, 0.0 when it is useless to them. */
        val mainWeight: Double,
        /** fribbels' Perfection for this piece, 0–100 — what [nota] turns into the printed 0–10. */
        val score: Double,
        /** Their "Score": the same figure before a wrong main is charged for. Grades [rank]. */
        val percent: Double,
        val rank: String,
        /** The four substats as rolled, in the showcase's own order. */
        val subs: List<Sub>,
        /** Wanted stats this piece does NOT have, PT-BR labels — see [faltando]. */
        val faltando: List<String> = emptyList(),
    )

    /**
     * One substat of one relic: which stat, how many times it was ENHANCED, and whether this
     * character's ruler pays for it at all. [util] is the whole explanation of a low score — a piece
     * with two dead lines rolled into nothing, which is a different problem from rolling badly.
     *
     * [melhorias] is the showcase's `count` minus one, which is what the game itself marks with
     * chevrons: a substat exists because it was rolled, so that first roll is the line being there
     * and not an upgrade of it. A +15 relic distributes 5 enhancements when it was born with four
     * substats and 4 when it was born with three, so the chevrons across a row always sum to one of
     * those two — a total nobody can read off a `×count`, which counts the same rolls one higher.
     * The scorer never sees this field: [FribbelsScorer.substatValue] takes the raw `count`.
     *
     * [valor] is the line as the game prints it ("21.1%", "42"), rebuilt from the same `count`/`step`
     * the scorer prices — no showcase hands it over finished: Enka reports the two integers and
     * nothing else, and mihomo's own string is the one source of the two.
     *
     * The property key travels rather than a label, because the two renderers want different ones:
     * the message spells "Acerto de Efeito" and a relic row has 250 px for four of them.
     */
    data class Sub(val prop: String, val valor: String, val melhorias: Int, val util: Boolean)

    data class BuildReport(
        val relics: List<RelicScore>,
        /** Slots 1–6 with no relic equipped. */
        val missingSlots: List<Int>,
        val totalScore: Double,
        val totalRank: String,
        val weakest: RelicScore?,
        /** Prioritized, ready-to-render farm suggestions; empty when the build needs none. */
        val farmPlan: List<String> = emptyList(),
        /** What is wrong with the build as a whole, as ready-to-render lines (also the top of
         *  [farmPlan]). These cost the nota nothing — see the class KDoc. */
        val alertas: List<String> = emptyList(),
    )

    /**
     * Null when fribbels has no ruler for this character — an unharvested id, or a config whose
     * every substat weight is zero. Judging a build with no ruler is guessing, and `/build` says so
     * instead.
     */
    fun analyze(character: ShowcaseCharacter, meta: FribbelsMeta?): BuildReport? {
        val ruler = meta?.let(FribbelsScorer::prepare) ?: return null
        val relics = character.relics.sortedBy { it.slot }.map { scoreRelic(it, ruler) }
        val equippedSlots = relics.map { it.slot }.toSet()
        val missing = (1..6).filter { it !in equippedSlots }
        // fribbels' `scoreCharacterWithRelicsUsingScorer`: always over six, so an empty slot is a
        // real deficiency and not a neutral one.
        val total = relics.sumOf { it.percent } / 6.0
        val alertas = alertas(character, meta)
        return BuildReport(
            relics = relics,
            missingSlots = missing,
            totalScore = total,
            totalRank = FribbelsScorer.rating(total),
            weakest = relics.minByOrNull { it.score },
            farmPlan = farmPlan(relics, missing, ruler, alertas),
            alertas = alertas,
        )
    }

    internal fun scoreRelic(relic: ShowcaseRelic, ruler: FribbelsScorer.Meta): RelicScore {
        val score = FribbelsScorer.score(relic, ruler)
        return RelicScore(
            slot = relic.slot,
            level = relic.level,
            setName = relic.setName,
            // Our own short label, not the API's: theirs is both long ("Bônus de Dano Quântico") and
            // ambiguous, since a flat-HP head and a HP% body are BOTH called "PV" there.
            mainName = relic.mainAffix?.let { statLabel(it.type, it.name) } ?: "—",
            mainWeight = FribbelsScorer.mainStatWeight(relic.slot, relic.mainAffix?.type, ruler),
            score = score.perfection,
            percent = score.percent,
            rank = score.rating,
            subs = relic.subAffixes.map {
                Sub(
                    prop = it.type,
                    valor = valorSub(it.type, FribbelsScorer.substatValue(it.type, it.count, it.step, relic.grade)),
                    melhorias = (it.count - 1).coerceAtLeast(0),
                    util = (ruler.stats[it.type] ?: 0.0) > 0.0,
                )
            },
            faltando = faltando(relic, ruler),
        )
    }

    /**
     * The stats this character wants that this piece does not have — the actionable half of "two of
     * these four rolls were dead": naming the line that is MISSING says what a refarm is chasing,
     * where naming the useless one only repeated what the card already greys out.
     *
     * The main stat counts as present, because a relic never rolls its own main as a substat — a
     * corpo de Dano Crít. is not "faltando Dano Crít.".
     *
     * Capped at how many of the piece's own lines are DEAD, which is the only honest cap: a relic has
     * four substats and no fifth, so a piece whose every line the ruler pays for has nowhere to put
     * one more and gets no suggestion at all. Without this an `@$%!` piece was told to improve.
     */
    private fun faltando(relic: ShowcaseRelic, ruler: FribbelsScorer.Meta): List<String> {
        val vagas = relic.subAffixes.count { (ruler.stats[it.type] ?: 0.0) <= 0.0 }
        if (vagas == 0) return emptyList()
        val presentes = relic.subAffixes.mapTo(mutableSetOf()) { it.type } + setOfNotNull(relic.mainAffix?.type)
        return desejados(ruler).filterNot { it in presentes }.take(vagas).map { statPt(it) }
    }

    /**
     * The one thing a per-relic substat score structurally cannot see: the **limiares** fribbels
     * declares per character (Hysilens' 120% de Acerto de Efeito, Cyrene's 180 de Velocidade).
     * Below the gate the kit does not do what the substats are being praised for.
     *
     * Words only. fribbels charges for it — a missed hard SPD gate zeroes their sim score — but
     * they can afford to because their numbers are COMBAT stats, post team buffs. Ours come from
     * [StatPanel], which has no team in it: a Cyrene who reaches 180 only with Sparkle's buff reads
     * as 150 here, and a penalty computed off that would be a number nobody can reproduce.
     *
     * **There is deliberately no wrong-set alert here, and adding one back needs new data.** The
     * obvious source is not one: `simulation().relicSets/ornamentSets` is a set-MATCHING list for
     * fribbels' DPS benchmark, not a ranking. Their `getSimulationSets` defaults the benchmark to
     * entry `[0]` and then, if the player's equipped sets appear anywhere in the list, rebuilds the
     * benchmark around the player's own sets instead — upstream's comment on the constants says it
     * outright: it lets the benchmark "recognize a user's equipped sets as valid alternatives, even
     * when their value isn't fully reflected in sim output". A set is ON that list because the sim
     * cannot model it, and OFF it for reasons that include "nobody has written the sim yet". It
     * also belongs to the DPS-benchmark feature, which we never ported — our nota comes from
     * `scoring().stats`. Reading absence as "your set is wrong" told members with correct builds
     * that they were wrong, and there is no threshold of harvest quality that makes it true.
     */
    // ponytail: the day a team model exists, this can become fribbels' actual roll-gap penalty.
    // Until then a sentence is the honest form of "we can't see your buffs".
    internal fun alertas(character: ShowcaseCharacter, meta: FribbelsMeta): List<String> {
        val alertas = mutableListOf<String>()
        // No panel, no verdict: Enka builds it from the StarRailRes tables and a character those
        // tables haven't caught up with gets an empty list rather than a wrong one.
        if (character.stats.isNotEmpty()) {
            meta.breakpoints.forEach { bp ->
                val atual = valorPainel(character, bp.stat) ?: return@forEach
                if (bp.threshold <= 0.0 || atual >= bp.threshold) return@forEach
                alertas += "**${statPt(bp.stat)}**: ${fmtStat(bp.stat, atual)} para um limiar de " +
                    "${fmtStat(bp.stat, bp.threshold)} — abaixo disso o kit não entrega o que os substats prometem."
            }
        }
        return alertas
    }

    /** Equipped set name (normalized) → how many pieces of it are on this build. */
    private fun pecasPorConjunto(character: ShowcaseCharacter): Map<String, Int> =
        character.relicSets.associate { HsrCharacterService.normalize(it.name) to it.pieces }

    /**
     * Whether [pecas] completes one of fribbels' combinations: `[A, A]` needs 4pc of A, `[A, B]`
     * needs 2pc of each — the same two shapes their `getSimulationSets` matches on. Only ever
     * decides a ✓ in [renderRecommendations]; a build that matches nothing gets no mark and no
     * verdict, see [alertas].
     */
    internal fun conjuntoVestido(pecas: Map<String, Int>, par: List<String>): Boolean {
        val a = par.firstOrNull() ?: return false
        val b = par.getOrElse(1) { a }
        fun n(set: String) = pecas[HsrCharacterService.normalize(set)] ?: 0
        return if (a == b) n(a) >= 4 else n(a) >= 2 && n(b) >= 2
    }

    private fun rotuloConjunto(par: List<String>): String {
        val a = par.firstOrNull() ?: return "—"
        val b = par.getOrElse(1) { a }
        return if (a == b) "4pç $a" else "2pç $a + 2pç $b"
    }

    /**
     * A stat's value on the finished combat panel, in the breakpoint's own units (a fraction for
     * percentages). Null when the panel has no field for that property at all — a gate we cannot
     * judge is not a gate we get to mention. A property the panel DOES cover but omits is a genuine
     * zero: the game leaves a zeroed bonus row out, and so do both showcase sources.
     */
    private fun valorPainel(character: ShowcaseCharacter, prop: String): Double? {
        val campo = CAMPO_PAINEL[prop] ?: return null
        val display = character.stats.firstOrNull { it.field == campo }?.display ?: return 0.0
        val n = display.removeSuffix("%").toDoubleOrNull() ?: return null
        return if (display.endsWith("%")) n / 100 else n
    }

    /** Game property → the panel field [StatPanel] and mihomo both name it by. */
    private val CAMPO_PAINEL: Map<String, String> = mapOf(
        "HPDelta" to "hp", "AttackDelta" to "atk", "DefenceDelta" to "def", "SpeedDelta" to "spd",
        "CriticalChanceBase" to "crit_rate", "CriticalDamageBase" to "crit_dmg",
    ) + StatPanel.EXTRA_FIELDS.toMap()

    /** The panel's flat rows; everything else is a percentage stored as a fraction. */
    private val PLANOS = setOf("HPDelta", "AttackDelta", "DefenceDelta", "SpeedDelta")

    /** Whole numbers on both sides — no decimal, so no locale to get wrong. */
    private fun fmtStat(prop: String, value: Double): String =
        if (prop in PLANOS) value.toInt().toString() else "${Math.round(value * 100)}%"

    /**
     * A substat's value the way the card's own STATUS panel writes the finished stats, so a `21.1%`
     * in a relic row and a `240.8%` in the panel above it are the same kind of number: flat rows
     * (including Velocidade) whole, everything else truncated to a tenth with a `%`.
     *
     * Truncated and not rounded, for [StatPanel]'s reason — the game truncates, and a row that
     * disagrees with the game by a digit reads as a miscalculation even when it is not.
     */
    internal fun valorSub(prop: String, valor: Double): String =
        if (prop in PLANOS) valor.toInt().toString()
        else String.format(Locale.ROOT, "%.1f%%", FribbelsScorer.truncate10ths(valor))

    /**
     * Concrete "what to farm next" list, most impactful first: the build-level [alertas] (a wrong
     * set, a missed gate — they are about the whole build, not one piece), then empty slots, then
     * pieces whose fix is cheapest-per-gain (wrong main → replace; underleveled → just level it;
     * sub-par score → refarm chasing the right substats). At most [limit] lines; empty when every
     * piece is A-grade or better — nothing worth telling the player to grind.
     */
    internal fun farmPlan(
        relics: List<RelicScore>,
        missingSlots: List<Int>,
        ruler: FribbelsScorer.Meta,
        alertas: List<String> = emptyList(),
        limit: Int = 3,
    ): List<String> {
        // Sort key = current score, so the worst deficiency leads; empty slots score -1 and the
        // build-level alerts -2, below anything a single relic can reach.
        val plan = mutableListOf<Pair<Double, String>>()
        alertas.forEach { plan += -2.0 to it }
        missingSlots.forEach { slot ->
            plan += -1.0 to "**${SLOT_NAMES[slot]}** vazio — equipar qualquer peça decente aqui é o maior ganho."
        }
        relics.forEach { r ->
            val slotName = SLOT_NAMES[r.slot] ?: "Peça ${r.slot}"
            when {
                // Slots 1-2 have fixed mains; only 3-6 can have a wrong one. A slot whose config
                // accepts every main never lands here, since mainWeight is then always 1.
                r.slot >= 3 && r.mainWeight == 0.0 ->
                    plan += r.score to
                        "**$slotName**: main atual (${r.mainName}) não serve — troque por ${idealMains(r.slot, ruler)}."
                r.level < 15 ->
                    plan += r.score to "**$slotName**: subir de +${r.level} para +15 já melhora a nota."
                // Held at 50 on purpose when [FribbelsScorer.MIN_ROLL] rescaled everything by 1.25
                // (Heitor's call): the cutoff now bites at what used to be 40%, so the plan nags
                // about fewer pieces and only the genuinely bad ones. Still the bottom of `A`.
                r.score < 50.0 ->
                    plan += r.score to
                        "**$slotName** (${nota(r.score)}): refarm atrás de ${desiredSubs(ruler)} nos substats."
            }
        }
        return plan.sortedBy { it.first }.take(limit).map { it.second }
    }

    /** Best main stats for [slot] under this ruler, in fribbels' own priority order, PT-BR labels. */
    private fun idealMains(slot: Int, ruler: FribbelsScorer.Meta): String =
        ruler.parts[slot].orEmpty().take(3).joinToString("/") { statPt(it) }.ifEmpty { "—" }

    /** The character's most-wanted substats under this ruler, heaviest first. */
    private fun desiredSubs(ruler: FribbelsScorer.Meta): String =
        desejados(ruler).joinToString(" > ") { statPt(it) }

    /**
     * The four substats this character wants most, heaviest first — what both the refarm line and
     * [faltando] are written against.
     *
     * Flat PV/ATQ/DEF are left out: [FribbelsScorer.prepare] fixes their weight at 40% of the
     * percent line they stand for, so they can never be wanted on their own — they ride along, and
     * "faltando PV" would send someone farming for the consolation prize.
     */
    private fun desejados(ruler: FribbelsScorer.Meta): List<String> =
        ruler.sorted
            .filter { it.second > 0.0 && it.first !in FribbelsScorer.PERCENT_TO_FLAT.values }
            .take(4).map { it.first }

    val SLOT_NAMES = mapOf(
        1 to "Cabeça", 2 to "Mãos", 3 to "Corpo",
        4 to "Pés", 5 to "Esfera Planar", 6 to "Corda de Conexão",
    )

    /** Game property key → short PT-BR stat label for the recommendation lines. */
    private val PROP_PT = mapOf(
        "HPDelta" to "PV", "AttackDelta" to "ATQ", "DefenceDelta" to "DEF",
        "HPAddedRatio" to "PV%", "AttackAddedRatio" to "ATQ%", "DefenceAddedRatio" to "DEF%",
        "SpeedDelta" to "Velocidade", "CriticalChanceBase" to "Chance Crít.",
        "CriticalDamageBase" to "Dano Crít.", "StatusProbabilityBase" to "Acerto de Efeito",
        "StatusResistanceBase" to "RES de Efeito", "BreakDamageAddedRatioBase" to "Efeito de Quebra",
        "SPRatioBase" to "Regen. de Energia", "HealRatioBase" to "Aumento de Cura",
        "PhysicalAddedRatio" to "Dano Físico", "FireAddedRatio" to "Dano de Fogo",
        "IceAddedRatio" to "Dano de Gelo", "ThunderAddedRatio" to "Dano de Raio",
        "WindAddedRatio" to "Dano de Vento", "QuantumAddedRatio" to "Dano Quântico",
        "ImaginaryAddedRatio" to "Dano Imaginário",
    )

    /**
     * The six substats whose PT name does not fit a relic row four-across. Shorter than the card's
     * own STATUS panel says the same stats ("D.CRIT" vs "DANO CRIT") because the panel gives one
     * stat half a column and a relic row gives four of them 250 px between them — and the whole
     * stack of six rows is drawn at the size the longest of them needs.
     */
    private val PROP_CURTO = mapOf(
        "SpeedDelta" to "VEL", "CriticalChanceBase" to "CRIT", "CriticalDamageBase" to "D.CRIT",
        "StatusProbabilityBase" to "EFEITO", "StatusResistanceBase" to "RES EF",
        "BreakDamageAddedRatioBase" to "QUEBRA",
    )

    /** Card-sized label for a substat; [statPt] is already short enough for the other six. */
    internal fun statCurto(prop: String): String = PROP_CURTO[prop] ?: statPt(prop)

    /** Also used by the nanoka ingester to label recommended-build stats. */
    internal fun statPt(prop: String): String = PROP_PT[prop] ?: prop

    /**
     * Label for a stat we also have the API's own name for: ours when we know the property, theirs
     * otherwise — a property key ("PhysicalAddedRatio") on a card is worse than a long PT name.
     */
    internal fun statLabel(prop: String, fallback: String): String = PROP_PT[prop] ?: fallback

    /**
     * The fribbels-sourced reference block: sets, ideal mains (✓ when the equipped piece already
     * matches) and substat priority. Purely presentational — nothing here feeds the score.
     *
     * The sets are labelled "equivalentes" and not "recomendados" on purpose: they are the ones
     * fribbels' benchmark accepts as interchangeable, so a ✓ means "the benchmark keeps your set",
     * and no ✓ means nothing at all. See [alertas] for why that distinction cost us a feature.
     */
    internal fun renderRecommendations(character: ShowcaseCharacter, meta: FribbelsMeta): String = buildString {
        appendLine("**Recomendado (fribbels/hsr-optimizer):**")
        val equippedPieces = pecasPorConjunto(character)
        if (meta.relicSets.isNotEmpty()) {
            val options = meta.relicSets.map { pair ->
                val label = rotuloConjunto(pair)
                if (conjuntoVestido(equippedPieces, pair)) "$label ✓" else label
            }
            appendLine("• Conjuntos equivalentes: ${options.joinToString(" | ")}")
        }
        if (meta.ornamentSets.isNotEmpty()) {
            val options = meta.ornamentSets.map { set ->
                if ((equippedPieces[HsrCharacterService.normalize(set)] ?: 0) >= 2) "$set ✓" else set
            }
            appendLine("• Ornamentos equivalentes: ${options.joinToString(" | ")}")
        }
        val equippedMains = character.relics.associate { it.slot to it.mainAffix?.type }
        val mains = meta.mainStats.toSortedMap().mapNotNull { (slot, props) ->
            if (slot < 3 || props.isEmpty()) return@mapNotNull null
            val mark = when {
                equippedMains[slot] in props -> " ✓"
                equippedMains[slot] != null -> " ✗"
                else -> ""
            }
            "${SLOT_NAMES[slot]}: ${props.joinToString("/") { statPt(it) }}$mark"
        }
        if (mains.isNotEmpty()) appendLine("• Main stats: ${mains.joinToString(" • ")}")
        if (meta.substatPriority.isNotEmpty()) {
            appendLine("• Substats: ${meta.substatPriority.joinToString(" > ") { statPt(it) }}")
        }
    }.trim()

    /**
     * The advice half of [render]: what to actually do next. Split out because the `/build` card
     * draws every number in the report but cannot draw a sentence — this is the part that goes in
     * the message under it.
     *
     * The per-piece list here is what the piece is MISSING, not what it wasted. The dead rolls are
     * already greyed out in the card's own relic rows — repeating them in prose said nothing a
     * player could act on, since the useless line is exactly the one they cannot remove.
     */
    fun renderFarmPlan(report: BuildReport): String = buildString {
        if (report.farmPlan.isNotEmpty()) {
            appendLine("**Próximo farm:**")
            report.farmPlan.forEach { appendLine("• $it") }
        } else {
            // Nothing worth grinding — still point at the weakest piece for the min-maxers.
            report.weakest?.let {
                appendLine("Peça mais fraca: **${SLOT_NAMES[it.slot]}** (${nota(it.score)}) — é aqui que um farm rende mais.")
            }
        }
        val melhorias = report.relics.filter { it.faltando.isNotEmpty() }
        if (melhorias.isNotEmpty()) {
            appendLine()
            appendLine("**Possíveis melhorias:**")
            // Uma linha por peça: a lista corrida virava um parágrafo que ninguém lê no celular, e
            // o que se procura aqui é UMA peça, não a soma delas.
            melhorias.forEach {
                appendLine("• **${SLOT_NAMES[it.slot]}** — faltando ${it.faltando.joinToString(", ")}")
            }
        }
    }.trim()

    /** Renders the report as Discord markdown, PT-BR, all numbers straight from the math above. */
    fun render(
        character: ShowcaseCharacter,
        report: BuildReport,
        meta: FribbelsMeta? = null,
    ): String = buildString {
        append("**${character.name}** — Nv. ${character.level} • E${character.eidolon}")
        if (character.elementName.isNotBlank()) append(" • ${character.elementName}")
        if (character.pathName.isNotBlank()) append(" • ${character.pathName}")
        appendLine()
        character.lightCone?.let {
            appendLine("Cone de Luz: ${it.name} (S${it.superimposition}, Nv. ${it.level})")
        }
        if (character.relicSets.isNotEmpty()) {
            appendLine("Conjuntos: " + character.relicSets.joinToString(" • ") { "${it.pieces}pç ${it.name}" })
        }
        appendLine()
        appendLine("**Nota da build: ${nota(report.totalScore)}/10 (${report.totalRank})**")
        appendLine()
        report.relics.forEach { r ->
            append("• **${SLOT_NAMES[r.slot] ?: "Peça ${r.slot}"}** +${r.level} — ${nota(r.score)} (${r.rank})")
            // Slots 1-2 have fixed main stats, so only flag a wrong choice where one exists.
            if (r.slot >= 3 && r.mainWeight == 0.0) append(" · main fora do ideal (${r.mainName})")
            // The missing wanted stats are NOT repeated here: [renderFarmPlan], appended below,
            // already lists them per slot.
            appendLine()
        }
        report.missingSlots.forEach { appendLine("• **${SLOT_NAMES[it]}** — vazio!") }
        renderFarmPlan(report).takeIf { it.isNotEmpty() }?.let {
            appendLine()
            appendLine(it)
        }
        meta?.let {
            appendLine()
            appendLine(renderRecommendations(character, it))
            appendLine()
        }
        // Descreve a régua, não a origem dela: o que a pessoa precisa saber pra ler a nota é CONTRA
        // O QUE a peça foi medida. Heitor recusou duas vezes uma nota de rodapé sobre o fribbels.
        append(
            "_Nota = só os substats, na régua da personagem: a peça comparada com uma em que todo " +
                "roll caiu num substat que ela quer. O main stat não pontua — só define o que a " +
                "peça ainda podia rolar._",
        )
    }.trim()

    /**
     * The only place a score becomes something a player reads: the 0–100 shifted one decimal place
     * onto the 0–10 the members asked for, PT-BR comma included. Two decimals is not false precision
     * — [FribbelsScorer.truncate10ths] already quantizes to a tenth of a percent, so `84.4` → `8,44`
     * is the same number written differently, and a third decimal cannot exist.
     *
     * **Clamped at 10,00.** A nota out of ten that reads 10,98 is not a flex, it is a bug report —
     * Heitor called it the moment the first card rendered one. Beating the reference relic is still
     * visible, just as a badge instead of a digit: `@$%!` fires off the UNCLAMPED score, so a piece
     * that cleared 100 shows `10,00 @$%!` and a merely great one shows `9,99` with a letter.
     *
     * The clamp is display-only and stops here, deliberately. [FribbelsScorer] keeps scoring past
     * 100, `nota_build` stores the real value and the ranking's `ORDER BY` sorts on it, so two builds
     * that both draw `10,00` still take their true positions — clamping the stored number instead
     * would tie them and cost a whole tie-break column to undo. Same for the [FribbelsScorer.rating]
     * bands (which is how `@$%!` survives the clamp) and the `< 50.0` farm cutoff.
     */
    internal fun nota(score: Double): String = String.format(PT_BR, "%.2f", minOf(100.0, score) / 10)

    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
}
