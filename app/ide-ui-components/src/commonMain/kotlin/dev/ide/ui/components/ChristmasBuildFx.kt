package dev.ide.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Christmas build and run moments, the counterpart of [HalloweenBuildFx], kept quiet on purpose: a soft
 * fall of snow and a gold star when a build succeeds, a breath of frost along the bottom edge when one fails,
 * the progress bar as a row of presents being wrapped, a small fir whose lights twinkle while the build runs,
 * and a shimmer of gold when a program starts.
 *
 * The same rules as Halloween's: one-shot moments are a single [Animatable] tween, so the platform animation
 * scale applies and with animations off nothing is drawn; looping ones fall back to a still frame when
 * [motionIsAnimated] says motion is off; and nothing is composed at rest.
 */

/**
 * The root overlay for the one-shot build/run moments. Sits over the content area and takes no input; draws
 * nothing outside the season or between moments.
 */
@Composable
fun ChristmasBuildFx(backend: IdeBackend, modifier: Modifier = Modifier) {
    if (!isChristmas()) return
    Box(modifier.fillMaxSize()) {
        BuildSucceededSnow(backend)
        BuildFailedFrost(backend)
        RunShimmer(backend)
    }
}

/** Fires [onEdge] for each build seen running and then ending in [to]. */
@Composable
private fun OnBuildFinished(backend: IdeBackend, to: RunStatus, onEdge: suspend () -> Unit) {
    LaunchedEffect(backend) {
        var last: RunStatus? = null
        backend.build.buildState
            .map { it.status }
            .distinctUntilChanged()
            .collect { status ->
                if (last == RunStatus.Running && status == to) onEdge()
                last = status
            }
    }
}

// ---------------------------------------------------------------------------
// Succeeded: snow and a star
// ---------------------------------------------------------------------------

@Composable
private fun BoxScope.BuildSucceededSnow(backend: IdeBackend) {
    val progress = remember { Animatable(1f) }
    var burst by remember { mutableIntStateOf(0) }
    OnBuildFinished(backend, RunStatus.Succeeded) {
        burst++
        progress.snapTo(0f)
        progress.animateTo(1f, tween(SNOW_BURST_MS, easing = LinearEasing))
    }
    if (progress.isRunning) SnowAndStar(burst, { progress.value })
}

/**
 * A short fall of snow over the content area, with a gold star rising out of the bottom edge (where the build
 * console lives) and fading at the top of its climb. [progress] 0..1 through the moment; module-visible for the
 * snapshot.
 */
@Composable
internal fun SnowAndStar(seed: Int, progress: () -> Float, modifier: Modifier = Modifier) {
    val flakes = remember(seed) { burstOf(Random(seed * 6151 + 3)) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val snow = if (dark) ChristmasSnow else Color(0xFFB9CBC3)
    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer { alpha = 1f - ((progress() - 0.8f) / 0.2f).coerceIn(0f, 1f) },
    ) {
        val p = progress()
        val unit = size.minDimension
        for (f in flakes) {
            val t = ((p - f.delay) / (1f - MAX_FLAKE_DELAY)).coerceIn(0f, 1f)
            if (t <= 0f) continue
            // Snow drifts rather than falls: a gentle, nearly constant speed with a slow sideways sway.
            val y = -unit * 0.05f + size.height * 0.75f * t * f.speed
            val x = size.width * f.x + sin(t * 5f + f.phase) * unit * 0.03f
            if (f.crystal) drawSnowflake(Offset(x, y), unit * f.scale, snow.copy(alpha = 0.85f), f.phase * 40f + t * 60f)
            else drawSnowDot(Offset(x, y), unit * f.scale * 0.35f, snow.copy(alpha = 0.9f))
        }
        // The star: up from below the bottom edge over the first 40%, holding, then fading at the top.
        val rise = easeOutCubic((p / 0.4f).coerceIn(0f, 1f))
        val fade = 1f - ((p - 0.7f) / 0.3f).coerceIn(0f, 1f)
        if (fade > 0f) {
            val r = minOf(unit * 0.07f, 34.dp.toPx())
            val c = Offset(size.width / 2f, size.height + r * 2f - (size.height * 0.28f + r * 2f) * rise)
            drawStar(c, r * fade.coerceAtLeast(0.6f), ChristmasGold.copy(alpha = fade), glow = fade, rotation = (1f - rise) * -40f)
        }
    }
}

private class BurstFlake(val x: Float, val delay: Float, val speed: Float, val phase: Float, val scale: Float, val crystal: Boolean)

private fun burstOf(r: Random): List<BurstFlake> = List(22) { i ->
    BurstFlake(
        x = 0.03f + r.nextFloat() * 0.94f,
        delay = r.nextFloat() * MAX_FLAKE_DELAY,
        speed = 0.85f + r.nextFloat() * 0.5f,
        phase = r.nextFloat() * 6f,
        scale = 0.014f + r.nextFloat() * 0.012f,
        crystal = i % 3 == 0,
    )
}

// ---------------------------------------------------------------------------
// Failed: a breath of frost
// ---------------------------------------------------------------------------

@Composable
private fun BoxScope.BuildFailedFrost(backend: IdeBackend) {
    val progress = remember { Animatable(1f) }
    var seed by remember { mutableIntStateOf(0) }
    OnBuildFinished(backend, RunStatus.Failed) {
        seed++
        progress.snapTo(0f)
        progress.animateTo(1f, tween(FROST_MS, easing = LinearEasing))
    }
    if (progress.isRunning) FrostEdge(seed, { progress.value })
}

/**
 * Frost creeping a little way up from the bottom edge and the two lower corners, with a few ice crystals
 * forming in it, then melting away. It never reaches far enough to cover an error list for long, and takes no
 * input. [progress] 0..1; module-visible for the snapshot.
 */
@Composable
internal fun FrostEdge(seed: Int, progress: () -> Float, modifier: Modifier = Modifier) {
    val crystals = remember(seed) { frostOf(Random(seed * 3571 + 7)) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ice = if (dark) Color(0xFFD6ECF5) else Color(0xFF7FA9BF)
    Canvas(modifier.fillMaxSize().clipToBounds()) {
        val p = progress()
        // In over the first 35%, held, melting over the last 35%.
        val shown = when {
            p < 0.35f -> easeOutCubic(p / 0.35f)
            p > 0.65f -> 1f - ((p - 0.65f) / 0.35f).coerceIn(0f, 1f)
            else -> 1f
        }
        if (shown <= 0f) return@Canvas
        val reach = minOf(size.height * 0.18f, 140.dp.toPx()) * shown
        drawRect(
            Brush.verticalGradient(listOf(ice.copy(alpha = 0f), ice.copy(alpha = 0.22f * shown)), startY = size.height - reach, endY = size.height),
            Offset(0f, size.height - reach),
            androidx.compose.ui.geometry.Size(size.width, reach),
        )
        for (corner in listOf(Offset(0f, size.height), Offset(size.width, size.height))) {
            val r = reach * 1.8f
            drawCircle(Brush.radialGradient(listOf(ice.copy(alpha = 0.26f * shown), ice.copy(alpha = 0f)), corner, r), r, corner)
        }
        for (c in crystals) {
            val grow = ((shown - c.delay) / (1f - c.delay)).coerceIn(0f, 1f)
            if (grow <= 0f) continue
            val x = c.x * size.width
            val y = size.height - reach * c.height
            drawSnowflake(Offset(x, y), 7.dp.toPx() * c.scale * grow, ice.copy(alpha = 0.7f * shown), c.rotation)
        }
    }
}

private class FrostCrystal(val x: Float, val height: Float, val delay: Float, val scale: Float, val rotation: Float)

private fun frostOf(r: Random): List<FrostCrystal> = List(9) {
    // Most of them near the corners, where the frost is thickest.
    val edge = r.nextFloat() * 0.3f
    FrostCrystal(
        x = if (r.nextBoolean()) edge else 1f - edge,
        height = 0.15f + r.nextFloat() * 0.6f,
        delay = r.nextFloat() * 0.5f,
        scale = 0.6f + r.nextFloat() * 0.8f,
        rotation = r.nextFloat() * 60f,
    )
}

// ---------------------------------------------------------------------------
// Run started: a shimmer of gold
// ---------------------------------------------------------------------------

/** When a program run gets past its build and starts running, a few gold glints twinkle across the top. */
@Composable
private fun RunShimmer(backend: IdeBackend) {
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
                    progress.animateTo(1f, tween(SHIMMER_MS, easing = LinearEasing))
                }
                lastId = id
                lastPhase = phase
            }
    }
    if (progress.isRunning) GoldShimmer(burst, { progress.value })
}

/** The glints at [progress] (0..1) through a shimmer seeded by [seed]. Module-visible for the snapshot. */
@Composable
internal fun GoldShimmer(seed: Int, progress: () -> Float, modifier: Modifier = Modifier) {
    val glints = remember(seed) { glintsOf(Random(seed * 2903 + 5)) }
    Canvas(modifier.fillMaxSize().clipToBounds()) {
        val p = progress()
        val unit = minOf(size.minDimension, 900f)
        for (g in glints) {
            // Each glint swells and fades in its own short window, left to right like a ripple of bells.
            val t = ((p - g.start) / GLINT_LIFE).coerceIn(0f, 1f)
            if (t <= 0f || t >= 1f) continue
            val a = sin(t * PI.toFloat())
            val c = Offset(g.x * size.width, g.y * size.height * 0.3f + unit * 0.04f)
            drawCircle(Brush.radialGradient(listOf(ChristmasGold.copy(alpha = 0.35f * a), ChristmasGold.copy(alpha = 0f)), c, unit * 0.05f), unit * 0.05f, c)
            drawSparkle(c, unit * g.scale * (0.6f + 0.4f * a), ChristmasGold.copy(alpha = a))
        }
    }
}

/** A four-pointed glint: two thin crossed diamonds. */
internal fun DrawScope.drawSparkle(center: Offset, radius: Float, color: Color) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x, center.y, center.x + radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + radius)
        quadraticTo(center.x, center.y, center.x - radius, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - radius)
        close()
    }
    drawPath(path, color)
}

private class Glint(val x: Float, val y: Float, val start: Float, val scale: Float)

private fun glintsOf(r: Random): List<Glint> = List(10) { i ->
    val x = (i + r.nextFloat()) / 10f
    Glint(x = x, y = r.nextFloat(), start = x * (1f - GLINT_LIFE) * 0.8f + r.nextFloat() * 0.1f, scale = 0.018f + r.nextFloat() * 0.014f)
}

// ---------------------------------------------------------------------------
// Presents progress
// ---------------------------------------------------------------------------

/**
 * A build progress bar drawn as a row of small presents that get wrapped in turn: [progress] fills them left to
 * right, or, when null, a wave of wrapping travels along the row. Unwrapped boxes are plain outlines in the
 * theme's faint ink, so the bar reads on any theme. Sized from the height it is given, so it takes the footprint
 * of the bar it replaces; [gap] leaves an empty stretch in the middle (the dock's grab notch sits there).
 */
@Composable
fun PresentsProgress(progress: (() -> Float)?, modifier: Modifier = Modifier, gap: Dp = 0.dp) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val chase: State<Float>? = if (progress == null && motionIsAnimated()) {
        rememberInfiniteTransition(label = "presentsChase").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(PRESENTS_CHASE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "presentsChaseHead",
        )
    } else null
    Canvas(modifier) {
        val slots = pumpkinSlots(size.width, size.height, gap.toPx())
        if (slots.isEmpty()) return@Canvas
        for ((i, cx) in slots.withIndex()) {
            val wrapped = when {
                progress != null -> (progress() * slots.size - i).coerceIn(0f, 1f)
                chase != null -> {
                    val head = chase.value * (slots.size + 3) - 1.5f
                    (1f - abs(i - head) / 1.6f).coerceIn(0f, 1f)
                }
                else -> 0.6f
            }
            drawWrappingPresent(Offset(cx, size.height / 2f), size.height, wrapped, i, dim)
        }
    }
}

/** One present [height] tall: a faint box, with the wrapped one (in its turn's color) faded in by [wrapped]. */
private fun DrawScope.drawWrappingPresent(center: Offset, height: Float, wrapped: Float, index: Int, dim: Color) {
    val w = height * 1.05f
    drawGift(center, w, height, wrap = dim, ribbon = dim)
    if (wrapped <= 0f) return
    val wrap = WrapColors[index % WrapColors.size]
    val ribbon = if (wrap == ChristmasGold) ChristmasCranberry else ChristmasGold
    drawGift(center, w, height, wrap = wrap.copy(alpha = wrapped), ribbon = ribbon.copy(alpha = wrapped))
}

private val WrapColors = listOf(ChristmasCranberry, ChristmasFir, ChristmasGold, Color(0xFF5B8FB9))

// ---------------------------------------------------------------------------
// Fir status glyph
// ---------------------------------------------------------------------------

/**
 * The Christmas build status glyph: while the build runs, a small fir whose lights twinkle; when it finishes,
 * the star on top pops once before handing back to [glyph]. [finishing] is 0..1 through that pop, or null
 * while running.
 */
@Composable
internal fun ChristmasBuildGlyph(running: Boolean, finishing: Float?, size: Dp) {
    val twinkle: State<Float>? = if (running && motionIsAnimated()) {
        rememberInfiniteTransition(label = "firLights").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(TWINKLE_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "firLightsPhase",
        )
    } else null
    Canvas(Modifier.size(size)) { drawFirGlyph(twinkle?.value ?: 1f, finishing) }
}

/** The fir filling this canvas, lights at [phase] (radians), and the star's pop at [pop] (0..1) if set. */
internal fun DrawScope.drawFirGlyph(phase: Float, pop: Float?) {
    val h = size.minDimension
    drawFirTree(Offset(size.width / 2f, size.height * 0.98f), h * 0.96f, lights = 1f, lightPhase = phase)
    if (pop != null) {
        val c = Offset(size.width / 2f, size.height * 0.98f - h * 0.96f * 0.88f)
        val swell = sin(pop * PI.toFloat())
        drawStar(c, h * 0.1f * (1f + swell * 0.9f), ChristmasGold, glow = swell)
    }
}

private fun easeOutCubic(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

private const val SNOW_BURST_MS = 2600
private const val FROST_MS = 1800
private const val SHIMMER_MS = 1400
private const val PRESENTS_CHASE_MS = 1600
private const val TWINKLE_MS = 2200
/** The latest a flake may start falling, as a fraction of the burst. */
private const val MAX_FLAKE_DELAY = 0.35f
/** How much of the shimmer one glint lasts. */
private const val GLINT_LIFE = 0.32f
