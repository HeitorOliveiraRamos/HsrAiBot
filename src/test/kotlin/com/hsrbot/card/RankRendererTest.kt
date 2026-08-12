package com.hsrbot.card

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O card do `/rank` desenha pixels, então o que vale prender aqui é que ele NÃO depende de nada
 * externo pra existir: sem rede, sem banco, sem JDA. Todo hash de asset é nulo — ícone que falta
 * vira soquete vazio — e o desenho tem que sair inteiro assim mesmo, nas duas leituras.
 *
 * O corte do título é a única regra de texto do arquivo, e existe porque [CardRenderer.header]
 * desenha num corpo fixo: um nome comprido demais sairia pela borda do painel em vez de encolher.
 */
class RankRendererTest {

    private fun linhas(n: Int, comPersonagem: Boolean) = (1..n).map {
        LinhaRank(
            posicao = it,
            jogador = "Jogadora $it",
            nota = 95.0 - it * 3.0,
            letra = listOf("SS", "S", "A", "B", "C", "D")[it % 6],
            personagem = if (comPersonagem) "Personagem $it" else null,
            nivel = 80,
            eidolon = it % 7,
        )
    }

    private fun ranking(n: Int, comPersonagem: Boolean) = Ranking(
        nome = "Acheron",
        elemento = "Vento",
        caminho = "Niilismo",
        titulo = if (comPersonagem) "MELHORES BUILDS" else "ACHERON",
        escopo = "SERVIDOR",
        servidorNome = "Expresso Astral",
        linhas = linhas(n, comPersonagem),
        pagina = 0,
        paginas = 2,
        total = n + 10,
    )

    @Test
    fun `desenha o ranking de uma personagem`() {
        val img = RankRenderer.render(ranking(RankRenderer.POR_PAGINA, comPersonagem = false))
        assertEquals(1080, img.width)
        assertEquals(1080, img.height)
    }

    @Test
    fun `desenha o ranking geral, com a personagem em cada linha`() {
        val png = RankRenderer.png(ranking(RankRenderer.POR_PAGINA, comPersonagem = true))
        assertTrue(png.size > 1000, "PNG saiu vazio demais pra ter desenhado alguma coisa")
    }

    @Test
    fun `uma pagina curta desenha so o que tem`() {
        val img = RankRenderer.render(ranking(1, comPersonagem = true))
        assertEquals(1080, img.height)
    }

    @Test
    fun `pagina sem linha nenhuma ainda desenha o card`() {
        val vazio = ranking(0, comPersonagem = false).copy(linhas = emptyList(), total = 0)
        assertEquals(1080, RankRenderer.render(vazio).width)
    }

    @Test
    fun `titulo comprido e cortado, titulo curto e preservado`() {
        assertEquals("ACHERON", RankRenderer.corte("ACHERON"))
        val longo = RankRenderer.corte("DESBRAVADORA (RECORDAÇÃO) DA HARMONIA")
        assertTrue(longo.endsWith("…"), "esperava reticências, veio '$longo'")
        assertTrue(longo.length <= 26, "cortou pra ${longo.length} caracteres")
    }
}
