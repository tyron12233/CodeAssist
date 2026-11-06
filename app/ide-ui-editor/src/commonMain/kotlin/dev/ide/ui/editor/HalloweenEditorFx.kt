package dev.ide.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.drawGhost
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The editor's Halloween effects: embers off the candle caret, a cobweb that grows in the corner of an idle
 * editor, a ghost that drifts across once a session, and slime drips under error squiggles. Every animated
 * one lives in a layer of its own and runs on a tween or a ticker that stops at rest, so the code layer is
 * never redrawn for them and an idle editor costs nothing.
 */

// ---- candle embers ----

/**
 * The sparks the candle caret throws off as you type. Particles are kept in content coordinates, so they stay
 * where they were struck while the view scrolls, and timed on the candle's own clock (the flame ticker), so
 * they move exactly as often as the flame redraws and stop with it.
 */
internal class EmberField {
    private class Ember(val x: Float, val y: Float, val born: Float, val sway: Float, val drift: Float, val size: Float)

    private val embers = ArrayList<Ember>()
    private var seed = 1

    /** Strike one or two embers at the flame's tip ([x], [y] in content coordinates) at candle time [now]. */
    fun spawn(x: Float, y: Float, now: Float) {
        prune(now)
        val count = 1 + (nextRandom() * 2).toInt().coerceAtMost(1)
        repeat(count) {
            if (embers.size >= MAX_EMBERS) embers.removeAt(0)
            embers += Ember(
                x = x,
                y = y,
                born = now,
                sway = nextRandom() * 2f * PI.toFloat(),
                drift = nextRandom() * 2f - 1f,
                size = 1.3f + nextRandom() * 0.8f,
            )
        }
    }

    fun isEmpty(): Boolean = embers.isEmpty()

    /** Draw the embers alive at candle time [now], offset by the scroll; dead ones are dropped. */
    fun DrawScope.draw(now: Float, vOff: Float, hOff: Float, lineHeight: Float) {
        prune(now)
        val unit = 1.dp.toPx()
        for (e in embers) {
            val age = ((now - e.born) / EMBER_LIFE).coerceIn(0f, 1f)
            val x = e.x - hOff + sin(age * 7f + e.sway) * 2.5f * unit + e.drift * age * 6f * unit
            val y = e.y - vOff - age * lineHeight * 1.3f
            val fade = 1f - age * age
            val color = lerp(Color(0xFFFFE9A0), Color(0xFFFF7A1A), age)
            val r = e.size * unit * (1f - age * 0.5f)
            drawCircle(color.copy(alpha = 0.25f * fade), radius = r * 2.4f, center = Offset(x, y))
            drawCircle(color.copy(alpha = fade), radius = r, center = Offset(x, y))
        }
    }

    private fun prune(now: Float) {
        embers.removeAll { now - it.born >= EMBER_LIFE }
    }

    // A tiny LCG: the embers only need to look scattered, and this keeps the field free of a Random instance.
    private fun nextRandom(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 8) and 0xFFFF) / 65535f
    }

    private companion object {
        const val MAX_EMBERS = 12
        /** Seconds an ember lives, on the candle clock. */
        const val EMBER_LIFE = 1f
    }
}

// ---- slime drips under error squiggles ----

/**
 * A few slime drips hanging under an error squiggle from [x1] to [x2] at [y]. Placement and length come from
 * [seed] (the diagnostic's line and column), so the drips are the same every frame and only move when the
 * error does. Static on purpose: this is drawn in the code layer, which must not animate.
 */
internal fun DrawScope.slimeDrips(color: Color, x1: Float, x2: Float, y: Float, lineHeight: Float, seed: Int) {
    val width = x2 - x1
    if (width < 6f) return
    val unit = 1.dp.toPx()
    var h = seed * 0x9E3779B1.toInt() + 0x7F4A7C15
    fun next(): Float {
        h = h xor (h shl 13); h = h xor (h ushr 17); h = h xor (h shl 5)
        return ((h ushr 8) and 0xFFFF) / 65535f
    }
    // Roughly one drip per 26dp of squiggle, at least one, spaced irregularly.
    val count = (width / (26f * unit)).toInt().coerceIn(1, 6)
    for (i in 0 until count) {
        val slot = width / count
        val x = x1 + slot * i + slot * (0.2f + next() * 0.6f)
        val len = lineHeight * (0.16f + next() * 0.22f)
        val stem = 1.2f * unit
        val bulb = (1.6f + next() * 0.8f) * unit
        drawPath(Path().apply {
            moveTo(x - stem, y)
            quadraticTo(x - stem * 0.6f, y + len * 0.6f, x - bulb * 0.7f, y + len)
            lineTo(x + bulb * 0.7f, y + len)
            quadraticTo(x + stem * 0.6f, y + len * 0.6f, x + stem, y)
            close()
        }, color)
        drawCircle(color, bulb, Offset(x, y + len))
        // A glint, so it reads as wet rather than as a blot.
        drawCircle(Color.White.copy(alpha = 0.35f), bulb * 0.35f, Offset(x - bulb * 0.3f, y + len - bulb * 0.3f))
    }
}

/** The drip color: the error color pulled toward slime green, so it still reads as the error it hangs from. */
internal fun slimeColor(error: Color): Color = lerp(error, Color(0xFF7CD957), 0.6f)

// ---- corner cobweb ----

/**
 * A cobweb that grows in the editor's top-right corner once it has sat idle for [IDLE_BEFORE_WEB_MS], and is
 * brushed away by the next edit or caret move ([activity] changing). [enabled] is the seasonal gate.
 *
 * The growth is a tween read only in the draw, so it redraws this small layer alone; at rest there is no
 * canvas at all.
 */
@Composable
internal fun EditorCobweb(activity: Any, enabled: Boolean, color: Color, modifier: Modifier = Modifier, size: Dp = 112.dp) {
    val grow = remember { Animatable(0f) }
    val shown = remember { Animatable(0f) }
    LaunchedEffect(activity, enabled) {
        if (shown.value > 0f) shown.animateTo(0f, tween(BRUSH_AWAY_MS))
        grow.snapTo(0f)
        if (!enabled) return@LaunchedEffect
        delay(IDLE_BEFORE_WEB_MS)
        shown.snapTo(1f)
        grow.animateTo(1f, tween(WEB_GROW_MS, easing = LinearEasing))
    }
    if (shown.value > 0f) Canvas(modifier.size(size)) {
        drawCobweb(grow.value, color.copy(alpha = color.alpha * shown.value))
    }
}

/**
 * A web anchored in the top-right corner of this canvas, [progress] grown (0..1): the radial threads reach out
 * over the first 40%, then the rings are spun from the inside out, each sagging toward the corner the way silk
 * does between two threads.
 */
internal fun DrawScope.drawCobweb(progress: Float, color: Color) {
    val corner = Offset(size.width, 0f)
    val reach = min(size.width, size.height) * 0.98f
    val threads = 6
    val stroke = Stroke(width = 1.dp.toPx())
    val threadP = (progress / 0.4f).coerceIn(0f, 1f)
    fun point(angle: Float, r: Float) = Offset(corner.x + cos(angle) * r, corner.y + sin(angle) * r)
    // From pointing straight left (PI) to straight down (PI / 2), so the web fills the corner.
    val angles = List(threads) { i -> PI.toFloat() - (PI.toFloat() / 2f) * i / (threads - 1) }
    for (a in angles) drawLine(color, corner, point(a, reach * threadP), strokeWidth = stroke.width)

    val ringP = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
    if (ringP <= 0f) return
    val rings = 4
    for (k in 1..rings) {
        // Each ring takes its turn; a ring's share of ringP spins it across the threads.
        val own = (ringP * rings - (k - 1)).coerceIn(0f, 1f)
        if (own <= 0f) break
        val r = reach * k / (rings + 0.6f)
        val segments = ((threads - 1) * own).toInt()
        val partial = (threads - 1) * own - segments
        val path = Path()
        path.moveTo(point(angles[0], r).x, point(angles[0], r).y)
        for (s in 0 until threads - 1) {
            if (s > segments) break
            val a0 = angles[s]
            val a1 = angles[s + 1]
            val end = if (s == segments) a0 + (a1 - a0) * partial else a1
            if (s == segments && partial <= 0f) break
            val mid = (a0 + end) / 2f
            // The sag is a fraction of the chord it hangs across, so a short, half-spun span at a ring's growing
            // end dips as little as it is long instead of hooking back on itself.
            val half = kotlin.math.abs(end - a0) / 2f
            val sag = point(mid, r * cos(half) - 2f * r * sin(half) * 0.3f)
            val to = point(end, r)
            path.quadraticTo(sag.x, sag.y, to.x, to.y)
        }
        drawPath(path, color, style = stroke)
    }
}

// ---- wandering ghost ----

/** Whether the ghost has already been out this app session; it appears at most once per process. */
private object GhostSession {
    var shown = false
}

/**
 * A small translucent ghost that drifts across the code once after [IDLE_BEFORE_GHOST_MS] of idling, bobbing
 * as it goes, then fades. At most once per app session. If the user edits or moves the caret ([activity])
 * mid-flight, it fades out at once instead of finishing. [enabled] is the seasonal gate.
 */
@Composable
internal fun EditorGhost(activity: Any, enabled: Boolean, background: Color, modifier: Modifier = Modifier) {
    val flight = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var flying by remember { mutableStateOf(false) }
    LaunchedEffect(activity, enabled) {
        if (flying) {
            fade.animateTo(0f, tween(GHOST_FADE_MS))
            flying = false
            return@LaunchedEffect
        }
        if (!enabled || GhostSession.shown) return@LaunchedEffect
        delay(IDLE_BEFORE_GHOST_MS)
        GhostSession.shown = true
        fade.snapTo(1f)
        flight.snapTo(0f)
        flying = true
        flight.animateTo(1f, tween(GHOST_FLIGHT_MS, easing = LinearEasing))
        flying = false
    }
    if (!flying) return
    val dark = background.luminance() < 0.5f
    val body = if (dark) Color(0xFFF2ECF8) else Color(0xFF6E6380)
    val face = if (dark) Color(0xFF2A1E33) else Color(0xFFF7F2FA)
    val strength = if (dark) 0.5f else 0.3f
    Canvas(modifier) {
        drawWanderingGhost(flight.value, fade.value * strength, body, face)
    }
}

/** The ghost at [progress] (0..1) through its crossing, its whole opacity [alpha]. */
internal fun DrawScope.drawWanderingGhost(progress: Float, alpha: Float, body: Color, face: Color) {
    val w = 34.dp.toPx()
    // Fade in over the first tenth and out over the last, so it never pops at an edge.
    val envelope = min(1f, min(progress / 0.1f, (1f - progress) / 0.1f)).coerceIn(0f, 1f)
    val a = alpha * envelope
    if (a <= 0f) return
    val x = -w + (size.width + w * 2) * progress
    val y = size.height * 0.38f + sin(progress * 2f * PI.toFloat() * 2.5f) * 10.dp.toPx()
    drawGhost(Offset(x, y), w, body.copy(alpha = a), wave = progress * 40f, faceColor = face.copy(alpha = a))
}

private const val BRUSH_AWAY_MS = 200
private const val IDLE_BEFORE_WEB_MS = 10_000L
private const val WEB_GROW_MS = 4_000
private const val IDLE_BEFORE_GHOST_MS = 30_000L
private const val GHOST_FLIGHT_MS = 7_000
private const val GHOST_FADE_MS = 250
