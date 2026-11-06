package dev.ide.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hand-drawn Halloween art (a jack-o'-lantern, a bat, a ghost, a witch's hat, candy, a cauldron, the moon) as
 * plain [DrawScope] calls, so they scale to any size and need no image assets. Shared by every seasonal touch.
 */

/** A lit jack-o'-lantern [width] wide, centered on [center]; the face is cut out and glowing. */
fun DrawScope.drawPumpkin(center: Offset, width: Float) {
    val h = width * 0.82f
    val top = center.y - h / 2f
    // Stem first, so the body's top edge overlaps its foot.
    drawRect(Color(0xFF5B7A2A), Offset(center.x - width * 0.05f, top - h * 0.12f), Size(width * 0.1f, h * 0.2f))
    // Three overlapping lobes: the outer two darker, so the ribs read without drawing any lines.
    val rib = Color(0xFFD9701A)
    val lobeW = width * 0.56f
    drawOval(rib, Offset(center.x - width / 2f, top), Size(lobeW, h))
    drawOval(rib, Offset(center.x + width / 2f - lobeW, top), Size(lobeW, h))
    drawOval(Color(0xFFF28C28), Offset(center.x - lobeW * 0.42f, top), Size(lobeW * 0.84f, h))

    val glow = Color(0xFFFFC94A)
    val eyeY = center.y - h * 0.08f
    for (side in intArrayOf(-1, 1)) {
        val ex = center.x + side * width * 0.2f
        drawPath(Path().apply {
            moveTo(ex - width * 0.09f, eyeY + h * 0.07f)
            lineTo(ex + width * 0.09f, eyeY + h * 0.07f)
            lineTo(ex + side * width * 0.02f, eyeY - h * 0.1f)
            close()
        }, glow)
    }
    // A jagged grin: a band whose top edge zigzags, so it reads as teeth.
    val mouthTop = center.y + h * 0.12f
    val mouthW = width * 0.56f
    val left = center.x - mouthW / 2f
    drawPath(Path().apply {
        moveTo(left, mouthTop)
        val teeth = 5
        for (i in 1..teeth * 2) {
            val x = left + mouthW * i / (teeth * 2f)
            lineTo(x, if (i % 2 == 1) mouthTop + h * 0.07f else mouthTop)
        }
        quadraticTo(center.x, mouthTop + h * 0.34f, left, mouthTop)
        close()
    }, glow)
}

/**
 * A bat silhouette [wingspan] wide, centered on [center]. [flap] runs -1..1: positive raises the wing tips, so
 * driving it with a sine makes the bat fly.
 */
fun DrawScope.drawBat(center: Offset, wingspan: Float, flap: Float, color: Color) {
    val w = wingspan
    val lift = flap * w * 0.22f
    for (side in intArrayOf(-1, 1)) {
        fun x(f: Float) = center.x + side * w * f
        val cy = center.y
        drawPath(Path().apply {
            moveTo(center.x, cy - w * 0.03f)
            quadraticTo(x(0.25f), cy - w * 0.16f - lift, x(0.5f), cy - w * 0.04f - lift)
            // The scalloped trailing edge, each scallop pulled up a little less than the one outside it.
            quadraticTo(x(0.43f), cy + w * 0.01f - lift * 0.7f, x(0.37f), cy + w * 0.08f - lift * 0.55f)
            quadraticTo(x(0.31f), cy + w * 0.03f - lift * 0.45f, x(0.24f), cy + w * 0.1f - lift * 0.3f)
            quadraticTo(x(0.17f), cy + w * 0.05f - lift * 0.2f, x(0.1f), cy + w * 0.11f - lift * 0.08f)
            quadraticTo(x(0.05f), cy + w * 0.06f, center.x, cy + w * 0.07f)
            close()
        }, color)
        // An ear.
        drawPath(Path().apply {
            moveTo(x(0.015f), center.y - w * 0.05f)
            lineTo(x(0.045f), center.y - w * 0.12f)
            lineTo(x(0.06f), center.y - w * 0.04f)
            close()
        }, color)
    }
    drawOval(color, Offset(center.x - w * 0.06f, center.y - w * 0.07f), Size(w * 0.12f, w * 0.2f))
}

/**
 * A sheet ghost [width] wide, centered on [center]: a dome over a wavy hem, two eyes and an "o" mouth. [wave]
 * is a phase in radians that ripples the hem, so a caller animating it makes the ghost float.
 */
fun DrawScope.drawGhost(center: Offset, width: Float, color: Color, wave: Float = 0f, faceColor: Color = Color(0xFF2A1E33)) {
    val w = width
    val h = w * 1.25f
    val left = center.x - w / 2f
    val top = center.y - h / 2f
    val hemY = top + h * 0.88f
    drawPath(Path().apply {
        moveTo(left, hemY)
        lineTo(left, top + w / 2f)
        // The dome: a half circle across the full width.
        cubicTo(left, top - w * 0.12f, left + w, top - w * 0.12f, left + w, top + w / 2f)
        lineTo(left + w, hemY)
        // Four scallops along the hem, their depth rippling with [wave].
        val scallops = 4
        for (i in scallops downTo 1) {
            val x0 = left + w * i / scallops
            val x1 = left + w * (i - 1) / scallops
            val dip = h * (0.1f + 0.04f * sin(wave + i * 1.7f))
            quadraticTo((x0 + x1) / 2f, hemY + dip, x1, hemY)
        }
        close()
    }, color)
    val eyeY = top + h * 0.36f
    drawOval(faceColor, Offset(center.x - w * 0.24f, eyeY), Size(w * 0.13f, w * 0.18f))
    drawOval(faceColor, Offset(center.x + w * 0.11f, eyeY), Size(w * 0.13f, w * 0.18f))
    drawOval(faceColor, Offset(center.x - w * 0.07f, eyeY + w * 0.26f), Size(w * 0.14f, w * 0.12f))
}

/** A witch's hat [width] wide at the brim, its brim centered on [center]; the band in [band]. */
fun DrawScope.drawWitchHat(center: Offset, width: Float, color: Color, band: Color = Color(0xFFFF9A3C)) {
    val w = width
    val brimH = w * 0.14f
    drawOval(color, Offset(center.x - w / 2f, center.y - brimH / 2f), Size(w, brimH))
    // The cone leans and its tip bends over, the one detail that makes it a witch's hat and not a party hat.
    drawPath(Path().apply {
        moveTo(center.x - w * 0.26f, center.y)
        quadraticTo(center.x - w * 0.1f, center.y - w * 0.5f, center.x + w * 0.02f, center.y - w * 0.78f)
        quadraticTo(center.x + w * 0.2f, center.y - w * 0.82f, center.x + w * 0.3f, center.y - w * 0.66f)
        quadraticTo(center.x + w * 0.14f, center.y - w * 0.62f, center.x + w * 0.12f, center.y - w * 0.5f)
        quadraticTo(center.x + w * 0.2f, center.y - w * 0.2f, center.x + w * 0.26f, center.y)
        close()
    }, color)
    drawRect(band, Offset(center.x - w * 0.235f, center.y - w * 0.13f), Size(w * 0.47f, w * 0.08f))
}

/** A candy corn [height] tall, standing on its wide end, centered on [center], turned by [rotation] degrees. */
fun DrawScope.drawCandyCorn(center: Offset, height: Float, rotation: Float = 0f) {
    val h = height
    val w = h * 0.8f
    rotate(rotation, center) {
        val top = center.y - h / 2f
        fun band(from: Float, to: Float, color: Color) {
            // A slice of the triangle between two heights, as fractions from the tip.
            val y0 = top + h * from
            val y1 = top + h * to
            drawPath(Path().apply {
                moveTo(center.x - w / 2f * from, y0)
                lineTo(center.x + w / 2f * from, y0)
                lineTo(center.x + w / 2f * to, y1)
                lineTo(center.x - w / 2f * to, y1)
                close()
            }, color)
        }
        band(0f, 0.38f, Color(0xFFFFF3D6))
        band(0.38f, 0.72f, Color(0xFFFF9A2E))
        band(0.72f, 1f, Color(0xFFFFC93C))
    }
}

/** A wrapped sweet [width] across including the twists, in [color], turned by [rotation] degrees. */
fun DrawScope.drawWrappedCandy(center: Offset, width: Float, color: Color, rotation: Float = 0f) {
    val w = width
    rotate(rotation, center) {
        val r = w * 0.2f
        for (side in intArrayOf(-1, 1)) {
            drawPath(Path().apply {
                moveTo(center.x + side * r * 0.8f, center.y)
                lineTo(center.x + side * w / 2f, center.y - r)
                lineTo(center.x + side * w / 2f, center.y + r)
                close()
            }, color.copy(alpha = color.alpha * 0.8f))
        }
        drawCircle(color, r, center)
        drawCircle(Color.White.copy(alpha = 0.35f), r * 0.35f, Offset(center.x - r * 0.3f, center.y - r * 0.3f))
    }
}

/**
 * A cauldron [width] wide on its little legs, centered on [center], with green brew. [bubble] is a phase in
 * radians: the bubbles rise and pop with it, so a caller animating it makes the brew boil.
 */
fun DrawScope.drawCauldron(center: Offset, width: Float, color: Color, bubble: Float = 0f, brew: Color = Color(0xFF7CD957)) {
    val w = width
    val potTop = center.y - w * 0.18f
    // Bubbles first, behind the rim, each on its own cycle so they never rise in step.
    for (i in 0 until 3) {
        val t = ((bubble / (2f * PI.toFloat()) + i / 3f) % 1f)
        val bx = center.x + w * (-0.18f + i * 0.18f)
        val by = potTop - w * 0.32f * t
        drawCircle(brew.copy(alpha = 1f - t), w * (0.05f + 0.03f * t), Offset(bx, by))
    }
    drawOval(brew, Offset(center.x - w * 0.4f, potTop - w * 0.06f), Size(w * 0.8f, w * 0.14f))
    drawOval(color, Offset(center.x - w * 0.42f, potTop - w * 0.02f), Size(w * 0.84f, w * 0.62f))
    drawRoundRect(color, Offset(center.x - w * 0.46f, potTop - w * 0.05f), Size(w * 0.92f, w * 0.1f), CornerRadius(w * 0.05f))
    for (side in intArrayOf(-1, 1)) {
        drawRect(color, Offset(center.x + side * w * 0.26f - w * 0.04f, potTop + w * 0.52f), Size(w * 0.08f, w * 0.1f))
    }
}

/** A full moon of [radius] with two faint craters. */
fun DrawScope.drawMoon(center: Offset, radius: Float, color: Color) {
    drawCircle(color.copy(alpha = color.alpha * 0.25f), radius * 1.5f, center)
    drawCircle(color, radius, center)
    val crater = Color.Black.copy(alpha = 0.06f)
    drawCircle(crater, radius * 0.22f, Offset(center.x - radius * 0.3f, center.y - radius * 0.15f))
    drawCircle(crater, radius * 0.14f, Offset(center.x + radius * 0.25f, center.y + radius * 0.3f))
}

/**
 * A small flock of bats crosses the screen each time a build succeeds. Draws nothing the rest of the time,
 * and takes no input, so it can sit over the whole app. [enabled] is the seasonal gate: the caller passes
 * true only during Halloween with Seasonal touches on.
 *
 * The flight is one [Animatable] tween, so it obeys the platform's animation scale: with animations turned
 * off it completes at once and nothing is drawn, which is the right answer for someone who asked for that.
 */
@Composable
fun BuildSuccessBats(backend: IdeBackend, enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val progress = remember { Animatable(1f) }
    // A fresh seed per flight, so two builds in a row do not send the same flock along the same paths.
    var flight by remember { mutableIntStateOf(0) }
    LaunchedEffect(backend) {
        var last: RunStatus? = null
        backend.build.buildState
            .map { it.status }
            .distinctUntilChanged()
            .collect { status ->
                // Only a build seen running and then succeeding: a launch that opens on a finished build's
                // state should not set off a flock.
                if (last == RunStatus.Running && status == RunStatus.Succeeded) {
                    flight++
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(FLIGHT_MS, easing = LinearEasing))
                }
                last = status
            }
    }
    val flock = remember(flight) { flockOf(Random(flight * 7919 + 17)) }
    val color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF9A88AC) else Color(0xFF1E1424)
    // Between flights there is no canvas at all. During one, the progress is read only in the draw, so the
    // flight redraws this layer each frame without recomposing anything.
    if (progress.isRunning) Canvas(modifier.fillMaxSize()) {
        val p = progress.value
        val seconds = p * FLIGHT_MS / 1000f
        for (bat in flock) {
            val own = ((p - bat.delay) / (1f - MAX_DELAY)).coerceIn(0f, 1f)
            if (own <= 0f || own >= 1f) continue
            val span = size.minDimension * bat.scale
            val x = -span + (size.width + span * 2) * own
            val y = size.height * bat.height + span * 0.6f * sin(own * 2f * PI.toFloat() * 1.3f + bat.phase)
            val flap = sin(seconds * bat.flapHz * 2f * PI.toFloat() + bat.phase)
            drawBat(Offset(if (bat.leftward) size.width - x else x, y), span, flap, color)
        }
    }
}

private class Bat(
    val delay: Float,
    val height: Float,
    val scale: Float,
    val phase: Float,
    val flapHz: Float,
    val leftward: Boolean,
)

private fun flockOf(r: Random): List<Bat> {
    val leftward = r.nextBoolean()
    return List(6) {
        Bat(
            delay = r.nextFloat() * MAX_DELAY,
            height = 0.12f + r.nextFloat() * 0.5f,
            scale = 0.07f + r.nextFloat() * 0.06f,
            phase = r.nextFloat() * 2f * PI.toFloat(),
            flapHz = 4.5f + r.nextFloat() * 2.5f,
            leftward = leftward,
        )
    }
}

private const val FLIGHT_MS = 3200
/** The latest a bat may set off, as a fraction of the flight; the rest is the time it has to cross. */
private const val MAX_DELAY = 0.35f
