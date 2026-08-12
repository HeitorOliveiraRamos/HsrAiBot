package com.hsrbot.discord.command

import com.hsrbot.config.RuntimeConfig
import com.hsrbot.discord.util.BotMessages
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `/ia estado:desligar|ligar` — the AI kill switch, restricted to the **server owner**.
 *
 * Two layers, because neither is enough alone: [DefaultMemberPermissions.DISABLED] hides the
 * command from every non-admin (Discord enforces that server-side, so it never reaches the
 * bot), and the owner check below rejects the admins that Discord still lets through — there
 * is no "owner only" default permission to declare.
 *
 * Off means every LLM pass refuses: mentions, chat sessions, `/hsr`, `/contexto-do-canal` and
 * the vision pass. Everything that doesn't talk to Ollama — moderation, `/build`, `/uid` —
 * keeps working. The state is persisted by [RuntimeConfig], so a restart doesn't silently
 * bring the AI back.
 */
@Component
class IaCommand(private val runtime: RuntimeConfig) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "ia"

    override val definition: CommandData =
        Commands.slash(name, "Liga ou desliga as funções de IA do bot (só o dono do servidor pode usar)")
            .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
            .addOptions(
                OptionData(
                    OptionType.STRING,
                    "estado",
                    "desligar = para tudo que usa IA; ligar = volta ao normal",
                    true,
                ).addChoice("desligar", OFF).addChoice("ligar", ON)
            )

    override fun handle(event: SlashCommandInteractionEvent) {
        val member = event.member ?: return event.replyEphemeral(BotMessages.GUILD_ONLY)
        if (!member.isOwner) return event.replyEphemeral(BotMessages.OWNER_ONLY)

        val enable = event.getOption("estado")?.asString != OFF
        if (enable == runtime.aiEnabled) {
            return event.replyEphemeral(
                if (enable) "Já estou funcionando completamente, não precisa forçar a barra" else "Eu já nem posso falar e você quer me silenciar ainda mais? Assim fico sentida!"
            )
        }
        runtime.setAiEnabled(enable)
        log.info("AI switch set to {} by owner {}", if (enable) "ON" else "OFF", member.id)

        // Not ephemeral: a bot that stops answering the whole server should say so where the
        // whole server can see it.
        event.reply(
            if (enable) {
                "Eba! Pode me chamar que eu respondo dessa vez."
            } else {
                "Até mais!.\n" +
                    "-# Comandos que não usam IA (moderação, `/build`, `/uid`) continuam funcionando."
            }
        ).queue()
    }

    private companion object {
        const val ON = "on"
        const val OFF = "off"
    }
}
