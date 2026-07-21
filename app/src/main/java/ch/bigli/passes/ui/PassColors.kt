package ch.bigli.passes.ui

import kotlin.math.pow

/**
 * Chooses a legible text color for content drawn on [background]. Some passes (e.g. Loopy Loyalty
 * store cards) set foreground == background assuming their strip artwork sits behind the text; we
 * render flat color cards, so that would be invisible. Keeps [requested] when it already contrasts
 * enough with the background, otherwise returns black or white based on the background's luminance.
 * Colors are 0xAARRGGBB longs; alpha is ignored for the contrast calculation.
 *
 * @return an opaque 0xFFRRGGBB color as a Long.
 */
fun legibleTextColor(background: Long, requested: Long?): Long {
    val onBackground = if (relativeLuminance(background) > 0.5) BLACK else WHITE
    if (requested == null) return onBackground
    return if (contrastRatio(requested, background) >= MIN_CONTRAST) requested else onBackground
}

private const val BLACK = 0xFF000000L
private const val WHITE = 0xFFFFFFFFL
private const val MIN_CONTRAST = 3.0 // WCAG AA threshold for large text

private fun contrastRatio(a: Long, b: Long): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(argb: Long): Double {
    val r = linearize((argb shr 16) and 0xFF)
    val g = linearize((argb shr 8) and 0xFF)
    val b = linearize(argb and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

private fun linearize(channel: Long): Double {
    val s = channel / 255.0
    return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
}
