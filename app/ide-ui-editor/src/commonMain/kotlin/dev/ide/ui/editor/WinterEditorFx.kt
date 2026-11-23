package dev.ide.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.WinterLightColors
import dev.ide.ui.components.drawFairyLight
import dev.ide.ui.components.drawSnowDot
import dev.ide.ui.platform.nowMillis
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The editor's winter touches: light snow over an idle editor that settles along its top edge, a string of
 * lights down the gutter's edge, and icicles under error squiggles. The lights and icicles are static and drawn
 * in the code layer (which must not animate); the snow is an overlay of its own, on a single tween, so the code
 * is never redrawn for it and an idle editor that has finished snowing costs nothing.
 */

// ---- idle snowfall ----

/** When it last snowed in this process: a snowfall is a treat, not a screensaver, so it is rationed. */
private object SnowSession {
    var lastFallAt = Long.MIN_VALUE / 2
}

/**
 * After [IDLE_BEFORE_SNOW_MS] of idling, light snow falls across the code for a while and a thin cap of it
 * settles along the editor's top edge, where it stays until the next edit or caret move ([activity]) brushes
 * it away. A full fall happens at most once every [SNOW_REST_MS]; an idle in between just lets the cap settle.
 * [enabled] is the seasonal gate.
 */
@Composable
internal fun EditorSnowfall(activity: Any, enabled: Boolean, background: Color, modifier: Modifier = Modifier) {
    val fall = remember { Animatable(0f) }
    val cap = remember { Animatable(0f) }
    val shown = remember { Animatable(0f) }
    val flakes = remember { editorSnowOf(Random(2512)) }
    LaunchedEffect(activity, enabled) {
        if (shown.value > 0f) shown.animateTo(0f, tween(BRUSH_OFF_MS))
        fall.snapTo(0f)
        cap.snapTo(0f)
        if (!enabled) return@LaunchedEffect
        delay(IDLE_BEFORE_SNOW_MS)
        shown.snapTo(1f)
        val now = nowMillis()
        if (now - SnowSession.lastFallAt >= SNOW_REST_MS) {
            SnowSession.lastFallAt = now
            // The cap grows with the fall (see the draw), reaching full depth as the last flakes land.
            fall.animateTo(1f, tween(SNOW_FALL_MS, easing = LinearEasing))
        } else {
            cap.animateTo(1f, tween(CAP_SETTLE_MS))
        }
    }
    if (shown.value <= 0f) return
    val dark = background.luminance() < 0.5f
    val snow = if (dark) Color(0xFFF4F7F6) else Color(0xFFA9BDB5)
    val strength = if (dark) 0.55f else 0.5f
    Canvas(modifier) {
        val a = strength * shown.value
        val f = fall.value
        if (f > 0f && f < 1f) drawEditorSnow(flakes, f, snow.copy(alpha = a))
        drawSnowCap(maxOf(cap.value, f), if (dark) snow.copy(alpha = a * 0.75f) else snow.copy(alpha = a * 0.6f))
    }
}

internal class EditorFlake(val x: Float, val delay: Float, val speed: Float, val sway: Float, val phase: Float, val radius: Float)

internal fun editorSnowOf(r: Random): List<EditorFlake> = List(36) {
    val depth = r.nextFloat()
    EditorFlake(
        x = r.nextFloat(),
        delay = r.nextFloat() * 0.6f,
        speed = 0.7f + depth * 0.6f,
        sway = 4f + r.nextFloat() * 8f,
        phase = r.nextFloat() * 6f,
        radius = 1.2f + depth * 1.5f,
    )
}

/** The flakes at [progress] (0..1) through a fall. */
internal fun DrawScope.drawEditorSnow(flakes: List<EditorFlake>, progress: Float, color: Color) {
    val unit = 1.dp.toPx()
    for (f in flakes) {
        val t = ((progress - f.delay) / (1f - f.delay)).coerceIn(0f, 1f)
        if (t <= 0f || t >= 1f) continue
        val y = -4f * unit + size.height * 1.05f * t * f.speed
        if (y > size.height) continue
        val x = f.x * size.width + sin(t * 9f + f.phase) * f.sway * unit
        // Each flake fades over the end of its own fall instead of disappearing at the bottom edge.
        val fade = (1f - ((t - 0.8f) / 0.2f).coerceIn(0f, 1f))
        drawSnowDot(Offset(x, y), f.radius * unit, color.copy(alpha = color.alpha * fade))
    }
}

/**
 * A soft cap of settled snow along the top edge, [depth] (0..1) of its full few dp, with a lumpy lower edge
 * so it reads as snow on a ledge rather than a bar.
 */
internal fun DrawScope.drawSnowCap(depth: Float, color: Color) {
    if (depth <= 0f) return
    val unit = 1.dp.toPx()
    val base = 2.5f * unit * depth
    val lump = 2.5f * unit * depth
    val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        val steps = (size.width / (6f * unit)).toInt().coerceIn(8, 400)
        for (i in steps downTo 0) {
            val x = size.width * i / steps
            val y = base + lump * (0.5f + 0.3f * sin(x / (37f * unit)) + 0.2f * sin(x / (11f * unit) + 1.3f))
            lineTo(x, y)
        }
        close()
    }
    drawPath(path, color)
}

// ---- lights down the gutter ----

/** A light hangs on every this-many document lines. */
internal const val LIGHT_EVERY_LINES = 4

/**
 * A string of fairy lights down the gutter's right edge at [x]: a wire swagging gently between lights, one per
 * entry of [bulbs] (a document line and the y of its middle), each in its own color cycling with the line so a
 * light keeps its color as the code scrolls. Static, and quiet: the lights glow a little, they do not twinkle.
 */
internal fun DrawScope.gutterLights(x: Float, bulbs: List<Pair<Int, Float>>, lineHeight: Float, wire: Color) {
    val stroke = Stroke(width = 1.dp.toPx())
    val sag = lineHeight * 0.3f
    val path = Path().apply {
        moveTo(x, 0f)
        var lastY = 0f
        for ((_, y) in bulbs) {
            quadraticTo(x - sag, (lastY + y) / 2f, x, y)
            lastY = y
        }
        quadraticTo(x - sag, (lastY + size.height) / 2f, x, size.height)
    }
    drawPath(path, wire, style = stroke)
    val r = (lineHeight * 0.11f).coerceIn(1.5.dp.toPx(), 3.dp.toPx())
    for ((line, y) in bulbs) {
        val color = WinterLightColors[(line / LIGHT_EVERY_LINES) % WinterLightColors.size]
        // On the wire, which swags into the gutter, so no light ever sits over code.
        drawFairyLight(Offset(x, y), r, color.copy(alpha = 0.85f), glow = 0.7f)
    }
}

// ---- icicles under error squiggles ----

/**
 * A few icicles hanging under an error squiggle from [x1] to [x2] at [y]. Placement and length come from [seed]
 * (the diagnostic's line and column), so they are the same every frame and only move when the error does.
 */
internal fun DrawScope.icicles(color: Color, x1: Float, x2: Float, y: Float, lineHeight: Float, seed: Int) {
    val width = x2 - x1
    if (width < 6f) return
    val unit = 1.dp.toPx()
    var h = seed * 0x9E3779B1.toInt() + 0x2545F491
    fun next(): Float {
        h = h xor (h shl 13); h = h xor (h ushr 17); h = h xor (h shl 5)
        return ((h ushr 8) and 0xFFFF) / 65535f
    }
    // Roughly one icicle per 18dp of squiggle, at least one.
    val count = (width / (18f * unit)).toInt().coerceIn(1, 8)
    for (i in 0 until count) {
        val slot = width / count
        val x = x1 + slot * i + slot * (0.15f + next() * 0.7f)
        val len = lineHeight * (0.14f + next() * 0.24f)
        val half = (1.3f + next() * 0.9f) * unit
        drawPath(Path().apply {
            moveTo(x - half, y)
            lineTo(x + half, y)
            lineTo(x + half * 0.15f, y + len)
            lineTo(x - half * 0.15f, y + len)
            close()
        }, Brush.verticalGradient(listOf(color, color.copy(alpha = color.alpha * 0.35f)), startY = y, endY = y + len))
        // A glint down the left side, so it reads as ice rather than as a stain.
        drawLine(Color.White.copy(alpha = 0.45f), Offset(x - half * 0.45f, y + 1f), Offset(x - half * 0.1f, y + len * 0.6f), strokeWidth = 0.8f * unit)
    }
}

/** The icicle color: the error color pulled toward ice blue, so it still reads as the error it hangs from. */
internal fun iceColor(error: Color): Color = lerp(error, Color(0xFFBFE3F2), 0.55f)

private const val BRUSH_OFF_MS = 220
private const val IDLE_BEFORE_SNOW_MS = 12_000L
private const val SNOW_FALL_MS = 14_000
private const val CAP_SETTLE_MS = 900
/** A full snowfall at most this often; idles in between only let the cap settle. */
private const val SNOW_REST_MS = 5 * 60_000L
