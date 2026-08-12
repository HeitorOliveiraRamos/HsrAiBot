package com.hsrbot.discord.listener

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The prose under a card survives the framing step: uploading art used to cost `/build` its farm
 * plan, since the preview and the published post only ever carried the title.
 */
class CartaoPreviaCorpoTest {

    private val farm = "**Próximo farm:**\n• Botas"

    private fun previa(corpo: String) =
        Previa("**Build da Acheron**", null, "build.png", { _, _ -> null }, corpo = corpo)

    @Test
    fun `publish header carries the card's prose`() {
        assertTrue(previa(farm).sob("**Build da Acheron** — por @alguem").contains(farm))
    }

    @Test
    fun `a card without prose posts just its header`() {
        val cabecalho = "**Guia de ascensão da Blade**"
        assertTrue(previa("").sob(cabecalho) == cabecalho)
    }

    @Test
    fun `preview shows the prose above the framing hint`() {
        val texto = CartaoArteListener.textoPrevia("**Build da Acheron**", comArte = true, corpo = farm)
        assertTrue(texto.indexOf(farm) < texto.indexOf("As setas movem"))
        assertFalse(CartaoArteListener.textoPrevia("**Guia**", comArte = true).contains("\n\n"))
    }
}
