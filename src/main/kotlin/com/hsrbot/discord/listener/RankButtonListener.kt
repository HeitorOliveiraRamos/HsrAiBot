package com.hsrbot.discord.listener

import com.hsrbot.card.RankRenderer
import com.hsrbot.discord.command.RankCommand
import com.hsrbot.discord.util.BotMessages
import com.hsrbot.rank.RankingService
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.utils.FileUpload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Os ◀ ▶ do `/rank`: re-consulta o ranking e redesenha a página no lugar.
 *
 * **Sem estado guardado.** O ranking é consulta viva, então a página é recalculada a cada clique —
 * o que também significa que um card antigo mostra os números de agora quando alguém mexe nele, e
 * que os botões continuam funcionando depois de qualquer restart. O que o customId carrega é só a
 * pergunta (`rank:<personagem|->:<s|g>:<página>`); o servidor sai do próprio evento, de propósito,
 * pra ninguém levar estes botões pra outro lugar e ler o ranking de lá.
 */
@Component
class RankButtonListener(
    private val ranking: RankingService,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val partes = event.componentId.split(":")
        if (partes.size != 4 || partes[0] != RankCommand.PREFIXO) return
        val alvo = partes[1].takeIf { it != RankCommand.SEM_ALVO }
        // Escopo de servidor num lugar sem servidor (a mensagem foi parar numa DM) cai pro global,
        // que é a única leitura possível ali.
        val guild = event.guild.takeIf { partes[2] == "s" }
        val pedida = partes[3].toIntOrNull() ?: return

        event.deferEdit().queue()
        CompletableFuture
            .supplyAsync({ ranking.pagina(alvo, guild, pedida) }, executor)
            .whenComplete { pagina, ex ->
                if (ex != null) {
                    log.error("/rank página {} falhou para alvo {}", pedida, alvo, ex)
                    event.hook.sendMessage(BotMessages.ERROR).setEphemeral(true).queue()
                    return@whenComplete
                }
                if (pagina == null) {
                    // A lista esvaziou embaixo do card (ninguém mais no servidor, base limpa).
                    event.hook.sendMessage(BotMessages.RANK_VAZIO).setEphemeral(true).queue()
                    return@whenComplete
                }
                // setAttachments e NÃO setFiles: a mensagem já mostra uma imagem, e setFiles somaria
                // a nova em vez de trocar — a mesma pegadinha anotada no TierWizardListener.
                event.hook.editOriginalEmbeds(RankCommand.embed(pagina))
                    .setAttachments(FileUpload.fromData(RankRenderer.png(pagina), RankCommand.ARQUIVO))
                    .setComponents(RankCommand.botoes(alvo, guild, pagina))
                    .queue()
            }
    }
}
