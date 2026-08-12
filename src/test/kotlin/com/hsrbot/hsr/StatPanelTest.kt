package com.hsrbot.hsr

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The panel Enka doesn't compute. Every number here is worked out by hand in the comments, because
 * the failure mode is not a crash — it is a card that shows a plausible, wrong HP total.
 */
class StatPanelTest {

    private val promocoes = listOf(
        mapOf(
            "hp" to StatPanel.Crescimento(base = 100.0, step = 10.0),
            "atk" to StatPanel.Crescimento(base = 50.0, step = 5.0),
            "def" to StatPanel.Crescimento(base = 40.0, step = 4.0),
            "spd" to StatPanel.Crescimento(base = 95.0, step = 0.0),
            "crit_rate" to StatPanel.Crescimento(base = 0.05, step = 0.0),
            "crit_dmg" to StatPanel.Crescimento(base = 0.5, step = 0.0),
        ),
    )

    private fun painel(
        tracos: List<StatPanel.Prop> = emptyList(),
        coneBase: List<StatPanel.Prop> = emptyList(),
        reliquias: List<StatPanel.Prop> = emptyList(),
        conjuntos: List<StatPanel.Prop> = emptyList(),
        conePassivo: List<StatPanel.Prop> = emptyList(),
        nivel: Int = 11,
    ) = StatPanel.compute(nivel, 0, promocoes, tracos, coneBase, reliquias, conjuntos, conePassivo)
        .associate { it.field to it.display }

    /**
     * The order that matters: a percentage scales the character's base PLUS the cone's, and the flat
     * roll lands after. Getting it backwards inflates every build running a 5-star cone.
     */
    @Test
    fun `percentages scale the character and cone base together, flats land after`() {
        val p = painel(
            coneBase = listOf(StatPanel.Prop("BaseHP", 100.0)),
            reliquias = listOf(StatPanel.Prop("HPAddedRatio", 0.5), StatPanel.Prop("HPDelta", 50.0)),
        )
        // char base 100 + 10×10 = 200, + cone 100 = 300 · ×1.5 = 450 · + 50 flat = 500
        assertEquals("500", p["hp"])
    }

    /** Crit's 5%/50% floor comes from the promotions table and must be in the total. */
    @Test
    fun `crit starts from the character floor, not from zero`() {
        val p = painel(reliquias = listOf(StatPanel.Prop("CriticalChanceBase", 0.2)))
        assertEquals("25.0%", p["crit_rate"]) // 5% floor + 20% rolled
        assertEquals("50.0%", p["crit_dmg"]) // untouched floor still shows
    }

    /** Traces, relics and set bonuses are one pile — the panel can't tell them apart, nor should it. */
    @Test
    fun `traces, relics and set bonuses all sum into the same stat`() {
        val p = painel(
            tracos = listOf(StatPanel.Prop("CriticalChanceBase", 0.027)),
            reliquias = listOf(StatPanel.Prop("CriticalChanceBase", 0.1)),
            conjuntos = listOf(StatPanel.Prop("CriticalChanceBase", 0.08)),
        )
        assertEquals("25.7%", p["crit_rate"]) // 5 + 2.7 + 10 + 8
    }

    /** A bonus-only row appears only when the build has it — the game omits an empty one too. */
    @Test
    fun `bonus-only rows appear only when the build actually has them`() {
        assertTrue("quantum_dmg" !in painel())
        assertEquals("38.8%", painel(reliquias = listOf(StatPanel.Prop("QuantumAddedRatio", 0.388)))["quantum_dmg"])
    }

    /** Energy regen is a multiplier on 100%, so a +5% rope reads 105%, never 5%. */
    @Test
    fun `energy regen counts up from one hundred percent`() {
        assertEquals("105.0%", painel(reliquias = listOf(StatPanel.Prop("SPRatioBase", 0.05)))["sp_rate"])
    }

    /** A cone's HP/ATK/DEF base is not speed's business; its flat rolls are. */
    @Test
    fun `speed ignores the cone base and takes its flat rolls`() {
        val p = painel(
            coneBase = listOf(StatPanel.Prop("BaseHP", 1000.0)),
            reliquias = listOf(StatPanel.Prop("SpeedDelta", 25.0)),
        )
        assertEquals("120", p["spd"]) // 95 + 25
    }

    /**
     * Aglaea's cone (Tempo Tecido em Ouro, 23036) grants "+12 BASE SPD" — a `BaseSpeed` prop, which
     * the panel dropped on the floor entirely: uid 600532576 read 150 against enka's 162. Base, not
     * flat, so a SPD% set scales it: (95 + 12) × 1.06 + 25 = 138.42.
     */
    @Test
    fun `a cone passive granting base speed lands before the percentage`() {
        val p = painel(
            conjuntos = listOf(StatPanel.Prop("SpeedAddedRatio", 0.06)),
            reliquias = listOf(StatPanel.Prop("SpeedDelta", 25.0)),
            conePassivo = listOf(StatPanel.Prop("BaseSpeed", 12.0)),
        )
        assertEquals("138", p["spd"])
    }

    /**
     * The whole build, checked against enka.network's own card for uid 600281322 / Noite Eterna
     * (1413) — the numbers that caught the two bugs this test now guards.
     *
     * Base HP 2483.71 = character 1319.472 + cone 1164.24. HP% = 1.52352 from relics and traces,
     * PLUS 0.30 from the cone's S1 passive, which was the missing 745 HP. 2483.71 × 2.82352 +
     * 777.574 = 7790.4 → 7790, exactly what enka prints.
     */
    @Test
    fun `matches enka's own card for a real build`() {
        val p = StatPanel.compute(
            nivel = 80,
            promocao = 6,
            promocoes = List(7) {
                mapOf(
                    "hp" to StatPanel.Crescimento(base = 693.792, step = 7.9200000000000005),
                    "crit_dmg" to StatPanel.Crescimento(base = 0.5, step = 0.0),
                )
            },
            tracos = emptyList(),
            coneBase = listOf(StatPanel.Prop("BaseHP", 1164.24)),
            reliquias = listOf(
                StatPanel.Prop("HPAddedRatio", 1.52352),
                StatPanel.Prop("HPDelta", 777.57383),
                StatPanel.Prop("CriticalDamageBase", 1.90852),
            ),
            conjuntos = emptyList(),
            conePassivo = listOf(StatPanel.Prop("HPAddedRatio", 0.3)),
        ).associate { it.field to it.display }

        assertEquals("7790", p["hp"])
        // 240.852% — the game truncates, so 240.8 and not the 240.9 that rounding gives.
        assertEquals("240.8%", p["crit_dmg"])
    }

    /** Truncation is the game's own behaviour and differs from rounding on half the rows. */
    @Test
    fun `percentages truncate rather than round`() {
        val p = painel(reliquias = listOf(StatPanel.Prop("IceAddedRatio", 0.388803)))
        assertEquals("38.8%", p["ice_dmg"]) // 38.8803 → 38.8, never 38.9
    }

    /** A character the promotions table hasn't caught up with gets no panel, not a wrong one. */
    @Test
    fun `an unknown ascension yields no panel rather than a fabricated total`() {
        assertTrue(StatPanel.compute(80, 6, promocoes, listOf(), listOf(), listOf(), listOf()).isEmpty())
    }

    /**
     * The real tables, real shapes: StarRailRes' own JSON parsed by the same code the refresh uses.
     * Pins that `properties` is `[[2pc], [4pc]]` and that a conditional 4pc is an empty list.
     */
    @Test
    fun `parses StarRailRes set bonuses, keeping 2pc and 4pc apart`() {
        val root = ObjectMapper().readTree(
            """{"118":{"name":"Relojoeiro","properties":[[{"type":"BreakDamageAddedRatioBase","value":0.16}],[]]},
                "310":{"name":"Duran","properties":[[{"type":"AttackAddedRatio","value":0.12}],[]]}}""",
        )
        val tabela = StarRailResNames.porNivel(root)
        assertEquals(listOf(StatPanel.Prop("BreakDamageAddedRatioBase", 0.16)), tabela.getValue("118")[0])
        // The 4pc fires only after an ultimate, so the game's panel doesn't count it and neither do we.
        assertTrue(tabela.getValue("118")[1].isEmpty())
    }

    @Test
    fun `parses StarRailRes promotions into per-level growth`() {
        val root = ObjectMapper().readTree(
            """{"1407":{"values":[{"hp":{"base":753.984,"step":11.088}},{"hp":{"base":800.0,"step":12.0}}]}}""",
        )
        val tabela = StarRailResNames.promocoesDe(root)
        assertEquals(StatPanel.Crescimento(753.984, 11.088), tabela.getValue("1407")[0].getValue("hp"))
        assertEquals(2, tabela.getValue("1407").size)
    }

    /** A skill-level node carries no properties; it must sum to nothing rather than blow up. */
    @Test
    fun `parses trace nodes, including the ones that grant no stats`() {
        val root = ObjectMapper().readTree(
            """{"1407201":{"levels":[{"properties":[{"type":"CriticalChanceBase","value":0.027}]}]},
                "1407002":{"levels":[{"properties":[]},{"properties":[]}]}}""",
        )
        val tabela = StarRailResNames.tracosDe(root)
        assertEquals(listOf(StatPanel.Prop("CriticalChanceBase", 0.027)), tabela.getValue("1407201")[0])
        assertTrue(tabela.getValue("1407002")[0].isEmpty())
    }
}
