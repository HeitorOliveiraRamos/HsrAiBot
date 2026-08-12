package com.hsrbot.hsr

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the pure TS-parsing contract of the fribbels harvest against fixtures shaped
 * exactly like the machine-formatted repo files (a DPS with `simulation()` and a support
 * without one), plus the enum/property mappings and the JSONB round-trip.
 */
class FribbelsHarvesterTest {

    // Condensed from src/lib/conditionals/character/1400/Castorice.ts — real structure.
    private val dpsTs = """
        const conditionals = { id: 'not-a-char-id', teammates: [{ characterId: Cyrene.id }] }

        const simulation = (): SimulationMetadata => ({
          parts: {
            [Parts.Body]: [
              Stats.CR,
              Stats.CD,
            ],
          },
          substats: [
            Stats.CD,
            Stats.CR,
            Stats.HP_P,
          ],
          hardBreakpoints: [
            { stat: Stats.EHR, threshold: 1.20 },
          ],
          softBreakpoints: [
            { stat: Stats.SPD, threshold: 160 },
          ],
          relicSets: [
            [Sets.PoetOfMourningCollapse, Sets.PoetOfMourningCollapse],
            ...SPREAD_RELICS_4P_GENERAL_CONDITIONALS,
          ],
          ornamentSets: [
            Sets.BoneCollectionsSereneDemesne,
            ...SPREAD_ORNAMENTS_2P_GENERAL_CONDITIONALS,
          ],
          teammates: [
            {
              characterId: Cyrene.id,
              lightCone: ThisLoveForever.id,
            },
          ],
        })

        const scoring = (): ScoringMetadata => ({
          stats: {
            [Stats.ATK]: 0,
            [Stats.HP]: 1,
            [Stats.HP_P]: 1,
            [Stats.CR]: 1,
            [Stats.CD]: 1,
            [Stats.SPD]: 0,
          },
          parts: {
            [Parts.Body]: [
              Stats.CR,
              Stats.CD,
              Stats.HP_P,
            ],
            [Parts.Feet]: [
              Stats.HP_P,
            ],
            [Parts.PlanarSphere]: [
              Stats.HP_P,
              Stats.Quantum_DMG,
            ],
            [Parts.LinkRope]: [
              Stats.HP_P,
            ],
          },
          simulation: simulation(),
        })

        const display = { imageCenter: { x: 875 } }

        export const Castorice: CharacterConfig = {
          id: '1407',
          defaultLightCone: MakeFarewellsMoreBeautiful.id,
        }
    """.trimIndent()

    // Support without a simulation block (Asta-like).
    private val supportTs = """
        const scoring = (): ScoringMetadata => ({
          stats: {
            [Stats.SPD]: 1,
            [Stats.DEF]: 0.25,
          },
          parts: {
            [Parts.Body]: [],
            [Parts.Feet]: [
              Stats.SPD,
            ],
            [Parts.LinkRope]: [
              Stats.ATK_P,
              Stats.ERR,
            ],
          },
        })

        const display = { disableSpine: true }

        export const Asta: CharacterConfig = {
          id: '1009',
        }
    """.trimIndent()

    /** fribbels' scoringConstants.ts, including the nesting that makes expansion recursive. */
    private val constantsTs = """
        import { Sets } from 'lib/constants/constants'

        export const SPREAD_RELICS_4P_SUPPORT = [
          [Sets.SacerdosRelivedOrdeal, Sets.SacerdosRelivedOrdeal],
          [Sets.MessengerTraversingHackerspace, Sets.MessengerTraversingHackerspace],
        ]

        export const SPREAD_RELICS_4P_GENERAL_CONDITIONALS = [
          [Sets.EagleOfTwilightLine, Sets.EagleOfTwilightLine],
        ]

        export const SPREAD_RELICS_4P_HEAL = [
          ...SPREAD_RELICS_4P_SUPPORT,
          [Sets.PasserbyOfWanderingCloud, Sets.PasserbyOfWanderingCloud],
        ]

        export const SPREAD_ORNAMENTS_2P_GENERAL_CONDITIONALS = [
          Sets.SigoniaTheUnclaimedDesolation,
          Sets.ArcadiaOfWovenDreams,
        ]
    """.trimIndent()

    private val spreads = FribbelsHarvester.parseSpreads(constantsTs)

    @Test
    fun `parseCharacter extracts scoring and simulation from a DPS config`() {
        val parsed = FribbelsHarvester.parseCharacter(dpsTs, spreads)!!
        assertEquals(listOf("1407"), parsed.ids)
        assertEquals(mapOf("ATK" to 0.0, "HP" to 1.0, "HP_P" to 1.0, "CR" to 1.0, "CD" to 1.0, "SPD" to 0.0), parsed.stats)
        // scoring parts win over the simulation's own parts block
        assertEquals(listOf("CR", "CD", "HP_P"), parsed.parts["Body"])
        assertEquals(listOf("HP_P", "Quantum_DMG"), parsed.parts["PlanarSphere"])
        // The literal entries stay in front of the spread ones: fribbels' benchmark defaults to
        // `relicSets[0]`/`ornamentSets[0]`, so the order is load-bearing.
        assertEquals(
            listOf(
                "PoetOfMourningCollapse" to "PoetOfMourningCollapse",
                "EagleOfTwilightLine" to "EagleOfTwilightLine",
            ),
            parsed.relicSets,
        )
        assertEquals(
            listOf("BoneCollectionsSereneDemesne", "SigoniaTheUnclaimedDesolation", "ArcadiaOfWovenDreams"),
            parsed.ornamentSets,
        )
        assertEquals(listOf("CD", "CR", "HP_P"), parsed.substats)
        // Hard gates first, soft after — the order `distinctBy` relies on if a stat ever has both.
        assertEquals(listOf("EHR" to 1.20, "SPD" to 160.0), parsed.breakpoints)
    }

    @Test
    fun `parseCharacter handles a support without simulation`() {
        val parsed = FribbelsHarvester.parseCharacter(supportTs)!!
        assertEquals(listOf("1009"), parsed.ids)
        assertEquals(mapOf("SPD" to 1.0, "DEF" to 0.25), parsed.stats)
        assertEquals(emptyList(), parsed.parts["Body"])
        assertEquals(listOf("ATK_P", "ERR"), parsed.parts["LinkRope"])
        assertTrue(parsed.relicSets.isEmpty())
        assertTrue(parsed.ornamentSets.isEmpty())
        assertTrue(parsed.substats.isEmpty())
    }

    @Test
    fun `parseCharacter returns null without a config id`() {
        assertNull(FribbelsHarvester.parseCharacter("const scoring = () => ({ stats: {} })"))
        // The *B1.ts reruns: a config id like '1217b1' has no showcase to join to, so the whole
        // file is skipped rather than half-parsed onto the base character.
        assertNull(FribbelsHarvester.parseCharacter("export const HuohuoB1: CharacterConfig = {\n  id: '1217b1',\n}"))
    }

    /**
     * The five Desbravador files each export TWO configs — Caelus and Stelle — off ONE `scoring()`.
     * Reading only the last id used to drop one of each pair, which left five of the roster with no
     * ruler at all and so no `/build` and no `/rank` line.
     */
    @Test
    fun `both ids of a two-config file share the same scoring block`() {
        val ts = """
            const scoring = (): ScoringMetadata => ({
              stats: {
                [Stats.DEF_P]: 1,
              },
              flatMainstatBoost: Stats.DEF,
              parts: {
                [Parts.Body]: [
                  Stats.DEF_P,
                ],
              },
            })

            export const TrailblazerPreservationCaelus: CharacterConfig = {
              id: '8003',
              defaultLightCone: LandausChoice.id,
            }

            export const TrailblazerPreservationStelle: CharacterConfig = {
              id: '8004',
              defaultLightCone: LandausChoice.id,
            }
        """.trimIndent()
        val parsed = FribbelsHarvester.parseCharacter(ts)!!
        assertEquals(listOf("8003", "8004"), parsed.ids)
        assertEquals("DEF", parsed.flatMainstatBoost)
        assertEquals("DefenceDelta", parsed.toMeta(emptyMap(), emptyMap()).flatMainstatBoost)
    }

    @Test
    fun `a config without flatMainstatBoost yields none`() {
        assertNull(FribbelsHarvester.parseCharacter(dpsTs)!!.flatMainstatBoost)
    }

    @Test
    fun `parseSetsEnum reads names including escaped quotes`() {
        val ts = """
            export const OtherEnum = { Ignored: 'nope' } as const
            export const Sets = {
              PasserbyOfWanderingCloud: 'Passerby of Wandering Cloud',
              BoneCollectionsSereneDemesne: 'Bone Collection\'s Serene Demesne',
            } as const
        """.trimIndent()
        val sets = FribbelsHarvester.parseSetsEnum(ts)
        assertEquals("Passerby of Wandering Cloud", sets["PasserbyOfWanderingCloud"])
        assertEquals("Bone Collection's Serene Demesne", sets["BoneCollectionsSereneDemesne"])
        assertNull(sets["Ignored"])
    }

    @Test
    fun `toMeta maps enum keys to game properties and PT set names`() {
        val parsed = FribbelsHarvester.parseCharacter(dpsTs)!!
        val meta = parsed.toMeta(
            setsEnum = mapOf(
                "PoetOfMourningCollapse" to "Poet of Mourning Collapse",
                "BoneCollectionsSereneDemesne" to "Bone Collection's Serene Demesne",
            ),
            setPtByEn = mapOf("Poet of Mourning Collapse" to "Poeta do Colapso do Luto"),
        )
        assertEquals(1.0, meta.subWeights["CriticalChanceBase"])
        assertEquals(0.0, meta.subWeights["AttackDelta"])
        assertEquals(listOf("CriticalChanceBase", "CriticalDamageBase", "HPAddedRatio"), meta.mainStats[3])
        assertEquals(listOf("HPAddedRatio", "QuantumAddedRatio"), meta.mainStats[5])
        // pt name when StarRailRes maps it, en fallback otherwise
        assertEquals(listOf(listOf("Poeta do Colapso do Luto", "Poeta do Colapso do Luto")), meta.relicSets)
        assertEquals(listOf("Bone Collection's Serene Demesne"), meta.ornamentSets)
        assertEquals(listOf("CriticalDamageBase", "CriticalChanceBase", "HPAddedRatio"), meta.substatPriority)
        assertEquals(
            listOf(
                FribbelsMeta.Breakpoint("StatusProbabilityBase", 1.20),
                FribbelsMeta.Breakpoint("SpeedDelta", 160.0),
            ),
            meta.breakpoints,
        )
    }

    /**
     * Every config that declares sets spreads at least one `SPREAD_*` constant, so reading only the
     * literal entries captured 66% of the relic pairs and 37% of the ornaments — Tribbie listed 1
     * of her 11 ornaments. Nesting (`HEAL` spreads `SUPPORT`) and the literal-plus-spread overlap
     * that needs deduping are both real shapes upstream, not hypotheticals.
     */
    @Test
    fun `spread constants are expanded, nested, and deduped`() {
        val ts = """
            const simulation = (): SimulationMetadata => ({
              relicSets: [
                [Sets.SacerdosRelivedOrdeal, Sets.SacerdosRelivedOrdeal],
                ...SPREAD_RELICS_4P_HEAL,
              ],
              ornamentSets: [
                ...SPREAD_ORNAMENTS_2P_GENERAL_CONDITIONALS,
              ],
            })

            const scoring = (): ScoringMetadata => ({ stats: { [Stats.SPD]: 1 } })

            export const Hyacine: CharacterConfig = {
              id: '1409',
            }
        """.trimIndent()
        val parsed = FribbelsHarvester.parseCharacter(ts, spreads)!!
        // Sacerdos is named literally AND lives inside SPREAD_RELICS_4P_SUPPORT, which HEAL nests:
        // once each, first occurrence winning.
        assertEquals(
            listOf("SacerdosRelivedOrdeal", "MessengerTraversingHackerspace", "PasserbyOfWanderingCloud"),
            parsed.relicSets.map { it.first },
        )
        assertEquals(listOf("SigoniaTheUnclaimedDesolation", "ArcadiaOfWovenDreams"), parsed.ornamentSets)
    }

    /** An unknown constant inlines to nothing rather than surviving as a fake `Sets.` reference. */
    @Test
    fun `an unresolved spread drops out`() {
        assertEquals(
            "[Sets.A, Sets.A],\n",
            FribbelsHarvester.expandSpreads("[Sets.A, Sets.A],\n...SPREAD_NOT_HARVESTED", emptyMap()),
        )
    }

    @Test
    fun `a config without breakpoints yields none`() {
        assertTrue(FribbelsHarvester.parseCharacter(supportTs)!!.breakpoints.isEmpty())
    }

    @Test
    fun `FribbelsMeta survives the JSONB round-trip`() {
        val mapper = ObjectMapper()
        val meta = FribbelsMeta(
            subWeights = mapOf("CriticalChanceBase" to 1.0, "SpeedDelta" to 0.5),
            mainStats = mapOf(3 to listOf("CriticalChanceBase"), 6 to listOf("HPAddedRatio")),
            relicSets = listOf(listOf("A", "A"), listOf("B", "C")),
            ornamentSets = listOf("X"),
            substatPriority = listOf("CriticalDamageBase"),
            breakpoints = listOf(FribbelsMeta.Breakpoint("StatusProbabilityBase", 0.75)),
        )
        assertEquals(meta, FribbelsMeta.fromJson(mapper.readTree(meta.toJson(mapper))))
        // Rows harvested before breakpoints existed simply carry none — no migration, no backfill.
        val antigo = mapper.readTree("""{"subWeights": {"SpeedDelta": 1.0}}""")
        assertTrue(FribbelsMeta.fromJson(antigo).breakpoints.isEmpty())
    }

    /** `hsr_build_meta.ajuste` (V37): objects merge key by key, lists swap whole, null is a no-op. */
    @Test
    fun `the hand-written ajuste patches one field without restating the ruler`() {
        val mapper = ObjectMapper()
        val fribbels = FribbelsMeta(
            subWeights = mapOf("CriticalChanceBase" to 1.0, "SpeedDelta" to 1.0),
            mainStats = mapOf(3 to listOf("CriticalChanceBase"), 4 to listOf("AttackAddedRatio")),
            relicSets = listOf(listOf("A", "A")),
            ornamentSets = listOf("X", "Y"),
            substatPriority = listOf("CriticalDamageBase"),
        ).toJson(mapper)

        fun comAjuste(patch: String?) = FribbelsMeta.fromJson(
            FribbelsMeta.merge(mapper.readTree(fribbels), patch?.let(mapper::readTree)),
        )

        assertEquals(FribbelsMeta.fromJson(mapper.readTree(fribbels)), comAjuste(null))

        val pesos = comAjuste("""{"subWeights": {"SpeedDelta": 0.5}}""")
        assertEquals(mapOf("CriticalChanceBase" to 1.0, "SpeedDelta" to 0.5), pesos.subWeights)
        // Untouched keys survive the patch — that's the whole point of merging instead of replacing.
        assertEquals(listOf("X", "Y"), pesos.ornamentSets)

        // Slot 4 alone; slot 3 stays. A patched slot is replaced, not appended to.
        val mains = comAjuste("""{"mainStats": {"4": ["SpeedDelta"]}}""").mainStats
        assertEquals(mapOf(3 to listOf("CriticalChanceBase"), 4 to listOf("SpeedDelta")), mains)

        // Lists swap whole rather than merging by index.
        assertEquals(listOf("Z"), comAjuste("""{"ornamentSets": ["Z"]}""").ornamentSets)
    }

    /**
     * The configs give a flat stat the same weight as its percent counterpart and fribbels' own
     * `applyFlatStatScaling` rewrites it to 40% of that counterpart before scoring anything —
     * unconditionally, so a config weighting flat ATK with no ATK% ends up at 0. Harvesting the
     * config verbatim paid 2.5× for every flat roll, so the rewrite has to happen on our side too.
     */
    @Test
    fun `prepare rewrites flat stats to 40 percent of their percent counterpart`() {
        val meta = FribbelsMeta(
            subWeights = mapOf(
                "CriticalDamageBase" to 1.0, "CriticalChanceBase" to 1.0,
                "HPAddedRatio" to 1.0, "HPDelta" to 0.5, "AttackDelta" to 0.7,
            ),
            mainStats = mapOf(3 to listOf("CriticalChanceBase")),
            relicSets = emptyList(),
            ornamentSets = emptyList(),
            substatPriority = emptyList(),
        )
        val ruler = FribbelsScorer.prepare(meta)!!
        // Flat PV came in at 0.5 and is rewritten from PV% instead: 1.0 × 0.4.
        assertEquals(0.4, ruler.stats["HPDelta"]!!, 1e-9)
        // No ATQ%/DEF% weight to derive from, so their flat halves are destroyed, 0.7 and all.
        assertEquals(0.0, ruler.stats["AttackDelta"]!!, 1e-9)
        assertEquals(0.0, ruler.stats["DefenceDelta"]!!, 1e-9)
        // Weighted stats are untouched, and the ideal is the pool minus the slot-3 main, at the
        // reference relic's minimum rolls: 0.8 × (6×6.48 (CD) + 6.48 (PV%) + 2.592 (PV) + 0).
        assertEquals(1.0, ruler.stats["CriticalDamageBase"]!!, 1e-9)
        assertEquals(0.8 * 47.952, FribbelsScorer.computeOptimalScore(3, "CriticalChanceBase", ruler), 1e-9)
        assertNull(FribbelsScorer.prepare(meta.copy(subWeights = mapOf("HPDelta" to 0.9))))
    }
}
