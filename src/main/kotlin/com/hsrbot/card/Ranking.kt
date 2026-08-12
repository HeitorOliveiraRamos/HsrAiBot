package com.hsrbot.card

/**
 * Uma página do `/rank`, já resolvida — o [Ficha] do [RankRenderer], e o mesmo contrato dos outros
 * cards: strings prontas e hashes de asset, sem banco e sem Spring, então a imagem é função pura
 * deste objeto.
 *
 * A [Identidade] aqui é de quem ganha a coluna da esquerda: a personagem do ranking quando é o
 * ranking dela, e a **#1 da página** no ranking geral — o holofote é do topo.
 *
 * [servidorIcone] é URL do CDN do Discord e não um `BufferedImage` de propósito: uma imagem dentro
 * de um data class faz `equals` comparar referência (a armadilha que [Cartao] documenta), e
 * [CardRenderer.asset] já busca e cacheia URL absoluta em disco.
 */
data class Ranking(
    override val nome: String,
    override val elemento: String? = null,
    override val caminho: String? = null,
    override val raridade: Int = 5,
    override val arte: Arte = Arte(),
    /** O que está sendo ranqueado: `"ACHERON"` ou `"MELHORES BUILDS"`. */
    val titulo: String = "",
    /** De onde saíram as linhas: `"SERVIDOR"` ou `"GLOBAL"`. */
    val escopo: String = "",
    val servidorIcone: String? = null,
    val servidorNome: String? = null,
    val linhas: List<LinhaRank> = emptyList(),
    val pagina: Int = 0,
    val paginas: Int = 1,
    val total: Int = 0,
) : Identidade

/**
 * Uma linha do ranking. [personagem] e [icone] só vêm preenchidos no ranking geral — no ranking de
 * uma personagem seriam a mesma cara dez vezes, que é ruído, não informação.
 */
data class LinhaRank(
    val posicao: Int,
    val jogador: String,
    val nota: Double,
    val letra: String,
    val personagem: String? = null,
    val icone: String? = null,
    val nivel: Int = 0,
    val eidolon: Int = 0,
)
