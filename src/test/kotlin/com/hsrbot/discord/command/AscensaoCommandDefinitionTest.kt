package com.hsrbot.discord.command

import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Same reasoning as [GuiaCommandDefinitionTest]: a definition is only validated by Discord at
 * registration, so a mistake here surfaces as a failed startup after a deploy.
 */
class AscensaoCommandDefinitionTest {

    private val definicao = AscensaoCommand.definicao() as SlashCommandData

    @Test
    fun `qualquer membro pode pedir uma ascensao`() {
        assertEquals(DefaultMemberPermissions.ENABLED, definicao.defaultPermissions)
        assertTrue(definicao.isGuildOnly, "o card é postado no canal")
    }

    @Test
    fun `a personagem e obrigatoria e autocompletada, a arte e opcional`() {
        val (personagem, arte) = definicao.options
        assertEquals(AscensaoCommand.OPCAO, personagem.name)
        assertTrue(personagem.isRequired)
        assertTrue(personagem.isAutoComplete, "sem autocomplete a lista de personagens não cabe em lugar nenhum")

        // The upload is the one thing left to taste; everything else is derived, so a required
        // option here would be an option with no answer behind it.
        assertEquals(GuiaCommand.OPCAO_ARTE, arte.name)
        assertEquals(OptionType.ATTACHMENT, arte.type)
        assertFalse(arte.isRequired)
    }
}
