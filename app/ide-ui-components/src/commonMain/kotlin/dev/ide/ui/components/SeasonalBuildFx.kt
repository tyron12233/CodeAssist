package dev.ide.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.halloween_boo
import dev.ide.ui.theme.LocalSeason
import dev.ide.ui.theme.Season
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Halloween build and run moments, each gated on [LocalSeason] so outside the season every call here
 * draws exactly what it replaced: a ghost that says Boo when a build fails, the build progress as a row of
 * pumpkins, the build status glyph as a bubbling cauldron, and a burst of candy when a program starts.
 *
 * One-shot effects run on a single [Animatable] tween, so the platform's animation scale applies: with
 * animations off a tween completes at once and nothing is drawn. The looping ones (the pumpkin chase, the
 * bubbles) are infinite transitions, which do not finish at scale zero, so they fall back to a still frame
 * whenever [motionIsAnimated] says motion is off. Nothing here is composed, let alone ticking, at rest.
 */

/** True during Halloween with Seasonal touches on. */
@Composable
fun isHalloween(): Boolean = LocalSeason.current == Season.Halloween

/**
 * The root overlay for the one-shot build/run moments: [BuildFailedBoo] and [RunCandyRain]. Sits over the
 * content area and takes no input; draws nothing outside the season or between moments.
 */
@Composable
fun HalloweenBuildFx(backend: IdeBackend, modifier: Modifier = Modifier) {
    if (!isHalloween()) return
    Box(modifier.fillMaxSize()) {
        BuildFailedBoo(backend)
        RunCandyRain(backend)
    }
}

// ---------------------------------------------------------------------------
// Failed build: Boo
// ---------------------------------------------------------------------------

/**
 * On a build seen running and then failing, a ghost rises from the bottom edge, where the build console
 * lives on every layout (the phone's dock, the wide window's bottom pane), says Boo, and sinks again. It
 * never covers the error list for longer than its 1.6 s, and taps pass straight through it.
 */
@Composable
private fun BoxScope.BuildFailedBoo(backend: IdeBackend) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(backend) {
        var last: RunStatus? = null
        backend.build.buildState
            .map { it.status }
            .distinctUntilChanged()
            .collect { status ->
                if (last == RunStatus.Running && status == RunStatus.Failed) {
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(BOO_MS, easing = LinearEasing))
                }
                last = status
            }
    }
    if (progress.isRunning) BooGhost({ progress.value }, Modifier.align(Alignment.BottomCenter))
}

/**
 * The ghost and its speech bubble at [progress] (0..1) through the Boo: rising over the first quarter,
 * bobbing, then sinking over the last quarter. Read only in layers and the draw, so the 1.6 s never
 * recomposes. Public to the module so a snapshot can freeze it mid-Boo.
 */
@Composable
internal fun BooGhost(progress: () -> Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(bottom = 8.dp)
            .size(width = 132.dp, height = 112.dp)
            .graphicsLayer {
                val p = progress()
                // Up from fully below its own bottom edge, then back down: out of view at both ends.
                val shown = when {
                    p < 0.25f -> easeOut(p / 0.25f)
                    p > 0.75f -> 1f - easeIn((p - 0.75f) / 0.25f)
                    else -> 1f
                }
                translationY = (1f - shown) * size.height + sin(p * 14f) * 3.dp.toPx() * shown
                alpha = shown.coerceIn(0f, 1f)
            },
    ) {
        Canvas(Modifier.align(Alignment.BottomStart).padding(start = 18.dp).size(width = 58.dp, height = 74.dp)) {
            val wave = progress() * 18f
            val c = Offset(size.width / 2f, size.height / 2f)
            // A soft shadow under the sheet, so a white ghost still reads on a light theme.
            drawGhost(c + Offset(0f, 2.dp.toPx()), size.width * 0.92f, Color.Black.copy(alpha = 0.14f), wave)
            drawGhost(c, size.width * 0.9f, GhostWhite, wave)
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .graphicsLayer {
                    // The bubble pops in after the ghost is up and is gone before it sinks.
                    val p = progress()
                    val pop = ((p - 0.22f) / 0.1f).coerceIn(0f, 1f) * (1f - ((p - 0.7f) / 0.08f).coerceIn(0f, 1f))
                    alpha = pop
                    scaleX = 0.6f + 0.4f * pop
                    scaleY = 0.6f + 0.4f * pop
                }
                .background(BubbleInk, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(Res.string.halloween_boo),
                color = GhostWhite,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Candy rain on run
// ---------------------------------------------------------------------------

/**
 * When a program run gets past its build and starts running, a short burst of candy falls over the content
 * area. Keyed on the run console's own phase change (Building to Running for the same run), so a re-run
 * celebrates again and a compile failure, which never reaches Running, does not.
 */
@Composable
private fun RunCandyRain(backend: IdeBackend) {
    val progress = remember { Animatable(1f) }
    var burst by remember { mutableIntStateOf(0) }
    LaunchedEffect(backend) {
        var lastId: Int? = null
        var lastPhase: RunPhase? = null
        backend.build.runConsole
            .map { it?.let { rc -> rc.id to rc.phase } }
            .distinctUntilChanged()
            .collect { now ->
                val (id, phase) = now ?: (null to null)
                if (phase == RunPhase.Running && id == lastId && lastPhase == RunPhase.Building) {
                    burst++
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(CANDY_MS, easing = LinearEasing))
                }
                lastId = id
                lastPhase = phase
            }
    }
    if (progress.isRunning) CandyRain(burst, { progress.value })
}

/** The candy at [progress] (0..1) through a burst seeded by [seed]. Module-visible for the snapshot. */
@Composable
internal fun CandyRain(seed: Int, progress: () -> Float, modifier: Modifier = Modifier) {
    val pieces = remember(seed) { candyOf(Random(seed * 4243 + 11)) }
    Canvas(
        modifier
            .fillMaxSize()
            // Pieces start above the top edge and leave past the bottom; neither should paint over the chrome.
            .clipToBounds()
            // The whole burst fades over its last fifth rather than each piece popping out at the edge.
            .graphicsLayer { alpha = (1f - ((progress() - 0.8f) / 0.2f).coerceIn(0f, 1f)) },
    ) {
        val p = progress()
        val unit = size.minDimension
        for (c in pieces) {
            val t = ((p - c.delay) / (1f - MAX_CANDY_DELAY)).coerceIn(0f, 1f)
            if (t <= 0f) continue
            // A toss: a little upward kick, then gravity strong enough to clear the bottom by the end.
            val y = -unit * 0.08f + size.height * (c.kick * t + 1.25f * t * t)
            val x = size.width * c.x + unit * c.drift * t
            val spin = c.spin0 + c.spin * t
            val s = unit * c.scale
            if (c.corn) drawCandyCorn(Offset(x, y), s, spin)
            else drawWrappedCandy(Offset(x, y), s * 1.6f, c.color, spin)
        }
    }
}

private class Candy(
    val x: Float,
    val delay: Float,
    val kick: Float,
    val drift: Float,
    val spin0: Float,
    val spin: Float,
    val scale: Float,
    val corn: Boolean,
    val color: Color,
)

private fun candyOf(r: Random): List<Candy> = List(14) { i ->
    Candy(
        x = 0.05f + r.nextFloat() * 0.9f,
        delay = r.nextFloat() * MAX_CANDY_DELAY,
        kick = -0.05f - r.nextFloat() * 0.1f,
        drift = (r.nextFloat() - 0.5f) * 0.12f,
        spin0 = r.nextFloat() * 360f,
        spin = (r.nextFloat() - 0.5f) * 540f,
        scale = 0.035f + r.nextFloat() * 0.02f,
        corn = i % 2 == 0,
        color = CandyColors[r.nextInt(CandyColors.size)],
    )
}

// ---------------------------------------------------------------------------
// Pumpkin progress
// ---------------------------------------------------------------------------

/**
 * A build progress bar drawn as a row of small pumpkins that light up in turn: [progress] fills them left to
 * right, or, when null, a lit wave chases along the row. The pumpkins are sized from the height they are
 * given, so this takes whatever footprint the bar it stands in for had. [gap] leaves an empty stretch in the
 * middle (the dock's grab notch sits there).
 */
@Composable
fun PumpkinProgress(progress: (() -> Float)?, modifier: Modifier = Modifier, gap: Dp = 0.dp) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val chase: State<Float>? = if (progress == null && motionIsAnimated()) {
        rememberInfiniteTransition(label = "pumpkinChase").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(CHASE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "pumpkinChaseHead",
        )
    } else null
    Canvas(modifier) {
        val slots = pumpkinSlots(gap.toPx())
        if (slots.isEmpty()) return@Canvas
        for ((i, cx) in slots.withIndex()) {
            val lit = when {
                progress != null -> (progress() * slots.size - i).coerceIn(0f, 1f)
                chase != null -> {
                    // The head runs a little past both ends so the wave enters and leaves the row cleanly.
                    val head = chase.value * (slots.size + 3) - 1.5f
                    (1f - abs(i - head) / 1.6f).coerceIn(0f, 1f)
                }
                else -> 0.6f // motion off: a steady half-lit row still says "working"
            }
            drawLitPumpkin(Offset(cx, size.height / 2f), size.height, lit, dim)
        }
    }
}

/** Centers of the pumpkins that fit across this canvas, skipping any whose center falls in a centered [gap]. */
private fun DrawScope.pumpkinSlots(gap: Float): List<Float> {
    val w = size.height * 1.2f
    val pitch = w + size.height * 0.55f
    val count = floor((size.width + size.height * 0.55f) / pitch).toInt()
    if (count <= 0) return emptyList()
    val start = (size.width - (count * pitch - size.height * 0.55f)) / 2f + w / 2f
    val mid = size.width / 2f
    return List(count) { start + it * pitch }.filter { gap <= 0f || abs(it - mid) > gap / 2f + w / 2f }
}

/** One pumpkin [height] tall: an unlit silhouette in [dim], with the lit pumpkin faded in by [lit] (0..1). */
private fun DrawScope.drawLitPumpkin(center: Offset, height: Float, lit: Float, dim: Color) {
    val w = height * 1.2f
    drawOval(dim, Offset(center.x - w / 2f, center.y - height * 0.41f), androidx.compose.ui.geometry.Size(w, height * 0.82f))
    if (lit <= 0f) return
    if (lit > 0.5f) {
        // A halo that fades to nothing, so a row of lit pumpkins glows rather than sitting on discs.
        val r = height * 0.95f
        drawCircle(Brush.radialGradient(listOf(Glow.copy(alpha = 0.3f * lit), Color.Transparent), center, r), r, center)
    }
    withAlpha(lit) { drawPumpkin(center, w) }
}

/** Draws [block] composited at [alpha], for art whose colors are fixed. */
private inline fun DrawScope.withAlpha(alpha: Float, block: DrawScope.() -> Unit) {
    if (alpha >= 1f) {
        block()
        return
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { this.alpha = alpha })
        block()
        canvas.restore()
    }
}

// ---------------------------------------------------------------------------
// Cauldron status glyph
// ---------------------------------------------------------------------------

/**
 * The build status glyph for [status]: in the season, a cauldron that bubbles while the build runs and puffs
 * a little green smoke as it finishes, then hands back to [glyph] (the ordinary check or cross). Outside the
 * season it is just [glyph]. [size] is the glyph's own footprint.
 */
@Composable
fun SeasonalBuildGlyph(status: RunStatus, size: Dp, glyph: @Composable () -> Unit) {
    if (!isHalloween()) {
        glyph()
        return
    }
    val puff = remember { Animatable(1f) }
    // The status this glyph last showed: a plain holder, since changing it must not recompose anything.
    val last = remember { arrayOfNulls<RunStatus>(1) }
    LaunchedEffect(status) {
        val previous = last[0]
        last[0] = status
        if (previous == RunStatus.Running && status != RunStatus.Running) {
            puff.snapTo(0f)
            puff.animateTo(1f, tween(PUFF_MS, easing = LinearEasing))
        }
    }
    val running = status == RunStatus.Running
    // `last` still holds the previous status in the frame the build finishes (the effect that starts the puff
    // runs after it), so this keeps the cauldron up for that frame instead of flashing the check first.
    if (!running && !puff.isRunning && last[0] != RunStatus.Running) {
        glyph()
        return
    }
    val bubble: State<Float>? = if (running && motionIsAnimated()) {
        rememberInfiniteTransition(label = "cauldron").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(BUBBLE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "cauldronBubble",
        )
    } else null
    // Iron, not the text color: a white pot read as a bathtub on dark themes. Lighter on dark so it still
    // separates from the background, near-black on light.
    val pot = cauldronIron(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    Canvas(Modifier.size(size)) {
        drawCauldronGlyph(pot, bubble?.value ?: 1.2f, if (running) null else puff.value)
    }
}

/**
 * The cauldron filling this canvas, its brew at [bubble] (radians), and, when [puff] is set (0..1), a smoke
 * puff rising off it. Module-visible for the snapshot.
 */
internal fun DrawScope.drawCauldronGlyph(pot: Color, bubble: Float, puff: Float?) {
    val w = size.minDimension
    val c = Offset(size.width / 2f, size.height * 0.62f)
    drawCauldron(c, w, pot, bubble)
    if (puff != null) {
        // Three soft rings swelling and rising off the brew, fading as they go.
        for (i in 0 until 3) {
            val t = (puff * 1.3f - i * 0.15f).coerceIn(0f, 1f)
            if (t <= 0f || t >= 1f) continue
            val x = c.x + w * (i - 1) * 0.16f
            val y = c.y - w * 0.25f - w * 0.55f * t
            drawCircle(Smoke.copy(alpha = 0.55f * (1f - t)), w * (0.1f + 0.14f * t), Offset(x, y))
        }
    }
}

private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)
private fun easeIn(t: Float): Float = t * t

private const val BOO_MS = 1600
private const val CANDY_MS = 2000
private const val CHASE_MS = 1400
private const val BUBBLE_MS = 1300
private const val PUFF_MS = 900
/** The latest a candy may start falling, as a fraction of the burst. */
private const val MAX_CANDY_DELAY = 0.3f

private val GhostWhite = Color(0xFFF6F1FA)
private val BubbleInk = Color(0xFF2A1E33)
private val Glow = Color(0xFFFFB347)
private val Smoke = Color(0xFF8FE07A)
private val CandyColors = listOf(Color(0xFF9B5DE5), Color(0xFF7CD957), Color(0xFFFF8A1F), Color(0xFFFF5FA2))

/** The cauldron's iron for a dark or light background. Module-visible for the snapshot. */
internal fun cauldronIron(dark: Boolean): Color = if (dark) Color(0xFF6A5F73) else Color(0xFF2B2530)

