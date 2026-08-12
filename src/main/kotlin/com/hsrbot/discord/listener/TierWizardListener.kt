package com.hsrbot.discord.listener

import com.hsrbot.discord.command.TierListCommand
import com.hsrbot.discord.util.BotMessages
import com.hsrbot.tier.Grade
import com.hsrbot.tier.MudancasSpec
import com.hsrbot.tier.Papel
import com.hsrbot.tier.Rascunho
import com.hsrbot.tier.TierListService
import com.hsrbot.tier.TierWizard
import com.hsrbot.tier.TierWizard.Companion.PREFIXO
import com.hsrbot.tier.celula
import com.hsrbot.tier.comCelula
import com.hsrbot.tier.papelDaCelula
import com.hsrbot.tier.tamanho
import com.hsrbot.tier.tierDaCelula
import com.hsrbot.tier.titulo
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Drives the `/tierlist` form: every select, button and modal lands here, updates the draft and
 * redraws whatever it came from.
 *
 * There is no session map. The draft key rides in each component id and the answers live in
 * Postgres, so a form survives a restart, the 15-minute interaction timeout and someone leaving it
 * open overnight — and two clicks can never disagree about what the current list is.
 */
@Component
class TierWizardListener(
    private val listas: TierListService,
    private val wizard: TierWizard,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        val id = parse(event.componentId) ?: return
        // The navigation select is not an answer about the list — it is the row that could never be
        // buttons — so it is handled before anything touches the grade.
        if (TierWizard.celulaDe(id.campo, TierWizard.IR) != null) return navegar(event, id)

        val (celula, fatia) = TierWizard.fatiaDe(id.campo) ?: return
        val r = carregar(event, id.chave) ?: return
        listas.salvar(id.chave, aplicar(r.grade, celula, fatia, event.values.filter { it != TierWizard.VAZIO }))
        redesenhar(event, id.chave, celula)
    }

    private fun navegar(event: StringSelectInteractionEvent, id: Id) {
        when (val destino = event.values.firstOrNull()) {
            null -> return
            TierWizard.VER -> gerar(event, id.chave)
            else -> TierWizard.celulaDe(destino, TierWizard.PAGINA)?.let { redesenhar(event, id.chave, it) }
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = parse(event.componentId) ?: return
        TierWizard.celulaDe(id.campo, TierWizard.PAGINA)?.let { return redesenhar(event, id.chave, it) }
        // Checked before the exact matches below: "ajp2" is not "aj", but reading it as one would be
        // a silent wrong branch rather than a compile error.
        TierWizard.papelDe(id.campo)?.let { return abrirAjustes(event, id.chave, it) }
        when (id.campo) {
            TierWizard.VER -> gerar(event, id.chave)
            TierWizard.PUBLICAR -> publicar(event, id.chave)
            TierWizard.AJUSTES -> escolherColuna(event, id.chave)
            TierWizard.VOLTAR -> voltar(event, id.chave)
            TierWizard.COPIAR -> copiar(event, id.chave, confirmado = false)
            TierWizard.COPIAR_OK -> copiar(event, id.chave, confirmado = true)
        }
    }

    // -------------------- estado -------------------- //

    /**
     * One alphabetical slice of one cell answered.
     *
     * A select only ever reports the ids IT is showing, so the other three slices' picks are carried
     * over by hand — reading this one at face value would empty three quarters of the cell on every
     * click. The slices are recomputed from the grade as it is BEFORE the edit, which is stable:
     * this cell's contents never affect which characters its own slices offer.
     */
    private fun aplicar(grade: Grade, celula: Int, fatia: Int, escolhas: List<String>): Grade {
        val papel = papelDaCelula(celula)
        val tier = tierDaCelula(celula)
        val nesta = wizard.fatias(grade, papel, tier).getOrNull(fatia).orEmpty().toSet()
        val outras = grade.celula(papel, tier).filterNot { it in nesta }
        return grade.comCelula(papel, tier, (outras + escolhas).distinct())
    }

    // -------------------- ajustes -------------------- //

    /**
     * Swaps the image's own controls for the four column buttons. The image stays on screen the
     * whole time, which is the point — the markers being edited are the ones visible on it.
     */
    private fun escolherColuna(event: ButtonInteractionEvent, chave: String) {
        val r = listas.carregar(chave) ?: return sumiu(event)
        val manuais = Papel.entries.filter { listas.manual(r, it) }
        val estado = if (manuais.isEmpty()) {
            "Todas as colunas estão no **automático** — comparadas com a sua última lista publicada."
        } else {
            "Escritas por você: **${manuais.joinToString(", ") { it.rotulo }}**. O resto está no automático."
        }
        event.editMessage(
            "Qual coluna você quer ajustar?\n-# $estado\n" +
                "-# As caixas abrem com o que a imagem está mostrando agora — edite à vontade. " +
                "Deixar as três vazias devolve a coluna pro automático.",
        ).setComponents(wizard.controlesAjustes(chave)).queue()
    }

    /** The three boxes for one column, prefilled with whatever the image is currently drawing. */
    private fun abrirAjustes(event: ButtonInteractionEvent, chave: String, papel: Papel) {
        val r = listas.carregar(chave) ?: return sumiu(event)
        val m = listas.mudancasDe(r, papel)
        event.replyModal(
            wizard.modalAjustes(
                chave, papel,
                listas.nomesDe(m.novas), listas.nomesDe(m.subiram), listas.nomesDe(m.desceram),
            ),
        ).queue()
    }

    /**
     * A submitted column.
     *
     * What comes back IS the answer for it — the boxes arrived prefilled, so a name still there was
     * kept on purpose and one that is gone was removed on purpose. Three empty boxes is the one
     * input that means something else: it hands the column back to the automatic diff, which is
     * otherwise unreachable once an answer has been written.
     *
     * ponytail: the cost of that rule is that "this column has no markers at all, and I mean it"
     * cannot be said when the diff would find some. Nobody has wanted to say it; if that changes,
     * the escape hatch is a fifth button on the column picker rather than a fourth box.
     */
    override fun onModalInteraction(event: ModalInteractionEvent) {
        val id = parse(event.modalId) ?: return
        val papel = TierWizard.papelDe(id.campo) ?: return
        if (listas.carregar(id.chave) == null) return sumiu(event)

        val (novas, e1) = listas.resolverNomes(texto(event, TierWizard.NOVAS_IN))
        val (subiram, e2) = listas.resolverNomes(texto(event, TierWizard.SUBIRAM_IN))
        val (desceram, e3) = listas.resolverNomes(texto(event, TierWizard.DESCERAM_IN))
        val manual = MudancasSpec(novas, subiram, desceram)
        listas.salvarAjustes(id.chave, papel, manual.takeIf { !it.vazio })

        val erros = (e1 + e2 + e3).distinct()
        val aviso = when {
            erros.isNotEmpty() ->
                "Ajustei **${papel.rotulo}**, mas não achei: ${erros.joinToString(", ")}.\n" +
                    "-# Se a personagem tem mais de um caminho, diz qual — “Desbravadora (Recordação)” ou só “RMC”."
            manual.vazio -> "**${papel.rotulo}** voltou pro automático."
            else -> "Ajustei **${papel.rotulo}**."
        }
        mostrarImagem(event, id.chave, autorDe(event.member?.effectiveName, event.user.name), aviso)
    }

    private fun voltar(event: ButtonInteractionEvent, chave: String) {
        val r = listas.carregar(chave) ?: return sumiu(event)
        event.editMessage("Ficou assim.").setComponents(wizard.controles(chave, r.grade)).queue()
    }

    // -------------------- copiar -------------------- //

    /**
     * Someone else's published list into the clicker's own draft.
     *
     * Their open draft is what a copy has to overwrite — the unique index allows exactly one per
     * mode — so a draft with anything in it asks first. That confirmation is the whole reason this
     * is two steps: the alternative is silently throwing away work nobody agreed to lose.
     *
     * The published list itself is never touched. It is immutable, which is exactly what makes its
     * key safe to hand to strangers on a public button.
     */
    private fun copiar(event: ButtonInteractionEvent, chave: String, confirmado: Boolean) {
        val origem = listas.carregar(chave)
        if (origem == null || !origem.publicada) {
            event.reply(COPIA_SUMIU).setEphemeral(true).queue()
            return
        }
        val meu = listas.rascunhoAberto(event.user.id, origem.modo)
        if (!confirmado && meu != null && meu.grade.tamanho() > 0) {
            event.reply(
                "Você já tem uma tier list de **${TierListService.MODOS[origem.modo]}** em andamento, " +
                    "com ${meu.grade.tamanho()} colocações. Copiar essa aqui substitui a sua.",
            )
                .setEphemeral(true)
                .setActionRow(Button.danger(TierWizard.id(chave, TierWizard.COPIAR_OK), "Substituir mesmo assim"))
                .queue()
            return
        }
        val copia = listas.copiar(event.user.id, origem)
        val pagina = wizard.pagina(copia.chave, copia, TierListCommand.PRIMEIRA_CELULA)
        val texto = "## Tier list · ${titulo(copia)}\n" +
            "-# Copiei a lista pra você — agora ela é sua. Mexe à vontade e publica quando quiser.\n" +
            pagina.texto
        // The confirmation path edits the warning it came from; the direct path is a fresh reply,
        // since the message clicked is the public one and must not be touched.
        if (confirmado) {
            event.editMessage(texto).setComponents(pagina.linhas).queue()
        } else {
            event.reply(texto).setEphemeral(true).setComponents(pagina.linhas).queue()
        }
    }

    // -------------------- imagem -------------------- //

    private fun gerar(event: GenericComponentInteractionCreateEvent, chave: String) =
        mostrarImagem(event, chave, autorDe(event.member?.effectiveName, event.user.name), "Ficou assim.")

    /**
     * Renders the list and puts it back on screen with its controls.
     *
     * Always deferred: a cold asset cache means ~100 avatar fetches from the CDN, and doing that on
     * the gateway thread would stall every other interaction the bot is handling.
     */
    private fun mostrarImagem(event: IMessageEditCallback, chave: String, autor: String, conteudo: String) {
        event.deferEdit().queue()
        CompletableFuture.supplyAsync({ listas.carregar(chave)?.let { it to listas.png(chave, autor) } }, executor)
            .whenComplete { resultado, erro ->
                val r = resultado?.first
                val png = resultado?.second
                if (erro != null || r == null || png == null) {
                    log.error("falhou ao gerar a tier list {}", chave, erro)
                    event.hook.editOriginal(BotMessages.ERROR).setComponents().queue()
                    return@whenComplete
                }
                event.hook.editOriginal(
                    MessageEditBuilder()
                        .setContent(conteudo)
                        // setAttachments and not setFiles: this same path also runs over a message
                        // that ALREADY shows an image (coming back from Ajustes), and setFiles adds
                        // to the attachment list rather than replacing it — the author would end up
                        // with the old and the new list side by side.
                        .setAttachments(FileUpload.fromData(png, "tierlist.png"))
                        .setComponents(wizard.controles(chave, r.grade))
                        .build(),
                ).queue()
            }
    }

    /**
     * Posts the list and closes the draft, with the button that lets anyone start from it.
     *
     * An anonymous list is posted without the mention — the interaction that made it was ephemeral,
     * so the author's name appears nowhere at all; the server's own line is on the image either way.
     */
    private fun publicar(event: GenericComponentInteractionCreateEvent, chave: String) {
        event.deferEdit().queue()
        val autor = autorDe(event.member?.effectiveName, event.user.name)
        CompletableFuture.supplyAsync({ listas.carregar(chave)?.let { it to listas.png(chave, autor) } }, executor)
            .whenComplete { resultado, erro ->
                val r = resultado?.first
                val png = resultado?.second
                if (erro != null || r == null || png == null) {
                    log.error("falhou ao publicar a tier list {}", chave, erro)
                    event.hook.editOriginal(BotMessages.ERROR).setComponents().queue()
                    return@whenComplete
                }
                val assinatura = if (r.anonimo) "" else " — por ${event.user.asMention}"
                event.channel
                    .sendMessage("**Tier list · ${titulo(r)}**$assinatura")
                    .addFiles(FileUpload.fromData(png, "tierlist.png"))
                    .setComponents(wizard.botaoCopiar(chave))
                    .queue(
                        {
                            listas.publicar(chave)
                            event.hook.editOriginal("Publiquei no canal! A próxima já começa daqui.")
                                .setComponents().setAttachments().queue()
                        },
                        {
                            log.warn("não consegui publicar a tier list {}: {}", chave, it.message)
                            event.hook.editOriginal("Não consegui mandar no canal — me falta permissão por aqui.").queue()
                        },
                    )
            }
    }

    // -------------------- desenho -------------------- //

    private fun redesenhar(event: IMessageEditCallback, chave: String, celula: Int) {
        val r = listas.carregar(chave) ?: return
        val desenho = wizard.pagina(chave, r, celula)
        event.editMessage("## Tier list · ${titulo(r)}\n${desenho.texto}")
            .setComponents(desenho.linhas)
            // setAttachments(), not setFiles(): this is what DROPS the image when the form comes
            // back after a render. setFiles() would only add new ones.
            .setAttachments()
            .queue()
    }

    private fun carregar(event: IMessageEditCallback, chave: String): Rascunho? =
        listas.carregar(chave) ?: null.also { sumiu(event) }

    private fun sumiu(event: IMessageEditCallback) {
        event.editMessage(SUMIU).setComponents().setAttachments().queue()
    }

    /** The name drawn on the image: the server nickname when there is one. */
    private fun autorDe(apelido: String?, usuario: String) = apelido ?: usuario

    private fun texto(event: ModalInteractionEvent, id: String) = event.getValue(id)?.asString.orEmpty()

    private fun parse(componentId: String): Id? {
        val partes = componentId.split(":")
        if (partes.size < 3 || partes[0] != PREFIXO) return null
        return Id(partes[1], partes[2])
    }

    private data class Id(val chave: String, val campo: String)

    private companion object {
        const val SUMIU = "Essa tier list não existe mais — roda o `/tierlist` de novo."
        const val COPIA_SUMIU = "Essa tier list não está mais disponível pra copiar."
    }
}
