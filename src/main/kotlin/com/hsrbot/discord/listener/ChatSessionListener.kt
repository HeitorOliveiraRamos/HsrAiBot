package com.hsrbot.discord.listener

import com.hsrbot.ai.InferenceGate
import com.hsrbot.ai.OllamaAiService
import com.hsrbot.ai.VisionService
import com.hsrbot.config.BotProperties
import com.hsrbot.config.RuntimeConfig
import com.hsrbot.conversation.ConversationMessage
import com.hsrbot.conversation.ConversationService
import com.hsrbot.conversation.MessageRole
import com.hsrbot.conversation.UsuarioService
import com.hsrbot.discord.GuildGuard
import com.hsrbot.discord.util.BotMessages
import com.hsrbot.discord.util.DiscordMessageSender
import com.hsrbot.discord.util.ProgressStatus
import com.hsrbot.discord.util.TypingIndicator
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Forwards messages from users with an active `/iniciar-conversa` session to Ollama,
 * persisting both sides of the exchange as a single combined row.
 *
 * Like the @-mention path, this listener injects the user block resolved by
 * [UsuarioService] as a system block so the voice model knows the user's name (avoiding
 * leaked `{nome}` placeholders from the persona examples) plus whatever the user asked the
 * bot to remember.
 *
 * The user turn is no longer persisted before the AI call — exchanges are written as a
 * single combined row after the reply lands. A crash mid-call therefore loses the
 * in-flight user message; chat sessions are short-lived enough that this trade-off
 * (chosen explicitly) is acceptable in exchange for a simpler persistence shape.
 */
@Component
class ChatSessionListener(
    private val conversations: ConversationService,
    private val ai: OllamaAiService,
    private val sender: DiscordMessageSender,
    private val usuarioService: UsuarioService,
    private val properties: BotProperties,
    private val runtime: RuntimeConfig,
    private val executor: Executor,
    private val inferenceGate: InferenceGate,
    private val typingIndicator: TypingIndicator,
    private val visionService: VisionService,
    private val guildGuard: GuildGuard,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return

        val message = event.message
        val userId = message.author.id
        val channelId = message.channel.id

        if (!properties.allowsChannel(channelId, event.isFromGuild)) return
        // A session opened before the server left the allow-list keeps its rows, but stops
        // being answered — the fence is about what this host spends, not about the history.
        if (event.isFromGuild && !guildGuard.permite(event.guild.id)) return
        if (!event.isFromGuild && !guildGuard.permiteDm()) return

        val active = conversations.activeConversation(userId, channelId) ?: return
        val conversationId = active.id ?: return
        val content = message.contentRaw

        // History is the prior persisted exchanges; the current user turn is appended
        // in-memory before the AI call (inside the async block, where the vision pass can
        // augment it) and persisted atomically afterwards along with the reply.
        val priorHistory = conversations.history(conversationId)

        // Kill switch (`/ia estado:desligar`) — the session stays open, it just gets no
        // answers until the owner turns the AI back on. Silent, don't spam the channel.
        if (!runtime.aiEnabled) {
            return
        }

        // Share the same concurrency ceiling as the mention path so an active session and a
        // burst of mentions can't together overload the single Ollama. When full, ask the
        // user to retry rather than queueing behind a slow generation.
        if (!inferenceGate.tryAcquire()) {
            message.reply(BotMessages.BUSY_SESSION).queue()
            return
        }

        // Immediate feedback while the (slow, local) LLM pipeline runs; stopped in the
        // same completion handler that releases the gate permit. The progress status is a
        // reply that gets edited as pipeline stages advance, then deleted with the answer.
        val typing = typingIndicator.start(event.channel)
        val progress = ProgressStatus(message)

        try {
            CompletableFuture
                .supplyAsync(
                    {
                        val resolved = usuarioService.resolveForEvent(event)
                        // The augmented turn (text + extracted image content) is what gets
                        // persisted too, so a follow-up like "e os substatus?" still sees
                        // the image data in the session history.
                        val contentForAi = VisionService.augmentContent(
                            content,
                            visionService.describeFirstImage(message),
                        )
                        val historyForAi = priorHistory + ConversationMessage(
                            conversationId = conversationId,
                            role = MessageRole.USER,
                            content = contentForAi,
                        )
                        contentForAi to ai.respond(
                            history = historyForAi,
                            extraSystemPrompt = resolved?.systemPrompt,
                            userName = resolved?.effectiveName,
                            progress = progress,
                        )
                    },
                    executor,
                )
                // Single completion handler so the gate permit is released exactly once.
                .whenComplete { result, ex ->
                    try {
                        when {
                            // Cancelled via the status button: nothing to send, and the
                            // aborted exchange is deliberately not persisted.
                            progress.isCancelled -> {}
                            ex != null -> {
                                log.error("Failed to process chat-session message for conv {}", conversationId, ex)
                                message.reply(BotMessages.ERROR).queue()
                            }
                            else -> {
                                val (contentForAi, reply) = result
                                val persisted = conversations.recordExchange(conversationId, contentForAi, reply)
                                if (!persisted) {
                                    log.debug("Skipped recording exchange for conv {} — no longer active", conversationId)
                                }
                                sender.replyLong(message, reply)
                            }
                        }
                    } finally {
                        progress.close()
                        typing.close()
                        inferenceGate.release()
                    }
                }
        } catch (e: Exception) {
            progress.close()
            typing.close()
            inferenceGate.release()
            log.error("Failed to submit chat-session work for conv {}", conversationId, e)
            message.reply(BotMessages.ERROR).queue()
        }
    }
}
