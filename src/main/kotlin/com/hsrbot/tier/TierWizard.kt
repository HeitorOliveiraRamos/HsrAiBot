package com.hsrbot.tier

import com.hsrbot.card.norm
import com.hsrbot.hsr.HsrCharacterService
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.interactions.components.text.TextInput
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle
import net.dv8tion.jda.api.interactions.modals.Modal
import org.springframework.stereotype.Component
import kotlin.math.ceil

/**
 * Builds the pages of the `/tierlist` form.
 *
 * **One page is one cell**, and that is forced rather than chosen. The roster is ~97 characters
 * against Discord's 25-option ceiling, so the only picker that can show all of them is four
 * alphabetical selects — and four selects plus the navigation row IS the five-row message. There is
 * no room left to also ask which tier or which papel, so the page has to already know both. Twenty
 * cells, twenty pages, and a single navigation select that reaches all of them (20 + "ver a imagem"
 * is 21 options, inside the same 25).
 *
 * The wizard keeps no state of its own: the draft key rides in every component id and the answers
 * live in Postgres, so a form survives a restart and the 15-minute interaction timeout.
 */
@Component
class TierWizard(private val personagens: HsrCharacterService) {

    /** A rendered page: the text above the form and the rows of components under it. */
    data class Pagina(val texto: String, val linhas: List<ActionRow>)

    fun pagina(chave: String, r: Rascunho, celula: Int): Pagina {
        val papel = papelDaCelula(celula)
        val tier = tierDaCelula(celula)
        val atuais = r.grade.celula(papel, tier)
        val fatias = fatias(r.grade, papel, tier)

        val linhas = fatias.mapIndexed { i, fatia ->
            val marcados = atuais.filter { it in fatia }
            ActionRow.of(menu(id(chave, "$FATIA${dois(celula)}$i"), "${TIERS[tier]} · ${faixa(fatia)}", fatia, marcados))
        }
            // With no roster loaded there is nothing to slice, and a message needs at least one
            // component rather than a page that is only navigation.
            .ifEmpty { listOf(ActionRow.of(menu(id(chave, "$FATIA${dois(celula)}0"), "Personagens", emptyList(), emptyList()))) }
            .plusElement(navegacao(chave, r.grade, celula))

        return Pagina(
            "**${rotuloDaCelula(celula)}** — ${atuais.size} ${if (atuais.size == 1) "escolhida" else "escolhidas"}.\n" +
                "-# As listas são o elenco inteiro em ordem alfabética, partido em quatro. " +
                "Tanto faz em qual você marca.\n" +
                "-# Quem você já pôs em outro tier de **${papel.rotulo}** não aparece aqui. " +
                "A mesma personagem **pode** estar em outra coluna — quem faz dois papéis bem entra nos dois.",
            linhas,
        )
    }

    /**
     * The roster split into four slices that each fit a select, minus whoever is already in another
     * tier of the SAME papel.
     *
     * That subtraction is the only rule the form enforces about placement: one tier per papel, since
     * a character cannot be both S and B at the same job. Across papéis nothing is subtracted — a
     * character who is a top damage dealer and a decent support belongs in both columns, which is
     * exactly the case a Path-derived column could never express.
     *
     * Values are `character_id`, never names: five Desbravadoras answer to the same one, and the
     * grade is stored by id for the same reason.
     *
     * ponytail: four slices IS the page, so past ~100 characters the tail of the roster falls off
     * the end. When that day comes the last slice gets a "▼ mostrar o resto" flip like the relic
     * lists in `/guia` — the pinning in [menu] means nobody loses a pick in the meantime.
     */
    internal fun fatias(grade: Grade, papel: Papel, tier: Int): List<List<String>> {
        val ocupados = (0 until TIERS.size).filter { it != tier }.flatMap { grade.celula(papel, it) }.toSet()
        val ids = personagens.all().map { it.id }
            .filter { it !in ocupados }
            // Accent-insensitive, or "Árgenti" would sort after "Yunli".
            .sortedBy { norm(personagens.displayName(it)) }
        if (ids.isEmpty()) return emptyList()
        val porFatia = ceil(ids.size / FATIAS.toDouble()).toInt().coerceIn(1, MAX_OPCOES)
        return ids.chunked(porFatia).take(FATIAS)
    }

    /**
     * The rows under a rendered image: the same navigation select, plus Publicar and Ajustes.
     *
     * The select is reused rather than replaced by buttons for the reason it exists at all — twenty
     * cells do not fit in rows of five — and it lands the author straight in the column that is
     * wrong instead of at cell 0 to walk the whole form again.
     */
    fun controles(chave: String, grade: Grade): List<ActionRow> = listOf(
        navegacao(chave, grade, null),
        ActionRow.of(
            Button.success(id(chave, PUBLICAR), "Publicar no canal"),
            Button.secondary(id(chave, AJUSTES), "Ajustes"),
        ),
    )

    /**
     * Which column's markers to edit.
     *
     * A step rather than one modal for all four, because a modal holds five text fields and four
     * papéis × three lists is twelve. Three fields is also the shape that lets each box be one
     * question with one answer, instead of a syntax for packing three answers into one line.
     */
    fun controlesAjustes(chave: String): List<ActionRow> = listOf(
        ActionRow.of(
            Papel.entries.map { Button.secondary(id(chave, "$AJUSTE_PAPEL${it.ordinal}"), it.rotulo) } +
                Button.secondary(id(chave, VOLTAR), "← Voltar"),
        ),
    )

    /**
     * One column's markers, as three boxes of names.
     *
     * Every box arrives holding what the image is currently drawing — the author's own answer if
     * they gave one, and otherwise what the diff against their last list worked out — so what comes
     * back IS the answer for this column: a name added adds a marker, a name deleted removes one.
     * Emptying all three hands the column back to the automatic diff, which is the only way back.
     */
    fun modalAjustes(
        chave: String,
        papel: Papel,
        novas: String,
        subiram: String,
        desceram: String,
    ): Modal = Modal.create(id(chave, "$AJUSTE_PAPEL${papel.ordinal}"), "Ajustes · ${papel.rotulo}")
        .addComponents(
            ActionRow.of(caixa(NOVAS_IN, "Novas (✦)", novas)),
            ActionRow.of(caixa(SUBIRAM_IN, "Subiram (▲)", subiram)),
            ActionRow.of(caixa(DESCERAM_IN, "Desceram (▼)", desceram)),
        )
        .build()

    private fun caixa(id: String, rotulo: String, valor: String) =
        TextInput.create(id, rotulo, TextInputStyle.PARAGRAPH)
            .setRequired(false)
            .setMaxLength(1000)
            // Where the Path form gets taught: with five Desbravadoras on the roster, a bare name is
            // refused and nothing else on screen would say why.
            .setPlaceholder("Separadas por vírgula — Robin, Desbravadora (Recordação), RMC")
            .apply { valor.takeIf { it.isNotBlank() }?.let { setValue(it) } }
            .build()

    /**
     * The button that rides along with a published list, so anyone can start from it.
     *
     * Its id carries the PUBLISHED list's key, which is safe to hand to strangers precisely because
     * a published list is immutable — the click copies it into the clicker's own draft and touches
     * nothing of the author's.
     */
    fun botaoCopiar(chave: String): ActionRow =
        ActionRow.of(Button.primary(id(chave, COPIAR), "Usar como base"))

    /**
     * The row every page ends with: all twenty cells and the image, in one select.
     *
     * Buttons could never carry this — twenty destinations against five buttons a row — and the
     * count on each entry is what turns the dropdown into a progress display, so the author can see
     * which cells they have not touched without opening them.
     *
     * [atual] is null under a rendered image, where "you are here" would name a page nobody is on.
     */
    private fun navegacao(chave: String, grade: Grade, atual: Int?) = ActionRow.of(
        StringSelectMenu.create(id(chave, "$IR${dois(atual ?: 0)}"))
            .setPlaceholder(
                atual?.let { "Você está em ${rotuloDaCelula(it)} — ir para…" } ?: "Ajustar uma coluna…",
            )
            .setMinValues(1)
            .setMaxValues(1)
            .addOptions(
                (0 until CELULAS).map { c ->
                    val n = grade.celula(papelDaCelula(c), tierDaCelula(c)).size
                    SelectOption.of(rotuloDaCelula(c), "$PAGINA${dois(c)}")
                        .withDescription(if (n == 0) "vazio" else "$n ${if (n == 1) "personagem" else "personagens"}")
                        .withDefault(c == atual)
                } + SelectOption.of("Ver a imagem", VER)
                    .withDescription("Monta a tier list com o que já está preenchido"),
            )
            .build(),
    )

    /**
     * One slice as a select.
     *
     * No maximum beyond what Discord itself imposes: a tier holds as many characters as the author
     * says it does, up to the whole roster in one cell. There is no "12 fit on the card" ceiling
     * here the way there is for a build card's synergy grid — the renderer sizes itself to whatever
     * arrives.
     */
    private fun menu(id: String, dica: String, ids: List<String>, marcados: List<String>): StringSelectMenu {
        // Discord rejects a select with no options at all, so an empty slice has to become a
        // disabled placeholder rather than a 400 that breaks the whole message.
        if (ids.isEmpty()) {
            return StringSelectMenu.create(id).setPlaceholder(dica).setDisabled(true)
                .addOptions(SelectOption.of("Nada por aqui", VAZIO)).build()
        }
        // Whatever is already chosen HAS to be an option here or the next click deletes it: a select
        // reports only what it is SHOWING, and the listener cannot tell a pick that scrolled out of
        // sight from one the author removed. The slices already contain this cell's picks; this
        // covers the leftover case of an id the roster no longer has, so it can still be taken out.
        val visiveis = (marcados.filter { it !in ids } + ids).take(MAX_OPCOES)
        val presentes = visiveis.toSet()
        return StringSelectMenu.create(id)
            .setPlaceholder(dica)
            .setMinValues(0)
            .setMaxValues(visiveis.size)
            .addOptions(visiveis.map { SelectOption.of(corte(personagens.displayName(it), 100), it) })
            .setDefaultValues(marcados.filter { it in presentes })
            .build()
    }

    /** "A–F", off the first and last name of a slice, so each list says what it holds. */
    private fun faixa(fatia: List<String>): String {
        val primeira = norm(personagens.displayName(fatia.first())).firstOrNull()?.uppercaseChar() ?: '?'
        val ultima = norm(personagens.displayName(fatia.last())).firstOrNull()?.uppercaseChar() ?: '?'
        return if (primeira == ultima) "$primeira" else "$primeira–$ultima"
    }

    private fun corte(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    companion object {
        const val PREFIXO = "t"

        /** Prefix: one select per slice, `fa<célula><fatia>` — `fa031` is cell 03, slice 1. */
        const val FATIA = "fa"

        /** The navigation select, carrying the cell it sits on: `ir07`. */
        const val IR = "ir"

        /** A cell jump as a component id: `pg00`..`pg19`. Also what the navigation select's values are. */
        const val PAGINA = "pg"

        const val VER = "ver"
        const val PUBLICAR = "pub"
        const val AJUSTES = "aj"
        const val VOLTAR = "volta"
        const val COPIAR = "copiar"

        /** Confirming a copy that would replace a draft with something in it. */
        const val COPIAR_OK = "copiarok"

        /** Prefix: `ajp0`..`ajp3`, the papel whose markers a click is about to edit. */
        const val AJUSTE_PAPEL = "ajp"

        const val NOVAS_IN = "ajnovas"
        const val SUBIRAM_IN = "ajsubiram"
        const val DESCERAM_IN = "ajdesceram"

        const val VAZIO = "__vazio__"

        const val CELULAS = 20

        /** One page of lists: four rows of selects, plus the navigation row. */
        const val FATIAS = 4

        const val MAX_OPCOES = 25

        /** `t:<chave>:<campo>`. Nothing else needs carrying — there is no paged list on a page. */
        fun id(chave: String, campo: String) = "$PREFIXO:$chave:$campo"

        /** Cells are always two digits so a campo can be split at a fixed offset. */
        fun dois(n: Int) = "%02d".format(n)

        /** The cell a `pg07` or `ir07` names, or null when the campo is something else. */
        fun celulaDe(campo: String, prefixo: String): Int? =
            if (campo.startsWith(prefixo)) {
                campo.removePrefix(prefixo).take(2).toIntOrNull()?.takeIf { it in 0 until CELULAS }
            } else {
                null
            }

        /**
         * The papel an `ajp2` names. Never collides with the plain [AJUSTES] button, which is
         * matched exactly and is a strictly shorter string.
         */
        fun papelDe(campo: String): Papel? =
            if (campo.startsWith(AJUSTE_PAPEL)) {
                campo.removePrefix(AJUSTE_PAPEL).toIntOrNull()?.let { Papel.entries.getOrNull(it) }
            } else {
                null
            }

        /** `fa031` → cell 3, slice 1. */
        fun fatiaDe(campo: String): Pair<Int, Int>? {
            if (!campo.startsWith(FATIA)) return null
            val resto = campo.removePrefix(FATIA)
            val celula = resto.take(2).toIntOrNull()?.takeIf { it in 0 until CELULAS } ?: return null
            val fatia = resto.drop(2).toIntOrNull()?.takeIf { it in 0 until FATIAS } ?: return null
            return celula to fatia
        }
    }
}
