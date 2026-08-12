package com.hsrbot.hsr

import com.hsrbot.discord.command.BuildCommand
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the deterministic scoring contract behind `/build`.
 *
 * The anchor is [`the six Evernight relics score exactly 1,25x what the fribbels showcase prints`] —
 * a real showcase (UID 600281322) whose six percentages were read off fribbels' own site and then
 * scaled by the one thing we changed. Everything else here is a mechanism test; that one is the
 * promise, and it is the test to keep green if any of the others ever have to move.
 *
 * The small rulers below weigh `CriticalDamageBase` and `SpeedDelta` at 1.0 with a slot-3 main the
 * piece cannot also roll, so every ideal is `0.8 × (6×6.48 + 6.48) = 36.288` potential units — the
 * 0.8 being [FribbelsScorer]'s minimum-roll reference relic.
 */
class BuildAnalyzerTest {

    private val meta = FribbelsMeta(
        subWeights = mapOf("CriticalDamageBase" to 1.0, "SpeedDelta" to 1.0),
        mainStats = mapOf(3 to listOf("CriticalChanceBase")),
        relicSets = emptyList(),
        ornamentSets = emptyList(),
        substatPriority = emptyList(),
    )

    private val ruler = assertNotNull(FribbelsScorer.prepare(meta))

    private fun relic(
        slot: Int = 3,
        level: Int = 15,
        mainType: String = "CriticalChanceBase",
        subs: List<ShowcaseSubAffix> = emptyList(),
        grade: Int = 5,
    ) = ShowcaseRelic(
        slot = slot,
        setName = "Conjunto de Teste",
        level = level,
        mainAffix = ShowcaseAffix(type = mainType, name = mainType, display = "x"),
        subAffixes = subs,
        grade = grade,
    )

    private fun sub(type: String, count: Int, step: Int) = ShowcaseSubAffix(type, type, "x", count, step)

    // -------------------- the anchor -------------------- //

    /**
     * Evernight off UID 600281322, six relics, against fribbels' own `Evernight.ts` weights.
     *
     * [NO_SITE] is what their showcase renders for that exact build. Ours is that times 1.25 and
     * re-truncated to a tenth — the only thing we changed is the reference relic's roll quality, so
     * anything else moving means the mechanism drifted, not the scale.
     */
    @Test
    fun `the six Evernight relics score exactly 1,25x what the fribbels showcase prints`() {
        val evernight = FribbelsMeta(
            subWeights = mapOf(
                "HPDelta" to 1.0, "HPAddedRatio" to 1.0, "SpeedDelta" to 1.0,
                "CriticalChanceBase" to 1.0, "CriticalDamageBase" to 1.0,
            ),
            mainStats = mapOf(
                3 to listOf("CriticalChanceBase", "CriticalDamageBase", "HPAddedRatio"),
                4 to listOf("HPAddedRatio", "SpeedDelta"),
                5 to listOf("HPAddedRatio", "IceAddedRatio"),
                6 to listOf("HPAddedRatio"),
            ),
            relicSets = emptyList(),
            ornamentSets = emptyList(),
            substatPriority = emptyList(),
        )
        val relics = listOf(
            relic(
                slot = 1, mainType = "HPDelta",
                subs = listOf(
                    sub("HPAddedRatio", 5, 9), sub("AttackAddedRatio", 1, 1),
                    sub("CriticalChanceBase", 1, 0), sub("CriticalDamageBase", 2, 3),
                ),
            ),
            relic(
                slot = 2, mainType = "AttackDelta",
                subs = listOf(
                    sub("DefenceDelta", 1, 1), sub("HPAddedRatio", 1, 0),
                    sub("CriticalChanceBase", 3, 4), sub("CriticalDamageBase", 4, 5),
                ),
            ),
            relic(
                slot = 3, mainType = "CriticalDamageBase",
                subs = listOf(
                    sub("HPAddedRatio", 3, 3), sub("AttackAddedRatio", 1, 2),
                    sub("CriticalChanceBase", 2, 1), sub("StatusProbabilityBase", 2, 4),
                ),
            ),
            relic(
                slot = 4, mainType = "HPAddedRatio",
                subs = listOf(
                    sub("HPDelta", 1, 1), sub("AttackAddedRatio", 1, 1),
                    sub("CriticalChanceBase", 2, 3), sub("CriticalDamageBase", 5, 2),
                ),
            ),
            relic(
                slot = 5, mainType = "IceAddedRatio",
                subs = listOf(
                    sub("HPDelta", 1, 0), sub("HPAddedRatio", 3, 3),
                    sub("SpeedDelta", 1, 1), sub("CriticalDamageBase", 4, 5),
                ),
            ),
            relic(
                slot = 6, mainType = "HPAddedRatio",
                subs = listOf(
                    sub("DefenceAddedRatio", 1, 0), sub("CriticalChanceBase", 3, 5),
                    sub("CriticalDamageBase", 4, 7), sub("StatusResistanceBase", 1, 0),
                ),
            ),
        )
        val evernightRuler = assertNotNull(FribbelsScorer.prepare(evernight))
        assertEquals(NOSSAS, relics.map { FribbelsScorer.score(it, evernightRuler).perfection })
        // Their per-relic "Score" (no main-stat deduction) agrees piece for piece: every main here
        // is one the config asks for.
        assertEquals(NOSSAS, relics.map { FribbelsScorer.score(it, evernightRuler).percent })
        // …and each of ours IS theirs rescaled. Truncating to a tenth is the whole of the slack.
        NOSSAS.zip(NO_SITE).forEach { (nossa, deles) ->
            assertTrue(nossa >= deles * 1.25 - 0.1 && nossa < deles * 1.25 + 0.125, "$nossa vs $deles")
        }
        // Four of the six clear 100: on a build this good, beating a relic whose every roll came out
        // minimum is normal per PIECE. The build TOTAL is 95.8 — averaging over six slots is what
        // keeps `@$%!` rare on the headline number even when most of the pieces earned it.
        assertEquals(
            listOf("@\$%!", "@\$%!", "S+", "???", "@\$%!", "@\$%!"),
            relics.map { FribbelsScorer.score(it, evernightRuler).rating },
        )
    }

    /** What hsr.fribbels.com prints for UID 600281322's Evernight, piece by piece. */
    private val NO_SITE = listOf(84.4, 81.1, 52.3, 76.9, 84.4, 80.9)

    /**
     * The same six on our minimum-roll reference relic. Slots 1 and 5 both read 84.4 on their site
     * and split to 105.5/105.6 here — the raw values always differed below the tenth, and ×1.25
     * pushes that difference up into a digit the truncation can see. That is the scale working, not
     * the mechanism drifting; the bound below is what actually guards it.
     */
    private val NOSSAS = listOf(105.5, 101.3, 65.4, 96.1, 105.6, 101.1)

    // -------------------- the formula -------------------- //

    @Test
    fun `scoreRelic is substat perfection alone, with no main-stat term in the numerator`() {
        // 5 CD rolls with 5 upgrade steps: 5.184×5 + 0.648×5 = 29.16 points, worth 29.16 units at
        // CD's 6.48-per-max-roll scale, over an ideal of 36.288 → 80.3%. The ideal main adds nothing.
        val r = BuildAnalyzer.scoreRelic(relic(subs = listOf(sub("CriticalDamageBase", 5, 5))), ruler)
        assertEquals(80.3, r.score)
        assertEquals("SSS", r.rank)
        assertTrue(r.subs.all { it.util })
    }

    /**
     * A stat spent on the main stat is one the piece can no longer roll, so it leaves the divisor: a
     * CD-main body is judged against `6×CR + SPD`, not against an ideal holding CD rolls it was never
     * allowed to have.
     */
    @Test
    fun `the ideal drops the main stat from the substat pool`() {
        val crit = assertNotNull(
            FribbelsScorer.prepare(
                meta.copy(
                    subWeights = mapOf("CriticalDamageBase" to 1.0, "CriticalChanceBase" to 1.0, "SpeedDelta" to 0.5),
                    mainStats = mapOf(3 to listOf("CriticalDamageBase")),
                ),
            ),
        )
        // CD main is ideal, so it is the stat the perfect piece carries: pool = CR 6.48, SPD 3.24.
        assertEquals(0.8 * (6 * 6.48 + 3.24), FribbelsScorer.computeOptimalScore(3, "CriticalDamageBase", crit), 1e-9)
        // A CR main leaves CD in the pool, and the ideal is built on the heavier stat instead.
        assertEquals(0.8 * (6 * 6.48 + 3.24), FribbelsScorer.computeOptimalScore(3, "CriticalChanceBase", crit), 1e-9)
    }

    @Test
    fun `a main stat that cannot be a substat leaves the ideal alone`() {
        // Elemental damage is a sphere main and nothing else, so the pool keeps all four stats.
        assertEquals(0.8 * (6 * 6.48 + 6.48), FribbelsScorer.computeOptimalScore(5, "IceAddedRatio", ruler), 1e-9)
    }

    @Test
    fun `a one-stat character is scored against six rolls of that stat alone`() {
        val one = assertNotNull(FribbelsScorer.prepare(meta.copy(subWeights = mapOf("CriticalDamageBase" to 1.0))))
        assertEquals(0.8 * (6 * 6.48), FribbelsScorer.computeOptimalScore(3, "CriticalChanceBase", one), 1e-9)
        assertEquals(93.7, FribbelsScorer.score(relic(subs = listOf(sub("CriticalDamageBase", 5, 5))), one).percent)
        // …and when the main has TAKEN that stat there is no ideal at all, which reads as a zero.
        val body = relic(mainType = "CriticalDamageBase", subs = listOf(sub("CriticalChanceBase", 5, 5)))
        assertEquals(0.0, FribbelsScorer.score(body, one).percent)
        assertEquals("?", FribbelsScorer.score(body, one).rating)
    }

    /**
     * The flat/percent pair: two weighted substats that are two halves of one stat cannot fill four
     * lines, so the ideal is six of the heavier plus one of the lighter — and the flat half is
     * priced at 40% of the percent one.
     */
    @Test
    fun `a flat-and-percent pair is scored against the two of them only`() {
        val hp = assertNotNull(
            FribbelsScorer.prepare(meta.copy(subWeights = mapOf("HPAddedRatio" to 1.0), mainStats = mapOf(3 to listOf("HPAddedRatio")))),
        )
        assertEquals(0.8 * (6 * 6.48 + 0.4 * 6.48), FribbelsScorer.computeOptimalScore(3, "CriticalChanceBase", hp), 1e-9)
    }

    /**
     * `flatMainstatBoost`: on the twelve sustains that name one, a flat substat sitting on a relic
     * whose main is its percent form counts at full weight instead of 40%.
     */
    @Test
    fun `flatMainstatBoost lifts the flat substat off the 40 percent discount`() {
        val weights = mapOf("HPAddedRatio" to 1.0, "CriticalDamageBase" to 1.0)
        val mains = mapOf(3 to listOf("HPAddedRatio"))
        val comBoost = assertNotNull(
            FribbelsScorer.prepare(meta.copy(subWeights = weights, mainStats = mains, flatMainstatBoost = "HPDelta")),
        )
        val semBoost = assertNotNull(FribbelsScorer.prepare(meta.copy(subWeights = weights, mainStats = mains)))
        val peca = relic(mainType = "HPAddedRatio", subs = listOf(sub("HPDelta", 5, 5)))
        assertEquals(80.3, FribbelsScorer.score(peca, comBoost).percent)
        assertEquals(35.1, FribbelsScorer.score(peca, semBoost).percent)
    }

    /**
     * The main stat is not in the numerator but it is charged for: a slot-3..6 main the character
     * has no use for costs a whole maxed main stat (10 max rolls) off the Perfection, while their
     * separate "Score" line stays put.
     */
    @Test
    fun `a wrong main costs the perfection a whole main stat and voids the letter`() {
        val r = BuildAnalyzer.scoreRelic(
            relic(mainType = "AttackAddedRatio", subs = listOf(sub("CriticalDamageBase", 5, 5))),
            ruler,
        )
        assertEquals(80.3, r.percent)
        assertEquals(0.0, r.score)
        assertEquals("?", r.rank)
        assertEquals(0.0, r.mainWeight)
    }

    /** Head and hands have a fixed main, so there is no wrong choice to charge for there. */
    @Test
    fun `head and hands are never charged for their main`() {
        val r = BuildAnalyzer.scoreRelic(
            relic(slot = 1, mainType = "HPDelta", subs = listOf(sub("CriticalDamageBase", 5, 5))),
            ruler,
        )
        assertEquals(r.percent, r.score)
        assertEquals("SSS", r.rank)
    }

    /** Relic level never enters the score; an unleveled piece scores low by having fewer rolls. */
    @Test
    fun `relic level does not enter the score directly`() {
        val subs = listOf(sub("CriticalDamageBase", 5, 5))
        assertEquals(
            BuildAnalyzer.scoreRelic(relic(level = 15, subs = subs), ruler).score,
            BuildAnalyzer.scoreRelic(relic(level = 3, subs = subs), ruler).score,
        )
    }

    /** fribbels refuses to letter anything but a 5★ piece, and so do we. */
    @Test
    fun `a four-star relic is scored but not graded`() {
        val r = BuildAnalyzer.scoreRelic(relic(grade = 4, subs = listOf(sub("CriticalDamageBase", 5, 5))), ruler)
        assertTrue(r.score > 0.0, r.score.toString())
        assertEquals("?", r.rank)
    }

    @Test
    fun `scoreRelic flags zero-weight substats as dead`() {
        val r = BuildAnalyzer.scoreRelic(
            relic(subs = listOf(sub("HPDelta", 3, 0), sub("SpeedDelta", 2, 1))),
            ruler,
        )
        assertEquals(listOf("HPDelta"), r.subs.filterNot { it.util }.map { it.prop })
        // PV weighs 0 (its percent half does too), so only Velocidade counts.
        assertTrue(r.score > 0.0)
    }

    /**
     * The card draws every substat with its value and a dot per enhancement, and greys the dead
     * ones, so the row explains its own score. Order is the showcase's — the order the game lists.
     *
     * The counts drop by one on the way out: a substat's first roll is the line existing, and the
     * game marks only the enhancements after it. A `count` of 3 is two dots, and a line that never
     * came up again is drawn with none.
     *
     * The values are the game's own strings, rebuilt from `count`/`step`: flat rows whole (3 rolls
     * of PV plano = 101), percentages truncated to a tenth. Enka hands over neither.
     */
    @Test
    fun `scoreRelic conta melhorias, nao rolagens — o roll base nao é um ponto`() {
        val r = BuildAnalyzer.scoreRelic(
            relic(subs = listOf(sub("HPDelta", 3, 0), sub("SpeedDelta", 1, 0))),
            ruler,
        )
        assertEquals(
            listOf(
                BuildAnalyzer.Sub("HPDelta", "101", 2, util = false),
                BuildAnalyzer.Sub("SpeedDelta", "2", 0, util = true),
            ),
            r.subs,
        )
        // Truncado como o jogo faz, nunca arredondado: 4 rolagens de Dano Crít. dão 20.736%.
        assertEquals("20.7%", BuildAnalyzer.valorSub("CriticalDamageBase", FribbelsScorer.substatValue("CriticalDamageBase", 4, 0, 5)))
        // Long name for the message, short one for the row: the card has ~400 px for four of these.
        assertEquals("Velocidade", BuildAnalyzer.statPt("SpeedDelta"))
        assertEquals("VEL", BuildAnalyzer.statCurto("SpeedDelta"))
    }

    /**
     * A peça diz o que FALTA nela, não o que sobrou: a régua quer Velocidade e Dano Crít., a peça
     * rolou PV plano e Dano Crít., e a Chance Crít. do main não conta como falta — nenhuma relíquia
     * rola o próprio main como substat.
     */
    @Test
    fun `o plano de farm aponta o stat que falta em cada peça`() {
        val character = character(
            relics = listOf(relic(subs = listOf(sub("HPDelta", 3, 0), sub("CriticalDamageBase", 5, 5)))),
        )
        val texto = BuildAnalyzer.renderFarmPlan(assertNotNull(BuildAnalyzer.analyze(character, meta)))
        assertTrue("**Possíveis melhorias:**" in texto, texto)
        assertTrue("• **Corpo** — faltando Velocidade" in texto, texto)
    }

    /** Uma peça que já tem tudo que a régua pede não vira linha nenhuma. */
    @Test
    fun `nada falta numa peça com todos os stats desejados`() {
        val completa = relic(subs = listOf(sub("CriticalDamageBase", 5, 5), sub("SpeedDelta", 2, 2)))
        assertTrue(BuildAnalyzer.scoreRelic(completa, ruler).faltando.isEmpty())
    }

    /**
     * As melhorias de um +15 somam 5 numa peça que nasceu com quatro substats — é essa conta que as
     * setas do card têm que fechar, e é ela que um `×count` erra pra cima em uma por linha.
     */
    @Test
    fun `as setas de uma peça +15 somam as cinco melhorias do jogo`() {
        val quatroLinhas = relic(
            subs = listOf(
                sub("HPAddedRatio", 5, 9), sub("AttackAddedRatio", 1, 1),
                sub("CriticalChanceBase", 1, 0), sub("CriticalDamageBase", 2, 3),
            ),
        )
        assertEquals(5, BuildAnalyzer.scoreRelic(quatroLinhas, ruler).subs.sumOf { it.melhorias })
    }

    @Test
    fun `analyze averages over all six slots and reports missing ones`() {
        val character = character(
            relics = listOf(relic(subs = listOf(sub("CriticalDamageBase", 5, 5)))),
        )
        val report = assertNotNull(BuildAnalyzer.analyze(character, meta))
        assertEquals(listOf(1, 2, 4, 5, 6), report.missingSlots)
        // One 80.3% piece over six slots — empty slots drag the build down on purpose.
        assertEquals(80.3 / 6.0, report.totalScore, 1e-9)
        assertEquals(3, report.weakest?.slot)
    }

    /** No harvested config, no ruler, no verdict — `/build` refuses instead of inventing one. */
    @Test
    fun `analyze abstains when fribbels has no ruler for the character`() {
        assertNull(BuildAnalyzer.analyze(character(), null))
        assertNull(BuildAnalyzer.analyze(character(), meta.copy(subWeights = mapOf("SpeedDelta" to 0.0))))
    }

    // -------------------- o botão "SPD weight" -------------------- //

    /**
     * fribbels' 100%/0% toggle is ONE overridden substat weight, from which the whole ruler is
     * rebuilt — which is the difference between the feature and a cosmetic relabel.
     *
     * With Velocidade at 0 this character wants CD and nothing else, so the ideal relic shrinks from
     * `0.8 × (6×CD + SPD) = 36.288` units to `0.8 × 6×CD = 31.104`, and the five CD rolls that scored
     * 80.3% against the full ruler score 93.7% against the narrower one. That is upstream's stated
     * purpose: "better grading of low SPD builds" — the piece did not change, the ruler did.
     */
    @Test
    fun `zerar o peso da velocidade reconstroi a regua inteira`() {
        val semSpd = assertNotNull(BuildCommand.comPesoDeVelocidade(meta, 0.0))
        val ruler0 = assertNotNull(FribbelsScorer.prepare(semSpd))

        assertEquals(0.8 * (6 * 6.48), FribbelsScorer.computeOptimalScore(3, "CriticalChanceBase", ruler0), 1e-9)
        val soCd = relic(subs = listOf(sub("CriticalDamageBase", 5, 5)))
        assertEquals(80.3, BuildAnalyzer.scoreRelic(soCd, ruler).score)
        assertEquals(93.7, BuildAnalyzer.scoreRelic(soCd, ruler0).score)

        // And the rolls that stopped counting have to read as dead on the card, not as free value.
        // 111.9 is also the uncapped path in the open: this piece BEAT the reference relic.
        val comSpd = relic(subs = listOf(sub("CriticalDamageBase", 5, 5), sub("SpeedDelta", 2, 2)))
        assertEquals(111.9, BuildAnalyzer.scoreRelic(comSpd, ruler).score)
        assertEquals("@\$%!", BuildAnalyzer.scoreRelic(comSpd, ruler).rank)
        assertTrue(BuildAnalyzer.scoreRelic(comSpd, ruler).subs.all { it.util })
        assertEquals(93.7, BuildAnalyzer.scoreRelic(comSpd, ruler0).score)
        assertEquals(
            listOf("SpeedDelta"),
            BuildAnalyzer.scoreRelic(comSpd, ruler0).subs.filterNot { it.util }.map { it.prop },
        )
    }

    /**
     * No override, no change — a plain `/build` must keep scoring on the harvested config, and the
     * footer must say nothing at all about the ruler. The label carries no separator of its own: the
     * footer joins its fields, so a leading " · " would print a dangling one on the default card.
     */
    @Test
    fun `sem a opcao a regua e a do fribbels, intocada`() {
        assertEquals(meta, BuildCommand.comPesoDeVelocidade(meta, null))
        assertNull(BuildCommand.comPesoDeVelocidade(null, 0.0))
        assertEquals("", BuildCommand.rotuloSpd(null))
        assertEquals("", BuildCommand.avisoSpd(null))
        assertEquals("vel 0%", BuildCommand.rotuloSpd(0.0))
        assertEquals("vel 100%", BuildCommand.rotuloSpd(1.0))
    }

    /** An empty ideal-main list in the config means "any main is fine", not "every main is wrong". */
    @Test
    fun `a slot the config leaves open accepts every main it can roll`() {
        val aberto = assertNotNull(FribbelsScorer.prepare(meta.copy(mainStats = emptyMap())))
        assertEquals(1.0, FribbelsScorer.mainStatWeight(3, "AttackAddedRatio", aberto))
        assertEquals(FribbelsScorer.SLOT_MAIN_STATS[3], aberto.parts[3])
    }

    // -------------------- alertas de build -------------------- //

    /** Prisioneiro 4pç + Talia is the recommended build; EHR must reach 120%. */
    private val metaComGates = meta.copy(
        relicSets = listOf(listOf("Prisioneiro", "Prisioneiro")),
        ornamentSets = listOf("Talia"),
        breakpoints = listOf(FribbelsMeta.Breakpoint("StatusProbabilityBase", 1.20)),
    )

    private fun character(
        sets: List<ShowcaseRelicSet> = listOf(ShowcaseRelicSet("Prisioneiro", 4), ShowcaseRelicSet("Talia", 2)),
        stats: List<ShowcaseStat> = listOf(ShowcaseStat("effect_hit", "Acerto de Efeito", "120.0%")),
        relics: List<ShowcaseRelic> = listOf(relic(subs = listOf(sub("CriticalDamageBase", 5, 5)))),
    ) = ShowcaseCharacter(
        id = "1410", name = "Hysilens", level = 80, eidolon = 0,
        pathName = "Nada", elementName = "Vento", lightCone = null,
        relics = relics,
        relicSets = sets,
        stats = stats,
    )

    @Test
    fun `a build on the listed sets and above its breakpoint says nothing`() {
        assertTrue(BuildAnalyzer.alertas(character(), metaComGates).isEmpty())
    }

    /**
     * The regression this replaces a feature over. `simulation().relicSets/ornamentSets` is
     * fribbels' set-MATCHING list for their DPS benchmark — a set is on it when their sim cannot
     * model its value, off it when nobody has modelled it yet — so a build wearing something else
     * is not a build wearing something wrong. Judging it told members with correct builds they
     * were wrong; the sets now only ever earn a ✓ in `renderRecommendations`.
     */
    @Test
    fun `sets outside fribbels' list are never called wrong`() {
        val fora = character(sets = listOf(ShowcaseRelicSet("Prisioneiro", 2), ShowcaseRelicSet("Bandido", 2)))
        assertTrue(BuildAnalyzer.alertas(fora, metaComGates).none { "Conjunto" in it || "Ornamento" in it })

        // …and the whole build-level plan stays silent about them, since alertas seeds the farm plan.
        val plano = assertNotNull(BuildAnalyzer.analyze(fora, metaComGates)).farmPlan
        assertTrue(plano.none { "nenhum recomendado" in it }, plano.toString())
    }

    /**
     * A missed gate is a sentence and NOT a multiplier: our panel has no team buffs in it, and a
     * penalty computed off a number fribbels doesn't have would break the one promise the card
     * makes — that its percentages are theirs.
     */
    @Test
    fun `a missed breakpoint is reported without touching the nota`() {
        val curta = character(stats = listOf(ShowcaseStat("effect_hit", "Acerto de Efeito", "114.0%")))
        val alerta = BuildAnalyzer.alertas(curta, metaComGates).single()
        assertTrue("114%" in alerta && "120%" in alerta, alerta)

        val comGates = assertNotNull(BuildAnalyzer.analyze(curta, metaComGates))
        val semGates = assertNotNull(BuildAnalyzer.analyze(curta, meta))
        assertEquals(semGates.totalScore, comGates.totalScore, 1e-9)
        assertTrue("Acerto de Efeito" in comGates.farmPlan.first(), comGates.farmPlan.toString())
    }

    /** Enka builds the panel from the StarRailRes tables; a character they lag on gets none. */
    @Test
    fun `no combat panel means no breakpoint verdict`() {
        assertTrue(BuildAnalyzer.alertas(character(stats = emptyList()), metaComGates).isEmpty())
    }

    // -------------------- plano de farm -------------------- //

    private fun scored(
        slot: Int,
        score: Double,
        level: Int = 15,
        mainWeight: Double = 1.0,
        mainName: String = "x",
    ) = BuildAnalyzer.RelicScore(
        slot = slot, level = level, setName = "Set", mainName = mainName,
        mainWeight = mainWeight, score = score, percent = score,
        rank = FribbelsScorer.rating(score), subs = emptyList(),
    )

    /** Realistic ruler shape: an ideal main declared for all four selectable slots. */
    private val fullRuler = assertNotNull(
        FribbelsScorer.prepare(
            meta.copy(
                mainStats = mapOf(
                    3 to listOf("CriticalChanceBase", "HPAddedRatio"),
                    4 to listOf("SpeedDelta"),
                    5 to listOf("AttackAddedRatio"),
                    6 to listOf("SPRatioBase"),
                ),
            ),
        ),
    )

    @Test
    fun `farmPlan puts empty slots first and caps the list`() {
        val plan = BuildAnalyzer.farmPlan(
            relics = listOf(scored(slot = 3, score = 50.0, mainWeight = 0.5)),
            missingSlots = listOf(1, 2, 4, 5, 6),
            ruler = fullRuler,
        )
        assertEquals(3, plan.size)
        assertTrue(plan.all { "vazio" in it }, plan.toString())
    }

    @Test
    fun `farmPlan flags a wrong main with the ideal replacements, in the config's own order`() {
        val plan = BuildAnalyzer.farmPlan(
            relics = listOf(scored(slot = 3, score = 30.0, mainWeight = 0.0, mainName = "ATQ%")),
            missingSlots = emptyList(),
            ruler = fullRuler,
        )
        val line = plan.single()
        assertTrue("não serve" in line && "ATQ%" in line, line)
        assertTrue("Chance Crít./PV%" in line, line)
    }

    @Test
    fun `farmPlan suggests leveling an underleveled piece before anything else`() {
        val plan = BuildAnalyzer.farmPlan(
            relics = listOf(scored(slot = 4, score = 53.0, level = 8)),
            missingSlots = emptyList(),
            ruler = fullRuler,
        )
        assertTrue("+8 para +15" in plan.single(), plan.single())
    }

    @Test
    fun `farmPlan suggests a substat refarm for a sub-par piece whose main is acceptable`() {
        val plan = BuildAnalyzer.farmPlan(
            relics = listOf(scored(slot = 3, score = 40.0, mainWeight = 0.5)),
            missingSlots = emptyList(),
            ruler = fullRuler,
        )
        val line = plan.single()
        assertTrue("refarm" in line, line)
        assertTrue("Velocidade > Dano Crít." in line, line)
    }

    @Test
    fun `farmPlan is empty when nothing is worth farming, and render falls back to the weakest line`() {
        val relics = (1..6).map { scored(slot = it, score = 80.0) }
        assertTrue(BuildAnalyzer.farmPlan(relics, emptyList(), fullRuler).isEmpty())

        val report = BuildAnalyzer.BuildReport(
            relics = relics, missingSlots = emptyList(), totalScore = 80.0, totalRank = "SSS",
            weakest = relics.first(), farmPlan = emptyList(),
        )
        val out = BuildAnalyzer.render(acheron, report)
        assertTrue("Peça mais fraca" in out, out)
        assertTrue("Próximo farm" !in out, out)
    }

    @Test
    fun `render shows the farm plan section and the 0-10 scale`() {
        val report = BuildAnalyzer.BuildReport(
            relics = emptyList(), missingSlots = emptyList(), totalScore = 50.0, totalRank = "A",
            weakest = null, farmPlan = listOf("**Corpo**: subir de +8 para +15 já melhora a nota."),
        )
        val out = BuildAnalyzer.render(acheron, report)
        assertTrue("**Próximo farm:**" in out, out)
        assertTrue("+8 para +15" in out, out)
        assertTrue("Nota da build: 5,00/10 (A)" in out, out)
    }

    /**
     * The 0–10 the members read is the 0–100 with the decimal point moved, and nothing else: the
     * scorer already truncates to a tenth of a percent, so two decimals hold every value it can
     * produce. The golden Evernight percentages above are the ones spelled out here.
     */
    @Test
    fun `nota is the percentage shifted one decimal place, PT-BR, clamped at ten`() {
        assertEquals("6,53", BuildAnalyzer.nota(65.3))
        assertEquals("0,00", BuildAnalyzer.nota(0.0))
        assertEquals("9,99", BuildAnalyzer.nota(99.9))
        assertEquals("10,00", BuildAnalyzer.nota(100.0))
        // A nota out of ten never prints eleven; `@$%!` is what says the piece went past the ruler.
        assertEquals("10,00", BuildAnalyzer.nota(105.5))
        assertEquals("10,00", BuildAnalyzer.nota(111.9))
        assertEquals("@\$%!", FribbelsScorer.rating(111.9))
        // Below the clamp nothing collides: 1000 truncated tenths, 1000 distinct notas.
        val notas = (0..999).map { BuildAnalyzer.nota(it / 10.0) }
        assertEquals(1000, notas.distinct().size)
    }

    private val acheron = ShowcaseCharacter(
        id = "1308", name = "Acheron", level = 80, eidolon = 0,
        pathName = "Niilismo", elementName = "Raio", lightCone = null,
        relics = emptyList(), relicSets = emptyList(),
    )

    @Test
    fun `rating thresholds are fribbels' nineteen bands plus ours on top`() {
        // The rung the rescale opened: only a build that beat the reference relic gets here.
        assertEquals("@\$%!", FribbelsScorer.rating(100.0))
        assertEquals("@\$%!", FribbelsScorer.rating(124.9))
        assertEquals("???", FribbelsScorer.rating(99.9))
        assertEquals("???", FribbelsScorer.rating(90.0))
        assertEquals("SSS+", FribbelsScorer.rating(85.0))
        assertEquals("SSS", FribbelsScorer.rating(84.4))
        assertEquals("SS+", FribbelsScorer.rating(76.9))
        assertEquals("A", FribbelsScorer.rating(52.3))
        // The band fribbels skips: their ladder jumps F+ straight to D.
        assertEquals("E", FribbelsScorer.rating(10.0))
        assertEquals("F", FribbelsScorer.rating(0.1))
        // Not a bad grade — a refusal to grade. Zero, non-5★, and a useless main all land here.
        assertEquals("?", FribbelsScorer.rating(0.0))
        assertEquals("?", FribbelsScorer.rating(84.4, grade = 4))
        assertEquals("?", FribbelsScorer.rating(84.4, slot = 3, correctMain = false))
        assertEquals("SSS", FribbelsScorer.rating(84.4, slot = 1, correctMain = false))
    }

    @Test
    fun `MihomoParser maps the parsed API shape including 2pc+4pc set dedupe`() {
        val json = """
            {"player": {"nickname": "Heitor"},
             "characters": [{
               "id": "1308", "name": "Acheron", "level": 80, "rank": 1,
               "path": {"name": "Niilismo"}, "element": {"name": "Raio"},
               "light_cone": {"name": "Cone X", "rank": 2, "level": 80},
               "relics": [{
                 "name": "Chapéu", "type": 1, "set_name": "Set A", "rarity": 5, "level": 15,
                 "main_affix": {"type": "HPDelta", "name": "HP", "display": "705"},
                 "sub_affix": [{"type": "SpeedDelta", "name": "Velocidade", "display": "2.6", "count": 1, "step": 2}]
               }],
               "relic_sets": [
                 {"name": "Set A", "num": 2}, {"name": "Set A", "num": 4}, {"name": "Set B", "num": 2}
               ]
             }]}
        """.trimIndent()
        val profile = MihomoParser.parse(ObjectMapper().readTree(json))
        assertEquals("Heitor", profile.nickname)
        val c = profile.characters.single()
        assertEquals("Acheron", c.name)
        assertEquals(1, c.eidolon)
        assertEquals(2, c.lightCone?.superimposition)
        val r = c.relics.single()
        assertEquals(1, r.slot)
        assertEquals(5, r.grade)
        assertEquals("HPDelta", r.mainAffix?.type)
        assertEquals(1, r.subAffixes.single().count)
        // `step` matters as much as `count` and is the easier one to drop silently: without it the
        // scorer reads every roll as a low roll. Enka has this lock too (EnkaParserTest), and on a
        // network where Enka is unreachable the mihomo path is the ONLY one that runs.
        assertEquals(2, r.subAffixes.single().step)
        // One SPD roll at max quality: 2.0 + 2×0.3 = 2.6, one full 6.48-unit roll, over 36.288 → 17.8%
        assertEquals(17.8, BuildAnalyzer.scoreRelic(r, ruler).score)
        // 2pç+4pç of the same set collapse to the 4pç entry.
        assertEquals(listOf("Set A" to 4, "Set B" to 2), c.relicSets.map { it.name to it.pieces })
    }
}
