package dev.ide.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.LocalSeason
import dev.ide.ui.theme.Season
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Halloween touches outside the editor: the night behind Home, the ghost that haunts empty states, and
 * the nav glyphs. Each one checks [LocalSeason] itself, so a call site adds it unconditionally and gets the
 * ordinary UI on every other day of the year.
 */

/**
 * A moon behind the header and fog drifting along the bottom, for the back of the Home screen. Both are kept
 * faint enough that nothing in front of them loses contrast.
 *
 * The fog moves on a slow tick (about 12 updates a second) rather than every frame, on its own layer, so the
 * screen behind a static list is redrawn a dozen times a second and nothing in front of it is redrawn at all.
 * With the platform's animations turned off it holds still, as decoration should.
 */
@Composable
fun HalloweenBackdrop(modifier: Modifier = Modifier) {
    if (LocalSeason.current != Season.Halloween) return
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val moon = if (dark) Color(0xFFFFE9B0).copy(alpha = 0.1f) else Color(0xFFE9B860).copy(alpha = 0.2f)
    val fog = if (dark) Color.White.copy(alpha = 0.035f) else Color(0xFF8E7A9E).copy(alpha = 0.06f)
    val drift = remember { mutableFloatStateOf(0f) }
    val motion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    LaunchedEffect(motion > 0f) {
        if (motion <= 0f) return@LaunchedEffect
        while (true) {
            delay(FOG_TICK_MS)
            // One full pass of the fog every FOG_CYCLE_MS; the value wraps, and so does the drawing.
            drift.floatValue = (drift.floatValue + FOG_TICK_MS.toFloat() / FOG_CYCLE_MS) % 1f
        }
    }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        // In the empty stretch of the header row, never behind its controls: on a phone, just past the end of the
        // title and short of the inbox and account buttons; a wide window's header puts its buttons on the
        // right, so there it is the middle.
        val r = minOf(size.minDimension * 0.065f, 28.dp.toPx())
        val x = if (size.width < 600.dp.toPx()) size.width * 0.54f else size.width * 0.5f
        drawMoon(Offset(x, r * 1.3f), r, moon)
        val d = drift.floatValue
        // Three bands at different heights and speeds, so the fog never moves as one flat sheet.
        for (band in 0 until 3) {
            val y = size.height * (0.8f + band * 0.08f)
            val blobW = size.width * (0.55f + band * 0.1f)
            val shift = ((d * (1f + band * 0.6f)) % 1f) * size.width * (if (band % 2 == 0) 1f else -1f)
            for (i in -1..2) {
                val cx = i * size.width * 0.6f + shift
                val wrapped = ((cx % (size.width * 1.8f)) + size.width * 1.8f) % (size.width * 1.8f) - size.width * 0.4f
                val center = Offset(wrapped, y)
                val radius = blobW / 2f
                // A round gradient squashed into a wide, low bank: it fades to nothing on every side, where
                // a flattened oval over an unsquashed gradient would cut it off with a hard top and bottom.
                scale(1f, 0.32f, pivot = center) {
                    drawCircle(Brush.radialGradient(listOf(fog, Color.Transparent), center, radius), radius, center)
                }
            }
        }
    }
}

/**
 * A small ghost bobbing in place: the empty-state illustration during Halloween. A pale sheet with a soft
 * ground shadow under it, so it reads on a light background as well as a dark one.
 */
@Composable
fun HauntedGhost(size: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "ghost")
    val bob = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "bob")
    Canvas(modifier.size(size)) {
        val phase = bob.value * 2f * PI.toFloat()
        val w = this.size.width * 0.62f
        val lift = sin(phase) * this.size.height * 0.05f
        val groundY = this.size.height * 0.94f
        // The shadow shrinks as the ghost rises.
        val shadowW = w * (0.75f - lift / this.size.height)
        drawOval(
            Color.Black.copy(alpha = 0.1f),
            Offset(this.size.width / 2f - shadowW / 2f, groundY - w * 0.06f),
            Size(shadowW, w * 0.12f),
        )
        drawGhost(
            Offset(this.size.width / 2f, this.size.height * 0.46f + lift),
            w,
            Color(0xFFE9E0F4),
            wave = phase * 2f,
            faceColor = Color(0xFF3A2D48),
        )
    }
}

/**
 * The empty-state illustration: [HauntedGhost] during Halloween, [fallback] (the screen's usual glyph) on
 * every other day.
 */
@Composable
fun SeasonalEmptyArt(size: Dp, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    if (LocalSeason.current == Season.Halloween) HauntedGhost(size, modifier) else Box(modifier) { fallback() }
}

/** The three nav destinations that dress up for Halloween. */
enum class HalloweenGlyph { Pumpkin, Bat, WitchHat }

/**
 * A Halloween nav glyph at [size], drawn where the destination's usual glyph would be. Selected, it is in full
 * color (the pumpkin) or in [tint] (the bat and the hat, which have no color of their own). Unselected, the
 * pumpkin is drawn desaturated and dimmed, and the others in [tint], so the selected tab still stands out the
 * way a filled glyph does.
 */
@Composable
fun HalloweenNavGlyph(glyph: HalloweenGlyph, selected: Boolean, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val dimmed = remember {
        Paint().apply {
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            alpha = 0.7f
        }
    }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        when (glyph) {
            HalloweenGlyph.Pumpkin -> {
                if (!selected) drawContext.canvas.saveLayer(Rect(Offset.Zero, this.size), dimmed)
                drawPumpkin(Offset(c.x, c.y + s * 0.06f), s * 0.9f)
                if (!selected) drawContext.canvas.restore()
            }
            HalloweenGlyph.Bat -> drawBat(Offset(c.x, c.y + s * 0.04f), s * 1.05f, flap = 0.35f, color = tint)
            HalloweenGlyph.WitchHat -> drawWitchHat(
                Offset(c.x, c.y + s * 0.38f),
                s * 0.92f,
                tint,
                band = if (selected) Color(0xFFFF9A3C) else tint.copy(alpha = 0.45f),
            )
        }
    }
}

private const val FOG_TICK_MS = 80L
private const val FOG_CYCLE_MS = 60_000L
