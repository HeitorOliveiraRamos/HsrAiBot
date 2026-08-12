package com.hsrbot.discord.command

import com.hsrbot.guia.GuiaService
import com.hsrbot.guia.GuiaWizard
import com.hsrbot.hsr.HsrCharacterService
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executor

/**
 * `/guia <personagem>` — opens the form the builds team fills in to produce a build card.
 *
 * The character is an autocomplete option because it is the one list that cannot be a select: ~100
 * characters against a 25-option ceiling, where typing is the filter. Choices carry the game id as
 * their value, so the fourteen rows that share a name (both 7 de Março, every Desbravador path)
 * can never be confused with each other.
 *
 * Anyone can run it. The wizard itself lives in [GuiaWizard] and
 * [com.hsrbot.discord.listener.GuiaWizardListener].
 */
@Component
class GuiaCommand(
    private val personagens: HsrCharacterService,
    private val guias: GuiaService,
    private val wizard: GuiaWizard,
    private val executor: Executor,
) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = NOME

    /** Abre o assistente e já desenha a prévia. Os botões do wizard não passam por aqui. */
    override val cooldownSeconds = 10L

    override val definition: CommandData = definicao()

    override fun autocomplete(event: CommandAutoCompleteInteractionEvent) = event.replyPersonagens(personagens)

    override fun handle(event: SlashCommandInteractionEvent) {
        val digitado = event.getOption(OPCAO)?.asString.orEmpty()
        val id = personagens.idDigitado(digitado)
        val personagemId = id?.toIntOrNull()
        if (personagemId == null) {
            event.replyEphemeral("Não achei a personagem “$digitado”. Escolhe uma da lista que eu sugiro.")
            return
        }

        val rascunho = guias.rascunho(event.user.id, personagemId)
        if (rascunho == null) {
            event.replyEphemeral("Essa personagem ainda não está no meu banco de dados.")
            return
        }
        val anexo = event.getOption(OPCAO_ARTE)?.asAttachment
        if (anexo == null) {
            val pagina = wizard.pagina(rascunho.chave, rascunho.spec, 1, PAGINAS_INICIAIS)
            event.reply(cabecalho(id, pagina.texto)).setEphemeral(true).setComponents(pagina.linhas).queue()
            return
        }
        // Refused on the METADATA, before a byte moves: Discord already decoded the upload well
        // enough to know whether it is an image and how big it is.
        if (!anexo.isImage || anexo.size > ARTE_MAX_BYTES) {
            event.replyEphemeral("A arte tem que ser uma imagem de até ${ARTE_MAX_BYTES / 1024 / 1024}MB.")
            return
        }
        // Downloading and re-encoding on the gateway thread would stall every other interaction, so
        // defer. A failed upload still opens the wizard, with the complaint above it — the draft
        // exists eitherway, and answering "no" with no form is the one useless outcome here.
        event.deferReply(true).queue()
        anexo.proxy.download()
            .thenApplyAsync({ entrada ->
                // The nickname they go by in THIS server, falling back to the account name — the
                // same rule /tierlist signs a list with.
                val autor = event.member?.effectiveName ?: event.user.name
                entrada.use { guias.anexarArte(rascunho.chave, it.readAllBytes(), autor) }
            }, executor)
            .handle { erro, falha ->
                falha?.let { log.error("falhou ao anexar arte na guia {}", rascunho.chave, it) }
                if (falha == null) erro else "Não consegui baixar essa imagem."
            }
            .thenAccept { aviso ->
                // Re-read: attaching just rewrote the spec (the hash, and the frame it auto-set).
                val spec = guias.carregar(rascunho.chave)?.spec ?: return@thenAccept
                val pagina = wizard.pagina(rascunho.chave, spec, 1, PAGINAS_INICIAIS)
                val rodape = aviso?.let { "\n-# ⚠️ $it" }.orEmpty()
                event.hook.editOriginal(cabecalho(id, pagina.texto) + rodape)
                    .setComponents(pagina.linhas)
                    .queue()
            }
    }

    private fun cabecalho(id: String, texto: String) = "## ${personagens.displayName(id)}\n$texto"

    companion object {
        const val NOME = "guia"
        const val OPCAO = "personagem"
        const val OPCAO_ARTE = "arte"

        /**
         * Built here rather than in the instance so it can be asserted on without a database: a
         * malformed definition doesn't fail the build, it fails the bot's startup in production.
         *
         * Open to every member, deliberately — making a card is the fun part, and a published guide
         * is signed with its author's mention. If a server ever needs to narrow that, Discord's own
         * Integrations screen does it per-role without a redeploy.
         */
        /**
         * The image option is how an upload gets into the wizard AT ALL: a Discord modal holds text
         * and nothing else, and a button cannot ask for a file — an ATTACHMENT option on the command
         * that already opens the form is the only place the platform will take one. Re-running
         * `/guia` resumes the open draft, so attaching art later never costs an answer.
         */
        fun definicao(): CommandData =
            Commands.slash(NOME, "Monta o card de build de uma personagem")
                .addOptions(
                    OptionData(OptionType.STRING, OPCAO, "Personagem do card", true)
                        .setAutoComplete(true),
                    OptionData(OptionType.ATTACHMENT, OPCAO_ARTE, "Sua arte pro card (opcional)", false),
                )
                .setGuildOnly(true)

        /** Both paged set lists start on their first half. */
        const val PAGINAS_INICIAIS = "00"

        /**
         * Upload ceiling. Generous next to Discord's own 10MB, because it is only the first gate —
         * `normalizarArte` is what actually protects the bot, by refusing the pixel count rather
         * than the byte count.
         */
        const val ARTE_MAX_BYTES = 16 * 1024 * 1024
    }
}
