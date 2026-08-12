package com.hsrbot.discord.command

import com.hsrbot.card.AscensaoRenderer
import com.hsrbot.card.AscensaoService
import com.hsrbot.card.Enquadramento
import com.hsrbot.card.Foco
import com.hsrbot.discord.listener.CartaoArteListener
import com.hsrbot.discord.listener.CartaoPrevias
import com.hsrbot.discord.listener.Previa
import com.hsrbot.discord.listener.baixarArte
import com.hsrbot.discord.util.BotMessages
import com.hsrbot.hsr.HsrCharacterService
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.utils.FileUpload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.util.UUID
import java.util.concurrent.Executor

/**
 * `/ascensao <personagem>` — posts the ascension guide card: how much a character costs to take to
 * level 80 and to max every trace.
 *
 * No wizard and no draft, unlike `/guia`: the numbers are entirely derived from the harvest (V30's
 * `custos_melhoria` + `materiais`), so the answer to the same character is the same image whoever
 * asks — which is why the plain command posts straight into the channel and signs nothing.
 *
 * An upload changes that, and only that. `arte` takes a picture the same way `/guia` does, and
 * because a picture is the one thing here that needs framing, the card comes back as a private
 * preview with the same "Alterar enquadramento" the guide's art page offers — plus the button that
 * posts it. The member's name goes on the card, since it is now partly theirs.
 * See [com.hsrbot.discord.listener.CartaoArteListener] for the rest of that flow — shared with
 * `/build`, which grew the same upload option.
 */
@Component
class AscensaoCommand(
    private val personagens: HsrCharacterService,
    private val ascensoes: AscensaoService,
    private val previas: CartaoPrevias,
    private val executor: Executor,
) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = NOME

    /** Um cartão de ascensão inteiro por chamada — desenho puro, sem cache que salve. */
    override val cooldownSeconds = 10L

    override val definition: CommandData = definicao()

    override fun autocomplete(event: CommandAutoCompleteInteractionEvent) = event.replyPersonagens(personagens)

    override fun handle(event: SlashCommandInteractionEvent) {
        val digitado = event.getOption(OPCAO)?.asString.orEmpty()
        val personagemId = personagens.idDigitado(digitado)?.toIntOrNull()
        if (personagemId == null) {
            event.replyEphemeral("Não achei a personagem “$digitado”. Escolhe uma da lista que eu sugiro.")
            return
        }

        // Refused on the METADATA, before a byte moves: Discord already decoded the upload well
        // enough to know whether it is an image and how big it is.
        val anexo = event.getOption(GuiaCommand.OPCAO_ARTE)?.asAttachment
        if (anexo != null && (!anexo.isImage || anexo.size > GuiaCommand.ARTE_MAX_BYTES)) {
            event.replyEphemeral("A arte tem que ser uma imagem de até ${GuiaCommand.ARTE_MAX_BYTES / 1024 / 1024}MB.")
            return
        }

        // Rendering fetches icons from the CDN on a cold cache — never on the gateway thread, and
        // neither is downloading and re-decoding a stranger's picture. Ephemeral only when there is
        // something to frame: with no upload the card is finished the moment it is drawn.
        event.deferReply(anexo != null).queue()
        // The nickname they go by in THIS server, falling back to the account name — the same rule
        // /tierlist signs a list with.
        val autor = event.member?.effectiveName ?: event.user.name
        // Re-resolved on every render rather than captured once, so a populate cannot go stale
        // between the preview and the publish. Setting the credit unconditionally is safe: the
        // renderer only draws it beside an uploaded picture.
        val desenhar = { img: BufferedImage?, foco: Foco ->
            ascensoes.cartao(personagemId)
                ?.let { AscensaoRenderer.png(it.copy(arte = it.arte.copy(autor = autor)), img, foco) }
        }
        baixarArte(anexo, executor)
            .thenApplyAsync({ arte ->
                // The first preview is already framed to the character, so an upload that lands well
                // needs no nudging at all — the alpha scan runs here, off the gateway thread.
                val foco = arte?.let(Enquadramento::auto) ?: Foco.INTEIRO
                ascensoes.cartao(personagemId)?.nome?.let { nome -> Triple(nome, desenhar(arte, foco), arte to foco) }
            }, executor)
            .whenComplete { resultado, erro ->
                if (erro != null) {
                    log.error("falhou ao montar a ascensão de {}", personagemId, erro)
                    event.hook.editOriginal(BotMessages.ERROR).queue()
                    return@whenComplete
                }
                val png = resultado?.second
                if (resultado == null || png == null) {
                    event.hook.editOriginal(SEM_CUSTOS).queue()
                    return@whenComplete
                }
                val (nome, _, af) = resultado
                val (arte, foco) = af
                val titulo = TITULO.format(nome)
                val arquivo = FileUpload.fromData(png, ARQUIVO)
                if (anexo == null) {
                    event.hook.editOriginal(titulo).setFiles(arquivo).queue()
                    return@whenComplete
                }
                // They asked for a card WITH a picture, so they get the preview either way — a
                // picture that didn't survive costs them the framing button, never the card.
                val chave = UUID.randomUUID().toString().take(8)
                previas.guardar(chave, Previa(titulo, arte, ARQUIVO, desenhar, foco))
                event.hook
                    .editOriginal(
                        CartaoArteListener.textoPrevia(titulo, arte != null) +
                            if (arte == null) CartaoArteListener.ARTE_RECUSADA else "",
                    )
                    .setFiles(arquivo)
                    .setComponents(CartaoArteListener.botoes(chave, arte != null))
                    .queue()
            }
    }

    companion object {
        const val NOME = "ascensao"
        const val OPCAO = "personagem"

        /**
         * Both cases the service returns null for: a character srs hasn't published costs for yet
         * (the two betas), and a DB hiccup. One message, because the member can do the same thing
         * about either — nothing.
         */
        const val SEM_CUSTOS = "Ainda não tenho os materiais dessa personagem. Ela deve ser novinha demais."

        /** No emoji: the preview and the published post add their own around it. */
        const val TITULO = "**Guia de ascensão da %s**"

        const val ARQUIVO = "ascensao.png"

        /**
         * Built here rather than in the instance so it can be asserted on without a database: a
         * malformed definition doesn't fail the build, it fails the bot's startup in production.
         */
        fun definicao(): CommandData =
            Commands.slash(NOME, "Mostra os materiais pra subir uma personagem e os rastros dela")
                .addOptions(
                    OptionData(OptionType.STRING, OPCAO, "Personagem do card", true)
                        .setAutoComplete(true),
                    // Same reason `/guia` carries one: a Discord command option is the only place
                    // the platform will take a file, and this card has no wizard to ask later.
                    OptionData(OptionType.ATTACHMENT, GuiaCommand.OPCAO_ARTE, "Sua arte pro card (opcional)", false),
                )
                .setGuildOnly(true)
    }
}
