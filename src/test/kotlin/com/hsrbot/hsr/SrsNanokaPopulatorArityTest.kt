package com.hsrbot.hsr

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `personagem_hsr` is written with 59 positional bind parameters. The column list and the value
 * list live in two different places, so adding a column to one and forgetting the other is the
 * realistic mistake — and without this it would only ever show up as a SQL error in the middle of
 * a live populate run. Pins the two together; no database needed.
 */
class SrsNanokaPopulatorArityTest {

    @Test
    fun `personagem bind values match the column list one-for-one`() {
        // Deliberately sparse: the params builder must pad missing traces/eidolons/stories to a
        // fixed width, so an empty character has to produce exactly as many values as a full one.
        val sparse = PersonagemHsr(characterId = "1005")
        assertEquals(
            SrsNanokaPopulator.PERSONAGEM_COLS.size,
            SrsNanokaPopulator.personagemParams(sparse, 1005).size,
            "PERSONAGEM_COLS and personagemParams have drifted apart",
        )

        val full = PersonagemHsr(
            characterId = "1005",
            tracos = List(3) { NamedText("t$it", "d$it") },
            eidolons = List(6) { NamedText("e$it", "d$it") },
            historias = List(4) { "h$it" },
            arteFigura = "a".repeat(64),
            iconeMini = "b".repeat(64),
        )
        assertEquals(
            SrsNanokaPopulator.PERSONAGEM_COLS.size,
            SrsNanokaPopulator.personagemParams(full, 1005).size,
        )
    }

    @Test
    fun `the appended columns are the tail, in the order the values are appended`() {
        // Every migration since V20 APPENDS to this tail so the earlier positional indexes keep
        // their meaning — which is exactly what this pins. Sized off the list itself, so adding a
        // column means extending it here and nothing else.
        val esperado = listOf(
            // V20
            "arte_figura", "arte_completa", "icone_mini", "icone_elemento", "icone_caminho",
            "icone_atq_basico", "icone_pericia", "icone_pericia_suprema", "icone_talento",
            "icone_pericia_euforia",
            // V21
            "arte_retrato", "arte_fundo",
            // V27
            "icone_talento_memoespirito", "icone_pericia_memoespirito",
            // V30
            "custos_melhoria",
        )
        assertEquals(esperado, SrsNanokaPopulator.PERSONAGEM_COLS.takeLast(esperado.size))

        // The same values, in the same order, must land in the tail of the bind list.
        val p = PersonagemHsr(
            characterId = "1", arteFigura = "1", arteCompleta = "2", iconeMini = "3",
            iconeElemento = "4", iconeCaminho = "5", iconeAtqBasico = "6", iconePericia = "7",
            iconePericiaSuprema = "8", iconeTalento = "9", iconePericiaEuforia = "10",
            arteRetrato = "11", arteFundo = "12",
            iconeTalentoMemoespirito = "13", iconePericiaMemoespirito = "14",
            custosMelhoria = CustosMelhoria(personagem = listOf(CustoMaterial(29328, 308_000))),
        )
        val cauda = SrsNanokaPopulator.personagemParams(p, 1).takeLast(esperado.size)
        assertEquals((1..14).map { it.toString() }, cauda.dropLast(1))
        // The JSONB column carries serialised JSON, not the object — the column takes it through the
        // `?::jsonb` cast, and a bound object would fail at the driver.
        assertEquals("""{"personagem":[{"id":29328,"qtd":308000}],"rastros":[]}""", cauda.last())
    }
}
