package com.hsrbot.card

import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Optional operator branding drawn in the corner of every card: a logo and a short tag.
 *
 * Both are EMPTY by default — the bot ships unbranded, and every renderer treats a missing
 * brand as "draw nothing" rather than as an error, so an unconfigured card is a clean card
 * and never a broken one. Put your own server back in that corner with:
 *
 * ```
 * BOT_LOGO=/caminho/para/logo.png     # anything ImageIO reads; a transparent PNG looks best
 * BOT_TAG=discord.gg/seuservidor
 * ```
 *
 * `/rank` overrides both per card with the guild's own icon and name — that path passes them
 * explicitly to [Retrato.coluna] and never consults this object.
 *
 * Read from the environment rather than from `BotProperties` because the renderers are plain
 * objects with no Spring in them: that is what lets the `*PreviewStandalone` mains draw a card
 * without booting the app. Resolved once — drawing a card is a hot path.
 */
internal object Marca {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The tag text, trimmed. Null when unset, and then no tag is drawn at all. */
    val tag: String? by lazy {
        System.getenv("BOT_TAG")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The logo, or null when unset. A bad path warns once and degrades to no logo. */
    val logo: BufferedImage? by lazy {
        val path = System.getenv("BOT_LOGO")?.trim()?.takeIf { it.isNotEmpty() } ?: return@lazy null
        val file = File(path)
        if (!file.isFile) {
            log.warn("BOT_LOGO aponta para um arquivo que não existe: {}", path)
            return@lazy null
        }
        runCatching { ImageIO.read(file) }.getOrNull()
            ?: null.also { log.warn("BOT_LOGO não é uma imagem legível: {}", path) }
    }
}
