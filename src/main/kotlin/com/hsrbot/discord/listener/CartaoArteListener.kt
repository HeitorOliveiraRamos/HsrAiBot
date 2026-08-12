package com.hsrbot.discord.listener

import com.hsrbot.card.Enquadramento
import com.hsrbot.card.Foco
import com.hsrbot.card.normalizarArte
import com.hsrbot.discord.util.BotMessages
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.imageio.ImageIO

/**
 * The framing step a card grows when — and only when — the member uploaded their own art: the arrows
 * and zoom over the preview move the character inside the picture, then "Publicar no canal" sends it.
 *
 * Without an upload there is nothing to frame (the curated box already places the official
 * illustration), so a command posts straight to the channel and this listener never sees it.
 *
 * One listener for every card, because the framing question is the same one whatever is being framed
 * — the [Previa] carries its own [Previa.desenhar], so `/ascensao` and `/build` differ here by a
 * lambda and a caption rather than by a copy of this file. The controls themselves are
 * [Enquadramento]'s, shared with the `/guia` wizard, so an arrow means the same thing everywhere.
 */
@Component
class CartaoArteListener(
    private val previas: CartaoPrevias,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = parse(event.componentId) ?: return
        val previa = previas.pegar(id.chave)
        if (previa == null) {
            event.editMessage(SUMIU).setComponents().setAttachments().queue()
            return
        }
        if (id.campo == PUBLICAR) return publicar(event, id.chave, previa)
        // Every other button is a framing move, and framing needs a picture behind it — a second
        // client sitting on an older copy of the message is why that is checked rather than trusted.
        val imagem = previa.imagem ?: return
        val foco = Enquadramento.dir(id.campo)?.let { Enquadramento.aplicar(previa.foco, it) }
            ?: Enquadramento.auto(imagem).takeIf { id.campo == Enquadramento.RESET }
            ?: return
        val atualizada = previa.copy(foco = foco)
        previas.guardar(id.chave, atualizada)
        desenhar(event, id.chave, atualizada)
    }

    /** Re-renders the preview in place. Same message, same buttons — only the framing moved. */
    private fun desenhar(event: IMessageEditCallback, chave: String, previa: Previa) {
        event.deferEdit().queue()
        CompletableFuture
            .supplyAsync({ previa.png() }, executor)
            .whenComplete { png, erro ->
                if (erro != null || png == null) {
                    log.error("falhou ao reenquadrar '{}'", previa.titulo, erro)
                    event.hook.editOriginal(BotMessages.ERROR).setComponents().queue()
                    return@whenComplete
                }
                event.hook.editOriginal(
                    MessageEditBuilder()
                        .setContent(textoPrevia(previa.titulo, comArte = true, corpo = previa.corpo))
                        // setAttachments and not setFiles: this message ALREADY shows the previous
                        // framing, and setFiles adds to the attachment list instead of replacing it
                        // — the member would end up with both versions side by side.
                        .setAttachments(FileUpload.fromData(png, previa.arquivo))
                        .setComponents(botoes(chave, comArte = true))
                        .build(),
                ).queue()
            }
    }

    private fun publicar(event: ButtonInteractionEvent, chave: String, previa: Previa) {
        event.deferEdit().queue()
        CompletableFuture.supplyAsync({ previa.png() }, executor).whenComplete { png, erro ->
            if (erro != null || png == null) {
                log.error("falhou ao publicar '{}'", previa.titulo, erro)
                event.hook.editOriginal(BotMessages.ERROR).setComponents().queue()
                return@whenComplete
            }
            event.channel
                .sendMessage(previa.sob("${previa.titulo} — por ${event.user.asMention}"))
                .addFiles(FileUpload.fromData(png, previa.arquivo))
                .queue(
                    {
                        // The picture has done its job; holding it any longer is just heap.
                        previas.esquecer(chave)
                        event.hook.editOriginal("Publiquei no canal!").setComponents().setAttachments().queue()
                    },
                    {
                        log.warn("não consegui publicar o cartão {}: {}", chave, it.message)
                        event.hook.editOriginal("Não consegui mandar no canal — me falta permissão por aqui.").queue()
                    },
                )
        }
    }

    private fun parse(componentId: String): Id? {
        val partes = componentId.split(":")
        if (partes.size < 3 || partes[0] != PREFIXO) return null
        return Id(partes[1], partes[2])
    }

    private data class Id(val chave: String, val campo: String)

    companion object {
        const val PREFIXO = "cartao"
        const val PUBLICAR = "pub"

        fun id(chave: String, campo: String) = "$PREFIXO:$chave:$campo"

        /**
         * What is left to do with a preview: the framing pad and Publicar over a picture, only
         * Publicar without one. The card without an upload IS the official art, and a control with
         * nothing to move is worse than no control — so the arrows are offered only with [comArte].
         */
        // plusElement, not `+`: ActionRow is Iterable<Component>, so a plain `+ ActionRow` picks the
        // flattening overload and returns a list of loose buttons instead of one more row.
        fun botoes(chave: String, comArte: Boolean): List<ActionRow> =
            (if (comArte) Enquadramento.linhas { campo -> id(chave, campo) } else emptyList())
                .plusElement(ActionRow.of(Button.success(id(chave, PUBLICAR), "Publicar no canal")))

        fun textoPrevia(titulo: String, comArte: Boolean, corpo: String = "") =
            "$titulo — só você está vendo.\n" +
                (if (corpo.isBlank()) "" else "$corpo\n") +
                if (comArte) {
                    "-# As setas movem a personagem dentro da arte e a lupa dá zoom; " +
                        "**Recomeçar** volta ao enquadramento inicial. Publica quando estiver bom."
                } else {
                    "-# Confere e publica quando quiser."
                }

        const val SUMIU = "Essa prévia expirou — roda o comando de novo com a imagem."

        /** Footer for a card that posted with the official art because the upload didn't survive. */
        const val ARTE_RECUSADA = "\n-# ⚠️ Não consegui usar a sua imagem, então usei a arte oficial."
    }
}

/**
 * The upload, decoded and made safe, or null for "use the official art" — which covers no upload at
 * all, a download that failed and bytes [normalizarArte] refuses. A bad picture must not cost the
 * member their card: the numbers are the point, and the caller says so in a footer.
 *
 * Shared by every command that takes an `arte` option, since "download it, cap it, re-encode it,
 * give up quietly" is the same job each time.
 */
fun baixarArte(anexo: Message.Attachment?, executor: Executor): CompletableFuture<BufferedImage?> {
    if (anexo == null) return CompletableFuture.completedFuture(null)
    return anexo.proxy.download()
        .thenApplyAsync({ entrada ->
            entrada.use { normalizarArte(it.readAllBytes()) }
                ?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
        }, executor)
        .exceptionally {
            LoggerFactory.getLogger("CartaoArte").warn("não consegui baixar a arte enviada: {}", it.message)
            null
        }
}

/**
 * The open card previews, in memory.
 *
 * ponytail: a map and not a table. A preview is a picture nobody has published, worth exactly one
 * conversation — losing them on a restart costs the member one re-run of the command, and the
 * alternative is a table plus a sweep job for rows whose whole purpose is to be thrown away. The
 * pictures are what make it worth capping: [MAX] of them at ~1280px is tens of megabytes, so the
 * LRU is the ceiling, not a nicety.
 */
@Component
class CartaoPrevias {

    private val abertas: MutableMap<String, Previa> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Previa>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Previa>): Boolean = size > MAX
        },
    )

    fun guardar(chave: String, previa: Previa) {
        abertas[chave] = previa
    }

    fun pegar(chave: String): Previa? = abertas[chave]

    fun esquecer(chave: String) {
        abertas.remove(chave)
    }

    private companion object {
        const val MAX = 24
    }
}

/**
 * One preview: what to call it, whose picture is on it, where the character is inside that picture,
 * and how to draw the thing.
 *
 * [desenhar] is what makes this serve every card. It closes over whatever the card needs — a
 * character id and a service for `/ascensao`, an already-scored showcase for `/build` — so this
 * listener never learns what it is publishing.
 *
 * The decoded image and not the bytes, because every re-framing renders it again and a preview is
 * exactly the case where that happens repeatedly. `equals` therefore compares by reference — nothing
 * compares two of these, and the map is keyed by a uuid — so `copy` is worth more here than a
 * hand-written equals would be.
 *
 * [imagem] is null when the member sent a picture that did not survive [normalizarArte]. They still
 * get the preview and the publish button, on the official art: they asked for a card, and a picture
 * that failed to decode is a reason to say so, not to withhold the card.
 *
 * [corpo] is the prose that belongs UNDER the card — `/build`'s farm plan, which the same command
 * posts under a card that had no upload. Without it here, uploading art silently cost the member the
 * peça mais fraca and the possíveis melhorias: the picture is decoration, the advice is the answer.
 */
data class Previa(
    /** Already formatted for a message: `**Guia de ascensão da Blade**`. */
    val titulo: String,
    val imagem: BufferedImage?,
    val arquivo: String,
    val desenhar: (BufferedImage?, Foco) -> ByteArray?,
    val foco: Foco = Foco.INTEIRO,
    val corpo: String = "",
) {
    fun png(): ByteArray? = desenhar(imagem, foco)

    /** [cabecalho] with [corpo] under it, for a card that carries prose. */
    fun sob(cabecalho: String): String = if (corpo.isBlank()) cabecalho else "$cabecalho\n$corpo"
}
