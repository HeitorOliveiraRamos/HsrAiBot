package com.hsrbot.hsr

import com.hsrbot.config.BotProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * The one door `/build` knocks on for a showcase: Enka first, mihomo behind it.
 *
 * Both sources are proxies in front of the same HoYo endpoint, so both inherit its hiccups — but
 * they don't hiccup together, which is the entire point of keeping two. Enka leads because it has
 * the caching layer and the infrastructure; mihomo stays because it is the only one that returns a
 * finished combat stat panel, so a fallback card is a RICHER card, not a degraded one.
 *
 * Order is [BotProperties.Knowledge.showcaseSource] so a bad day at either provider is an env var
 * and a restart, not a deploy.
 */
@Component
class ShowcaseService(
    private val enka: EnkaClient,
    private val mihomo: MihomoClient,
    private val properties: BotProperties,
    private val executor: Executor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Cached per UID so repeated `/build` calls (comparing two characters, retrying a typo) don't
     * hammer either public API — a showcase only changes when the player edits it in game. Lives
     * here rather than in the clients so a UID is fetched once no matter which source answered.
     */
    private val cache = ConcurrentHashMap<String, Pair<Long, ShowcaseProfile>>()

    /** UIDs [warm] already has a fetch in flight for. */
    private val warming = ConcurrentHashMap.newKeySet<String>()

    /** What's already in hand, or null. For callers that cannot afford to wait — see [warm]. */
    fun cached(uid: String): ShowcaseProfile? =
        cache[uid]?.takeIf { (at, _) -> System.currentTimeMillis() - at < CACHE_TTL_MS }?.second

    /**
     * Fetches into the cache off-thread, for the `/build` picker: Discord gives autocomplete 3
     * seconds and asks again on every keystroke, so the picker reads [cached] and calls this on a
     * miss instead of blocking the gateway thread on a fetch it can't finish in time. One fetch per
     * UID at a time — otherwise a cold UID would mean one call to a public API per letter typed.
     */
    fun warm(uid: String) {
        if (cached(uid) != null || !warming.add(uid)) return
        executor.execute {
            try {
                fetch(uid)
            } finally {
                warming.remove(uid)
            }
        }
    }

    fun fetch(uid: String): ShowcaseResult {
        val now = System.currentTimeMillis()
        cache[uid]?.let { (at, profile) -> if (now - at < CACHE_TTL_MS) return ShowcaseResult.Ok(profile) }

        val (primeira, reserva) = ordem()
        val resultado = when (val r = primeira.second(uid)) {
            // A definitive "no such player" is final from either source: both read the same HoYo
            // upstream, so a second lookup buys a typo nothing but another 2 seconds of waiting.
            is ShowcaseResult.Ok, ShowcaseResult.NotFound -> r
            ShowcaseResult.Error -> {
                log.warn("Vitrine: {} falhou pro uid {} — tentando {}", primeira.first, uid, reserva.first)
                reserva.second(uid)
            }
        }

        if (resultado is ShowcaseResult.Ok) {
            if (cache.size > CACHE_MAX_ENTRIES) {
                cache.entries.removeIf { now - it.value.first >= CACHE_TTL_MS }
            }
            cache[uid] = now to resultado.profile
        }
        return resultado
    }

    /** Configured source first, the other one behind it. Named so the fallback log says which. */
    private fun ordem(): Pair<Fonte, Fonte> {
        val enkaFonte: Fonte = "enka" to enka::fetch
        val mihomoFonte: Fonte = "mihomo" to mihomo::fetch
        return if (properties.knowledge.showcaseSource.equals("mihomo", ignoreCase = true)) {
            mihomoFonte to enkaFonte
        } else {
            enkaFonte to mihomoFonte
        }
    }

    private companion object {
        private const val CACHE_TTL_MS = 5L * 60 * 1000
        private const val CACHE_MAX_ENTRIES = 500
    }
}

/** A named showcase source. A function type rather than an interface — two methods, same shape. */
private typealias Fonte = Pair<String, (String) -> ShowcaseResult>
