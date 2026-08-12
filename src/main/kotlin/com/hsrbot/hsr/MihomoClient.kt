package com.hsrbot.hsr

import com.hsrbot.config.BotProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches a player's showcased characters from mihomo's parsed API (`{mihomoUrl}/{uid}?lang=pt`) —
 * the FALLBACK source since the switch to [EnkaClient]; see [ShowcaseService] for the order.
 *
 * Still worth keeping rather than deleting: mihomo returns the one thing Enka does not, a finished
 * combat stat panel, so a card built from this source carries a stat block that an Enka-built one
 * omits. Caching lives in [ShowcaseService] so a UID is cached once regardless of which source
 * answered.
 */
@Component
class MihomoClient(
    private val properties: BotProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * mihomo is a proxy in front of HoYo's own API and answers 5xx when that call hiccups —
     * a UID that fetches fine seconds later. A network blip lands the same way. One retry
     * turns either into a card instead of an error the player can do nothing about; a real
     * answer (2xx, 400, 404) is final on the first attempt. The retry stays even though
     * [ShowcaseService] already fell back to get here: this is the last source there is.
     */
    fun fetch(uid: String): ShowcaseResult {
        attempt(uid)?.let { return it }
        Thread.sleep(RETRY_DELAY_MS)
        return attempt(uid) ?: ShowcaseResult.Error
    }

    /** One request. Null means "transient, worth retrying"; anything else is the answer. */
    private fun attempt(uid: String): ShowcaseResult? = try {
        val url = "${properties.knowledge.mihomoUrl.trimEnd('/')}/$uid?lang=pt"
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Mozilla/5.0 (compatible; HsrBot/1.0; +discord)")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        when {
            // 400 is mihomo's "Invalid uid" and reads to the player exactly like 404
            // "User not found": check the number you linked.
            resp.statusCode() == 404 || resp.statusCode() == 400 -> ShowcaseResult.NotFound
            resp.statusCode() !in 200..299 -> {
                // The body carries mihomo's own `detail` — logging it is what tells us next
                // time whether it was rate limiting or the upstream being down.
                log.warn("Mihomo: HTTP {} for uid {} — {}", resp.statusCode(), uid, resp.body().take(200))
                if (resp.statusCode() >= 500) null else ShowcaseResult.Error
            }
            else -> ShowcaseResult.Ok(MihomoParser.parse(mapper.readTree(resp.body())))
        }
    } catch (e: Exception) {
        log.warn("Mihomo: fetch failed for uid {}: {}", uid, e.message)
        null
    }

    companion object {
        /** Long enough for a hiccup to pass, short enough to stay inside Discord's 15min hook. */
        private const val RETRY_DELAY_MS = 800L
    }
}
