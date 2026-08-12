package com.hsrbot.discord.command

import com.hsrbot.hsr.HsrCharacterService
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.build.CommandData

/**
 * A single slash command. Implementations are picked up automatically as Spring beans,
 * registered with Discord at startup, and routed to by [CommandRouter].
 */
interface SlashCommand {
    val name: String
    val definition: CommandData
    /** True when this slash command invokes AI/model functionality and should be gated by TEST_CHANNEL_IDS. */
    val requiresAi: Boolean get() = false

    /**
     * Seconds the same person must wait between two runs of THIS command, enforced by
     * [CommandRouter] through [com.hsrbot.discord.util.Cooldowns]. 0 (the default) = no wait,
     * which is right for anything that only reads a row and replies.
     *
     * Override it where a run costs the host real work — a card render, an Enka fetch, an
     * Ollama pass. The number is the floor on how fast one user can spend that, so it is
     * sized against the work, not against how annoying the wait feels.
     */
    val cooldownSeconds: Long get() = 0

    fun handle(event: SlashCommandInteractionEvent)

    /**
     * Suggestions for an option declared with `setAutoComplete(true)`. Discord shows at most 25 and
     * re-asks on every keystroke, which is the only workable picker for a list of ~100 characters.
     * Commands without such an option leave this alone.
     */
    fun autocomplete(event: CommandAutoCompleteInteractionEvent) = Unit
}

/**
 * Replies privately and returns — the shape every refusal in the moderation commands takes,
 * so a rejected action never spams the channel with the reason.
 */
fun SlashCommandInteractionEvent.replyEphemeral(message: String) {
    reply(message).setEphemeral(true).queue()
}

/** Discord shows at most 25 autocomplete choices, and re-asks on every keystroke. */
private const val LIMITE_SUGESTOES = 25

/**
 * Character suggestions for a `personagem` option — the picker shared by every card command.
 *
 * Choices carry the GAME ID as their value, not the name: fourteen rows share a display name (both
 * 7 de Março, every Desbravador path), so a name-valued choice would pick one of them at random.
 */
fun CommandAutoCompleteInteractionEvent.replyPersonagens(personagens: HsrCharacterService) {
    val termo = HsrCharacterService.normalize(focusedOption.value)
    val escolhas = personagens.all()
        .filter { c -> termo.isEmpty() || c.names.any { HsrCharacterService.normalize(it).contains(termo) } }
        .map { Command.Choice(personagens.displayName(it.id), it.id) }
        .sortedBy { it.name }
        .take(LIMITE_SUGESTOES)
    replyChoices(escolhas).queue()
}

/**
 * What the user actually typed, as a game id. A picked suggestion arrives as the id already;
 * anything typed free-hand goes through the same fuzzy resolver the rest of the bot uses.
 */
fun HsrCharacterService.idDigitado(digitado: String): String? =
    digitado.takeIf { byId(it) != null } ?: resolveId(digitado)
