package com.hsrbot.discord.command

import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Same reasoning as [ModerationCommandDefinitionTest]: a definition is only validated by Discord at
 * registration, so a mistake here surfaces as a failed startup after a deploy. For `/guia` the two
 * things that carry weight are that it stays open to every member, and the autocomplete flag,
 * without which the character option becomes a free-text box against ~100 characters.
 */
class GuiaCommandDefinitionTest {

    private val definicao = GuiaCommand.definicao() as SlashCommandData

    @Test
    fun `qualquer membro pode montar um card`() {
        // Deliberately ungated. A server that wants it narrower does that in its own Integrations
        // screen; nothing here should hide the command by default.
        assertEquals(DefaultMemberPermissions.ENABLED, definicao.defaultPermissions)
        assertTrue(definicao.isGuildOnly, "publicar a guia precisa de um canal de servidor")
    }

    @Test
    fun `a personagem e obrigatoria e autocompletada`() {
        val opcao = definicao.options.first { it.name == GuiaCommand.OPCAO }
        assertTrue(opcao.isRequired)
        assertTrue(opcao.isAutoComplete, "sem autocomplete a lista de personagens não cabe em lugar nenhum")
    }

    @Test
    fun `a arte e um anexo opcional`() {
        // The ONLY place Discord will take a file for this feature: modals hold text and nothing
        // else, and a button cannot ask for one. Optional, or every card would demand a picture.
        val arte = definicao.options.first { it.name == GuiaCommand.OPCAO_ARTE }
        assertEquals(OptionType.ATTACHMENT, arte.type)
        assertFalse(arte.isRequired)
    }
}
