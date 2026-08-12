package com.hsrbot.discord.command

import com.hsrbot.card.CardRenderer
import com.hsrbot.card.RankRenderer
import com.hsrbot.card.Ranking
import com.hsrbot.discord.util.BotMessages
import com.hsrbot.hsr.HsrCharacterService
import com.hsrbot.rank.RankingService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.utils.FileUpload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * `/rank [personagem] [escopo]` — o ranking das builds que o `/build` já pontuou.
 *
 * Sem personagem é o ranking geral: TODAS as builds pontuadas ranqueadas entre si, com o dono de
 * cada uma no crédito — é um ranking de gente, então a mesma personagem aparece quantas vezes for
 * preciso. Com personagem é o ranking daquela personagem, todo mundo que tem ela ordenado pela nota.
 *
 * Nada aqui calcula nota nenhuma — quem calcula é o `/build`, e o [RankingService] guarda. Sem LLM
 * no caminho, então a resposta é rápida e os números são os mesmos do card de build.
 */
@Component
class RankCommand(
    private val ranking: RankingService,
    private val personagens: HsrCharacterService,
    private val executor: Executor,
) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = NOME

    /** Consulta o placar e desenha a tabela. */
    override val cooldownSeconds = 10L

    override val definition: CommandData = definicao()

    override fun autocomplete(event: CommandAutoCompleteInteractionEvent) =
        event.replyPersonagens(personagens)

    override fun handle(event: SlashCommandInteractionEvent) {
        val digitado = event.getOption(OPCAO_PERSONAGEM)?.asString?.trim()?.takeIf { it.isNotEmpty() }
        // Uma sugestão escolhida já chega como id; o que foi digitado à mão passa pelo mesmo resolver
        // fuzzy do resto do bot. Não resolveu, recusa: cair no ranking geral calado seria responder
        // outra pergunta.
        val alvo = digitado?.let { personagens.idDigitado(it) }
        if (digitado != null && alvo == null) {
            event.replyEphemeral(BotMessages.rankPersonagemDesconhecida(digitado))
            return
        }
        // Na DM não existe servidor pra filtrar, então o escopo é global de qualquer jeito.
        val guild = event.guild.takeIf { event.getOption(OPCAO_ESCOPO)?.asString != ESCOPO_GLOBAL }

        event.deferReply().queue()
        CompletableFuture
            .supplyAsync({ ranking.pagina(alvo, guild, 0) }, executor)
            .whenComplete { pagina, ex ->
                if (ex != null) {
                    log.error("/rank falhou para alvo {} guild {}", alvo, guild?.id, ex)
                    event.hook.sendMessage(BotMessages.ERROR).queue()
                    return@whenComplete
                }
                if (pagina == null) {
                    val nome = alvo?.let { personagens.displayName(it) }
                    event.hook
                        .sendMessage(
                            if (nome == null) BotMessages.RANK_VAZIO
                            else BotMessages.rankSemNotas(nome, guild != null),
                        )
                        .queue()
                    return@whenComplete
                }
                event.hook.editOriginalEmbeds(embed(pagina))
                    .setAttachments(FileUpload.fromData(RankRenderer.png(pagina), ARQUIVO))
                    .setComponents(botoes(alvo, guild, pagina))
                    .queue()
            }
    }

    internal companion object {

        const val NOME = "rank"
        const val OPCAO_PERSONAGEM = "personagem"
        const val OPCAO_ESCOPO = "escopo"
        const val ARQUIVO = "rank.png"

        const val ESCOPO_SERVIDOR = "servidor"
        const val ESCOPO_GLOBAL = "global"

        /** Prefixo do customId dos botões, e o que o [com.hsrbot.discord.listener.RankButtonListener] escuta. */
        const val PREFIXO = "rank"

        /** Sem personagem no ranking geral — o customId não tem campo vazio. */
        const val SEM_ALVO = "-"

        /**
         * A imagem é o conteúdo; o embed é só a moldura que dá título, cor do elemento e o número da
         * página sem gastar pixel do card.
         */
        fun embed(r: Ranking): MessageEmbed = EmbedBuilder()
            .setTitle("🏆 ${r.titulo} · ${if (r.escopo == "GLOBAL") "global" else r.servidorNome ?: "servidor"}")
            .setColor(CardRenderer.accentFor(r.elemento))
            .setImage("attachment://$ARQUIVO")
            .setFooter("Página ${r.pagina + 1}/${r.paginas}  ·  ${r.total} builds  ·  as notas vêm do /build")
            .build()

        /**
         * ◀ ▶ apontando pros índices vizinhos, que o listener dobra de volta pra faixa — os botões
         * não guardam cursor nenhum, igual os de [com.hsrbot.discord.util.MessagePaginator].
         *
         * A guild NÃO vai no id: ela sai do próprio evento, então ninguém leva estes botões pra outro
         * servidor e lê o ranking de lá. Uma página só é lista vazia de botões.
         */
        fun botoes(alvo: String?, guild: Guild?, r: Ranking): List<ActionRow> {
            if (r.paginas <= 1) return emptyList()
            val chave = "$PREFIXO:${alvo ?: SEM_ALVO}:${if (guild != null) "s" else "g"}"
            return listOf(
                ActionRow.of(
                    Button.secondary("$chave:${r.pagina - 1}", "◀"),
                    Button.secondary("$chave:${r.pagina + 1}", "▶"),
                ),
            )
        }

        /**
         * Montada aqui e não na instância pra poder ser testada sem banco: uma definição malformada
         * não quebra o build, quebra a subida do bot em produção.
         */
        fun definicao(): CommandData =
            Commands.slash(NOME, "O ranking das builds que eu já avaliei")
                .addOptions(
                    OptionData(
                        OptionType.STRING, OPCAO_PERSONAGEM,
                        "De quem é o ranking (vazio = as melhores builds de todo mundo)", false,
                    ).setAutoComplete(true),
                    OptionData(OptionType.STRING, OPCAO_ESCOPO, "Deste servidor ou de todo mundo", false)
                        .addChoices(
                            Command.Choice("Deste servidor", ESCOPO_SERVIDOR),
                            Command.Choice("Global", ESCOPO_GLOBAL),
                        ),
                )
    }
}
