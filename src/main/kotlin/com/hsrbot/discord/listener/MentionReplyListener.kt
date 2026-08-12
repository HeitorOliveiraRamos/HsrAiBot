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
import com.hsrbot.discord.ChainEntry
import com.hsrbot.discord.GuildGuard
import com.hsrbot.discord.ReplyChainResolver
import com.hsrbot.discord.util.BotMessages
import com.hsrbot.discord.util.Cooldowns
import com.hsrbot.discord.util.DiscordMessageSender
import com.hsrbot.discord.util.ProgressStatus
import com.hsrbot.discord.util.TypingIndicator
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Replies when the bot is @-mentioned in any channel. Skipped when the mentioning user
 * is already in an active `/iniciar-conversa` session (the chat listener handles those).
 *
 * Context is scoped to the Discord reply chain: a fresh @-mention (no reply pointer)
 * starts with a clean slate, while replying to one of the bot's messages walks that thread
 * upward so the back-and-forth carries context. Two separate mention threads in the same
 * channel never bleed into each other. The user block resolved by [UsuarioService]
 * (effective name, live highest role and permissions, plus whatever the user asked the bot
 * to remember) is injected as a system block.
 */
@Component
class MentionReplyListener(
    private val ai: OllamaAiService,
    private val sender: DiscordMessageSender,
    private val conversations: ConversationService,
    private val usuarioService: UsuarioService,
    private val replyChainResolver: ReplyChainResolver,
    private val properties: BotProperties,
    private val runtime: RuntimeConfig,
    private val executor: Executor,
    private val inferenceGate: InferenceGate,
    private val typingIndicator: TypingIndicator,
    private val visionService: VisionService,
    private val cooldowns: Cooldowns,
    private val guildGuard: GuildGuard,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return
        if (conversations.isInActiveSession(event.author.id, event.channel.id)) return
        if (!properties.allowsChannel(event.channel.id, event.isFromGuild)) return
        // Silent, unlike the slash-command refusals: a mention that shouldn't be served is
        // one the bot was never meant to see, and answering would be the same noise the
        // fence exists to avoid.
        if (event.isFromGuild && !guildGuard.permite(event.guild.id)) return
        if (!event.isFromGuild && !guildGuard.permiteDm()) return

        val selfUser = event.jda.selfUser
        // Allow processing for either an explicit mention OR when the incoming
        // message is a reply to the bot's own message (so users can continue a
        // mini-chat by replying without re-mentioning).
        val raw = event.message.contentRaw
        val referenced = event.message.referencedMessage
        val isMention = selfUser in event.message.mentions.users
        val isReplyToSelf = referenced?.author?.id == selfUser.id
        if (!isMention && !isReplyToSelf) return
        if (referenced != null && referenced.author.isBot && referenced.author.id != selfUser.id) {
            // Replying to a different bot — ignore.
            return
        }

        val withoutMention = raw.replace("<@${selfUser.id}>", "").trim()
        // The DM window is the longer one: a channel makes an impatient user visible to
        // everyone in it, a DM makes them visible to nobody.
        val cooldownSeconds =
            if (event.isFromGuild) properties.reply.cooldownSeconds else properties.reply.dmCooldownSeconds
        val wait = cooldowns.tentar(Cooldowns.mencao(event.author.id), cooldownSeconds)
        if (wait > 0) {
            event.message.reply(BotMessages.cooldown(wait)).queue()
            return
        }

        // Kill switch (`/ia estado:desligar`) — stay silent, don't spam the channel.
        if (!runtime.aiEnabled) {
            return
        }

        // Bound concurrent LLM pipelines: a single Ollama serializes generations, so a burst
        // of mentions would otherwise pile up and slow every reply. When the gate is full,
        // answer immediately in character instead of joining the queue. The cooldown above
        // already paces a single user's retries, so this can't be spammed.
        if (!inferenceGate.tryAcquire()) {
            event.message.reply(BotMessages.busy(event.author.effectiveName)).queue()
            return
        }

        // Immediate feedback while the (slow, local) LLM pipeline runs; stopped in the
        // same completion handler that releases the gate permit. The progress status is a
        // reply that gets edited as pipeline stages advance, then deleted with the answer.
        val typing = typingIndicator.start(event.channel)
        val progress = ProgressStatus(event.message)

        try {
            CompletableFuture
                .supplyAsync(
                    {
                        // Resolve / upsert the user row on the AI executor so the JDA lookups
                        // don't block the gateway thread.
                        val resolved = usuarioService.resolveForEvent(event)

                        // Reconstruct the Discord reply chain (oldest → newest). Bounded by
                        // maxHops + budget so worst-case latency stays predictable; safe to
                        // call here because we're already off the gateway thread.
                        val chain = replyChainResolver.resolveChain(event.message, selfUser.id)
                        val currentName = resolved?.effectiveName ?: event.author.effectiveName

                        // Image attachments become text (build screenshots etc.) so the rest
                        // of the pipeline stays text-only. Null when vision is disabled or
                        // fails — the reply then proceeds exactly as before.
                        val content = VisionService.augmentContent(
                            withoutMention,
                            visionService.describeFirstImage(event.message),
                        )
                        val history = buildHistory(chain, referenced, currentName, content, selfUser.id)

                        ai.respond(
                            history = history,
                            extraSystemPrompt = resolved?.systemPrompt,
                            userName = resolved?.effectiveName,
                            progress = progress,
                        )
                    },
                    executor,
                )
                // Single completion handler so the gate permit is released exactly once,
                // whether the pipeline succeeded or threw.
                .whenComplete { reply, ex ->
                    try {
                        when {
                            // Cancelled via the status button: the "Cancelando..." line
                            // (kept by progress.close()) is the whole outcome.
                            progress.isCancelled -> {}
                            ex != null -> {
                                log.error("MentionReplyListener failed", ex)
                                event.message.reply(BotMessages.ERROR).queue()
                            }
                            else -> sender.replyLong(event.message, reply)
                        }
                    } finally {
                        progress.close()
                        typing.close()
                        inferenceGate.release()
                    }
                }
        } catch (e: Exception) {
            // supplyAsync can reject synchronously if the executor is saturated; release the
            // permit we just took so it isn't leaked.
            progress.close()
            typing.close()
            inferenceGate.release()
            log.error("MentionReplyListener could not submit work", e)
            event.message.reply(BotMessages.ERROR).queue()
        }
    }

    /**
     * Assembles the LLM history from the reply chain (when present) plus the current
     * user turn. Every human/other-bot turn is prefixed with `[name]:` so the voice
     * model can tell speakers apart in multi-user chains.
     *
     * Fallback when the chain is empty:
     *  - if the message is a direct reply to another human, keep the legacy
     *    `[em resposta a ...]` snippet so single-hop replies still carry that context
     *    even when chain resolution couldn't walk further.
     *  - otherwise just the current turn (the original stateless behavior).
     */
    private fun buildHistory(
        chain: List<ChainEntry>,
        referenced: Message?,
        currentUserName: String,
        currentContent: String,
        selfUserId: String,
    ): List<ConversationMessage> = buildList {
        if (chain.isNotEmpty()) {
            addAll(historyFromChain(chain, currentUserName, currentContent))
        } else {
            // If chain resolution failed but the message is a direct reply, include
            // the referenced message as context. If the referenced message was from
            // the bot, add it as an ASSISTANT turn; otherwise for a human add the
            // legacy "[em resposta a ...]" snippet.
            if (referenced != null) {
                if (referenced.author.id == selfUserId) {
                    add(
                        ConversationMessage(
                            conversationId = 0L,
                            role = MessageRole.ASSISTANT,
                            content = referenced.contentRaw,
                        )
                    )
                } else if (!referenced.author.isBot) {
                    add(
                        ConversationMessage(
                            conversationId = 0L,
                            role = MessageRole.USER,
                            content = "[em resposta a ${referenced.author.effectiveName}: " +
                                "\"${referenced.contentRaw.take(500)}\"]",
                        )
                    )
                }
            }
            add(
                ConversationMessage(
                    conversationId = 0L,
                    role = MessageRole.USER,
                    content = currentContent,
                )
            )
        }
    }

    internal companion object {
        /**
         * Pure mapping of a resolved reply [chain] (oldest → newest) plus the current user
         * turn into LLM history. Each human/other-bot turn is prefixed with `[name]:` so the
         * voice model can tell speakers apart; the bot's own turns stay unprefixed (they map
         * to the assistant role). Extracted from [buildHistory] so this prefixing contract
         * is unit-testable without a live JDA [Message].
         */
        internal fun historyFromChain(
            chain: List<ChainEntry>,
            currentUserName: String,
            currentContent: String,
        ): List<ConversationMessage> = buildList {
            chain.forEach { entry ->
                val text = when {
                    entry.role == MessageRole.ASSISTANT -> entry.content
                    entry.isOtherBot -> "[outro bot ${entry.authorName}]: ${entry.content}"
                    else -> "[${entry.authorName}]: ${entry.content}"
                }
                add(ConversationMessage(conversationId = 0L, role = entry.role, content = text))
            }
            add(
                ConversationMessage(
                    conversationId = 0L,
                    role = MessageRole.USER,
                    content = "[$currentUserName]: $currentContent",
                )
            )
        }
    }
}
