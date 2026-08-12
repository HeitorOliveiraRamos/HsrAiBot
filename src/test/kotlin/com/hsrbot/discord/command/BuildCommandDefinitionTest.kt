package com.hsrbot.discord.command

import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Same reasoning as [AscensaoCommandDefinitionTest]: a definition is only validated by Discord at
 * registration, so a mistake here surfaces as a failed startup after a deploy.
 */
class BuildCommandDefinitionTest {

    private val definicao = BuildCommand.definicao() as SlashCommandData

    @Test
    fun `a personagem e obrigatoria e a arte e opcional`() {
        val (personagem, arte) = definicao.options
        assertEquals(BuildCommand.OPCAO, personagem.name)
        assertTrue(personagem.isRequired)
        // The picker is the whole point: without this Discord never asks for suggestions.
        assertTrue(personagem.isAutoComplete)

        assertEquals(GuiaCommand.OPCAO_ARTE, arte.name)
        assertEquals(OptionType.ATTACHMENT, arte.type)
        assertFalse(arte.isRequired)
    }

    /**
     * The two choices are the two the ruler can be overridden with, and their VALUES are what
     * `handle` parses back into a weight — a relabelled choice is cosmetic, a re-valued one silently
     * scores every build on a ruler nobody asked for.
     */
    @Test
    fun `o peso da velocidade e opcional e so aceita 100 ou 0`() {
        val spd = definicao.options.single { it.name == BuildCommand.OPCAO_SPD }
        assertEquals(OptionType.STRING, spd.type)
        assertFalse(spd.isRequired)
        assertEquals(listOf("1", "0"), spd.choices.map { it.asString })
    }

    /**
     * Unlike the other card commands this one is NOT guild-only: it rates the caller's own showcase,
     * which is a perfectly reasonable thing to ask for in a DM.
     */
    @Test
    fun `pode ser usado fora de um servidor`() {
        assertFalse(definicao.isGuildOnly)
    }

    /**
     * Discord refuses a definition where a required option follows an optional one, and `usuario`
     * is the newest option — so this asserts the ORDER, not just the presence.
     */
    @Test
    fun `o usuario e opcional e vem depois da personagem obrigatoria`() {
        val usuario = definicao.options.single { it.name == BuildCommand.OPCAO_USUARIO }
        assertEquals(OptionType.USER, usuario.type)
        assertFalse(usuario.isRequired)
        assertEquals(definicao.options.lastIndex, definicao.options.indexOf(usuario))
        assertTrue(definicao.options.takeWhile { it.isRequired }.isNotEmpty())
        assertFalse(definicao.options.dropWhile { it.isRequired }.any { it.isRequired })
    }
}
