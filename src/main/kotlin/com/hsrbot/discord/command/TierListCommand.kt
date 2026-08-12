package com.hsrbot.discord.command

import com.hsrbot.tier.Rascunho
import com.hsrbot.tier.TierListService
import com.hsrbot.tier.TierWizard
import com.hsrbot.tier.tamanho
import com.hsrbot.tier.titulo
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component

/**
 * `/tierlist <modo>` — opens the form that builds a tier list image for one of the three endgames.
 *
 * The first list an author makes is the tedious one: twenty cells, ~97 characters, placed by hand.
 * Every list after it opens as a copy of their last published one for the same mode, so the job
 * becomes moving whoever changed — and the image then marks who went up, who went down and who is
 * new since that list.
 *
 * Anyone can run it. The form lives in [TierWizard] and
 * [com.hsrbot.discord.listener.TierWizardListener].
 */
@Component
class TierListCommand(
    private val listas: TierListService,
    private val wizard: TierWizard,
) : SlashCommand {

    override val name = NOME

    /** A mais cara da casa: o Discord obriga a paginar, e cada página é uma imagem. */
    override val cooldownSeconds = 15L

    override val definition: CommandData = definicao()

    override fun handle(event: SlashCommandInteractionEvent) {
        val modo = event.getOption(OPCAO_MODO)?.asString
        if (modo == null || modo !in TierListService.MODOS) {
            event.replyEphemeral("Escolhe um dos modos da lista.")
            return
        }
        val rascunho = listas.rascunho(
            autorId = event.user.id,
            modo = modo,
            versao = event.getOption(OPCAO_VERSAO)?.asString?.trim()?.takeIf { it.isNotEmpty() },
            anonimo = event.getOption(OPCAO_ANONIMO)?.asBoolean,
        )
        val pagina = wizard.pagina(rascunho.chave, rascunho, PRIMEIRA_CELULA)
        event.reply(cabecalho(rascunho) + pagina.texto)
            .setEphemeral(true)
            .setComponents(pagina.linhas)
            .queue()
    }

    /**
     * The title, plus — on a seeded list only — who is missing from it.
     *
     * On an EMPTY list that hint would be the entire roster, which tells the author nothing. On a
     * seeded one it is exactly the interesting set: released since the last list, or dropped from
     * it, and either way the characters they have to go and place.
     */
    private fun cabecalho(r: Rascunho): String {
        val titulo = "## Tier list · ${titulo(r)}\n"
        if (r.grade.tamanho() == 0) {
            return titulo + "-# Lista nova, do zero. Cada página é uma coluna e um tier — a de baixo troca de página.\n"
        }
        val faltando = listas.naoColocadas(r.grade)
        val aviso = when {
            faltando.isEmpty() -> "Continuei de onde a sua última lista publicada parou."
            else -> "Continuei da sua última lista publicada. **Fora da lista** " +
                "(${faltando.size}): ${faltando.take(MAX_FALTANDO).joinToString(", ")}" +
                (if (faltando.size > MAX_FALTANDO) " e mais ${faltando.size - MAX_FALTANDO}" else "") + "."
        }
        return titulo + "-# $aviso\n"
    }

    companion object {
        const val NOME = "tierlist"
        const val OPCAO_MODO = "modo"
        const val OPCAO_VERSAO = "versao"
        const val OPCAO_ANONIMO = "anonimo"

        /** Where a freshly opened form starts: Dano Principal · S. */
        const val PRIMEIRA_CELULA = 0

        private const val MAX_FALTANDO = 12

        /**
         * Built here rather than in the instance so it can be asserted on without a database: a
         * malformed definition doesn't fail the build, it fails the bot's startup in production.
         *
         * `modo` is a fixed choice and not autocomplete — there are three of them, and it is also
         * the draft key, so a typo would silently fork a second list instead of resuming.
         */
        fun definicao(): CommandData =
            Commands.slash(NOME, "Monta a sua tier list de fim de jogo")
                .addOptions(
                    OptionData(OptionType.STRING, OPCAO_MODO, "Qual modo de fim de jogo", true)
                        .addChoices(TierListService.MODOS.map { (chave, rotulo) -> Command.Choice(rotulo, chave) }),
                    OptionData(OptionType.STRING, OPCAO_VERSAO, "Versão do jogo, ex: v4.3", false),
                    OptionData(OptionType.BOOLEAN, OPCAO_ANONIMO, "Esconde o seu nome na imagem", false),
                )
                .setGuildOnly(true)
    }
}
