package com.hsrbot.card

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Resolves a character into the ascension card the renderer draws — the read side of V30.
 *
 * There is no draft, no author and no spec: unlike `/guia`, this card is entirely derived from the
 * harvest, so the whole feature is two SELECTs and [AscensaoRenderer]. That is also why there is no
 * PNG cache — nothing to key it on that isn't just "the populate ran again", and a card renders in
 * well under a second once the CDN icons are on disk (they are shared by every character).
 *
 * Fails open like [com.hsrbot.hsr.HsrRepository]: a DB hiccup returns null and the caller says it
 * couldn't build the card, rather than throwing at the interaction.
 */
@Component
class AscensaoService(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The card for a character's game id, or null when it doesn't exist or has no costs stored. */
    fun cartao(characterId: Int): Ascensao? = try {
        // Bound as Int: `character_id` is INTEGER and PgJDBC types a String param as varchar, which
        // Postgres refuses outright — the same trap HsrRepository.personagens documents.
        val p = jdbc.queryForList("SELECT * FROM personagem_hsr WHERE character_id = ? LIMIT 1", characterId)
            .firstOrNull()
        p?.let { ascensao(it, icones()) }
    } catch (e: Exception) {
        log.warn("ascensão de {} indisponível: {}", characterId, e.message)
        null
    }

    /**
     * The whole 142-row dictionary, read per card rather than cached: a populate run rewrites it,
     * and a stale map draws the wrong icons — which is worse and quieter than one small query.
     */
    private fun icones(): Map<Int, String?> =
        jdbc.queryForList("SELECT material_id, icone FROM materiais")
            .associate { (it["material_id"] as Number).toInt() to str(it["icone"]) }
}
