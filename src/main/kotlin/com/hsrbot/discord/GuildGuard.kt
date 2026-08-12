package com.hsrbot.discord

import com.hsrbot.config.BotProperties
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Which servers this bot answers in — the outer fence around everything else.
 *
 * Slash commands are registered GLOBALLY ([DiscordBootstrap]), so an invite link that leaks
 * (or a well-meaning friend adding the bot to a 5k-member server) points every render, every
 * Enka fetch and every Ollama call at one small VPS, with no cap and no bill anyone else
 * pays. `TEST_CHANNEL_IDS` doesn't cover it: that gates channels within a server, and only
 * for the AI commands.
 *
 * Unset ([BotProperties.guildIds] empty) = every server, i.e. exactly today's behaviour.
 * Set = the listed servers, and a join anywhere else is undone on the spot.
 *
 * Servers joined BEFORE the list was set are only reported, never left: leaving is visible
 * to other people and needs an admin to undo, so an id typo would silently evict the bot
 * from the servers she belongs to. The log line says which ones and what to do about it.
 */
@Component
class GuildGuard(private val properties: BotProperties) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** True when the bot may serve [guildId]. A null id is a DM — see [permiteDm]. */
    fun permite(guildId: String?): Boolean {
        if (guildId == null) return true
        val permitidos = properties.guildIds.filter { it.isNotBlank() }
        return permitidos.isEmpty() || guildId in permitidos
    }

    /**
     * Whether a DM should be served at all.
     *
     * ponytail: a switch, not a "does this person share an allowed server" check. The
     * accurate version needs the member cache this bot deliberately doesn't fill
     * (`createDefault` caches almost no members, so `mutualGuilds` would answer "no" for
     * most legitimate users) or a REST lookup per DM. The DM path is already behind a
     * mention, the inference gate and its own longer cooldown; if that stops being enough,
     * turn the switch off — upgrade to a member fetch only if DMs must stay open to some.
     */
    fun permiteDm(): Boolean = properties.dmEnabled

    /** A server that isn't on the list never gets a first reply — leave before anyone asks. */
    override fun onGuildJoin(event: GuildJoinEvent) {
        if (permite(event.guild.id)) {
            log.info("Entrei no servidor {} ({})", event.guild.name, event.guild.id)
            return
        }
        log.warn(
            "Servidor NÃO permitido: {} ({}) — saindo. Para liberar, some o id em GUILD_IDS.",
            event.guild.name,
            event.guild.id,
        )
        event.guild.leave().queue(
            null,
            { log.error("não consegui sair de {}: {}", event.guild.id, it.message) },
        )
    }

    /** Startup report. Deliberately does not leave anything — see the class doc. */
    override fun onReady(event: ReadyEvent) {
        val fora = event.jda.guilds.filterNot { permite(it.id) }
        if (fora.isEmpty()) return
        log.warn(
            "{} servidor(es) fora de GUILD_IDS — comandos e menções serão recusados neles. " +
                "Some o id em GUILD_IDS para liberar, ou remova o bot pelo painel do Discord: {}",
            fora.size,
            fora.joinToString { "${it.name} (${it.id})" },
        )
    }
}
