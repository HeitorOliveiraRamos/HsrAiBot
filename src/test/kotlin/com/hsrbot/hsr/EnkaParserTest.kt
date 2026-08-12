package com.hsrbot.hsr

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the Enka payload → [ShowcaseProfile] mapping, which is the whole risk of the migration:
 * Enka answers the raw game payload, so every field the fribbels formula eats is one we now
 * dig out ourselves instead of reading off mihomo's pre-chewed JSON.
 *
 * The fixture mirrors the real response shape (`detailInfo.avatarDetailList[].relicList[]` with
 * `_flat.props` + a parallel `subAffixList`), including the case that would silently zero a score
 * if mishandled: a substat with NO `step` key.
 */
class EnkaParserTest {

    private val mapper = ObjectMapper()

    private val root = mapper.readTree(
        javaClass.getResourceAsStream("/enka-showcase.json")!!.readAllBytes().decodeToString(),
    )

    /** Stands in for the DB + StarRailRes tables; ids match the fixture. */
    private val nomes = EnkaLookups(
        personagem = { id ->
            mapOf(
                "1407" to StarRailResNames.Personagem("Castorice", "A Recordação", "Quântico"),
            )[id] ?: StarRailResNames.Personagem(id, null, null)
        },
        conjunto = { mapOf("118" to "Poeta do Colapso do Luto", "310" to "Duran, Dinastia Dançante")[it] },
        cone = { mapOf("23037" to "Assim Falou Fósforo Branco")[it] },
    )

    private fun perfil() = EnkaParser.parse(root, nomes)

    @Test
    fun `maps the player and both showcased characters`() {
        val perfil = perfil()
        assertEquals("tore", perfil.nickname)
        assertEquals(listOf("1407", "1409"), perfil.characters.map { it.id })

        val castorice = perfil.characters.first()
        assertEquals("Castorice", castorice.name)
        assertEquals(80, castorice.level)
        // Enka calls the eidolon `rank`, and `rank` on the EQUIPMENT means superimposition —
        // crossing those two is the easy mistake this pins down.
        assertEquals(2, castorice.eidolon)
        assertEquals("A Recordação", castorice.pathName)
        assertEquals("Quântico", castorice.elementName)
    }

    @Test
    fun `carries the roll counts the score formula runs on`() {
        val cabeca = perfil().characters.first().relics.single { it.slot == 1 }

        assertEquals("HPDelta", cabeca.mainAffix?.type)
        assertEquals(15, cabeca.level)
        assertEquals("Poeta do Colapso do Luto", cabeca.setName)

        // props[1..] pair positionally with subAffixList — the substat TYPE comes from the former
        // and its rolls from the latter, so a mispairing shows up as the wrong count on the wrong
        // stat rather than as a crash.
        assertEquals(
            listOf("CriticalDamageBase" to (5 to 5), "SpeedDelta" to (2 to 1), "AttackAddedRatio" to (1 to 0)),
            cabeca.subAffixes.map { it.type to (it.count to it.step) },
        )
    }

    /**
     * Enka writes a substat bare and the same stat as a main with a `Base` suffix; the scorer keys
     * everything the suffixed way. Unmapped, the `CriticalChance` weight misses and the two substats
     * that decide most builds score 0 and print as dead rolls — with no crash and no log line to
     * show for it. The fixture carries Enka's REAL spelling on both sides so this stays pinned.
     */
    @Test
    fun `substat spellings are normalized onto the score table's keys, mains left alone`() {
        val relicas = perfil().characters.first().relics
        // Fixture: slot 1 carries `CriticalDamage`, slot 2 `CriticalChance` — both bare, as Enka sends.
        assertEquals(
            listOf("CriticalDamageBase", "SpeedDelta", "AttackAddedRatio"),
            relicas.single { it.slot == 1 }.subAffixes.map { it.type },
        )
        assertEquals("CriticalChanceBase", relicas.single { it.slot == 2 }.subAffixes.single().type)
        // A main already arrives suffixed and must NOT be double-suffixed.
        assertEquals("CriticalChanceBase", relicas.single { it.slot == 3 }.mainAffix?.type)
    }

    /** A substat that never rolled up has no `step` key at all; that is a real 0, not missing data. */
    @Test
    fun `a substat without a step key reads as step zero, not a dropped roll`() {
        val semStep = perfil().characters.first().relics
            .single { it.slot == 1 }.subAffixes.single { it.type == "AttackAddedRatio" }
        assertEquals(1, semStep.count)
        assertEquals(0, semStep.step)
    }

    @Test
    fun `counts set bonuses off the equipped pieces, ignoring lone ones`() {
        val castorice = perfil().characters.first()
        assertEquals(
            mapOf("Poeta do Colapso do Luto" to 4, "Duran, Dinastia Dançante" to 2),
            castorice.relicSets.associate { it.name to it.pieces },
        )

        // The second character wears a single piece of a set — no bonus, so no row, exactly as
        // mihomo reported it.
        assertTrue(perfil().characters[1].relicSets.isEmpty())
    }

    @Test
    fun `reads the light cone, keeping superimposition separate from level`() {
        val cone = perfil().characters.first().lightCone
        assertNotNull(cone)
        assertEquals("Assim Falou Fósforo Branco", cone!!.name)
        assertEquals(1, cone.superimposition)
        assertEquals(80, cone.level)
        // No equipment block at all on the second character.
        assertNull(perfil().characters[1].lightCone)
    }

    /**
     * The panel is summed from tables the fixture's lookups don't provide, so it stays empty here —
     * [StatPanelTest] owns the arithmetic. What this pins is that an absent promotions table yields
     * NO panel rather than a fabricated one.
     */
    @Test
    fun `no promotions table means no panel, not an invented one`() {
        assertTrue(perfil().characters.all { it.stats.isEmpty() })
    }

    /**
     * The panel reads `_flat.props` on a separate path from the substats, and it needs the same
     * `Base` normalization: a rolled `CriticalChance` must reach the same pile as the trace and set
     * tables' `CriticalChanceBase`, or crit reports as traces-only.
     */
    @Test
    fun `the panel normalizes relic prop spellings the same way substats are`() {
        val comTabelas = EnkaLookups(
            personagem = nomes.personagem,
            conjunto = nomes.conjunto,
            cone = nomes.cone,
            // Seven ascension rows, as the real table has — the fixture's character is at promotion 6.
            promocoes = { List(7) { mapOf("crit_rate" to StatPanel.Crescimento(0.05, 0.0)) } },
        )
        val painel = EnkaParser.parse(root, comTabelas).characters.first().stats
            .associate { it.field to it.display }
        // Fixture crit-rate rolls: 0.0648 (slot 2 sub) + 0.0324 (slot 5 sub); slot 3's 0.2916 is a
        // MAIN and already suffixed. 5 + 6.48 + 3.24 + 29.16 = 43.88 → all four reach one pile,
        // shown truncated as the game does it.
        assertEquals("43.8%", painel["crit_rate"])
    }

    @Test
    fun `an unknown character still lands on the card, under its id`() {
        assertEquals("1409", perfil().characters[1].name)
    }

    @Test
    fun `a hidden showcase is a profile with no characters, not an error`() {
        val escondido = mapper.readTree("""{"detailInfo":{"nickname":"tore","isDisplayAvatar":false}}""")
        val perfil = EnkaParser.parse(escondido, nomes)
        assertEquals("tore", perfil.nickname)
        assertTrue(perfil.characters.isEmpty())
    }

    /** The whole point of the migration: the scorer must read an Enka relic identically. */
    @Test
    fun `the parsed relic scores through BuildAnalyzer unchanged`() {
        val cabeca = perfil().characters.first().relics.single { it.slot == 1 }
        val ruler = FribbelsScorer.prepare(
            FribbelsMeta(
                subWeights = mapOf("CriticalDamageBase" to 1.0, "SpeedDelta" to 1.0),
                mainStats = emptyMap(), relicSets = emptyList(),
                ornamentSets = emptyList(), substatPriority = emptyList(),
            ),
        )!!
        // subs: CD 5.184×5 + 0.648×5 = 29.16 points = 29.16 units; SPD 2×2.0 + 1×0.3 = 4.3 points,
        // worth 6.48/2.6 each = 10.72 units. Head's main is flat PV, worth nothing here and not a
        // stat the piece could roll away, so the ideal keeps both: 0.8 × (6×6.48 + 6.48) = 36.288.
        val nota = BuildAnalyzer.scoreRelic(cabeca, ruler)
        assertEquals(109.8, nota.score)
        // AttackAddedRatio carries no weight here, so it is a dead roll and must be named as one.
        assertEquals(listOf("AttackAddedRatio"), nota.subs.filterNot { it.util }.map { it.prop })
    }

    /** `tid` is `<rarity+1>…`: without the offset every 5★ piece would be scored as a 6★ (i.e. on
     *  the 5★ fallback) and, worse, graded `?` for not being 5★. */
    @Test
    fun `relic rarity comes off the tid`() {
        assertTrue(perfil().characters.flatMap { it.relics }.all { it.grade == 5 })
    }
}
