package com.hsrbot.discord.util

/**
 * User-facing fallback copy, centralized so the bot's out-of-band replies (rate limit, busy,
 * unexpected errors) stay consistent instead of reading like a stack trace.
 *
 * These are NOT the persona file — they're the hardcoded operational strings the listeners
 * send when there is no model output to render, e.g. when the pipeline failed or was rejected
 * before it ever reached Ollama. Deliberately neutral and un-gendered: the persona is a file
 * you swap, and copy baked into the binary should not fight whatever character you gave it.
 */
object BotMessages {

    /** Per-user reply cooldown hit. */
    fun cooldown(waitSeconds: Long): String =
        "Uma de cada vez! Espera ${waitSeconds}s e me chama de novo."

    /** Inference gate full, on the mention path (so we can address the user by name). */
    fun busy(name: String): String =
        "Estou respondendo outra coisa agora, $name — me dá uns segundos e chama de novo."

    /** Inference gate full, inside an active session. */
    const val BUSY_SESSION =
        "Só um instante, ainda estou respondendo outra coisa. Manda de novo daqui a pouco."

    /** A ◀ ▶ page button clicked after its row was swept (30d retention) or the DB is down. */
    const val PAGES_EXPIRED =
        "Essa resposta é antiga demais e as páginas já foram embora. Pergunta de novo que eu respondo."

    /** A slash command was used in a guild channel outside the configured allow-list. */
    const val CHANNEL_NOT_ALLOWED =
        "Não posso falar neste canal. Me chama num dos canais liberados."

    /** A moderation command needs a server; it was run in a DM. */
    const val GUILD_ONLY =
        "Esse comando só funciona dentro de um servidor."

    /**
     * A command run in a server outside `GUILD_IDS`. Only happens where the bot was already
     * a member when the allow-list was set — a later invite is undone by
     * [com.hsrbot.discord.GuildGuard] before anyone can type.
     */
    const val GUILD_NOT_ALLOWED =
        "Não atendo neste servidor. Fala com quem me hospeda se isso for engano."

    /** Someone tried to talk to the bot in the DMs while `DM_ENABLED=false`. */
    const val DM_DISABLED =
        "Não converso no privado. Me chama num servidor onde eu esteja."

    /** `/ia` run by someone who isn't the server owner. */
    const val OWNER_ONLY =
        "Só quem é dono do servidor mexe nessa chave."

    /** Any AI path while the owner has the kill switch off (`/ia estado:desligar`). */
    const val AI_OFF =
        "Minha IA está desligada. Só quem é dono do servidor pode religar com `/ia estado:ligar`."

    /**
     * Someone asked for a moderation action in conversation. The bot has no such ability
     * anymore — it's slash commands only — so point at the one that does it instead of
     * letting the persona improvise a refusal (or, worse, claim it did the thing).
     */
    fun useCommand(command: String): String =
        "Isso eu não faço no papo — é `/$command` que resolve.\n" +
            "-# Tudo que mexe no servidor é comando. Digita `/` que eles aparecem."

    /** A reply pipeline failed unexpectedly. */
    const val ERROR =
        "Me embolei aqui e não consegui responder. Tenta de novo daqui a pouco."

    /** `/build` without a linked UID. */
    const val BUILD_NO_UID =
        "Primeiro eu preciso saber quem você é no jogo. Vincula seu UID com `/uid uid:<seu UID>` e me chama de novo."

    /**
     * `/build usuario:<alguém>` where that member never ran `/uid`. Só quem é dono do UID pode
     * vincular, então a saída aqui é pedir — nunca digitar o UID por cima.
     */
    fun buildAlvoSemUid(alvo: String): String =
        "Ainda não sei quem **$alvo** é no jogo. Só a própria pessoa pode vincular: peça para " +
            "rodar `/uid uid:<UID>` que depois disso eu avalio a vitrine dela."

    /**
     * `/build` where the showcase source doesn't know the UID (wrong number or profile hidden).
     * [alvo] nulo = a vitrine é de quem chamou, e aí a cobrança pode ser na segunda pessoa.
     */
    fun buildUidNotFound(uid: String, alvo: String? = null): String =
        if (alvo == null) {
            "Não achei o UID **$uid** — confere se digitou certo e se o seu perfil está público nas " +
                "configurações do jogo (Privacidade → mostrar detalhes dos personagens)."
        } else {
            "Não achei o UID **$uid**, de **$alvo** — pode ser que o perfil não esteja público nas " +
                "configurações do jogo (Privacidade → mostrar detalhes dos personagens)."
        }

    /** `/build` for a character that isn't in the player's showcase. */
    fun buildNotInShowcase(query: String, available: List<String>, alvo: String? = null): String {
        val list = available.filter { it.isNotBlank() }
        val vitrine = alvo?.let { "a vitrine de **$it**" } ?: "sua vitrine"
        val suffix = if (list.isEmpty()) {
            "Essa vitrine está vazia — a personagem precisa estar na vitrine do jogo pra eu avaliar."
        } else {
            "Nela eu enxergo: ${list.joinToString(", ")}. Coloca a personagem na vitrine do jogo pra eu poder avaliar."
        }
        return "Não encontrei **$query** em $vitrine! $suffix"
    }

    /** `/rank` with nothing to rank yet — the board fills itself from `/build`. */
    const val RANK_VAZIO =
        "Ainda não tenho nota de ninguém pra ranquear. Roda `/build` numa personagem da sua vitrine " +
            "que eu guardo a sua — e a de todo mundo que fizer o mesmo."

    /** `/rank` for a character nobody has scored (or that only exists outside this server). */
    fun rankSemNotas(nome: String, servidor: Boolean): String =
        "Ninguém aqui tem **$nome** avaliada ainda. " +
            if (servidor) "Tenta `escopo:global` pra ver o ranking geral, ou roda `/build $nome` pra ser o primeiro."
            else "Roda `/build $nome` pra ser o primeiro."

    /** `/rank` where the typed name doesn't resolve to a character. */
    fun rankPersonagemDesconhecida(query: String): String =
        "Não conheço nenhuma **$query**. Escolhe uma da lista que eu sugiro enquanto você digita."

    /** `/build` for a character the community weight table doesn't cover yet. */
    fun buildNoWeights(name: String): String =
        "A **$name** é nova demais — a régua da comunidade ainda não tem pesos pra ela, e eu não " +
            "chuto nota. Tenta de novo depois da próxima atualização da tabela."

    /**
     * Live pipeline status lines. Shown while a reply is being produced — the listener posts
     * the first one as a reply and edits it in place as stages advance, then deletes it when
     * the real answer lands. Operational copy, not persona output.
     */
    const val STATUS_KNOWLEDGE = "🔎 Procurando na base de conhecimento…"
    const val STATUS_WEB = "🌐 Não estava tudo na base, pesquisando na internet…"
    const val STATUS_WRITING = "Só um minuto, organizando a resposta…"

    /** The requester pressed Cancelar on a live status message. */
    const val CANCELLED = "Cancelando."

    /** The cancel button pressed by someone other than the user who asked. */
    const val NOT_YOUR_BUTTON = "Esse botão só funciona para quem me chamou."

    /**
     * Canned openers for a code-rendered answer (build/kit paths) — the FALLBACK when the
     * greeting model fails or emits junk, so the deterministic path never blocks on the
     * LLM. Picked by [seed] (the query's hash): same question, same opener.
     */
    private val ANSWER_OPENERS = listOf(
        "Anota aí%s:",
        "Aqui está%s — direto da base:",
        "Achei%s, olha só:",
    )

    fun answerOpener(name: String?, seed: Int): String {
        val who = name?.trim()?.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""
        return ANSWER_OPENERS[Math.floorMod(seed, ANSWER_OPENERS.size)].format(who)
    }

    /**
     * HSR knowledge question where no real source (local base nor web) backed the answer.
     * Sent INSTEAD of letting the model invent — abstaining beats a confident wrong kit.
     * Static (no LLM call) so the abstain path stays fast.
     */
    fun knowledgeMiss(name: String?): String {
        val who = name?.trim()?.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: ""
        return "Não achei nada confiável sobre isso na base nem na web$who. " +
            "Pode ser que esse nome não exista ou esteja escrito diferente — me dá mais detalhes?"
    }
}
