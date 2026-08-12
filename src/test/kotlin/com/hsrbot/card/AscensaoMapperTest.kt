package com.hsrbot.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the `personagem_hsr` row → [Ascensao] mapping: the two grids keep the stored order, each
 * cell picks up its icon from the `materiais` dictionary, and a row with nothing stored produces no
 * card at all. No database — the resolver's own SQL is one SELECT, the mapping is where the rules are.
 */
class AscensaoMapperTest {

    private val icones = mapOf(29328 to "credito-hash", 409960 to "livro-hash", 267806 to "boss-hash")

    private fun linha(custos: String?) = mapOf(
        "nome" to "Blade",
        "nome_en" to "Blade",
        "elemento" to "Vento",
        "caminho" to "A Destruição",
        "raridade" to 5,
        "arte_retrato" to "retrato-hash",
        "custos_melhoria" to custos,
    )

    @Test
    fun `as duas grades mantem a ordem guardada e pegam o icone pelo id`() {
        val a = ascensao(
            linha(
                """
                {"personagem":[{"id":29328,"qtd":308000},{"id":409960,"qtd":290},{"id":267806,"qtd":65}],
                 "rastros":[{"id":29328,"qtd":3000000}]}
                """.trimIndent(),
            ),
            icones,
        )!!
        assertEquals("Blade", a.nome)
        assertEquals(5, a.raridade)
        assertEquals("retrato-hash", a.arte.retrato)
        assertEquals(listOf(308_000L, 290L, 65L), a.personagem.map { it.qtd })
        assertEquals(listOf("credito-hash", "livro-hash", "boss-hash"), a.personagem.map { it.icone })
        assertEquals(listOf(3_000_000L), a.rastros.map { it.qtd })
    }

    @Test
    fun `a arte e a ilustracao inteira, enquadrada pelo arte_foco`() {
        // The card draws `arte_completa` placed by the curated box, exactly like the build card's v2
        // layout — the bust is only what is left when a character has no box.
        val custos = """{"personagem":[{"id":29328,"qtd":1}],"rastros":[]}"""
        val comFoco = linha(custos) + mapOf(
            "arte_completa" to "completa-hash",
            "arte_foco_x" to 0.2, "arte_foco_y" to 0.1, "arte_foco_largura" to 0.3, "arte_foco_altura" to 0.8,
        )
        val a = ascensao(comFoco, icones)!!
        assertEquals("completa-hash", a.arte.completa)
        assertEquals(Foco(0.2, 0.1, 0.3, 0.8), a.arte.foco)
        assertTrue(a.arte.podeV2)
        // And a character nobody has framed falls back instead of guessing where they are.
        assertFalse(ascensao(linha(custos), icones)!!.arte.podeV2)
    }

    @Test
    fun `um material fora do dicionario vira celula sem icone, nunca um erro`() {
        val a = ascensao(linha("""{"personagem":[{"id":999999,"qtd":7}],"rastros":[]}"""), icones)!!
        assertEquals(7L, a.personagem.single().qtd)
        assertNull(a.personagem.single().icone, "o card desenha o soquete vazio; nada pode estourar aqui")
    }

    @Test
    fun `sem custos guardados nao existe card`() {
        // The two betas srs hasn't published. Null, not an empty card: two blank panels read as a
        // rendering bug, and the command has a better answer than showing one.
        assertNull(ascensao(linha(null), icones))
        assertNull(ascensao(linha("""{"personagem":[],"rastros":[]}"""), icones))
        // And a column that somehow holds junk degrades the same way instead of throwing.
        assertNull(ascensao(linha("nao é json"), icones))
    }
}
