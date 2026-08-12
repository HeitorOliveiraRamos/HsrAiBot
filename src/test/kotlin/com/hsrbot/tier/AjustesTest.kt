package com.hsrbot.tier

import com.hsrbot.card.Mudanca
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The markers: what the list works out on its own, and where the author's own answer takes over.
 *
 * The rule that needs pinning most is the switch between the two. It is the PRESENCE of a papel's
 * entry, never whether that entry has anything in it — get it wrong and an author who deliberately
 * cleared a column silently gets the computed markers back on the next render.
 */
class AjustesTest {

    private fun grade(vararg celulas: Triple<Papel, Int, List<String>>): Grade =
        celulas.fold(emptyMap()) { g, (p, t, ids) -> g.comCelula(p, t, ids) }

    @Test
    fun `sem lista anterior nao ha marcador nenhum`() {
        val g = grade(Triple(Papel.DP, 0, listOf("1", "2")))
        assertEquals(MudancasSpec(), automatico(null, g, Papel.DP))
    }

    @Test
    fun `sobe, desce, entra nova e fica igual`() {
        val antes = grade(Triple(Papel.DP, 2, listOf("sobe", "igual")), Triple(Papel.DP, 0, listOf("desce")))
        val agora = grade(
            Triple(Papel.DP, 0, listOf("sobe", "nova")),
            Triple(Papel.DP, 2, listOf("igual")),
            Triple(Papel.DP, 4, listOf("desce")),
        )
        val m = automatico(antes, agora, Papel.DP)
        assertEquals(listOf("nova"), m.novas)
        assertEquals(listOf("sobe"), m.subiram)
        assertEquals(listOf("desce"), m.desceram)
    }

    @Test
    fun `trocar de coluna nao e subir nem descer`() {
        // Estava em Suporte S, agora está em Dano Principal D. Não subiu nem desceu — mudou de
        // papel, e uma seta ali seria uma afirmação que a lista não faz.
        val antes = grade(Triple(Papel.SUP, 0, listOf("mudou")))
        val agora = grade(Triple(Papel.DP, 4, listOf("mudou")))
        val m = automatico(antes, agora, Papel.DP)
        assertEquals(MudancasSpec(), m)
        assertEquals(Mudanca.NENHUMA, m.de("mudou"))
    }

    @Test
    fun `o marcador manual de uma coluna nao vaza para outra`() {
        val ajustes: Ajustes = mapOf(Papel.DP.chave to MudancasSpec(subiram = listOf("x")))
        assertEquals(Mudanca.SUBIU, ajustes.getValue(Papel.DP.chave).de("x"))
        assertNull(ajustes[Papel.SUP.chave], "Suporte continua no automático")
    }

    @Test
    fun `uma coluna manual e vazia continua manual`() {
        // A regra que decide tudo: a chave existir é o que manda, não ela ter conteúdo. Uma coluna
        // que o autor limpou de propósito não pode voltar sozinha pro automático.
        val ajustes: Ajustes = mapOf(Papel.DP.chave to MudancasSpec())
        assertTrue(Papel.DP.chave in ajustes)
        assertTrue(ajustes.getValue(Papel.DP.chave).vazio)
        assertEquals(Mudanca.NENHUMA, ajustes.getValue(Papel.DP.chave).de("qualquer"))
    }

    @Test
    fun `nova ganha de subiu, que ganha de desceu`() {
        val m = MudancasSpec(novas = listOf("a"), subiram = listOf("a", "b"), desceram = listOf("a", "b", "c"))
        assertEquals(Mudanca.NOVA, m.de("a"))
        assertEquals(Mudanca.SUBIU, m.de("b"))
        assertEquals(Mudanca.DESCEU, m.de("c"))
        assertEquals(Mudanca.NENHUMA, m.de("d"))
    }

    @Test
    fun `marcadores de quem saiu da coluna somem`() {
        // O autor marcou "saiu" como nova em Dano Principal e depois tirou ela da coluna. O marcador
        // não tem mais em quem cair, e limpar na leitura é o que evita ter que caçar isso em toda
        // edição.
        val g = grade(Triple(Papel.DP, 0, listOf("ficou")))
        val ajustes: Ajustes = mapOf(Papel.DP.chave to MudancasSpec(novas = listOf("ficou", "saiu")))
        val limpo = ajustes.semRestos(g)
        assertEquals(listOf("ficou"), limpo.getValue(Papel.DP.chave).novas)
        assertTrue(Papel.DP.chave in limpo, "limpar os restos não pode desligar o modo manual")
    }
}
