package com.hsrbot.discord.command

import com.hsrbot.config.BotProperties
import com.hsrbot.discord.GuildGuard
import com.hsrbot.discord.util.BotMessages
import com.hsrbot.discord.util.Cooldowns
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CommandRouter(
    commands: List<SlashCommand>,
    private val properties: BotProperties,
    private val guildGuard: GuildGuard,
    private val cooldowns: Cooldowns,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val byName: Map<String, SlashCommand> = commands.associateBy { it.name }

    init {
        log.info("Registered {} slash commands: {}", byName.size, byName.keys)
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        val command = byName[event.name] ?: return

        // Channel allow-list ([BotProperties.testChannelIds]). Answered rather than ignored:
        // a slash command that gets no response at all shows the user a red "the application
        // did not respond" error, which reads as a bug instead of a deliberate scope.
        // Only apply the allow-list to commands that actually use AI; non-AI commands should work
        // in every guild channel even when TEST_CHANNEL_IDS is configured.
        if (command.requiresAi && !properties.allowsChannel(event.channel.id, event.isFromGuild)) {
            event.reply(BotMessages.CHANNEL_NOT_ALLOWED).setEphemeral(true).queue()
            return
        }

        // The server fence, unlike the channel one, applies to EVERY command: a server we
        // don't serve shouldn't get free card renders either. Only reachable for a guild
        // joined before GUILD_IDS was set — a join after it never gets this far
        // ([GuildGuard.onGuildJoin] leaves first).
        if (event.isFromGuild && !guildGuard.permite(event.guild?.id)) {
            event.reply(BotMessages.GUILD_NOT_ALLOWED).setEphemeral(true).queue()
            return
        }
        if (!event.isFromGuild && !guildGuard.permiteDm()) {
            event.reply(BotMessages.DM_DISABLED).setEphemeral(true).queue()
            return
        }

        // Paces the expensive commands per user. Deliberately after the gates above, so a
        // refused command never costs anyone a wait, and before handle(), so the work the
        // wait exists to bound is the work that doesn't happen.
        val espera = cooldowns.tentar(Cooldowns.comando(event.user.id, command.name), command.cooldownSeconds)
        if (espera > 0) {
            event.reply(BotMessages.cooldown(espera)).setEphemeral(true).queue()
            return
        }

        try {
            command.handle(event)
        } catch (e: Exception) {
            log.error("Error handling /{}", event.name, e)
            if (!event.isAcknowledged) {
                event.reply(BotMessages.ERROR).setEphemeral(true).queue()
            }
        }
    }

    /**
     * Autocomplete fires on every keystroke and has no user-visible failure mode other than an
     * empty dropdown, so a broken suggestion list is logged and swallowed rather than answered
     * with an error the user cannot act on.
     */
    override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
        val command = byName[event.name] ?: return
        try {
            command.autocomplete(event)
        } catch (e: Exception) {
            log.error("Error autocompleting /{}", event.name, e)
        }
    }
}
