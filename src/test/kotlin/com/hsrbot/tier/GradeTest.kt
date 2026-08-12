package com.hsrbot.tier

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The grade is the whole data model, and two of its rules are the ones the design argument turned
 * on: a character sits in ONE tier per papel, and in as MANY papéis as the author wants. Both are
 * properties of these functions plus the wizard's option filtering, so they get pinned here.
 */
class GradeTest {

    @Test
    fun `uma celula vazia ou ausente le como lista vazia`() {
        val vazia: Grade = emptyMap()
        assertEquals(emptyList(), vazia.celula(Papel.DP, 0))
        assertEquals(emptyList(), mapOf("dp" to listOf(listOf("1"))).celula(Papel.DP, 4))
        assertNull(vazia.tierDe(Papel.DP, "1"))
    }

    @Test
    fun `comCelula materializa os cinco tiers e nao toca nos outros`() {
        val g: Grade = emptyMap<String, List<List<String>>>().comCelula(Papel.SUP, 2, listOf("7", "9"))
        assertEquals(5, g.getValue("sup").size)
        assertEquals(listOf("7", "9"), g.celula(Papel.SUP, 2))
        assertEquals(emptyList(), g.celula(Papel.SUP, 0))

        val depois = g.comCelula(Papel.SUP, 0, listOf("3"))
        assertEquals(listOf("7", "9"), depois.celula(Papel.SUP, 2), "escrever num tier não pode limpar outro")
    }

    @Test
    fun `a mesma personagem pode estar em dois papeis`() {
        // A correção que derrubou o mapeamento por caminho: quem faz dois papéis bem entra nos dois,
        // e nada aqui trata isso como conflito.
        val g = emptyMap<String, List<List<String>>>()
            .comCelula(Papel.DP, 0, listOf("1310"))
            .comCelula(Papel.SUP, 2, listOf("1310"))
        assertEquals(0, g.tierDe(Papel.DP, "1310"))
        assertEquals(2, g.tierDe(Papel.SUP, "1310"))
        assertEquals(setOf("1310"), g.todos())
        assertEquals(2, g.tamanho(), "uma personagem em dois papéis conta duas colocações")
    }

    @Test
    fun `celulas vao e voltam do numero que o componentId carrega`() {
        (0 until TierWizard.CELULAS).forEach { c ->
            assertEquals(c, celulaDe(papelDaCelula(c), tierDaCelula(c)))
        }
        assertEquals("Dano Principal · S", rotuloDaCelula(0))
        assertEquals("Protetor · D", rotuloDaCelula(19))
    }

    @Test
    fun `os campos dos componentes voltam a virar celula e fatia`() {
        assertEquals(0 to 0, TierWizard.fatiaDe("fa000"))
        assertEquals(19 to 3, TierWizard.fatiaDe("fa193"))
        assertNull(TierWizard.fatiaDe("fa203"), "célula fora de 0..19 não é campo válido")
        assertNull(TierWizard.fatiaDe("fa004"), "fatia fora de 0..3 não é campo válido")
        assertNull(TierWizard.fatiaDe("ir00"))

        assertEquals(7, TierWizard.celulaDe("pg07", TierWizard.PAGINA))
        assertEquals(7, TierWizard.celulaDe("ir07", TierWizard.IR))
        assertNull(TierWizard.celulaDe("ver", TierWizard.IR))
    }
}
