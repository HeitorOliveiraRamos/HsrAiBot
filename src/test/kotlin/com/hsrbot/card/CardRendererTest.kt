package com.hsrbot.card

import com.hsrbot.hsr.HsrTaxonomy
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The renderer draws pixels, so there is no output to assert on directly. What IS worth pinning
 * down is behaviour the rest of the feature leans on: a card renders whatever it is given without
 * throwing, the same ficha always produces the same bytes (the guide cache is keyed by a hash of
 * the spec, so a non-deterministic renderer would serve the wrong card), and the fields a guide
 * author fills in actually reach the canvas.
 *
 * No network and no database: every icon hash is null, and a null asset draws nothing by design.
 */
class CardRendererTest {

    private fun pixels(img: BufferedImage): IntArray =
        img.getRGB(0, 0, img.width, img.height, null, 0, img.width)

    private val base = Ficha(
        nome = "Blade",
        elemento = "Vento",
        caminho = "A Destruição",
        rastros = listOf(
            Rastro("Talento"), Rastro("Perícia Suprema"), Rastro("Perícia"), Rastro("Ataque Básico"),
        ),
        cones = listOf(Cone("Eu Sou Como Tu Me Vês"), Cone("Uma Coroação Sem Gratidão")),
        reliquias = listOf(Linha(Parte("Gênio das Estrelas Brilhantes", pecas = 4))),
        ornamentos = listOf(Linha(Parte("Cidade da Convergência Estelar", pecas = 2))),
        status = Status(corpo = "Chance de Crit", pes = "ATQ%", esfera = "Bônus de Dano de Vento", corda = "ATQ%"),
        metas = listOf(Meta("Chance de Crit", "100% em combate"), Meta("Dano Crit", "120%+ pré-combate")),
    )

    @Test
    fun `renderiza uma ficha vazia sem explodir`() {
        val img = CardRenderer.render(Ficha(nome = "Sem Dados"))
        assertEquals(1080, img.width)
        assertEquals(1080, img.height)
    }

    @Test
    fun `a mesma ficha rende sempre os mesmos pixels`() {
        // Load-bearing: the guide cache reuses a PNG whenever the spec hash matches.
        assertContentEquals(pixels(CardRenderer.render(base)), pixels(CardRenderer.render(base)))
    }

    @Test
    fun `sobreposicao, rastros e metas mudam o card`() {
        val semNada = pixels(CardRenderer.render(base))
        val comS5 = base.copy(cones = base.cones.map { it.copy(sobreposicao = 5) })
        val semUmRastro = base.copy(rastros = base.rastros.dropLast(1))
        val semAlvo = base.copy(metas = base.metas.map { Meta(it.stat) })
        listOf("sobreposição" to comS5, "rastro a menos" to semUmRastro, "meta sem alvo" to semAlvo).forEach { (o, f) ->
            assertFalse(semNada.contentEquals(pixels(CardRenderer.render(f))), "$o não mudou nada no card")
        }
    }

    @Test
    fun `uma linha 2 + 2 desenha as duas metades`() {
        val dividida = base.copy(
            reliquias = listOf(Linha(Parte("Gênio das Estrelas Brilhantes", pecas = 2), Parte("Grão-Duque em Brasas", pecas = 2))),
        )
        assertEquals(2, CardRenderer.gearRows(dividida.reliquias))
        assertFalse(pixels(CardRenderer.render(base)).contentEquals(pixels(CardRenderer.render(dividida))))
    }

    @Test
    fun `a coluna de equipamento nunca passa do painel`() {
        // Three split lines are six sets; only three rows fit above the STATUS band.
        val seis = List(3) { Linha(Parte("A", pecas = 2), Parte("B", pecas = 2)) }
        assertEquals(3, CardRenderer.gearRows(seis))
        assertTrue(CardRenderer.render(base.copy(reliquias = seis)).height == 1080)
    }

    @Test
    fun `o escurecimento do topo nao deixa emenda vertical`() {
        // The top band used to stop at x=560 while the right-column scrim only reaches full
        // strength at 620, so the 60px between them stayed lit: a white smear beside RASTROS with
        // nothing in the art to explain it. y=40 is above every panel, so only the washes are there.
        val img = CardRenderer.render(base)
        val salto = (520..700).map { luminancia(img.getRGB(it, 40)) }
            .zipWithNext { a, b -> kotlin.math.abs(a - b) }
            .max()
        assertTrue(salto <= 4, "emenda vertical no topo do card: salto de $salto de luminância")
    }

    @Test
    fun `as sinergias ganham o painel das outras secoes`() {
        // A synergy with no icon still draws its header and box — that is the whole point of the
        // panel, since the avatars sit straight on the splash without it.
        val comSinergias = base.copy(sinergias = List(5) { Sinergia("Robin") })
        assertFalse(
            pixels(CardRenderer.render(base)).contentEquals(pixels(CardRenderer.render(comSinergias))),
            "a seção SINERGIAS não desenhou nada",
        )
    }

    private fun luminancia(argb: Int) =
        ((argb shr 16 and 0xFF) * 30 + (argb shr 8 and 0xFF) * 59 + (argb and 0xFF) * 11) / 100

    @Test
    fun `o credito so aparece na arte de quem mandou ela`() {
        val arquivo = kotlin.io.path.createTempFile("arte-", ".png")
        val img = BufferedImage(600, 900, BufferedImage.TYPE_INT_ARGB)
        img.createGraphics().apply {
            color = java.awt.Color(0x30, 0x90, 0xC0)
            fillRect(0, 0, img.width, img.height)
            dispose()
        }
        javax.imageio.ImageIO.write(img, "png", arquivo.toFile())

        val enviada = base.copy(arte = Arte(enviada = arquivo, foco = Foco.INTEIRO, v2 = true))
        val assinada = enviada.copy(arte = enviada.arte.copy(autor = "heitorzinho"))
        assertFalse(
            pixels(CardRenderer.render(enviada)).contentEquals(pixels(CardRenderer.render(assinada))),
            "o crédito não foi desenhado na arte enviada",
        )
        // The official illustration is nobody's to sign: a name with no upload behind it changes
        // nothing, which is what stops a guide from crediting whoever happened to fill the form.
        assertContentEquals(
            pixels(CardRenderer.render(base)),
            pixels(CardRenderer.render(base.copy(arte = base.arte.copy(autor = "heitorzinho")))),
            "assinou um card que está usando a arte oficial",
        )
        arquivo.toFile().delete()
    }

    @Test
    fun `todo caminho e todo elemento tem o icone que o card desenha`() {
        // Renaming a file in resources/guias_info/ is silent — res() only warns and the card draws
        // one badge less — so this is the only thing that catches an alias left pointing at the old
        // name. It exists because exactly that happened: erudicao.png.
        HsrTaxonomy.PATHS.keys.forEach {
            assertNotNull(CardRenderer.res("caminhos", CardRenderer.pathFile(it)), "sem ícone pro caminho $it")
        }
        HsrTaxonomy.ELEMENTS.keys.forEach {
            assertNotNull(CardRenderer.res("elementos", CardRenderer.elementFile(it)), "sem ícone pro elemento $it")
        }
    }

    @Test
    fun `um hash da srs e uma url da nanoka resolvem sem colidir no cache`() {
        val (urlSrs, arquivoSrs) = CardRenderer.assetRef("a".repeat(64))
        assertEquals("https://cdn.starrailstation.com/assets/${"a".repeat(64)}.webp", urlSrs)
        assertEquals("${"a".repeat(64)}.webp", arquivoSrs, "o cache já preenchido tem que continuar valendo")

        // A nanoka URL is fetched verbatim, and — the whole point of escaping the URL and not its
        // basename — two assets of the same character land in two different files.
        val base = "https://static.nanoka.cc/assets/hsr"
        val (urlNan, arquivoNan) = CardRenderer.assetRef("$base/avatarroundicon/1512.webp")
        assertEquals("$base/avatarroundicon/1512.webp", urlNan)
        val (_, arquivoArte) = CardRenderer.assetRef("$base/avatardrawcard/1512.webp")
        assertNotEquals(arquivoNan, arquivoArte, "'1512.webp' é o basename dos dois — não pode ser a chave")
        listOf(arquivoNan, arquivoArte).forEach {
            assertFalse(it.contains('/'), "a chave vira nome de arquivo, não pode ter separador: $it")
        }
    }

    @Test
    fun `abrevia so os nomes longos e deixa o resto como veio`() {
        // The game's own labels are what the curated rows and the wizard both use.
        assertEquals("CC", CardRenderer.abreviar("Chance Crít."))
        assertEquals("DC", CardRenderer.abreviar("Dano Crít."))
        assertEquals("ERR", CardRenderer.abreviar("Regen. de Energia"))
        assertEquals("CC", CardRenderer.abreviar("Chance de Crit"))
        assertEquals("ATQ%", CardRenderer.abreviar("ATQ%"))
        assertEquals("Vontade de Ferro", CardRenderer.abreviar("Vontade de Ferro"))
    }
}
