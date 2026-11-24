package dev.ide.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The winter drawings, as plain [DrawScope] functions so the cards, the nav glyphs, the build moments, the
 * editor and the countdown's night sky share one snowflake, one star and one aurora. Each takes a center (or a
 * base) and a size in pixels and draws nothing else, so a caller decides about layers and animation.
 *
 * The season is holiday-neutral on purpose (winter, then the new year), and so is the art: snow, stars, the
 * northern lights and fireworks, in the Aurora night palette.
 */

/** Deep navy: the night every winter card and the sky are drawn on. */
val WinterNavy = Color(0xFF0E1424)
/** A lighter navy, for the top of a night gradient. */
val WinterNavyTop = Color(0xFF1B2644)
/** Aurora teal, the season's main color. */
val AuroraTeal = Color(0xFF6FE0C8)
/** A darker teal, for shading. */
val AuroraTealShade = Color(0xFF3FAE98)
/** Soft violet, the aurora's other edge. */
val AuroraViolet = Color(0xFFB9A6FF)
/** Star gold: stars, sparkles, the countdown's highlights. */
val StarGold = Color(0xFFF2CF7A)
/** Snow, a touch warm so it does not glare on a light theme. */
val WinterSnow = Color(0xFFF7F5F0)

/** The colors a string of lights, the stars and the fireworks cycle through. */
val WinterLightColors = listOf(StarGold, AuroraTeal, AuroraViolet, Color(0xFF8FC8FF), Color(0xFFF2A6C4))

/**
 * A disc of [radius] at [center] filled with a radial gradient of [colors]. Draws nothing for a radius that is
 * not positive: Android's RadialGradient throws on one, and an animation that grows a glow from nothing hands
 * it exactly 0 on its first frame (the 3.48.0 countdown crash).
 */
fun DrawScope.drawRadialGlow(colors: List<Color>, center: Offset, radius: Float) {
    if (!(radius > 0f)) return
    drawCircle(Brush.radialGradient(colors, center, radius), radius, center)
}

/**
 * A six-armed snowflake of [radius] at [center], each arm with one pair of short branches, turned by
 * [rotation] degrees. Drawn as strokes so it stays crisp from a 3 dp flake to a 40 dp one.
 */
fun DrawScope.drawSnowflake(center: Offset, radius: Float, color: Color, rotation: Float = 0f) {
    val stroke = (radius * 0.16f).coerceAtLeast(1f)
    rotate(rotation, pivot = center) {
        for (i in 0 until 6) {
            val a = (i * PI / 3).toFloat()
            val dir = Offset(cos(a), sin(a))
            val tip = center + dir * radius
            drawLine(color, center, tip, stroke, StrokeCap.Round)
            // One pair of branches, two thirds of the way out, swept back toward the center.
            val at = center + dir * (radius * 0.6f)
            for (side in listOf(-1f, 1f)) {
                val b = a + side * (PI / 4).toFloat()
                val end = at + Offset(cos(b), sin(b)) * (radius * 0.32f)
                drawLine(color, at, end, stroke * 0.85f, StrokeCap.Round)
            }
        }
    }
}

/** A soft round flake: the falling snow, where the arms of [drawSnowflake] would be too small to read. */
fun DrawScope.drawSnowDot(center: Offset, radius: Float, color: Color) {
    drawRadialGlow(listOf(color, color.copy(alpha = color.alpha * 0.5f), color.copy(alpha = 0f)), center, radius * 1.7f)
}

/**
 * A five-pointed star of [radius] at [center]. [glow] (0..1) adds a soft halo behind it.
 */
fun DrawScope.drawStar(center: Offset, radius: Float, color: Color, glow: Float = 0f, rotation: Float = 0f) {
    if (glow > 0f) {
        drawRadialGlow(listOf(color.copy(alpha = 0.45f * glow), color.copy(alpha = 0f)), center, radius * 2.2f)
    }
    val path = Path()
    val inner = radius * 0.45f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else inner
        val a = (-PI / 2 + i * PI / 5).toFloat() + rotation * (PI / 180f).toFloat()
        val p = Offset(center.x + cos(a) * r, center.y + sin(a) * r)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, color)
}

/**
 * A star in the night sky: a bright core with four soft rays and a halo, [radius] being the rays' reach.
 * [twinkle] (0..1) scales the rays, so a caller can make it breathe.
 */
fun DrawScope.drawSkyStar(center: Offset, radius: Float, color: Color, twinkle: Float = 1f) {
    if (!(radius > 0f)) return
    drawRadialGlow(listOf(color.copy(alpha = color.alpha * 0.35f), color.copy(alpha = 0f)), center, radius * 1.4f)
    drawSparkle(center, radius * (0.55f + 0.45f * twinkle), color)
    drawCircle(Color.White.copy(alpha = color.alpha * 0.9f), radius * 0.16f, center)
}

/** A four-pointed glint: a thin cross of curved rays meeting at [center]. */
fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}

/**
 * The northern lights: curtains of light hanging between [top] and [top] + [height], drawn as thin vertical
 * rays that are brightest at a wavy lower hem and fade upward, the way an aurora looks from the ground. The hem
 * sways with [phase] (radians) and the color drifts from teal to violet across the sky. [strength] (0..1)
 * scales the whole thing, so it can fade in.
 */
fun DrawScope.drawAurora(top: Float, height: Float, phase: Float = 0f, strength: Float = 1f) {
    if (strength <= 0f || size.width <= 0f || height <= 0f) return
    val w = size.width
    val step = (w / 90f).coerceAtLeast(2f)
    var x = 0f
    while (x <= w) {
        val f = x / w
        // Two overlapping curtains: a long slow one and a shorter one riding on it.
        val hem = top + height * (0.62f + 0.18f * sin(f * 5.2f + phase) + 0.08f * sin(f * 13f + phase * 1.7f))
        val reach = height * (0.45f + 0.2f * sin(f * 3.1f + phase * 0.6f + 1.3f))
        val glow = (0.55f + 0.45f * sin(f * 7.3f + phase * 1.3f)).coerceIn(0.15f, 1f)
        val color = lerpColor(AuroraTeal, AuroraViolet, (0.5f + 0.5f * sin(f * 2.4f + 0.8f)).coerceIn(0f, 1f))
        val a = 0.22f * strength * glow
        drawRect(
            Brush.verticalGradient(
                listOf(color.copy(alpha = 0f), color.copy(alpha = a * 0.5f), color.copy(alpha = a), color.copy(alpha = 0f)),
                startY = hem - reach,
                endY = hem + height * 0.04f,
            ),
            topLeft = Offset(x, hem - reach),
            size = Size(step * 2.2f, reach + height * 0.04f),
        )
        x += step
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/**
 * One firework at [progress] (0..1): a ring of sparks flying out from [center] to [radius], each trailing a
 * short tail and fading as it falls a little. [seed] turns the ring so two bursts do not look identical.
 */
fun DrawScope.drawFirework(center: Offset, radius: Float, progress: Float, color: Color, seed: Int = 0, sparks: Int = 14) {
    if (progress <= 0f || progress >= 1f) return
    val out = 1f - (1f - progress) * (1f - progress) // fast out, slow settle
    val fade = (1f - progress).coerceIn(0f, 1f)
    val turn = (seed * 0.7f) % (2f * PI.toFloat())
    for (i in 0 until sparks) {
        val a = turn + i * 2f * PI.toFloat() / sparks
        val dir = Offset(cos(a), sin(a))
        val drop = Offset(0f, radius * 0.25f * progress * progress)
        val head = center + dir * (radius * out) + drop
        val tail = center + dir * (radius * out * 0.65f) + drop * 0.7f
        drawLine(color.copy(alpha = 0.45f * fade), tail, head, radius * 0.03f, StrokeCap.Round)
        drawCircle(color.copy(alpha = fade), radius * 0.035f, head)
    }
    if (progress < 0.25f) drawCircle(Color.White.copy(alpha = 1f - progress * 4f), radius * 0.1f, center)
}

/**
 * A small snowman standing on [base], [height] tall: two snowballs, coal eyes and buttons, a carrot nose and a
 * teal scarf. Drawn with a faint outline so it shows on a light background.
 */
fun DrawScope.drawSnowman(base: Offset, height: Float, snow: Color = WinterSnow, outline: Color = Color(0x22000000), tilt: Float = 0f, scarf: Color = AuroraTealShade) {
    rotate(tilt, pivot = base) {
        val bodyR = height * 0.28f
        val headR = height * 0.19f
        val body = Offset(base.x, base.y - bodyR)
        val head = Offset(base.x, body.y - bodyR - headR * 0.8f)
        drawCircle(outline, bodyR + height * 0.012f, body)
        drawCircle(snow, bodyR, body)
        drawCircle(outline, headR + height * 0.012f, head)
        drawCircle(snow, headR, head)
        val coal = Color(0xFF2B2A2E)
        drawCircle(coal, headR * 0.11f, head + Offset(-headR * 0.36f, -headR * 0.15f))
        drawCircle(coal, headR * 0.11f, head + Offset(headR * 0.36f, -headR * 0.15f))
        val nose = Path().apply {
            moveTo(head.x, head.y + headR * 0.02f)
            lineTo(head.x + headR * 0.75f, head.y + headR * 0.16f)
            lineTo(head.x, head.y + headR * 0.24f)
            close()
        }
        drawPath(nose, Color(0xFFF08A3C))
        for (i in 0 until 3) drawCircle(coal, bodyR * 0.08f, body + Offset(0f, -bodyR * 0.45f + i * bodyR * 0.4f))
        // The scarf: a band at the neck and one tail hanging down the side.
        val neckY = head.y + headR * 0.82f
        drawRoundRect(scarf, Offset(head.x - headR * 0.95f, neckY - headR * 0.16f), Size(headR * 1.9f, headR * 0.34f), CornerRadius(headR * 0.15f))
        drawRoundRect(scarf, Offset(head.x + headR * 0.35f, neckY), Size(headR * 0.34f, headR * 0.95f), CornerRadius(headR * 0.12f))
    }
}

/**
 * Rolling snowy hills across the bottom of this canvas, [rise] of its height at the tallest, in [color]: two
 * overlapping bands, the back one paler.
 */
fun DrawScope.drawSnowHills(rise: Float, color: Color, back: Color = color.copy(alpha = color.alpha * 0.55f)) {
    val w = size.width
    val h = size.height
    val top = h - h * rise
    val far = Path().apply {
        moveTo(0f, top + h * rise * 0.35f)
        cubicTo(w * 0.25f, top - h * rise * 0.05f, w * 0.45f, top + h * rise * 0.45f, w * 0.7f, top + h * rise * 0.15f)
        cubicTo(w * 0.85f, top - h * rise * 0.02f, w * 0.95f, top + h * rise * 0.2f, w, top + h * rise * 0.1f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(far, back)
    val near = Path().apply {
        moveTo(0f, top + h * rise * 0.65f)
        cubicTo(w * 0.3f, top + h * rise * 0.35f, w * 0.55f, top + h * rise * 0.85f, w, top + h * rise * 0.55f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
    }
    drawPath(near, color)
}

/** One fairy light: a small glowing dot of [radius] at [at], its halo [glow] (0..1) strong. */
fun DrawScope.drawFairyLight(at: Offset, radius: Float, color: Color, glow: Float = 1f) {
    if (glow > 0f) {
        drawRadialGlow(listOf(color.copy(alpha = 0.4f * glow * color.alpha), color.copy(alpha = 0f)), at, radius * 3f)
    }
    drawCircle(color, radius, at)
    drawCircle(Color.White.copy(alpha = 0.5f * color.alpha), radius * 0.4f, at - Offset(radius * 0.25f, radius * 0.25f))
}
