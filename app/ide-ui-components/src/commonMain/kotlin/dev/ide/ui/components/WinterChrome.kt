package dev.ide.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.LocalSeason
import dev.ide.ui.theme.Season
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The winter touches outside the editor: snow behind Home and a snowman on empty states. Like the Halloween
 * ones, each checks [LocalSeason] itself, so a call site adds it unconditionally.
 */

/** True during the winter season with Seasonal touches on. */
@Composable
fun isWinter(): Boolean = LocalSeason.current == Season.Winter

/**
 * Light snow falling behind the Home screen, a low drift of snowy hills along its bottom edge and, on a dark
 * theme, a faint aurora along the top. Kept faint so
 * nothing in front of it loses contrast.
 *
 * The flakes move on a slow tick (about 12 updates a second) rather than every frame, on their own layer, the
 * same budget as Halloween's fog: a static list in front is never redrawn for them. With the platform's
 * animations off the snow holds still.
 */
@Composable
fun WinterBackdrop(modifier: Modifier = Modifier) {
    if (!isWinter()) return
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val flake = if (dark) Color.White.copy(alpha = 0.16f) else Color(0xFF8D98B3).copy(alpha = 0.22f)
    val hills = if (dark) Color.White.copy(alpha = 0.035f) else Color(0xFF6E7A9A).copy(alpha = 0.07f)
    val fall = remember { mutableFloatStateOf(0f) }
    val flakes = remember { snowOf(Random(1225), BACKDROP_FLAKES) }
    val motion = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    LaunchedEffect(motion > 0f) {
        if (motion <= 0f) return@LaunchedEffect
        while (true) {
            delay(SNOW_TICK_MS)
            // Not wrapped: flakes fall at different speeds, so no single period brings them all back to the
            // start, and wrapping would make every one of them jump. A float holds days of this exactly enough.
            fall.floatValue += SNOW_TICK_MS.toFloat() / SNOW_CYCLE_MS
        }
    }
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val t = fall.floatValue
        // A faint aurora along the top on a dark theme, swaying with the same slow clock as the snow.
        if (dark) drawAurora(top = 0f, height = size.height * 0.2f, phase = t * 2f, strength = 0.35f)
        drawSnowHills(rise = 0.09f, color = hills)
        val unit = 1.dp.toPx()
        for (f in flakes) {
            // Each flake falls the full height once per cycle at its own speed, from its own start, and wraps.
            val y = ((f.start + t * f.speed) % 1f) * (size.height * 1.05f) - size.height * 0.025f
            val x = f.x * size.width + sin((t * f.speed * 6f + f.phase) * PI.toFloat()) * f.sway * unit
            drawSnowDot(Offset(x, y), f.radius * unit, flake)
        }
    }
}

/**
 * A snowman, gently rocking: the empty-state illustration in winter. A soft ground shadow keeps it standing
 * on a light background as well as a dark one.
 */
@Composable
fun FrostySnowman(size: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "snowman")
    val rock = t.animateFloat(-1f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "rock")
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Canvas(modifier.size(size)) {
        val ground = this.size.height * 0.94f
        drawOval(
            Color.Black.copy(alpha = 0.1f),
            Offset(this.size.width * 0.24f, ground - this.size.height * 0.035f),
            androidx.compose.ui.geometry.Size(this.size.width * 0.52f, this.size.height * 0.07f),
        )
        drawSnowman(
            Offset(this.size.width / 2f, ground),
            this.size.height * 0.92f,
            outline = if (dark) Color(0x00000000) else Color(0x26000000),
            tilt = rock.value * 3f,
        )
    }
}

/**
 * The season's empty-state mascot, if it has one: the ghost at Halloween, the snowman in winter, nothing on
 * an ordinary day.
 */
@Composable
fun SeasonalMascot(size: Dp, modifier: Modifier = Modifier) {
    when (LocalSeason.current) {
        Season.Halloween -> HauntedGhost(size, modifier)
        Season.Winter -> FrostySnowman(size, modifier)
        null -> {}
    }
}

internal class Flake(val x: Float, val start: Float, val speed: Float, val sway: Float, val phase: Float, val radius: Float)

/** [count] flakes scattered across the width, at different depths: the far ones small, slow and faint. */
internal fun snowOf(r: Random, count: Int): List<Flake> = List(count) {
    val depth = r.nextFloat()
    Flake(
        x = r.nextFloat(),
        start = r.nextFloat(),
        speed = 0.6f + depth * 0.8f,
        sway = 6f + r.nextFloat() * 10f,
        phase = r.nextFloat() * 2f,
        radius = 1.1f + depth * 1.6f,
    )
}

private const val BACKDROP_FLAKES = 26
private const val SNOW_TICK_MS = 80L
/** How long a flake at speed 1 takes to cross the whole screen. */
private const val SNOW_CYCLE_MS = 30_000L
