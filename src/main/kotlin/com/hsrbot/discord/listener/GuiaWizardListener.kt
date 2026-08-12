package com.hsrbot.discord.listener

import com.hsrbot.card.Enquadramento
import com.hsrbot.card.Foco
import com.hsrbot.discord.util.BotMessages
import com.hsrbot.guia.ConeSpec
import com.hsrbot.guia.FocoSpec
import com.hsrbot.guia.GuiaFormulario
import com.hsrbot.guia.GuiaService
import com.hsrbot.guia.GuiaSpec
import com.hsrbot.guia.GuiaWizard
import com.hsrbot.guia.GuiaWizard.Companion.PREFIXO
import com.hsrbot.guia.MetaSpec
import com.hsrbot.guia.RastroSpec
import com.hsrbot.hsr.HsrCharacterService
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.modals.Modal
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Drives the `/guia` form: every select, button and modal submission lands here, updates the
 * draft's spec and redraws the page it came from.
 *
 * There is no session map. The draft key rides in each component id and the answers live in
 * Postgres, so a wizard survives a restart, a 15-minute interaction timeout and someone leaving it
 * open overnight — and two clicks can never disagree about what the current answers are.
 */
@Component
class GuiaWizardListener(
    private val guias: GuiaService,
    private val wizard: GuiaWizard,
    private val personagens: HsrCharacterService,
    private val executor: Executor,
) : ListenerAdapter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
        val id = parse(event.componentId) ?: return
        // The navigation select is not an answer about the build — it is the row that used to be
        // four buttons, so it is handled before anything touches the spec.
        GuiaWizard.indiceDe(id.campo, GuiaWizard.IR)?.let { return navegar(event, id, it) }

        val spec = carregar(event, id.chave) ?: return
        // The "▼ Mostrar as outras" entry is a page flip, not an answer — the real picks made in
        // the same click are still saved.
        val escolhas = event.values.filter { it != GuiaWizard.MAIS }
        val paginas = if (GuiaWizard.MAIS in event.values) virar(id.paginas, id.campo) else id.paginas

        guias.salvar(id.chave, aplicar(spec, id.campo, escolhas))
        redesenhar(event, id.chave, GuiaWizard.paginaDe(id.campo), paginas)
    }

    /**
     * One entry of the navigation select: another page, the card, or the Detalhes modal.
     *
     * The select's own id carries the page it is sitting on ([GuiaWizard.IR]`<n>`), which is how the
     * Detalhes modal knows where to put the author back — it is the one modal reachable from every
     * page, so it cannot assume a fixed one the way the framing and metas modals can.
     */
    private fun navegar(event: StringSelectInteractionEvent, id: Id, origem: Int) {
        when (val destino = event.values.firstOrNull()) {
            null -> return
            GuiaWizard.MODAL -> abrirModal(event, id.chave) { chave, spec -> wizard.modal(chave, spec, origem) }
            GuiaWizard.GERAR -> gerar(event, id.chave)
            else -> GuiaWizard.paginaDoCampo(destino)?.let { irPara(event, id, it) }
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val id = parse(event.componentId) ?: return
        GuiaWizard.paginaDoCampo(id.campo)?.let { return irPara(event, id, it) }
        // The framing pad — the arrows, the zoom and Recomeçar — all land here and redraw the card.
        if (Enquadramento.dir(id.campo) != null || id.campo == Enquadramento.RESET) return nudge(event, id)
        when (id.campo) {
            // Only a wizard left open from before the navigation select still has this button.
            GuiaWizard.MODAL -> abrirModal(event, id.chave) { chave, spec -> wizard.modal(chave, spec) }
            GuiaWizard.METAS_MODAL -> abrirMetas(event, id.chave)
            GuiaWizard.TIRAR_ARTE -> {
                guias.removerArte(id.chave)
                // Straight back to the framing view — now on the official art, arrows and all.
                enquadrar(event, id.chave, id.paginas)
            }
            GuiaWizard.GERAR -> gerar(event, id.chave)
            GuiaWizard.PUBLICAR -> publicar(event, id.chave)
        }
    }

    /**
     * One press of the framing pad. Nudges the draft's frame and redraws — through [editar] because
     * the pad also sits under a rendered card, and a finished guide is immutable, so moving its
     * framing has to start from a copy.
     *
     * Recomeçar (and any nudge that lands back on the fallback) stores null: an untouched frame keeps
     * the guide's hash equal to every other guide on the default, so they share one rendered card.
     */
    private fun nudge(event: ButtonInteractionEvent, id: Id) {
        val r = guias.editar(id.chave, event.user.id)
        if (r == null) {
            event.editMessage(GUIA_SUMIU).setComponents().queue()
            return
        }
        val spec = r.spec
        val fallback = wizard.focoBase(spec)
        // RESET carries no Dir, so it falls straight through to null — back to the fallback frame.
        val novo: FocoSpec? = Enquadramento.dir(id.campo)?.let { dir ->
            val base = (spec.foco ?: fallback)?.let { Foco(it.x, it.y, it.largura, it.altura) } ?: Foco.INTEIRO
            val f = Enquadramento.aplicar(base, dir)
            FocoSpec(f.x, f.y, f.w, f.h).takeIf { it != fallback }
        }
        guias.salvar(r.chave, spec.copy(foco = novo))
        enquadrar(event, r.chave, id.paginas)
    }

    /**
     * Opens a page. EVERY jump goes through [GuiaService.editar], including the ones between two
     * wizard pages, because the same jumps also sit under a rendered card — and a finished guide is
     * immutable, so editing one has to start from a copy. On a draft `editar` hands the draft
     * straight back, so the one path costs nothing.
     */
    private fun irPara(event: GenericComponentInteractionCreateEvent, id: Id, pagina: Int) {
        when (val r = guias.editar(id.chave, event.user.id)) {
            null -> event.editMessage(GUIA_SUMIU).setComponents().queue()
            // Keep ITS key from here on: a clone has a new one, and the old key is a finished guide.
            // The art page shows a live card to frame against, so it renders instead of redrawing.
            else -> if (pagina == GuiaWizard.PG_ARTE) enquadrar(event, r.chave, id.paginas)
            else redesenhar(event, r.chave, pagina, id.paginas)
        }
    }

    override fun onModalInteraction(event: ModalInteractionEvent) {
        val id = parse(event.modalId) ?: return
        val spec = carregar(event, id.chave) ?: return
        if (id.campo == GuiaWizard.METAS_MODAL) {
            // One box per chosen status, in the order the modal was built from: an empty one is a
            // status with no target, which the card draws as a numbered priority.
            guias.salvar(
                id.chave,
                spec.copy(
                    metas = spec.metas.mapIndexed { i, m ->
                        m.copy(alvo = texto(event, "${GuiaWizard.METAS_IN}$i").trim().takeIf { it.isNotEmpty() })
                    },
                ),
            )
            redesenhar(event, id.chave, GuiaWizard.PG_METAS, PAGINAS_PADRAO)
            return
        }
        detalhes(event, id, spec)
    }

    /**
     * The Detalhes modal: order for every column, plus the superimposition ranks.
     *
     * Each box arrives prefilled with the current answer, so what comes back IS the answer — a name
     * swapped for another set that really exists swaps the set, and a line removed removes it. What
     * a box can never do is empty a column by accident: a blank one leaves that column alone, and a
     * box nobody could read a name out of does too, with the complaint shown under the form.
     */
    private fun detalhes(event: ModalInteractionEvent, id: Id, spec: GuiaSpec) {
        val cones = GuiaFormulario.parseSobreposicoes(
            texto(event, GuiaWizard.SOBREPOSICAO_IN), spec.cones, wizard.conesDoCaminho(spec.personagemId),
        )
        val reliquias = GuiaFormulario.parseConjuntos(
            texto(event, GuiaWizard.ORDEM_REL_IN), GuiaWizard.nomesDe(spec.reliquias),
            wizard.conjuntosDisponiveis(GuiaWizard.RELIQUIAS_SQL),
        )
        val ornamentos = GuiaFormulario.parseConjuntos(
            texto(event, GuiaWizard.ORDEM_ORN_IN), GuiaWizard.nomesDe(spec.ornamentos),
            wizard.conjuntosDisponiveis(GuiaWizard.ORNAMENTOS_SQL),
        )
        val digitadas = texto(event, GuiaWizard.SINERGIAS_IN)
        val (sinergias, desconhecidas) =
            if (digitadas.isBlank()) spec.sinergias to emptyList()
            else resolver(GuiaFormulario.parseSinergias(digitadas))

        guias.salvar(
            id.chave,
            spec.copy(
                cones = cones.valores,
                // Rebuilt rather than reordered, because the names may have changed — and rebuilt
                // through `linhas` so a "2 + 2" survives as long as both of its sets did.
                reliquias = GuiaWizard.linhas(reliquias.valores, divisaoAtual(spec, reliquias.valores), 4),
                ornamentos = GuiaWizard.linhas(ornamentos.valores, null, 2),
                sinergias = sinergias.take(GuiaFormulario.MAX_SINERGIAS),
            ),
        )
        val avisos = cones.erros + reliquias.erros + ornamentos.erros + desconhecidas
        // Back to the page the modal was opened from — it is reachable from all six, and the origin
        // rides in the modal's own id because a submission carries nothing else about where it came
        // from.
        val origem = id.paginas.getOrNull(2)?.digitToIntOrNull() ?: GuiaWizard.PG_EQUIP
        redesenhar(event, id.chave, origem, PAGINAS_PADRAO, avisos)
    }

    // -------------------- ações -------------------- //

    /** A modal has to be the immediate answer to a click — nothing may be deferred before it. */
    private fun abrirModal(
        event: GenericComponentInteractionCreateEvent,
        chave: String,
        qual: (String, GuiaSpec) -> Modal,
    ) {
        val rascunho = guias.carregar(chave)
        if (rascunho == null) {
            event.editMessage(GUIA_SUMIU).setComponents().queue()
            return
        }
        event.replyModal(qual(chave, rascunho.spec)).queue()
    }

    /**
     * The value boxes are built from the chosen stats, so with none chosen there is no modal to
     * build — JDA rejects one with zero fields. The button is disabled in that case, which is not
     * quite enough: a second client sitting on an older copy of the message still shows it live.
     */
    private fun abrirMetas(event: GenericComponentInteractionCreateEvent, chave: String) {
        val spec = guias.carregar(chave)?.spec
        when {
            spec == null -> event.editMessage(GUIA_SUMIU).setComponents().queue()
            spec.metas.isEmpty() -> event.reply(METAS_VAZIAS).setEphemeral(true).queue()
            else -> event.replyModal(wizard.modalMetas(chave, spec)).queue()
        }
    }

    private fun gerar(event: GenericComponentInteractionCreateEvent, chave: String) {
        event.deferEdit().queue()
        // Rendering fetches art from the CDN on a cold cache — never on the gateway thread.
        CompletableFuture.supplyAsync({ guias.cartao(chave) }, executor).whenComplete { cartao, erro ->
            if (erro != null || cartao == null) {
                log.error("falhou ao gerar a guia {}", chave, erro)
                event.hook.editOriginal(BotMessages.ERROR).setComponents().queue()
                return@whenComplete
            }
            val aviso = if (cartao.reaproveitada) "\n-# Alguém já tinha feito essa guia igualzinha — reaproveitei." else ""
            event.hook.editOriginal(
                MessageEditBuilder()
                    .setContent("Ficou assim.$aviso")
                    .setFiles(FileUpload.fromData(cartao.png, "guia.png"))
                    // Straight back into whichever section is wrong, instead of one "Ajustar" that
                    // drops you at page 1 to walk the whole form again. Buttons here and a select
                    // in the form: this message has four spare rows, the form has none.
                    .setComponents(
                        ActionRow.of(
                            GuiaWizard.SECOES.map { n ->
                                Button.secondary(
                                    GuiaWizard.id(cartao.chave, GuiaWizard.campoDaPagina(n), PAGINAS_PADRAO),
                                    GuiaWizard.NOMES_PAGINA.getValue(n),
                                )
                            } + Button.success(
                                GuiaWizard.id(cartao.chave, GuiaWizard.PUBLICAR, PAGINAS_PADRAO),
                                "Publicar no canal",
                            ),
                        ),
                    )
                    .build(),
            ).queue()
        }
    }

    private fun publicar(event: GenericComponentInteractionCreateEvent, chave: String) {
        event.deferEdit().queue()
        CompletableFuture.supplyAsync({ guias.cartao(chave) }, executor).whenComplete { cartao, erro ->
            if (erro != null || cartao == null) {
                log.error("falhou ao publicar a guia {}", chave, erro)
                event.hook.editOriginal(BotMessages.ERROR).setComponents().queue()
                return@whenComplete
            }
            val nome = guias.carregar(cartao.chave)?.spec?.personagemId?.let { guias.nomePersonagem(it) }.orEmpty()
            event.channel
                .sendMessage("**Guia de $nome** — por ${event.user.asMention}")
                .addFiles(FileUpload.fromData(cartao.png, "guia.png"))
                .queue(
                    {
                        guias.publicar(cartao.chave)
                        event.hook.editOriginal("Publiquei no canal!").setComponents().setAttachments().queue()
                    },
                    {
                        log.warn("não consegui publicar a guia {}: {}", cartao.chave, it.message)
                        event.hook.editOriginal("Não consegui mandar no canal — me falta permissão por aqui.").queue()
                    },
                )
        }
    }

    // -------------------- estado -------------------- //

    /**
     * One answered field folded into the spec.
     *
     * The set and cone lists go through [GuiaWizard.ordenar] first. Those selects collect WHICH sets
     * and cones the guide uses, never in what order — that answer belongs to the author and is given
     * in the Detalhes modal, and taking `event.values` at face value would overwrite it with
     * Discord's own option order on every click.
     */
    private fun aplicar(spec: GuiaSpec, campo: String, escolhas: List<String>): GuiaSpec {
        GuiaWizard.indiceDe(campo, GuiaWizard.SINERGIA)?.let { return sinergias(spec, it, escolhas) }
        GuiaWizard.indiceDe(campo, GuiaWizard.RASTRO)?.let { return rastros(spec, it, escolhas.firstOrNull()) }
        return when (campo) {
            // A cone that stays selected keeps the rank the author already gave it; a new one starts
            // at the rank most people actually own it at.
            GuiaWizard.CONES -> {
                val raridades = wizard.conesDoCaminho(spec.personagemId)
                spec.copy(
                    cones = GuiaWizard.ordenar(spec.cones.map { it.nome }, escolhas).map { n ->
                        ConeSpec(
                            n,
                            spec.cones.firstOrNull { it.nome == n }?.sobreposicao
                                ?: GuiaFormulario.sobreposicaoPadrao(raridades[n]),
                        )
                    },
                )
            }
            GuiaWizard.RELIQUIAS -> GuiaWizard.ordenar(GuiaWizard.nomesDe(spec.reliquias), escolhas).let { nomes ->
                spec.copy(reliquias = GuiaWizard.linhas(nomes, divisaoAtual(spec, nomes), 4))
            }
            GuiaWizard.ORNAMENTOS -> spec.copy(
                ornamentos = GuiaWizard.linhas(GuiaWizard.ordenar(GuiaWizard.nomesDe(spec.ornamentos), escolhas), null, 2),
            )
            GuiaWizard.COMBINACAO -> spec.copy(
                reliquias = GuiaWizard.linhas(GuiaWizard.nomesDe(spec.reliquias), par(escolhas.firstOrNull()), 4),
            )
            // A status that stays selected keeps the target the author already typed for it.
            GuiaWizard.METAS -> spec.copy(
                metas = GuiaWizard.ordenar(spec.metas.map { it.stat }, escolhas)
                    .map { s -> MetaSpec(s, spec.metas.firstOrNull { it.stat == s }?.alvo) },
            )
            GuiaWizard.CORPO -> spec.copy(corpo = escolhas.firstOrNull())
            GuiaWizard.PES -> spec.copy(pes = escolhas.firstOrNull())
            GuiaWizard.ESFERA -> spec.copy(esfera = escolhas.firstOrNull())
            GuiaWizard.CORDA -> spec.copy(corda = escolhas.firstOrNull())
            else -> spec
        }
    }

    /**
     * One alphabetical slice of the roster answered.
     *
     * A select only ever reports the names IT is showing, so the other three slices' picks are
     * carried over by hand — reading this one at face value would empty the rest of the team on
     * every click. Order survives the same way it does everywhere else: what stays keeps its place.
     */
    private fun sinergias(spec: GuiaSpec, fatia: Int, escolhas: List<String>): GuiaSpec {
        val nomes = wizard.fatiasSinergias(spec.personagemId).getOrNull(fatia).orEmpty()
        val outras = spec.sinergias.filterNot { it in nomes }
        return spec.copy(
            sinergias = GuiaWizard.ordenar(spec.sinergias, outras + escolhas).take(GuiaFormulario.MAX_SINERGIAS),
        )
    }

    /**
     * One trace slot answered. The list stays compact — position IS priority, so there are no holes
     * in it — and a trace picked for a slot it is not already in MOVES there rather than appearing
     * twice, since the same ability cannot be both the second and the fourth priority.
     */
    private fun rastros(spec: GuiaSpec, slot: Int, escolha: String?): GuiaSpec {
        val nomes = spec.rastros.map { it.rotulo }.toMutableList()
        if (escolha == null) {
            if (slot in nomes.indices) nomes.removeAt(slot)
        } else {
            nomes.remove(escolha)
            if (slot in nomes.indices) nomes[slot] = escolha else nomes += escolha
        }
        return spec.copy(rastros = nomes.take(GuiaWizard.MAX_RASTROS).map { RastroSpec(it) })
    }

    /** `"2:0:1"` → the pair of chosen sets worn at two pieces each; anything else means no split. */
    private fun par(valor: String?): Pair<Int, Int>? {
        val n = valor?.removePrefix(GuiaWizard.DIVIDIR)?.split(":")?.mapNotNull { it.toIntOrNull() }
        return if (valor?.startsWith(GuiaWizard.DIVIDIR) == true && n?.size == 2) n[0] to n[1] else null
    }

    /** Keeps an existing "2 + 2" alive across a re-pick, as long as both of its sets survived. */
    private fun divisaoAtual(spec: GuiaSpec, escolhas: List<String>): Pair<Int, Int>? {
        val dividida = spec.reliquias.firstOrNull { it.partes.size > 1 } ?: return null
        val a = escolhas.indexOf(dividida.partes[0].nome)
        val b = escolhas.indexOf(dividida.partes[1].nome)
        return if (a >= 0 && b >= 0) a to b else null
    }

    /**
     * Flips one paged list to its other half.
     *
     * ponytail: two halves is all 32 relic and 28 ornament sets need. Past 48 sets this hides the
     * tail — swap the toggle for a modulo over the real page count when that day comes.
     */
    private fun virar(paginas: String, campo: String): String {
        val i = if (campo == GuiaWizard.RELIQUIAS) 0 else 1
        val virada = if (paginas.getOrNull(i) == '0') '1' else '0'
        return paginas.mapIndexed { pos, c -> if (pos == i) virada else c }.joinToString("")
    }

    /**
     * Typed names → canonical ones, plus a complaint for each that named no single character.
     *
     * The canonical name is the DISPLAY one, so a Trailblazer is stored as "Desbravadora (A
     * Recordação)" and never as the bare "Desbravadora" five rows answer to — the card looks its
     * portrait up from this string, and a name that means five people can only pick one at random.
     * It is also exactly what the synergy lists carry as their values, so a name typed here and a
     * name ticked there are the same string.
     */
    private fun resolver(nomes: List<String>): Pair<List<String>, List<String>> {
        val bons = mutableListOf<String>()
        val erros = mutableListOf<String>()
        nomes.forEach { n ->
            val canonico = personagens.canonicalName(n)
            if (canonico == null) erros += SEM_PERSONAGEM.format(n)
            else bons += canonico
        }
        return bons to erros
    }

    // -------------------- desenho -------------------- //

    /**
     * The framing view: the card as the draft looks right now, with the pad under it. Redrawn on
     * every arrow press, so it renders through [GuiaService.previa] — which draws the draft WITHOUT
     * finishing it — never [GuiaService.cartao], which would freeze the guide mid-nudge. Off the
     * gateway like [gerar], because a cold cache still fetches art from the CDN.
     */
    private fun enquadrar(event: IMessageEditCallback, chave: String, paginas: String) {
        event.deferEdit().queue()
        CompletableFuture.supplyAsync({ guias.previa(chave) }, executor).whenComplete { png, erro ->
            val rascunho = guias.carregar(chave)
            if (erro != null || png == null || rascunho == null) {
                log.error("falhou ao enquadrar a guia {}", chave, erro)
                event.hook.editOriginal(BotMessages.ERROR).setComponents().queue()
                return@whenComplete
            }
            val nome = guias.nomePersonagem(rascunho.spec.personagemId).orEmpty()
            val desenho = wizard.pagina(chave, rascunho.spec, GuiaWizard.PG_ARTE, paginas)
            event.hook.editOriginal(
                MessageEditBuilder()
                    .setContent("## $nome\n${desenho.texto}")
                    // setAttachments, not setFiles: a nudge redraws a message that ALREADY carries the
                    // previous frame, and setFiles would stack the two side by side.
                    .setAttachments(FileUpload.fromData(png, "guia.png"))
                    .setComponents(desenho.linhas)
                    .build(),
            ).queue()
        }
    }

    private fun redesenhar(
        event: IMessageEditCallback,
        chave: String,
        pagina: Int,
        paginas: String,
        avisos: List<String> = emptyList(),
    ) {
        val rascunho = guias.carregar(chave) ?: return
        val nome = guias.nomePersonagem(rascunho.spec.personagemId).orEmpty()
        val desenho = wizard.pagina(chave, rascunho.spec, pagina, paginas)
        val rodape = if (avisos.isEmpty()) "" else "\n" + avisos.joinToString("\n") { "-# ⚠️ $it" }
        event.editMessage("## $nome\n${desenho.texto}$rodape")
            .setComponents(desenho.linhas)
            // setAttachments(), not setFiles(): this is what DROPS the card image when the
            // form comes back after a render. setFiles() would only add new ones.
            .setAttachments()
            .queue()
    }

    private fun carregar(event: IMessageEditCallback, chave: String): GuiaSpec? {
        val rascunho = guias.carregar(chave)
        if (rascunho == null) {
            event.editMessage(GUIA_SUMIU).setComponents().queue()
            return null
        }
        return rascunho.spec
    }

    private fun texto(event: ModalInteractionEvent, id: String) = event.getValue(id)?.asString.orEmpty()

    private fun parse(componentId: String): Id? {
        val partes = componentId.split(":")
        if (partes.size < 4 || partes[0] != PREFIXO) return null
        return Id(partes[1], partes[2], partes[3])
    }

    private data class Id(val chave: String, val campo: String, val paginas: String)

    private companion object {
        /** Coming back from a modal or a rendered card, neither paged select is on screen. */
        const val PAGINAS_PADRAO = GuiaWizard.PAGINAS_PADRAO

        const val GUIA_SUMIU = "Essa guia não existe mais — roda o `/guia` de novo."
        const val METAS_VAZIAS = "Escolhe os status na lista primeiro, aí eu pergunto os alvos."

        /** Covers the ambiguous case too: "Desbravadora" is five people, and only the Path says which. */
        const val SEM_PERSONAGEM = "Não achei a personagem “%s” para as sinergias. " +
            "Se ela tem mais de um caminho, diz qual — “Desbravadora (Recordação)” ou só “RMC”."
    }
}
