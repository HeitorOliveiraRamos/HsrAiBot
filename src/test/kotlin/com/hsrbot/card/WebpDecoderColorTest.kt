package com.hsrbot.card

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the webp decoder against a silent colour regression.
 *
 * Every character splash and icon on a build card is a lossy-VP8 webp, and a wrong decoder does not
 * fail — it just returns subtly wrong pixels. `com.twelvemonkeys.imageio:imageio-webp` (both 3.12.0
 * and 3.14.0) decodes these R+10.9 G+6.7 B-25.5 away from libwebp and Apple ImageIO, which agree
 * with each other to 0.00 on every channel. That reads as a yellow cast over every card, and it took
 * a pixel-level diff to spot. `com.github.usefulness:webp-imageio` (libwebp via JNI) is exact.
 *
 * The fixture is a 64x64 lossy webp in the same VP8X+ALPH+VP8 container the CDN serves, with a
 * blue-dominant half (the channel the bad decoder wrecks) and a warm half. If someone swaps the
 * decoder back, or a second reader wins the ImageIO SPI race, the blue half drifts and this fails.
 */
class WebpDecoderColorTest {

    private fun swatch(): BufferedImage {
        ImageIO.scanForPlugins()
        val stream = javaClass.getResourceAsStream("/img/color-swatch.webp")
            ?: fail("missing fixture /img/color-swatch.webp")
        return assertNotNull(ImageIO.read(stream), "no ImageIO reader decoded the webp fixture")
    }

    @Test
    fun `lossy webp decodes to reference colours, not the yellow-cast approximation`() {
        val img = swatch()
        // Sampled below the alpha ramp so we compare fully opaque pixels only.
        assertChannels(img, x = 16, y = 40, r = 0x3C, g = 0x50, b = 0xC8)
        assertChannels(img, x = 48, y = 40, r = 0xC8, g = 0x64, b = 0x32)
    }

    private fun assertChannels(img: BufferedImage, x: Int, y: Int, r: Int, g: Int, b: Int) {
        val argb = img.getRGB(x, y)
        val got = Triple((argb shr 16) and 255, (argb shr 8) and 255, argb and 255)
        val worst = maxOf(abs(got.first - r), abs(got.second - g), abs(got.third - b))
        // q92 lossy round-trip stays in single digits; the bad decoder is off by ~25 on blue.
        assertTrue(
            worst <= TOLERANCE,
            "($x,$y) expected ~RGB($r,$g,$b) but decoded RGB${got} — worst channel off by $worst. " +
                "A wrong webp decoder is on the classpath (see this class's doc).",
        )
    }

    private companion object {
        const val TOLERANCE = 12
    }
}
