package com.hsrbot.discord.command

import com.hsrbot.card.Avaliacao
import com.hsrbot.card.AvaliacaoRenderer
import com.hsrbot.card.AvaliacaoService
import com.hsrbot.card.Enquadramento
import com.hsrbot.card.Foco
import com.hsrbot.conversation.UsuarioService
import com.hsrbot.discord.listener.CartaoArteListener
import com.hsrbot.discord.listener.CartaoPrevias
import com.hsrbot.discord.listener.Previa
import com.hsrbot.discord.listener.baixarArte
import com.hsrbot.discord.util.BotMessages
import com.hsrbot.discord.util.DiscordMessageSender
import com.hsrbot.hsr.BuildAnalyzer
import com.hsrbot.hsr.FribbelsMeta
import com.hsrbot.hsr.HsrCharacterService
import com.hsrbot.hsr.ShowcaseCharacter
import com.hsrbot.hsr.ShowcaseService
import com.hsrbot.hsr.ShowcaseResult
import com.hsrbot.rank.RankingService
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.CommandInteractionPayload
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.utils.FileUpload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.awt.image.BufferedImage
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * `/build <personagem> [usuario]` — rates a showcased build with real numbers, end to end in code:
 * linked UID → [ShowcaseService] fetch (Enka, mihomo behind it) → [BuildAnalyzer] over
 * [com.hsrbot.hsr.FribbelsScorer], which reproduces fribbels/hsr-optimizer's own Perfection
 * percentages → a card. No LLM in the loop, so the reply is fast, the numbers can't be hallucinated
 * and the member can check them against fribbels' showcase; no InferenceGate either, since Ollama is
 * never touched.
 *
 * The card carries the verdict and the message carries what to do about it — the farm plan is a
 * sentence per line, which is prose, and prose belongs in a message where it can be read on a phone
 * and copied. [BuildAnalyzer.render] stays as the fallback for a card that fails to draw: a picture
 * that didn't render is no reason to withhold an answer we already computed.
 *
 * The UID is whoever [OPCAO_USUARIO] names, falling back to the caller. Nothing here is private —
 * a showcase is public in the game and the notas are already on `/rank` — so a member rating a
 * friend's build needs no permission beyond that friend having linked a UID themselves.
 */
@Component
class BuildCommand(
    private val usuarioService: UsuarioService,
    private val vitrines: ShowcaseService,
    private val hsrCharacters: HsrCharacterService,
    private val avaliacoes: AvaliacaoService,
    private val ranking: RankingService,
    private val previas: CartaoPrevias,
    private val sender: DiscordMessageSender,
    private val executor: Executor,
) : SlashCommand {

    private val log = LoggerFactory.getLogger(javaClass)

    override val name = NOME

    // Uma vitrine do Enka + um cartão renderizado por chamada, e o Enka agora vê UM ip pra
    // todo mundo: quem segura o Enter aqui não gasta só a CPU da VPS, gasta a cota que atende
    // o servidor inteiro. A vitrine fica em cache por UID, então repetir de fato não custa
    // nada — o que esta espera barra é o desenho, que custa sempre.
    override val cooldownSeconds = 10L

    override val definition: CommandData = definicao()

    /**
     * The picker lists the showcase actually being rated — [OPCAO_USUARIO]'s if one was picked,
     * otherwise the caller's — so nobody has to remember how the game spells a name, and rating
     * somebody else doesn't mean typing their roster blind. A cold cache answers with nothing and
     * warms off-thread instead of blocking the gateway thread: the fetch outlives Discord's 3s
     * autocomplete window, but it lands well before the member has finished typing, and it is the
     * same fetch `/build` was about to make anyway.
     *
     * Discord only sends the options already filled in, so the target is read on every keystroke
     * rather than remembered: pick the member after typing the name and the next letter re-lists
     * against the right showcase.
     */
    override fun autocomplete(event: CommandAutoCompleteInteractionEvent) {
        val uid = usuarioService.uidDe(alvoId(event))
        val vitrine = uid?.let(vitrines::cached)
        if (uid != null && vitrine == null) vitrines.warm(uid)

        val termo = normalize(event.focusedOption.value)
        val escolhas = vitrine?.characters.orEmpty()
            .filter { c -> termo.isEmpty() || nomes(c).any { normalize(it).contains(termo) } }
            // Valued by game id, like every other character picker: the showcase spells names in
            // pt, and two showcased variants can share one ("7 de Março"). See replyPersonagens.
            .map { c -> Command.Choice(rotulo(c), c.id) }
        event.replyChoices(escolhas).queue()
    }

    /** Every language's name, so typing "firefly" still finds the showcased "Vagalume". */
    private fun nomes(c: ShowcaseCharacter): List<String> =
        hsrCharacters.byId(c.id)?.names ?: listOf(c.name)

    /** The roster's unambiguous label, or the showcase's own name for a character it doesn't know. */
    private fun rotulo(c: ShowcaseCharacter): String =
        if (hsrCharacters.byId(c.id) != null) hsrCharacters.displayName(c.id) else c.name

    /** Either the card and its advice, or the one thing we can tell the member instead. */
    private sealed interface Resultado {
        data class Recusa(val mensagem: String) : Resultado
        data class Cartao(val cartao: Avaliacao, val farm: String, val completo: String) : Resultado
    }

    /**
     * Whose build is being rated. [nome] is null when that's the caller — it is the possessive the
     * refusals need ("sua vitrine" vs "a vitrine de Fulano"), so carrying it as null-means-me keeps
     * the two copies from drifting apart. [id] is the Discord account the notas are filed under.
     */
    private data class Alvo(val id: String, val uid: String, val nome: String?)

    override fun handle(event: SlashCommandInteractionEvent) {
        val query = event.getOption(OPCAO)?.asString?.trim().orEmpty()
        // Absent = the character's own weight, which is what fribbels' sidebar shows until you touch it.
        val spd = event.getOption(OPCAO_SPD)?.asString?.toDoubleOrNull()
        // Só o NOME sai do entity resolvido — o id vem do mesmo [alvoId] que o autocomplete usa, pra
        // não existirem duas regras de "de quem é essa build" que possam divergir.
        val outro = event.getOption(OPCAO_USUARIO)?.asUser
        val id = alvoId(event)
        val uid = usuarioService.uidDe(id)
        if (uid == null) {
            val recusa = outro?.let { BotMessages.buildAlvoSemUid(it.effectiveName) } ?: BotMessages.BUILD_NO_UID
            event.reply(recusa).setEphemeral(true).queue()
            return
        }
        val alvo = Alvo(id, uid, outro?.effectiveName)

        // Refused on the METADATA, before a byte moves — same rule as /ascensao.
        val anexo = event.getOption(GuiaCommand.OPCAO_ARTE)?.asAttachment
        if (anexo != null && (!anexo.isImage || anexo.size > GuiaCommand.ARTE_MAX_BYTES)) {
            event.replyEphemeral("A arte tem que ser uma imagem de até ${GuiaCommand.ARTE_MAX_BYTES / 1024 / 1024}MB.")
            return
        }

        // Ephemeral only when there is something to frame: with no upload the card is finished the
        // moment it is drawn.
        event.deferReply(anexo != null).queue()
        val autor = event.member?.effectiveName ?: event.user.name

        try {
            baixarArte(anexo, executor)
                .thenApplyAsync({ arte -> avaliar(alvo, query, autor, spd) to arte }, executor)
                .whenComplete { (resultado, arte), ex ->
                    if (ex != null) {
                        log.error("/build failed for uid {} query '{}'", uid, query, ex)
                        event.hook.sendMessage(BotMessages.ERROR).queue()
                    } else {
                        responder(event, resultado, arte, anexo != null)
                    }
                }
        } catch (e: Exception) {
            log.error("/build could not submit work", e)
            event.hook.sendMessage(BotMessages.ERROR).queue()
        }
    }

    private fun responder(
        event: SlashCommandInteractionEvent,
        resultado: Resultado,
        arte: BufferedImage?,
        comAnexo: Boolean,
    ) {
        if (resultado is Resultado.Recusa) {
            sender.sendLong(event.hook, resultado.mensagem)
            return
        }
        val (cartao, farm, completo) = resultado as Resultado.Cartao
        // The showcase is a moment in time, so unlike /ascensao the scored card is captured rather
        // than re-resolved: re-framing a picture must not silently re-rate a different build.
        val desenhar = { img: BufferedImage?, foco: Foco -> AvaliacaoRenderer.png(cartao, img, foco) }
        // Framed to the character from the first render, so a clean upload needs no nudging.
        val enquadre = arte?.let(Enquadramento::auto) ?: Foco.INTEIRO
        val png = runCatching { desenhar(arte, enquadre) }
            .onFailure { log.error("falhou ao desenhar a build de {}", cartao.nome, it) }
            .getOrNull()
        if (png == null) {
            // The numbers survived the render failing, so send those.
            sender.sendLong(event.hook, completo)
            return
        }

        val titulo = TITULO.format(cartao.nome)
        val arquivo = FileUpload.fromData(png, ARQUIVO)
        if (!comAnexo) {
            event.hook.editOriginal("$titulo\n$farm").setFiles(arquivo).queue()
            return
        }
        val chave = UUID.randomUUID().toString().take(8)
        // O farm vai junto da prévia E do post: uma build avaliada com arte enviada responde a mesma
        // coisa que uma sem — a peça mais fraca e as possíveis melhorias são a resposta, a arte é
        // enfeite.
        previas.guardar(chave, Previa(titulo, arte, ARQUIVO, desenhar, enquadre, farm))
        event.hook
            .editOriginal(
                CartaoArteListener.textoPrevia(titulo, arte != null, farm) +
                    if (arte == null) CartaoArteListener.ARTE_RECUSADA else "",
            )
            .setFiles(arquivo)
            .setComponents(CartaoArteListener.botoes(chave, arte != null))
            .queue()
    }

    private fun avaliar(alvo: Alvo, query: String, autor: String, spd: Double?): Resultado {
        val uid = alvo.uid
        val profile = when (val result = vitrines.fetch(uid)) {
            is ShowcaseResult.Ok -> result.profile
            ShowcaseResult.NotFound -> return Resultado.Recusa(BotMessages.buildUidNotFound(uid, alvo.nome))
            ShowcaseResult.Error -> return Resultado.Recusa(BotMessages.ERROR)
        }
        // A vitrine inteira vai pro ranking, não só a personagem pedida: os dados já estão aqui e a
        // pontuação é CPU pura, então um `/build` enche o `/rank` de oito builds em vez de uma. É
        // fail-open lá dentro — o ranking nunca custa a avaliação que o membro veio pedir.
        //
        // Na conta de [Alvo.id], não na de quem chamou: a build é de quem tem o UID, e o `/rank`
        // ranqueia pessoas. Avaliar a vitrine de alguém atualiza as notas DESSA pessoa — de resto é
        // o mesmo dado que o `/build` dela produziria.
        //
        // Sempre na régua PADRÃO, de propósito: ele busca a própria meta e não vê o [spd] daqui. Um
        // placar em que cada linha saiu com um peso de Velocidade diferente não ranqueia nada, e
        // ninguém ia poder desempatá-lo zerando a Velocidade.
        ranking.registrar(alvo.id, uid, profile)
        // Showcase names arrive in pt whichever source answered; the id fallback lets a user type
        // the English/Spanish name ("firefly") — or pick a suggestion, which arrives as the id
        // already — and still land on the right showcased character.
        val character = matchCharacter(query, profile.characters)
            ?: hsrCharacters.idDigitado(query)?.let { id -> profile.characters.firstOrNull { it.id == id } }
            ?: return Resultado.Recusa(
                BotMessages.buildNotInShowcase(query, profile.characters.map { it.name }, alvo.nome),
            )
        val meta = comPesoDeVelocidade(hsrCharacters.fribbelsMeta(character.id), spd)
        val relatorio = BuildAnalyzer.analyze(character, meta)
            ?: return Resultado.Recusa(BotMessages.buildNoWeights(character.name))

        val cartao = avaliacoes.cartao(character, relatorio, rotuloSpd(spd), profile.nickname, uid)
        return Resultado.Cartao(
            // Setting the credit unconditionally is safe: the renderer only draws it beside an
            // uploaded picture, so on the official art it costs nothing and says nothing.
            cartao = cartao.copy(arte = cartao.arte.copy(autor = autor)),
            farm = BuildAnalyzer.renderFarmPlan(relatorio) + avisoSpd(spd),
            completo = BuildAnalyzer.render(character, relatorio, meta) + avisoSpd(spd),
        )
    }

    internal companion object {

        const val NOME = "build"
        const val OPCAO = "personagem"
        const val OPCAO_SPD = "velocidade"
        const val OPCAO_USUARIO = "usuario"
        const val ARQUIVO = "build.png"

        /**
         * The Discord account whose showcase this interaction is about: the picked member, or the
         * caller. Works on both event types — [CommandInteractionPayload] is what carries the
         * options, and Discord echoes the options already filled in on autocomplete too.
         *
         * `asString`, never `asUser`: an autocomplete interaction carries option VALUES but no
         * resolved entities, so `asUser` throws there. The raw snowflake is all a UID lookup wants.
         */
        internal fun alvoId(event: CommandInteractionPayload): String =
            event.getOption(OPCAO_USUARIO)?.asString ?: event.user.id

        /** The game property fribbels' sidebar calls `Stats.SPD`. */
        private const val VELOCIDADE = "SpeedDelta"

        /**
         * fribbels' **SPD weight** toggle, which their showcase sidebar renders as a 100%/0% pair.
         * All it does upstream is override `scoringMetadata.stats[Stats.SPD]` and re-run scoring off
         * the overridden ruler, so one [FribbelsMeta.subWeights] entry is the whole feature: the
         * ideal relic, the dead-substat list and the farm plan all follow from
         * [com.hsrbot.hsr.FribbelsScorer.prepare] reading that map.
         *
         * What upstream deliberately does NOT touch, and neither does this: `parts` — Velocidade
         * stays the right boots main even when a SPD substat has stopped being worth anything — and
         * the breakpoints, since a gate the kit needs is a gate whether or not the player is
         * grading rolls by it.
         *
         * Null [peso] means no override at all, which is the state the sidebar starts in: 99 of
         * fribbels' ~100 configs weigh SPD at 1 and three at 0, and that per-character default is
         * the right answer until somebody says otherwise.
         */
        internal fun comPesoDeVelocidade(meta: FribbelsMeta?, peso: Double?): FribbelsMeta? =
            if (meta == null || peso == null) meta
            else meta.copy(subWeights = meta.subWeights + (VELOCIDADE to peso))

        /**
         * The card footer's ruler note, and ONLY when the caller overrode the weight: a default
         * `/build` prints no note at all, while a card scored on a ruler nobody else is using has to
         * say so or its numbers can't be checked against anyone's.
         *
         * Empty, not " · vel …", because the footer joins its own separators — see
         * [com.hsrbot.card.AvaliacaoRenderer.rodape].
         *
         * `VEL`, not `VELOCIDADE`, because the footer is one fitted line that silently drops
         * whatever overflows: measured, the long form pushes a 16-character nickname onto a second
         * line that never gets drawn, and the disclosure is the last thing that may vanish. `VEL` is
         * also what the card's own STATUS block already calls the stat.
         */
        internal fun rotuloSpd(peso: Double?): String =
            peso?.let { "vel ${(it * 100).toInt()}%" }.orEmpty()

        /**
         * The same disclosure in words, appended to the message under the card — the footer is small
         * and abbreviated, and this is the copy that says which button it was. Also lands on the
         * text fallback that replaces the card when it fails to draw.
         */
        internal fun avisoSpd(peso: Double?): String = peso?.let {
            "\nSubstats de Velocidade valendo ${(it * 100).toInt()}% nesta avaliação"
        }.orEmpty()

        /** No emoji: the preview and the published post add their own around it. */
        const val TITULO = "**Build da %s**"

        /** Accent/case-insensitive matching, shared with the hsr_character name resolver. */
        internal fun normalize(s: String): String = HsrCharacterService.normalize(s)

        /**
         * Picks the showcased character the user meant: exact normalized match first, then a
         * single containment match in either direction ("dan heng" → "Dan Heng • Lua Imbibitora").
         * Ambiguous containment (two matches) returns null — better to list the showcase than
         * to guess the wrong character and rate it.
         */
        internal fun matchCharacter(query: String, characters: List<ShowcaseCharacter>): ShowcaseCharacter? {
            val q = normalize(query)
            if (q.isEmpty()) return null
            characters.firstOrNull { normalize(it.name) == q }?.let { return it }
            val contains = characters.filter { normalize(it.name).contains(q) || q.contains(normalize(it.name)) }
            return contains.singleOrNull()
        }

        /**
         * Built here rather than in the instance so it can be asserted on without a database: a
         * malformed definition doesn't fail the build, it fails the bot's startup in production.
         */
        fun definicao(): CommandData =
            Commands.slash(NOME, "Avalio a build de uma personagem da vitrine (relíquias, nota e o que farmar)")
                .addOptions(
                    OptionData(
                        OptionType.STRING, OPCAO,
                        "Escolhe uma da lista (é a vitrine do jogo) ou digita o nome", true,
                    ).setAutoComplete(true),
                    // Same reason /ascensao carries one: a command option is the only place Discord
                    // will take a file, and this card has no wizard to ask later.
                    OptionData(OptionType.ATTACHMENT, GuiaCommand.OPCAO_ARTE, "Sua arte pro card (opcional)", false),
                    // Choices instead of a boolean: this mirrors a 100%/0% segmented control, and
                    // "Verdadeiro/Falso" says nothing about which end is which.
                    OptionData(
                        OptionType.STRING, OPCAO_SPD,
                        "Peso da Velocidade nos substats (padrão: o da régua da personagem)", false,
                    )
                        .addChoice("Considerar (100%)", "1")
                        .addChoice("Ignorar (0%)", "0"),
                    // Discord's own member picker, like every other command that takes a person.
                    // Last because it is optional and Discord requires the required option first —
                    // so the flow is: click this field, pick the member, THEN type the character,
                    // and the picker lists their showcase.
                    OptionData(
                        OptionType.USER, OPCAO_USUARIO,
                        "De quem é a build. Vazio = você (precisa ter UID vinculado)", false,
                    ),
                )
    }
}
