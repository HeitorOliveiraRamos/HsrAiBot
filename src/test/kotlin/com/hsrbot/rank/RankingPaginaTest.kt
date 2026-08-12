package com.hsrbot.rank

import com.hsrbot.card.RankRenderer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * O fatiamento de página é a única lógica não-trivial da leitura do `/rank`, e é o que os botões
 * ◀ ▶ pisam a cada clique: a página vem de um customId, ou seja, de um botão que pode ser mais
 * velho que a lista que ele está paginando.
 *
 * Três coisas importam: a última página é curta, as pontas dão a volta, e um índice fora da faixa
 * (a lista encolheu desde que aquele botão foi desenhado) dobra pra dentro em vez de estourar.
 */
class RankingPaginaTest {

    private val porPagina = RankRenderer.POR_PAGINA

    private fun lista(n: Int) = (1..n).toList()

    @Test
    fun `lista vazia ainda tem uma pagina`() {
        assertEquals(1, RankingService.paginas(0))
        assertEquals(1, RankingService.paginas(1))
    }

    @Test
    fun `pagina cheia nao abre uma pagina vazia depois dela`() {
        assertEquals(1, RankingService.paginas(porPagina))
        assertEquals(2, RankingService.paginas(porPagina + 1))
        assertEquals(3, RankingService.paginas(porPagina * 3))
    }

    @Test
    fun `a ultima pagina leva so o que sobrou`() {
        val (indice, fatia) = RankingService.fatia(lista(porPagina + 3), 1)
        assertEquals(1, indice)
        assertEquals(3, fatia.size)
        assertEquals(porPagina + 1, fatia.first())
    }

    @Test
    fun `as pontas dao a volta`() {
        val linhas = lista(porPagina * 2)
        // ◀ na primeira vai pra última...
        assertEquals(1, RankingService.fatia(linhas, -1).first)
        // ...e ▶ na última volta pra primeira.
        assertEquals(0, RankingService.fatia(linhas, 2).first)
    }

    @Test
    fun `indice velho de uma lista que encolheu dobra pra dentro`() {
        // O botão foi desenhado quando havia 5 páginas; agora só há 2.
        val (indice, fatia) = RankingService.fatia(lista(porPagina + 1), 4)
        assertEquals(0, indice)
        assertEquals(porPagina, fatia.size)
    }
}
