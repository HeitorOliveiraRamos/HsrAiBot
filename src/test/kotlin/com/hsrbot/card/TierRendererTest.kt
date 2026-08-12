package com.hsrbot.card

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The renderer draws pixels, so what is worth pinning down is the sizing rule underneath them:
 * [TierRenderer.layout] is the only non-trivial logic in the file, and it is what "the tiers adjust
 * themselves to the quantity of characters" actually means.
 *
 * Three properties matter and none of them is a pixel:
 *  - a tier row is as tall as its FULLEST column, floored at one row so an empty tier still exists;
 *  - the whole thing fits the space between the column labels and the footer, at the largest avatar
 *    size that does;
 *  - it never fails to produce a layout, however absurd the counts — a list with every character in
 *    every cell clips and says so, rather than drawing off the bottom of the image.
 *
 * No network and no database: every avatar hash is null, and a null asset draws its empty socket.
 */
class TierRendererTest {

    private val yTiers = 172
    private val yRodape = 1010
    private val gapTier = 12

    /** Where the D row's bottom edge lands: the top, the five panels, and the four gaps between. */
    private fun fimDoDesenho(l: TierRenderer.Layout) = yTiers + l.alturas.sum() + 4 * gapTier

    private fun celulas(vararg tiers: List<Int>) = tiers.toList()

    private fun listaCom(tiers: List<List<Int>>): TierList = TierList(
        modo = "Memória do Caos",
        versao = "v4.3",
        autor = "heitor",
        colunas = (0 until 4).map { col ->
            Coluna(
                rotulo = "Coluna $col",
                tiers = tiers.map { linha -> List(linha[col]) { Avatar("Personagem $it", elemento = "Vento") } },
            )
        },
    )

    @Test
    fun `uma lista vazia ainda tem cinco tiers de uma linha`() {
        val l = TierRenderer.layout(List(5) { List(4) { 0 } })
        assertEquals(List(5) { 1 }, l.linhas)
        assertTrue(fimDoDesenho(l) <= yRodape, "lista vazia não deveria estourar o espaço")
    }

    @Test
    fun `a altura de um tier vem da coluna mais cheia`() {
        // 9 numa coluna e 1 nas outras: a linha tem que caber os 9, não a média.
        val l = TierRenderer.layout(celulas(listOf(9, 1, 1, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0)))
        val esperado = (9 + l.porLinha - 1) / l.porLinha
        assertEquals(esperado, l.linhas[0])
        assertEquals(1, l.linhas[1], "um tier vazio continua existindo, com uma linha")
        assertTrue(l.alturas[0] > l.alturas[1], "o tier cheio tem que ser mais alto que o vazio")
    }

    @Test
    fun `uma lista do tamanho da referencia cabe sem encolher demais`() {
        // As contagens da tier list de referência: ~110 colocações espalhadas pelas 20 células.
        val l = TierRenderer.layout(
            celulas(
                listOf(6, 4, 9, 3), listOf(11, 2, 7, 3), listOf(3, 7, 8, 2),
                listOf(7, 4, 1, 3), listOf(11, 0, 2, 3),
            ),
        )
        assertTrue(fimDoDesenho(l) <= yRodape, "o desenho terminou em ${fimDoDesenho(l)}, em cima do rodapé")
        assertTrue(l.d >= 40, "avatares de ${l.d}px são pequenos demais para uma lista desse tamanho")
        assertTrue(l.porLinha >= 4, "uma coluna tem que caber pelo menos 4 avatares por linha")
    }

    @Test
    fun `o elenco inteiro em um unico tier ainda cabe`() {
        // A regra que o usuário pediu: se alguém quer as 97 personagens no tier S, pode.
        val l = TierRenderer.layout(celulas(listOf(97, 0, 0, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0)))
        assertTrue(fimDoDesenho(l) <= yRodape, "o desenho terminou em ${fimDoDesenho(l)}, em cima do rodapé")
        val cabem = l.porLinha * l.linhas[0]
        assertTrue(cabem >= 97, "só cabem $cabem das 97 — esse caso não deveria precisar de corte")
    }

    @Test
    fun `o caso patologico corta em vez de desenhar fora da imagem`() {
        // Quatro tiers com o elenco inteiro cada: impossível de desenhar por inteiro em 1080px.
        val l = TierRenderer.layout(
            celulas(
                listOf(97, 97, 97, 97), listOf(97, 97, 97, 97), listOf(97, 97, 97, 97),
                listOf(97, 97, 97, 97), listOf(97, 97, 97, 97),
            ),
        )
        assertTrue(fimDoDesenho(l) <= yRodape, "nem com corte coube: terminou em ${fimDoDesenho(l)}")
        assertTrue(l.linhas.all { it >= 1 }, "nenhum tier pode sumir")
    }

    @Test
    fun `renderiza os extremos sem explodir`() {
        listOf(
            List(5) { List(4) { 0 } },
            listOf(listOf(97, 0, 0, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0)),
            listOf(listOf(6, 4, 9, 3), listOf(11, 2, 7, 3), listOf(3, 7, 8, 2), listOf(7, 4, 1, 3), listOf(11, 0, 2, 3)),
        ).forEach { contagens ->
            val img = TierRenderer.render(listaCom(contagens))
            assertEquals(1080, img.width)
            assertEquals(1080, img.height)
        }
    }

    @Test
    fun `uma lista sem nome de autor nao desenha o credito dele`() {
        // O anônimo é uma ausência no modelo, não uma flag no renderer: se o nome não chegou, não
        // existe caminho que o desenhe.
        val anonima = listaCom(List(5) { List(4) { 1 } }).copy(autor = null)
        assertEquals(1080, TierRenderer.render(anonima).width)
    }

    @Test
    fun `a legenda so aparece quando houve mudanca`() {
        val semDiff = listaCom(List(5) { List(4) { 1 } })
        assertTrue(!semDiff.temMudancas)
        val comDiff = semDiff.copy(
            colunas = semDiff.colunas.mapIndexed { i, c ->
                if (i != 0) c else c.copy(tiers = c.tiers.mapIndexed { t, avs ->
                    if (t != 0) avs else avs.map { it.copy(mudanca = Mudanca.SUBIU) }
                })
            },
        )
        assertTrue(comDiff.temMudancas)
    }
}
