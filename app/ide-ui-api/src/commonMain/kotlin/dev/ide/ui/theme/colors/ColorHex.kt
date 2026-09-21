package dev.ide.ui.theme.colors

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * `#RRGGBB` / `#AARRGGBB` conversions, shared by the scheme file format and the picker UI.
 *
 * Hand-written rather than taken from a platform graphics helper because both callers are in common code
 * that also builds for iOS, and because the file format has to be exact: a scheme is something people hand
 * to each other, so the hex it round-trips through is part of the contract.
 */

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun byteHex(value: Int): String = "${HEX_DIGITS[(value shr 4) and 0xF]}${HEX_DIGITS[value and 0xF]}"

/** `#RRGGBB`, or `#AARRGGBB` when the color is not fully opaque. */
fun Color.toHex(): String {
    val a = (alpha * 255f).roundToInt().coerceIn(0, 255)
    val r = (red * 255f).roundToInt().coerceIn(0, 255)
    val g = (green * 255f).roundToInt().coerceIn(0, 255)
    val b = (blue * 255f).roundToInt().coerceIn(0, 255)
    return if (a == 255) "#${byteHex(r)}${byteHex(g)}${byteHex(b)}" else "#${byteHex(a)}${byteHex(r)}${byteHex(g)}${byteHex(b)}"
}

/** `0xAARRGGBB`, the form the settings store and the existing color picker speak. */
fun Color.toArgbLong(): Long {
    val a = (alpha * 255f).roundToInt().coerceIn(0, 255).toLong()
    val r = (red * 255f).roundToInt().coerceIn(0, 255).toLong()
    val g = (green * 255f).roundToInt().coerceIn(0, 255).toLong()
    val b = (blue * 255f).roundToInt().coerceIn(0, 255).toLong()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Parse `#RGB`, `#RRGGBB`, `#AARRGGBB` (with or without the `#`), or null when it is not one of those.
 *
 * Three-digit and prefix-less forms are accepted because a hand-written or imported scheme uses them and
 * refusing a color a person can obviously read is not worth the strictness.
 */
fun hexToColor(text: String): Color? {
    val hex = text.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (hex.isEmpty() || hex.any { !it.isHexDigit() }) return null
    val argb: Long = when (hex.length) {
        3 -> {
            val r = hex[0].hexValue(); val g = hex[1].hexValue(); val b = hex[2].hexValue()
            0xFF000000L or ((r * 17L) shl 16) or ((g * 17L) shl 8) or (b * 17L)
        }
        6 -> 0xFF000000L or hex.toLong(16)
        8 -> hex.toLong(16)
        else -> return null
    }
    return Color(argb)
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun Char.hexValue(): Int = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    else -> this - 'A' + 10
}
