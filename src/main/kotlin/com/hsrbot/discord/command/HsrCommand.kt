package com.hsrbot.discord.command

import com.hsrbot.ai.InferenceGate
import com.hsrbot.ai.OllamaAiService
import com.hsrbot.config.RuntimeConfig
import com.hsrbot.discord.util.BotMessages
import com.hsrbot.discord.util.DiscordMessageSender
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * `/hsr <pergunta>` — direct line to the HSR knowledge pipeline.
 *
 * Two reasons this exists alongside @-mentions:
 *  - routing: the question goes straight to grounding, skipping the intent gate, so it
 *    can never be misclassified as chat;
 *  - UX: `deferReply` gives a native "pensando…" state with a 15-minute window, which
 *    fits a slow local-Ollama knowledge answer far better than a plain message reply.
 */
@Component
class HsrCommand(
    private val ai: OllamaAiService,
    private val sender: DiscordMessageSender,
    private val executor: Executor,
    private val inferenceGate: InferenceGate,
    private val runtime: RuntimeConfig,
) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = "hsr"
    override val requiresAi: Boolean = true

    /** Curto: o [com.hsrbot.ai.InferenceGate] já limita quantas passadas rodam juntas. */
    override val cooldownSeconds = 5L

    override val definition: CommandData =
        Commands.slash(name, "Pergunte algo sobre Honkai: Star Rail (respondo só com dados reais)")
            .addOption(
                OptionType.STRING,
                "pergunta",
                "Sua pergunta sobre HSR: personagem, kit, build, cone, relíquia, lore…",
                true,
            )

    override fun handle(event: SlashCommandInteractionEvent) {
        val question = event.getOption("pergunta")?.asString?.trim().orEmpty()
        if (question.isEmpty()) {
            event.reply(BotMessages.knowledgeMiss(event.user.effectiveName)).setEphemeral(true).queue()
            return
        }
        if (!runtime.aiEnabled) {
            event.replyEphemeral(BotMessages.AI_OFF)
            return
        }
        // Same concurrency ceiling as the message listeners; when full, decline fast and
        // ephemerally instead of deferring a reply we might take too long to fill.
        if (!inferenceGate.tryAcquire()) {
            event.reply(BotMessages.busy(event.user.effectiveName)).setEphemeral(true).queue()
            return
        }
        event.deferReply().queue()

        try {
            CompletableFuture
                .supplyAsync({ ai.answerHsrQuestion(question, event.user.effectiveName) }, executor)
                .whenComplete { answer, ex ->
                    try {
                        if (ex != null) {
                            log.error("/hsr failed for '{}'", question, ex)
                            event.hook.sendMessage(BotMessages.ERROR).queue()
                        } else {
                            sender.sendLong(event.hook, answer)
                        }
                    } finally {
                        inferenceGate.release()
                    }
                }
        } catch (e: Exception) {
            // supplyAsync can reject synchronously if the executor is saturated; release the
            // permit we just took so it isn't leaked.
            inferenceGate.release()
            log.error("/hsr could not submit work", e)
            event.hook.sendMessage(BotMessages.ERROR).queue()
        }
    }
}
