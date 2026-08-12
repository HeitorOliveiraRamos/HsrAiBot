package com.hsrbot.discord.util

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Per-user pacing, shared by every entry point that costs the host something: the mention
 * pipeline (an Ollama generation) and the slash commands that render a card or call an
 * external API.
 *
 * It lives here rather than in one listener because the bot moved off the author's machine.
 * A held-down Enter on `/build` used to cost a warm Mac a few seconds of CPU; on the VPS it
 * pegs the box AND spends the shared Enka quota — one IP now answers for every member, so a
 * single impatient user can get the whole bot throttled. [com.hsrbot.ai.InferenceGate] only
 * bounds the AI path, and the card commands never touch it.
 *
 * MUST be concurrent: JDA dispatches events on several gateway threads, and the whole point
 * is the case where the same person fires twice at once. [tentar] therefore decides and
 * records in one [ConcurrentHashMap.compute], so two racing clicks can't both pass.
 */
@Component
class Cooldowns {

    /** key → the instant it becomes free again. Expiry, not the start: see [podar]. */
    private val expiraEm = ConcurrentHashMap<String, Long>()

    /**
     * Zero when [chave] may act now — and the wait is armed — otherwise the whole seconds
     * left, rounded up, for the message the caller sends back.
     *
     * [segundos] of 0 or less means "no pacing", which is the default for the cheap commands.
     */
    fun tentar(chave: String, segundos: Long): Long {
        if (segundos <= 0) return 0
        val agora = System.currentTimeMillis()
        var espera = 0L
        expiraEm.compute(chave) { _, expira ->
            if (expira != null && expira > agora) {
                espera = (expira - agora + 999) / 1000
                expira
            } else {
                agora + TimeUnit.SECONDS.toMillis(segundos)
            }
        }
        if (espera == 0L) podar(agora)
        return espera
    }

    /**
     * Drops entries whose wait already elapsed, once the map grows past [MAX]. Storing the
     * EXPIRY is what makes this safe with mixed windows: a 5s mention and a 15s `/tierlist`
     * share the map, and pruning by "older than my own window" would hand the slower one a
     * free pass. An expired entry is inert anyway, so this never changes behaviour.
     *
     * Opportunistic, on the allowed path only — a background sweeper for a map that costs
     * 24 bytes an entry would be more machinery than the thing it maintains.
     */
    private fun podar(agora: Long) {
        if (expiraEm.size > MAX) expiraEm.entries.removeIf { it.value <= agora }
    }

    internal companion object {
        /** Soft cap above which [podar] runs. */
        private const val MAX = 10_000

        /** Key for the mention/reply pipeline. */
        fun mencao(userId: String) = "$userId:@"

        /** Key for a slash command, scoped per command so `/uid` isn't blocked by `/build`. */
        fun comando(userId: String, comando: String) = "$userId:/$comando"
    }
}
