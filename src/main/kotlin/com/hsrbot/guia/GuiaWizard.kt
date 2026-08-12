package com.hsrbot.guia

import com.hsrbot.card.Enquadramento
import com.hsrbot.card.RASTRO_ICONE
import com.hsrbot.card.foco
import com.hsrbot.card.norm
import com.hsrbot.hsr.HsrCharacterService
import com.hsrbot.hsr.HsrTaxonomy
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import kotlin.math.ceil

/**
 * Builds the pages of the `/guia` form and the modals behind them.
 *
 * Everything here is shaped by three hard Discord limits: a message holds five rows, a select takes
 * a whole row and holds 25 options, and a modal holds five TEXT fields and nothing else. The four
 * sections the author sees — equipamento e sinergias, rastros, status e metas, arte — need eleven
 * pickers between them, and eleven pickers do not fit in four messages of five rows. So two of the
 * sections are drawn as two pages each, and **navigation is a select, not a row of buttons**: six
 * pages plus the card plus the Detalhes modal is more than the five buttons a row holds, and a
 * select holds 25 entries in the same single row. A select can open a modal just like a button can
 * (`ComponentInteraction` is an `IModalCallback`), so nothing is lost by moving them into it.
 *
 * The wizard keeps no state of its own. Every answer goes straight into the draft's spec, and the
 * only UI state — which half of the set lists a select is showing — rides in the component ids, so
 * a wizard survives a restart and two people can never race over one draft.
 */
@Component
class GuiaWizard(private val jdbc: JdbcTemplate, private val personagens: HsrCharacterService) {

    /** A rendered page: the text above the form and the rows of components under it. */
    data class Pagina(val texto: String, val linhas: List<ActionRow>)

    fun pagina(chave: String, spec: GuiaSpec, pagina: Int, paginas: String): Pagina = when (pagina) {
        PG_SINERGIAS -> sinergias(chave, spec, paginas)
        PG_RASTROS -> rastros(chave, spec, paginas)
        PG_STATUS -> status(chave, spec, paginas)
        PG_METAS -> metas(chave, spec, paginas)
        PG_ARTE -> arte(chave, spec, paginas)
        else -> equipamento(chave, spec, paginas)
    }

    // -------------------- 1 · equipamento -------------------- //

    private fun equipamento(chave: String, spec: GuiaSpec, paginas: String): Pagina {
        val escolhidas = nomesDe(spec.reliquias)
        val ornamentos = nomesDe(spec.ornamentos)
        val linhas = listOf(
            ActionRow.of(
                menu(id(chave, CONES, paginas), "Cones de luz (até 3)",
                    conesDoCaminho(spec.personagemId).map { (nome, raridade) -> opcao(nome, "$raridade★") },
                    3, spec.cones.map { it.nome }),
            ),
            ActionRow.of(
                menu(id(chave, RELIQUIAS, paginas), "Relíquias (até 3)",
                    paginar(conjuntos(RELIQUIAS_SQL), paginas[0], escolhidas), 3, escolhidas),
            ),
            // Right under the relic list, because it is a question ABOUT that list: which two of the
            // sets just chosen are worn at two pieces each.
            ActionRow.of(menu(id(chave, COMBINACAO, paginas), "Como usar as relíquias", combinacoes(escolhidas), 1,
                listOfNotNull(valorCombinacao(spec.reliquias, escolhidas)))),
            ActionRow.of(
                menu(id(chave, ORNAMENTOS, paginas), "Ornamentos (até 3)",
                    paginar(conjuntos(ORNAMENTOS_SQL), paginas[1], ornamentos), 3, ornamentos),
            ),
            navegacao(chave, paginas, PG_EQUIP),
        )
        return Pagina(
            "**1 · Equipamento.** Já deixei marcado o que a build recomendada usa; troque o que quiser.\n" +
                "-# Cones fora do caminho da personagem e 3★ ficam de fora da lista. Cone 4★ entra em " +
                "**S5** e 5★ em **S1** — dá pra mudar em **Detalhes**.\n" +
                "-# Em **Detalhes** também sai a ordem em que tudo isso aparece no card.",
            linhas,
        )
    }

    // -------------------- 1 · sinergias -------------------- //

    /**
     * The team, as four alphabetical lists.
     *
     * ~97 characters against a 25-option ceiling is the one list that genuinely does not fit, and
     * Discord has no search inside a select (only slash-command autocomplete has it, which is why
     * `/guia` picks the character that way). Four fixed alphabetical slices beat one paged list for
     * the same reason a phone book beats a stack of cards: a name is always in the same place, it
     * is always visible, and nothing has to be paged to find it.
     *
     * Each slice's ceiling counts what the OTHER slices already hold, so the twelve-avatar grid is
     * enforced by Discord itself instead of by silently dropping the thirteenth pick.
     */
    private fun sinergias(chave: String, spec: GuiaSpec, paginas: String): Pagina {
        val fatias = fatiasSinergias(spec.personagemId)
        val linhas = fatias.mapIndexed { i, fatia ->
            val marcados = spec.sinergias.filter { it in fatia }
            val restante = GuiaFormulario.MAX_SINERGIAS - (spec.sinergias.size - marcados.size)
            ActionRow.of(
                menu(id(chave, "$SINERGIA$i", paginas), "Sinergias · ${faixa(fatia)}",
                    fatia.map { opcao(it, null) }, restante.coerceAtLeast(marcados.size).coerceAtLeast(1), marcados),
            )
        }
            // With no roster loaded there is nothing to slice, and a message needs at least the one
            // disabled placeholder rather than a page that is only navigation.
            .ifEmpty {
                listOf(ActionRow.of(menu(id(chave, "${SINERGIA}0", paginas), "Sinergias", emptyList(), 1, emptyList())))
            }
            .plusElement(navegacao(chave, paginas, PG_SINERGIAS))
        return Pagina(
            "**1 · Sinergias.** Quem joga bem com ela — até ${GuiaFormulario.MAX_SINERGIAS}, " +
                "escolhidas ${spec.sinergias.size}.\n" +
                "-# As listas são o elenco inteiro em ordem alfabética, partido em quatro. " +
                "Tanto faz em qual você marca.\n" +
                "-# A ordem delas no card é a ordem em que você marcou — pra trocar, é em **Detalhes**.",
            linhas,
        )
    }

    /**
     * The roster minus the guide's own character, split into four slices that each fit a select.
     *
     * ponytail: four slices is the whole page, so past ~100 characters the tail of the roster falls
     * off the end. When that day comes the last slice gets the "▼ Mostrar as outras" flip the relic
     * lists already use — the pinning in [menu] means nobody loses a pick in the meantime.
     */
    internal fun fatiasSinergias(personagemId: Int): List<List<String>> {
        val nomes = personagens.all().map { it.id }
            .filter { it != personagemId.toString() }
            .map { personagens.displayName(it) }
            .distinct()
            // Accent-insensitive, or "Árgenti" would sort after "Yunli".
            .sortedBy { norm(it) }
        if (nomes.isEmpty()) return emptyList()
        val porFatia = ceil(nomes.size / FATIAS_SINERGIA.toDouble()).toInt().coerceIn(1, MAX_OPCOES)
        return nomes.chunked(porFatia).take(FATIAS_SINERGIA)
    }

    /** "A–F", off the first and last name of a slice, so each list says what it holds. */
    private fun faixa(fatia: List<String>): String {
        val primeira = norm(fatia.first()).firstOrNull()?.uppercaseChar() ?: '?'
        val ultima = norm(fatia.last()).firstOrNull()?.uppercaseChar() ?: '?'
        return if (primeira == ultima) "$primeira" else "$primeira–$ultima"
    }

    // -------------------- 2 · rastros -------------------- //

    /**
     * Trace priority as four ordered selects, one per position.
     *
     * The order IS the answer here, and a select hands its values back in option order rather than
     * click order — so a single "pick up to four" list could never say which one leads. One select
     * per position asks the same question in a way the platform can actually answer, and it retires
     * the typed `Talento > Perícia >= Ataque Básico` line along with the `>=` nobody should have had
     * to learn.
     *
     * The options are the abilities THIS character has an icon for, which is what makes the euphoria
     * and memosprite entries appear exactly where they exist: a trace with no icon is dropped by the
     * renderer, so offering one would be offering an answer that silently does nothing.
     */
    private fun rastros(chave: String, spec: GuiaSpec, paginas: String): Pagina {
        val disponiveis = rastrosDisponiveis(spec.personagemId)
        val escolhidos = spec.rastros.map { it.rotulo }
        val linhas = (0 until MAX_RASTROS).map { i ->
            ActionRow.of(
                menu(id(chave, "$RASTRO$i", paginas), "${i + 1}º rastro",
                    disponiveis.map { opcao(it, null, "${i + 1}º: ") }, 1, listOfNotNull(escolhidos.getOrNull(i))),
            )
        }
            .plusElement(navegacao(chave, paginas, PG_RASTROS))
        return Pagina(
            "**2 · Rastros.** A ordem de prioridade que o card mostra, do primeiro ao quarto.\n" +
                "-# Escolher um rastro que já está em outra posição move ele pra cá. " +
                "Pra deixar de fora, é só desmarcar.",
            linhas,
        )
    }

    /** The trace labels this character actually has artwork for, in the card's own order. */
    internal fun rastrosDisponiveis(personagemId: Int): List<String> {
        val p = jdbc.queryForList("SELECT * FROM personagem_hsr WHERE character_id = ? LIMIT 1", personagemId)
            .firstOrNull() ?: return emptyList()
        return RASTRO_ICONE.filterValues { p[it] != null }.keys.toList()
    }

    // -------------------- 3 · status -------------------- //

    private fun status(chave: String, spec: GuiaSpec, paginas: String): Pagina {
        val slots = listOf(
            CORPO to ("Corpo" to spec.corpo), PES to ("Pés" to spec.pes),
            ESFERA to ("Esfera" to spec.esfera), CORDA to ("Corda" to spec.corda),
        )
        val linhas = slots.map { (campo, dados) ->
            val (rotulo, atual) = dados
            ActionRow.of(
                // The slot goes on every LABEL, not just the placeholder: Discord hides a
                // placeholder as soon as something is chosen, which would leave four identical
                // dropdowns with no way to tell which piece each one is for.
                menu(id(chave, campo, paginas), "$rotulo — status principal",
                    MAIN_STATS.getValue(campo).map { opcao(it, null, "$rotulo: ") }, 1, listOfNotNull(atual)),
            )
        }
            // plusElement, not `+`: ActionRow is Iterable<ItemComponent>, so plain `+` picks the
            // flattening overload and silently turns the rows into a list of loose buttons.
            .plusElement(navegacao(chave, paginas, PG_STATUS))
        return Pagina(
            "**3 · Status.** O status principal de cada peça que dá pra escolher.\n" +
                "-# As metas — o que a build persegue — estão na página seguinte.",
            linhas,
        )
    }

    // -------------------- 3 · metas -------------------- //

    /**
     * The status targets, as a list plus a box for each value.
     *
     * They used to be one free-text field where every line was `Status: alvo`, which meant learning
     * a syntax to answer a question with thirteen possible subjects. A select cannot hold the value
     * and a modal cannot hold a select, so the answer is split across both: pick the stats here,
     * then type only the numbers in a modal whose FIELD LABELS are the stats picked.
     */
    private fun metas(chave: String, spec: GuiaSpec, paginas: String): Pagina {
        val linhas = listOf(
            ActionRow.of(
                menu(id(chave, METAS, paginas), "Metas de status (até ${GuiaFormulario.MAX_METAS})",
                    GuiaFormulario.STATS.map { opcao(it, null) }, GuiaFormulario.MAX_METAS,
                    spec.metas.map { it.stat }),
            ),
            ActionRow.of(
                // Nothing to fill in until a stat is chosen, and a modal with zero fields is a JDA
                // error rather than an empty modal.
                Button.primary(id(chave, METAS_MODAL, paginas), "Valores das metas")
                    .withDisabled(spec.metas.isEmpty()),
            ),
            navegacao(chave, paginas, PG_METAS),
        )
        return Pagina(
            "**3 · Metas.** O que a build persegue. Já deixei marcados os substatus da build recomendada.\n" +
                "-# Em **Valores das metas**: o alvo de cada um (\"100% em combate\", \"3200+\"). " +
                "Sem alvo, o card lista o status como prioridade numerada.",
            linhas,
        )
    }

    // -------------------- 4 · arte -------------------- //

    private fun arte(chave: String, spec: GuiaSpec, paginas: String): Pagina {
        val linhas = (Enquadramento.linhas { campo -> id(chave, campo, paginas) } +
            listOfNotNull(
                // Only once there is something to remove: a way out that removes nothing is a trap.
                ActionRow.of(Button.danger(id(chave, TIRAR_ARTE, paginas), "Tirar a arte enviada"))
                    .takeIf { spec.arte != null },
            )).plusElement(navegacao(chave, paginas, PG_ARTE))
        val arte = if (spec.arte == null) {
            "-# Essa guia está usando a arte oficial — as setas reenquadram ela. Pra usar uma sua, " +
                "roda o `/guia` de novo com a imagem em **arte**; o rascunho continua de onde parou."
        } else {
            "-# Essa guia está usando uma **arte enviada por você**, já enquadrada na personagem."
        }
        return Pagina(
            "**4 · Arte.** Onde a personagem fica dentro da imagem.\n" +
                "-# As setas movem ela, a lupa dá zoom e **Recomeçar** volta ao enquadramento " +
                "inicial. O card acima mostra cada ajuste na hora.\n" + arte,
            linhas,
        )
    }

    // -------------------- navegação -------------------- //

    /**
     * The row every page ends with: any page, the card and the Detalhes modal, in one select.
     *
     * A row of buttons carried this until the form grew to six pages — four section buttons plus the
     * card was already the five-button ceiling, and there was no sixth slot for the pages the two
     * biggest sections had to split into. One select holds all of it in the same single row, which
     * is what leaves four whole rows for the pickers on every page.
     *
     * The page you are on is the select's default value, so the closed dropdown reads as "you are
     * here" rather than as an empty box.
     */
    private fun navegacao(chave: String, paginas: String, atual: Int) = ActionRow.of(
        // The page it sits on rides in its own id, so the Detalhes modal — the one modal every page
        // can open — knows where to put the author back.
        StringSelectMenu.create(id(chave, "$IR$atual", paginas))
            .setPlaceholder("Você está em ${NOMES_PAGINA.getValue(atual)} — ir para…")
            .setMinValues(1)
            .setMaxValues(1)
            .addOptions(
                NOMES_PAGINA.map { (n, nome) ->
                    SelectOption.of(nome, campoDaPagina(n))
                        .withDescription(DICAS_PAGINA[n])
                        .withDefault(n == atual)
                } + listOf(
                    SelectOption.of("Detalhes", MODAL)
                        .withDescription("Ordem dos cones, dos conjuntos e das sinergias, e a sobreposição"),
                    SelectOption.of("Ver o card", GERAR)
                        .withDescription("Monta a imagem com o que já está preenchido"),
                ),
            )
            .build(),
    )

    /** Cones of this character's Path, minus the 3★ ones nobody guides with: name → rarity. */
    internal fun conesDoCaminho(personagemId: Int): Map<String, Int> {
        val caminho = HsrTaxonomy.canonicalPath(
            jdbc.queryForList("SELECT caminho FROM personagem_hsr WHERE character_id = ?", String::class.java, personagemId)
                .firstOrNull(),
        )
        return jdbc.queryForList("SELECT nome, caminho, raridade FROM cones_de_luz WHERE raridade >= 4 ORDER BY raridade DESC, nome")
            .filter { HsrTaxonomy.canonicalPath(it["caminho"] as? String) == caminho }
            // The widest Path has 21 of these today. take() is the seatbelt for the patch that
            // pushes one of them past 25 and would otherwise have Discord reject the message.
            .take(MAX_OPCOES)
            .associate { (it["nome"] as String) to ((it["raridade"] as Number).toInt()) }
    }

    /** Every set name, for the Detalhes box: typing one that exists is how a set gets swapped. */
    internal fun conjuntosDisponiveis(sql: String): List<String> =
        jdbc.queryForList(sql).map { it["nome"] as String }

    private fun conjuntos(sql: String): List<SelectOption> = jdbc.queryForList(sql)
        .map { opcao(it["nome"] as String, (it["efeito_2_pecas"] as? String)?.let { e -> "2 pçs: $e" }) }

    /**
     * How the chosen relic sets are actually worn. Naming both sets in the label is what keeps this
     * unambiguous: Discord returns a select's values in option order, not click order, so "the first
     * two" would be a guess about which pair the author meant to split.
     */
    private fun combinacoes(escolhidas: List<String>): List<SelectOption> {
        val quatro = SelectOption.of("Cada conjunto com 4 peças", QUATRO)
        if (escolhidas.size < 2) return listOf(quatro)
        val pares = escolhidas.indices.flatMap { a -> (a + 1 until escolhidas.size).map { b -> a to b } }
            .map { (a, b) ->
                SelectOption.of("2 ${curto(escolhidas[a])} + 2 ${curto(escolhidas[b])}", "$DIVIDIR$a:$b")
            }
        return (listOf(quatro) + pares).take(MAX_OPCOES)
    }

    // -------------------- modais -------------------- //

    /**
     * The boxes that ask for ORDER — the one answer no select can give, since Discord returns a
     * select's values in option order and never in click order.
     *
     * Every box arrives holding the current answer, so what comes back IS the answer: swap a name
     * for another set that exists and the set is swapped, delete a line and it is gone, move a line
     * and the card moves it. That makes these boxes a faster way to fix a whole column than the
     * lists are, without being the only way to do anything.
     */
    fun modal(chave: String, spec: GuiaSpec, origem: Int = PG_EQUIP): Modal = Modal
        .create(id(chave, MODAL, "$PAGINAS_PADRAO$origem"), "Detalhes")
        .addComponents(
            ActionRow.of(
                campo(SOBREPOSICAO_IN, "Cones — ordem e sobreposição", TextInputStyle.PARAGRAPH,
                    GuiaFormulario.formatSobreposicoes(spec.cones), "Um por linha, na ordem — Nome do cone: S5"),
            ),
            ActionRow.of(
                campo(ORDEM_REL_IN, "Relíquias — ordem", TextInputStyle.PARAGRAPH,
                    GuiaFormulario.formatOrdem(spec.reliquias), "Um conjunto por linha, do primeiro ao último"),
            ),
            ActionRow.of(
                campo(ORDEM_ORN_IN, "Ornamentos — ordem", TextInputStyle.PARAGRAPH,
                    GuiaFormulario.formatOrdem(spec.ornamentos), "Um conjunto por linha, do primeiro ao último"),
            ),
            ActionRow.of(
                // The placeholder is where the Path form gets taught: with five Desbravadoras on the
                // roster, a bare name is refused and nothing else on screen would say why.
                campo(SINERGIAS_IN, "Sinergias — ordem", TextInputStyle.PARAGRAPH,
                    GuiaFormulario.formatSinergias(spec.sinergias),
                    "Sunday, Robin, Desbravadora (Recordação) — ou a sigla, RMC"),
            ),
        )
        .build()

    /**
     * One box per chosen status, LABELLED with it — so the author types "100% em combate" and never
     * the name of the thing they already picked from a list. An empty box is a status with no
     * target, which the card draws as a numbered priority.
     */
    fun modalMetas(chave: String, spec: GuiaSpec): Modal =
        Modal.create(id(chave, METAS_MODAL, PAGINAS_PADRAO), "Valores das metas")
            .addComponents(
                spec.metas.take(GuiaFormulario.MAX_METAS).mapIndexed { i, m ->
                    ActionRow.of(campo("$METAS_IN$i", corte(m.stat, 45), TextInputStyle.SHORT, m.alvo.orEmpty(), "100% em combate"))
                },
            )
            .build()

    /**
     * The frame this guide falls back to when `spec.foco` is null: what a nudge starts from before
     * the author has moved anything, and what [Recomeçar][Enquadramento.RESET] resets to.
     *
     * The listener also compares a nudged frame against this before storing it — one equal to the
     * fallback is stored as null, so a guide left on the default keeps the same hash as every other
     * guide left on it and shares its one rendered card.
     *
     * For the official art that fallback is the curated `arte_foco_*` box. For an uploaded picture
     * it is the WHOLE image — the curated box measures a different illustration and means nothing
     * here; the flattering opening frame is written straight into `spec.foco` by
     * [GuiaService.anexarArte] instead, so a fresh upload already looks framed without being pinned
     * to a default it could never share.
     */
    fun focoBase(spec: GuiaSpec): FocoSpec? =
        if (spec.arte != null) INTEIRA else focoCurado(spec.personagemId)

    /** The curated box straight off `personagem_hsr`. */
    fun focoCurado(personagemId: Int): FocoSpec? = jdbc
        .queryForList(
            "SELECT arte_foco_x, arte_foco_y, arte_foco_largura, arte_foco_altura FROM personagem_hsr " +
                "WHERE character_id = ?",
            personagemId,
        )
        .firstOrNull()?.let { foco(it) }
        ?.let { FocoSpec(it.x, it.y, it.w, it.h) }

    // -------------------- helpers -------------------- //

    private fun menu(
        id: String,
        dica: String,
        opcoes: List<SelectOption>,
        max: Int,
        marcados: List<String>,
    ): StringSelectMenu {
        // Discord rejects a select with no options at all, so an empty list has to become a
        // disabled placeholder rather than a 400 that breaks the whole message.
        if (opcoes.isEmpty()) {
            return StringSelectMenu.create(id).setPlaceholder(dica).setDisabled(true)
                .addOptions(SelectOption.of("Nada por aqui", VAZIO)).build()
        }
        // Whatever is already chosen HAS to be an option here, or the next click deletes it: a
        // select reports only what it is showing, and the listener has no way to tell an answer
        // that scrolled out of sight from one the author removed. [paginar] reserves the room for
        // the two long lists; this covers everyone else — a cone whose Path was patched away, a
        // main stat spelled the way an older spec spelled it.
        val disponiveis = opcoes.map { it.value }.toSet()
        val visiveis = (marcados.filter { it !in disponiveis }.map { SelectOption.of(corte(it, 100), it) } + opcoes)
            .take(MAX_OPCOES)
        val valores = visiveis.map { it.value }.toSet()
        return StringSelectMenu.create(id)
            .setPlaceholder(dica)
            .setMinValues(0)
            .setMaxValues(minOf(max, visiveis.size))
            .addOptions(visiveis)
            .setDefaultValues(marcados.filter { it in valores })
            .build()
    }

    // The label may be decorated and shortened to fit Discord's 100 chars; the VALUE never is — it
    // is the name we look the set up by (and, for a main stat, the string printed on the card), so
    // a truncated or prefixed one would resolve to nothing at render time or show up as "Corpo:
    // Chance Crít." on the PNG.
    private fun opcao(nome: String, descricao: String?, prefixo: String = "") =
        SelectOption.of(corte(prefixo + nome, 100), nome).withDescription(descricao?.let { corte(it, 100) })


    /** Set names are long; a split option has to name two of them inside one 100-char label. */
    private fun curto(nome: String) = corte(nome.substringBefore(" da ").substringBefore(" do "), 40)

    private fun valorCombinacao(reliquias: List<LinhaSpec>, escolhidas: List<String>): String? {
        val dividida = reliquias.firstOrNull { it.partes.size > 1 } ?: return QUATRO
        val a = escolhidas.indexOf(dividida.partes[0].nome)
        val b = escolhidas.indexOf(dividida.partes[1].nome)
        return if (a < 0 || b < 0) QUATRO else "$DIVIDIR${minOf(a, b)}:${maxOf(a, b)}"
    }

    companion object {

        private fun campo(id: String, rotulo: String, estilo: TextInputStyle, valor: String, dica: String) =
            TextInput.create(id, rotulo, estilo)
                .setRequired(false)
                .setPlaceholder(dica)
                .setMaxLength(if (estilo == TextInputStyle.SHORT) 200 else 500)
                .apply { valor.takeIf { it.isNotBlank() }?.let { setValue(it) } }
                .build()

        private fun corte(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

        const val PREFIXO = "g"
        const val CONES = "cones"
        const val RELIQUIAS = "rel"
        const val ORNAMENTOS = "orn"
        const val COMBINACAO = "comb"
        const val CORPO = "corpo"
        const val PES = "pes"
        const val ESFERA = "esfera"
        const val CORDA = "corda"
        const val METAS = "metas"
        const val MODAL = "modal"
        const val METAS_MODAL = "metasval"
        const val TIRAR_ARTE = "semarte"
        const val GERAR = "gerar"
        const val PUBLICAR = "pub"

        /** The navigation select. Its VALUES are the same campos a navigation button would carry. */
        const val IR = "ir"

        /** Prefixes: one select per synergy slice (`sin0`..`sin3`) and per trace slot (`rast0`..). */
        const val SINERGIA = "sin"
        const val RASTRO = "rast"

        const val PG_EQUIP = 1
        const val PG_SINERGIAS = 2
        const val PG_RASTROS = 3
        const val PG_STATUS = 4
        const val PG_METAS = 5
        const val PG_ARTE = 6

        /**
         * Six pages under the four sections the author is told about. The number in the label is the
         * SECTION, so "1 · Equipamento" and "1 · Sinergias" read as two halves of one thing rather
         * than as six unrelated steps.
         */
        val NOMES_PAGINA = mapOf(
            PG_EQUIP to "1 · Equipamento",
            PG_SINERGIAS to "1 · Sinergias",
            PG_RASTROS to "2 · Rastros",
            PG_STATUS to "3 · Status",
            PG_METAS to "3 · Metas",
            PG_ARTE to "4 · Arte",
        )

        private val DICAS_PAGINA = mapOf(
            PG_EQUIP to "Cones, relíquias e ornamentos",
            PG_SINERGIAS to "Com quem ela joga bem",
            PG_RASTROS to "A ordem de prioridade das habilidades",
            PG_STATUS to "O status principal de cada peça",
            PG_METAS to "O que a build persegue",
            PG_ARTE to "Onde a personagem fica na imagem",
        )

        /** The four buttons under a rendered card — one per section, landing on its first page. */
        val SECOES = listOf(PG_EQUIP, PG_RASTROS, PG_STATUS, PG_ARTE)

        /** A page jump as a component id: `pg1`..`pg6`. */
        fun campoDaPagina(n: Int) = "$PAGINA$n"

        /**
         * The page a jump names, whether it came from the navigation select, a card button or a
         * wizard someone left open before the form was six pages ([LEGADO]).
         */
        fun paginaDoCampo(campo: String): Int? =
            campo.removePrefix(PAGINA).toIntOrNull()?.takeIf { campo.startsWith(PAGINA) && it in NOMES_PAGINA }
                ?: LEGADO[campo]

        private const val PAGINA = "pg"

        /** Ids from the three-page form. Kept so a wizard already on screen still navigates. */
        private val LEGADO = mapOf("volta" to PG_EQUIP, "prox" to PG_STATUS, "pmetas" to PG_METAS)

        /** Coming back from a modal or a rendered card, neither paged select is on screen. */
        const val PAGINAS_PADRAO = "00"

        /** The whole image — what an upload is framed by until its author says otherwise. */
        val INTEIRA = FocoSpec(0.0, 0.0, 1.0, 1.0)

        const val MAIS = "__mais__"
        const val VAZIO = "__vazio__"
        const val QUATRO = "4"
        const val DIVIDIR = "2:"

        const val SOBREPOSICAO_IN = "sobreposicao"
        const val ORDEM_REL_IN = "ordemrel"
        const val ORDEM_ORN_IN = "ordemorn"
        const val SINERGIAS_IN = "sinergias"

        /** Prefix: the value boxes are `metas0`..`metas3`, one per chosen status, in spec order. */
        const val METAS_IN = "metas"

        /** Four traces is what the card's row holds without the icons shrinking to nothing. */
        const val MAX_RASTROS = 4

        /** One page of synergy lists: four rows of selects, plus the navigation row. */
        const val FATIAS_SINERGIA = 4

        private const val MAX_OPCOES = 25
        private const val POR_PAGINA = MAX_OPCOES - 1

        /**
         * `g:<chave>:<campo>:<páginas>`, where páginas is one digit per paged list (relíquias,
         * ornamentos). Carrying it on every component of a page means a click always knows which
         * halves of those lists the author is looking at, with nothing kept server-side.
         */
        fun id(chave: String, campo: String, paginas: String) = "$PREFIXO:$chave:$campo:$paginas"

        /**
         * One page of a list too long for a select: everything already chosen, then as much of the
         * requested half of the REST as still fits, then the page flip.
         *
         * Pinning the choices is what makes the answer safe to read at face value. A select reports
         * only the options it is SHOWING, so a set picked on the other half would come back as
         * "removed" the first time the author clicked anything — the answers would be destroyed by
         * a UI detail. Pinned, every choice is always visible, always ticked and always reported.
         *
         * Paging over the unchosen options rather than over all of them is the other half of that:
         * hoisting into a fixed-size page would push the tail of the half off the end, and those
         * sets would be on neither page.
         */
        internal fun paginar(todas: List<SelectOption>, pagina: Char, marcados: List<String>): List<SelectOption> {
            if (todas.size <= MAX_OPCOES) return todas
            val (escolhidas, resto) = todas.partition { it.value in marcados }
            val porPagina = (POR_PAGINA - escolhidas.size).coerceAtLeast(1)
            val paginas = (resto.size + porPagina - 1) / porPagina
            val atual = (pagina - '0').coerceIn(0, paginas - 1)
            // take() is the seatbelt: 25 options is a hard limit and going over is a 400 in front
            // of the author, not an exception we would ever see.
            return (escolhidas + resto.drop(atual * porPagina).take(porPagina)).take(POR_PAGINA) +
                SelectOption.of("▼ Mostrar as outras", MAIS).withDescription("Alterna para a outra metade da lista")
        }

        /**
         * The author's order, kept across a re-pick: what survives stays where it was, what is new
         * lands at the end. Discord hands a select's values back in OPTION order, so without this
         * every click would reimpose the alphabetical list order on the card and quietly undo the
         * order the author set in the modal.
         */
        fun ordenar(atuais: List<String>, escolhas: List<String>): List<String> =
            atuais.filter { it in escolhas } + escolhas.filterNot { it in atuais }

        /** Which page a field belongs to, so a click rebuilds the page it came from. */
        fun paginaDe(campo: String): Int = when {
            campo.startsWith(SINERGIA) -> PG_SINERGIAS
            campo.startsWith(RASTRO) -> PG_RASTROS
            campo == METAS -> PG_METAS
            campo in setOf(CORPO, PES, ESFERA, CORDA) -> PG_STATUS
            else -> PG_EQUIP
        }

        /** The slot a `sin0`/`rast2` id names, or null when the campo is something else. */
        fun indiceDe(campo: String, prefixo: String): Int? =
            if (campo.startsWith(prefixo)) campo.removePrefix(prefixo).toIntOrNull() else null

        val MAIN_STATS = mapOf(
            CORPO to listOf("Chance Crít.", "Dano Crít.", "ATQ%", "PV%", "DEF%", "Acerto de Efeito", "Aumento de Cura"),
            PES to listOf("Velocidade", "ATQ%", "PV%", "DEF%"),
            ESFERA to listOf(
                "Dano de Fogo", "Dano de Gelo", "Dano de Raio", "Dano de Vento", "Dano Quântico",
                "Dano Imaginário", "Dano Físico", "ATQ%", "PV%", "DEF%",
            ),
            CORDA to listOf("ATQ%", "PV%", "DEF%", "Regen. de Energia", "Efeito de Quebra"),
        )

        const val RELIQUIAS_SQL = "SELECT nome, efeito_2_pecas FROM reliquias ORDER BY nome"
        const val ORNAMENTOS_SQL = "SELECT nome, efeito_2_pecas FROM ornamentos_planos ORDER BY nome"

        /** Every set named in a column, in the order the card draws them. */
        fun nomesDe(linhas: List<LinhaSpec>): List<String> = linhas.flatMap { l -> l.partes.map { it.nome } }

        /**
         * Chosen sets → recommendation lines, in the order [nomes] are in — that order is an answer
         * the author gives in the modal, so nothing here may rearrange it. [dividir] is the pair of
         * indices picked for a "2 + 2": those two collapse into one line, which takes the place of
         * the EARLIER of them. Everything else is a set worn at [pecasPadrao].
         */
        fun linhas(nomes: List<String>, dividir: Pair<Int, Int>?, pecasPadrao: Int): List<LinhaSpec> {
            val par = dividir?.takeIf { it.first in nomes.indices && it.second in nomes.indices }
            if (par == null) return nomes.map { LinhaSpec(listOf(ParteSpec(it, pecasPadrao))) }
            val a = minOf(par.first, par.second)
            val b = maxOf(par.first, par.second)
            return nomes.mapIndexedNotNull { i, nome ->
                when (i) {
                    a -> LinhaSpec(listOf(ParteSpec(nomes[a], 2), ParteSpec(nomes[b], 2)))
                    b -> null
                    else -> LinhaSpec(listOf(ParteSpec(nome, pecasPadrao)))
                }
            }
        }
    }
}
