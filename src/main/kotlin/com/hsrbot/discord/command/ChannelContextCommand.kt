package com.hsrbot.discord.command

import com.hsrbot.ai.OllamaAiService
import com.hsrbot.config.BotProperties
import com.hsrbot.config.RuntimeConfig
import com.hsrbot.conversation.ConversationMessage
import com.hsrbot.conversation.MessageRole
import com.hsrbot.discord.util.BotMessages
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

@Component
class ChannelContextCommand(
    private val ai: OllamaAiService,
    private val properties: BotProperties,
    private val runtime: RuntimeConfig,
    private val executor: Executor,
) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "contexto-do-canal"
    override val requiresAi: Boolean = true

    /** Resume 50 mensagens no modelo — a passada mais longa que um comando dispara. */
    override val cooldownSeconds = 30L

    override val definition: CommandData =
        Commands.slash(name, "Verifica o contexto atual do canal que você está")

    override fun handle(event: SlashCommandInteractionEvent) {
        // This path summarizes with the LLM, so the `/ia` kill switch applies. It doesn't use
        // the InferenceGate, so the check has to be explicit here.
        if (!runtime.aiEnabled) {
            event.replyEphemeral(BotMessages.AI_OFF)
            return
        }
        event.deferReply(true).queue()

        event.channel.history.retrievePast(properties.context.historyFetchSize).queue(
            { recent ->
                val history = recent.reversed().map { msg ->
                    ConversationMessage(
                        conversationId = 0L,
                        role = MessageRole.USER,
                        content = "${msg.author.name}${if (msg.author.isBot) "Bot" else ""}: ${msg.contentRaw}",
                    )
                }
                val prompt = "Based on the previous message history, provide a summary of " +
                    "the conversation's context in high detail, explaining what author had " +
                    "in it's mind. The summary should highlight the main topics discussed " +
                    "and the participants involved. Stay in whatever voice your persona defines."

                val withPrompt = history + ConversationMessage(
                    conversationId = 0L,
                    role = MessageRole.USER,
                    content = prompt,
                )
                CompletableFuture
                    .supplyAsync({ ai.chat(withPrompt) }, executor)
                    .thenAccept { summary -> event.hook.sendMessage(summary).queue() }
                    .exceptionally { ex ->
                        log.error("Failed to summarize channel context", ex)
                        event.hook.sendMessage(BotMessages.ERROR).queue()
                        null
                    }
            },
            { error ->
                log.error("Failed to fetch channel history", error)
                event.hook.sendMessage(
                    "Não foi possível recuperar o histórico de mensagens para resumir o contexto."
                ).queue()
            },
        )
    }
}
