package com.hsrbot.config

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The knobs that change WITHOUT restarting the bot: the three Ollama model slots and the AI
 * kill switch.
 *
 * Same mechanism as [com.hsrbot.discord.ActivityStatusWatcher]: a plain `key=value` file in
 * the working dir, polled every 5s. That is deliberate — this is host-local operational
 * config (which weights are pulled on THIS machine), not domain data, so the DB would buy a
 * migration + repository + polling for nothing, and an actuator endpoint would mean pulling
 * a web server into an app that runs with `web-application-type: none`. The file also works
 * while the bot is DOWN: `./bot.sh --model` on a stopped bot just writes it and the next
 * boot reads it.
 *
 * The file WINS over the `.env` values, which only seed the defaults. Delete it to go back
 * to `.env`. Recognised keys (all optional):
 *
 * ```
 * voice=gemma4:12b-it-q8_0    # persona/prose pass (also the legacy /contexto-do-canal pass)
 * brain=gemma4:12b-it-q8_0    # intent gate, condenser, judge, knowledge retelling
 * vision=qwen2.5vl:latest     # image→text pass; EMPTY value = vision off
 * ai=off                      # kill switch: off makes every LLM pass refuse
 * ```
 *
 * ponytail: hand-parsed key=value instead of [java.util.Properties] — Properties escapes the
 * `:` in every model tag on write (`gemma4\:12b-it-q8_0`), and bot.sh reads/writes this same
 * file. The embedding model is deliberately NOT switchable here: its dimensions have to match
 * the pgvector column, so changing it needs a reindex, not a hot swap.
 */
@Component
class RuntimeConfig(properties: BotProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Persona/prose pass, plus the legacy single-pass path. */
    @Volatile
    final var voiceModel: String = properties.voiceModelName
        private set

    /** Constrained passes (gate, condenser, judge) and the knowledge retelling. */
    @Volatile
    final var brainModel: String = properties.brainModelName
        private set

    /** Null/blank = vision disabled: image attachments are ignored (see [com.hsrbot.ai.VisionService]). */
    @Volatile
    final var visionModel: String? = properties.visionModelName
        private set

    /**
     * False = every LLM pass refuses (`/ia estado:desligar`). Non-AI features — moderation,
     * `/build`, `/uid` — are untouched, since none of them talks to Ollama.
     */
    @Volatile
    final var aiEnabled: Boolean = true
        private set

    /** Raw content of the last applied file, so an unchanged file costs one read per tick. */
    @Volatile
    private var last: String = ""

    /** initialDelay 0: the file is applied at boot too, so a swap survives `bot.sh restart`. */
    @Scheduled(initialDelay = 0, fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    fun poll() {
        val raw = runCatching { if (Files.exists(FILE)) Files.readString(FILE) else "" }
            .getOrElse { return } // unreadable/half-written: skip this tick, keep current
        if (raw == last) return
        last = raw
        apply(parse(raw))
    }

    /**
     * Flips the AI kill switch and persists it. Persisted on purpose: an owner who shut the
     * AI down should not have it come back by itself after a crash or a redeploy.
     */
    @Synchronized
    fun setAiEnabled(enabled: Boolean) {
        aiEnabled = enabled
        val body = "voice=$voiceModel\nbrain=$brainModel\nvision=${visionModel.orEmpty()}\n" +
            "ai=${if (enabled) "on" else "off"}\n"
        runCatching { Files.writeString(FILE, body) }
            .onSuccess { last = body } // our own write must not read back as an external change
            .onFailure { log.warn("Could not write {} — the switch holds only until restart", FILE, it) }
        log.info("AI switch → {}", if (enabled) "ON" else "OFF")
    }

    private fun apply(values: Map<String, String>) {
        values["voice"]?.takeIf { it.isNotEmpty() }?.let { voiceModel = it }
        values["brain"]?.takeIf { it.isNotEmpty() }?.let { brainModel = it }
        // vision is the one slot where an EMPTY value is meaningful (= disable), so a present
        // key always wins, blank or not.
        values["vision"]?.let { visionModel = it.ifEmpty { null } }
        values["ai"]?.let { aiEnabled = it.lowercase() !in OFF_VALUES }
        log.info(
            "Runtime config: voice={} brain={} vision={} ai={}",
            voiceModel, brainModel, visionModel ?: "(off)", if (aiEnabled) "on" else "off",
        )
    }

    companion object {
        private val FILE: Path = Path.of(".bot.runtime")
        private val OFF_VALUES = setOf("off", "false", "0", "no")

        /**
         * Parses the `key=value` file. Blank lines and `#` comments are skipped, keys are
         * lower-cased, and a line without `=` is ignored — a half-written or hand-edited file
         * degrades to "keep whatever is already loaded" instead of blanking a model name.
         */
        internal fun parse(raw: String): Map<String, String> =
            raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
    }
}
