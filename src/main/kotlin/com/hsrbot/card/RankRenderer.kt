package com.hsrbot.card

import com.hsrbot.hsr.BuildAnalyzer
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Desenha uma página do `/rank`: a personagem em destaque na esquerda ([Retrato], o mesmo dos cards
 * de `/build` e `/ascensao`) e dez linhas de ranking na direita.
 *
 * As duas leituras cabem no mesmo desenho porque só muda o assunto da linha. No ranking de UMA
 * personagem o assunto é o jogador (a personagem já é a coluna inteira da esquerda); no ranking
 * geral o assunto é a personagem, e o jogador vira o crédito embaixo dela — que é exatamente o que
 * "a melhor Acheron do servidor é a do fulano" quer dizer.
 *
 * Entrada é um [Ranking] resolvido e nada mais: sem banco, sem Spring, sem JDA. Ícone de servidor e
 * ícones mini são buscados por [CardRenderer.asset] e falham soft — falta de ícone custa um soquete
 * vazio, nunca a linha.
 */
object RankRenderer {

    fun render(r: Ranking): BufferedImage {
        // ponytail: mesmo motivo do CardRenderer — a descoberta de plugins é preguiçosa e perde o webp.
        ImageIO.scanForPlugins()

        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

        val pal = Paleta.elemento(r.elemento)
        Retrato.coluna(g, r, pal, logo = CardRenderer.asset(r.servidorIcone), tag = r.servidorNome)
        lista(g, r, pal)
        rodape(g, r)

        CardRenderer.frame(g)
        g.dispose()
        return img
    }

    fun png(r: Ranking): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(render(r), "png", out)
        out.toByteArray()
    }

    private fun lista(g: Graphics2D, r: Ranking, pal: Paleta) {
        CardRenderer.header(g, corte(r.titulo.uppercase()), RX, Y_LISTA, RW, pal.regua)
        CardRenderer.panel(g, RX, Y_LISTA + 12, RW, LISTA_H, pal)

        r.linhas.take(POR_PAGINA).forEachIndexed { i, l -> linha(g, l, pal, Y_LISTA + 22 + i * LINHA_H) }
    }

    private fun linha(g: Graphics2D, l: LinhaRank, pal: Paleta, y: Int) {
        val medalha = MEDALHAS[l.posicao]
        if (medalha != null) {
            // O pódio ganha uma faixa própria. Sem ela as três primeiras linhas são só mais três
            // linhas, e o topo é a única coisa que alguém procura numa imagem de ranking.
            g.color = CardRenderer.alpha(medalha, 22)
            g.fill(RoundRectangle2D.Float((RX + 8).toFloat(), y.toFloat(), (RW - 16).toFloat(), 72f, 16f, 16f))
        }

        g.font = CardRenderer.din(if (l.posicao < 100) 27f else 21f, bold = true)
        g.color = medalha ?: CardRenderer.alpha(DIM, 190)
        val pos = "${l.posicao}"
        g.drawString(pos, RX + 52 - g.fontMetrics.stringWidth(pos), y + 44)

        // O ícone só existe no ranking geral, e é ele que empurra o texto pra direita.
        val icone = l.icone?.let(CardRenderer::asset)
        val temIcone = l.personagem != null
        if (temIcone) {
            g.color = Color(0xFF, 0xFF, 0xFF, if (icone == null) 8 else 14)
            g.fill(RoundRectangle2D.Float((RX + 66).toFloat(), (y + 8).toFloat(), 56f, 56f, 16f, 16f))
            g.color = CardRenderer.alpha(pal.borda, if (icone == null) 55 else 90)
            g.stroke = BasicStroke(1.4f)
            g.draw(RoundRectangle2D.Float((RX + 66).toFloat(), (y + 8).toFloat(), 56f, 56f, 16f, 16f))
            icone?.let { CardRenderer.drawContain(g, it, RX + 70, y + 12, 48, 48) }
        }

        val x = RX + if (temIcone) 136 else 72
        // O texto para onde a NOTA começa, não onde o painel acaba: a nota é desenhada da direita
        // pra esquerda, então medir contra a borda deixava "Desbravadora (Recordação)" passar por
        // baixo do número.
        val largura = RX + RW - RESERVA_NOTA - x
        val (f, linhas) = CardRenderer.fitText(
            g, l.personagem ?: l.jogador, largura, 1, 23f, 13f, CardRenderer.din(23f, bold = true),
        )
        CardRenderer.drawLines(g, linhas.take(1), f, x, y + 32, Color.WHITE)

        val legenda = listOfNotNull(
            l.personagem?.let { "por ${l.jogador}" },
            "Nv.${l.nivel}",
            "E${l.eidolon}",
        ).joinToString("  ·  ")
        val (lf, llinhas) = CardRenderer.fitText(g, legenda, largura + 40, 1, 15f, 10f, CardRenderer.din(15f))
        CardRenderer.drawLines(g, llinhas.take(1), lf, x, y + 56, CardRenderer.alpha(DIM, 165))

        g.font = CardRenderer.din(28f, bold = true)
        val nota = fmt(l.nota)
        val nw = g.fontMetrics.stringWidth(nota)
        g.paint = AvaliacaoRenderer.tintaDoRank(l.letra, RX + RW - 78 - nw, nw)
        g.drawString(nota, RX + RW - 78 - nw, y + 44)
        AvaliacaoRenderer.chip(
            g, l.letra, RX + RW - 66, y + 21, 56,
            AvaliacaoRenderer.tintaDoRank(l.letra, RX + RW - 66, 56), 30,
        )
    }

    /** De onde as linhas vieram e onde a página está — o resto do contexto que a imagem carrega sozinha. */
    private fun rodape(g: Graphics2D, r: Ranking) {
        val texto = listOfNotNull(
            r.escopo.takeIf { it.isNotBlank() }?.uppercase(),
            "PÁGINA ${r.pagina + 1}/${r.paginas}",
            "${r.total} ${if (r.total == 1) "BUILD" else "BUILDS"}",
        ).joinToString("  ·  ")
        val (f, linhas) = CardRenderer.fitText(g, texto, RW, 1, 14f, 9f, CardRenderer.din(14f, tracking = 0.1))
        CardRenderer.drawLines(g, linhas.take(1), f, RX, Y_RODAPE, CardRenderer.alpha(Color.WHITE, 150))
    }

    /**
     * [CardRenderer.header] desenha num corpo fixo e centralizado, então um nome comprido
     * ("Desbravadora (Recordação)") sairia pela borda do painel. Cortar é honesto aqui: no ranking de
     * uma personagem o nome inteiro já está desenhado enorme na coluna da esquerda.
     */
    internal fun corte(texto: String): String =
        if (texto.length <= MAX_TITULO) texto else texto.take(MAX_TITULO - 1).trimEnd() + "…"

    private fun fmt(nota: Double): String = BuildAnalyzer.nota(nota)

    // -------------------- constantes -------------------- //

    private const val W = Retrato.W
    private const val H = Retrato.H

    /** Coluna da direita, a mesma dos outros cards quadrados. */
    private const val RX = 548
    private const val RW = W - 40 - RX

    /** Dez linhas é o que cabe sem espremer, e é uma página de ranking que se lê no celular. */
    const val POR_PAGINA = 10

    private const val Y_LISTA = 92
    private const val LINHA_H = 82
    private const val LISTA_H = 20 + POR_PAGINA * LINHA_H

    private const val Y_RODAPE = 984

    private const val MAX_TITULO = 26

    /** Nota (28pt, até "10.0") mais o chip da letra — o que a linha reserva na ponta direita. */
    private const val RESERVA_NOTA = 152

    private val OURO = Color(0xF2, 0xC9, 0x6B)
    private val PRATA = Color(0xD8, 0xDD, 0xE6)
    private val BRONZE = Color(0xD8, 0x9E, 0x6B)
    private val DIM = Color(0xD5, 0xCB, 0xD8)

    private val MEDALHAS = mapOf(1 to OURO, 2 to PRATA, 3 to BRONZE)
}
