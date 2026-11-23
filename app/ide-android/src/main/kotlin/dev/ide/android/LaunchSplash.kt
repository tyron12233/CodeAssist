package dev.ide.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The launch background: the same `@color/splash_background` the system splash and the window draw. */
internal val SplashBackground = Color(0xFF0E0E12)

/**
 * Where the system splash (API 31+) drew its icon, in window pixels: the centre and the side of its icon view.
 * The in-app splash starts drawing the logo exactly there, so the hand-over from the system's splash to ours
 * cannot be seen.
 */
internal data class SplashIcon(val centerX: Float, val centerY: Float, val viewSize: Float)

/**
 * The logo's side with no system splash to measure (below API 31): the adaptive-icon foreground at the
 * splash's "no icon background" size. `drawable/splash_window.xml` centres the logo at this same size, so the
 * starting window and the first frame of this splash agree.
 */
private val DEFAULT_LOGO_SIZE = 288.dp

/**
 * The system's icon view is the visible circle of an adaptive icon (72dp of its 108dp canvas, scaled up);
 * the foreground layer, which is what the logo bitmap is, spans 108/72 of it.
 */
private const val ICON_VIEW_TO_LOGO = 1.5f

/** Before this, a launch is quick enough that a caption would only flash; after it, say something is happening. */
private const val SLOW_LAUNCH_CAPTION_MS = 2_500L

private val GlowColor = Color(0xFF8B5CF6)

/**
 * The launch screen while the engine boots: the app's logo with its caret blinking, as in an editor, over a
 * slow purple glow. No progress bar, because there is no progress to report; a blinking caret says "working"
 * in this app's own terms.
 *
 * It starts identical to the system splash (same logo, place, size and background, caret on, no glow), then
 * animates. When [ready], it waits for the app underneath to draw its first frames (the heaviest frames of
 * the launch, which a fade running through them would stutter on), fades out while the logo grows a little,
 * and calls [onFinished]. With an [error] the logo stops and the error shows below it.
 *
 * The caret blinks by cross-fading the full logo with `splash_logo_body`, a copy with the caret painted out
 * (generated once from the launcher foreground). Both are summed with [BlendMode.Plus] on an offscreen layer,
 * which is an exact per-pixel blend, so the caret-on frame is the launcher logo pixel for pixel.
 */
@Composable
internal fun LaunchSplash(
    systemIcon: SplashIcon?,
    ready: Boolean,
    error: String?,
    onFinished: () -> Unit,
) {
    val logo = ImageBitmap.imageResource(R.drawable.splash_logo)
    val body = ImageBitmap.imageResource(R.drawable.splash_logo_body)
    var origin by remember { mutableStateOf(Offset.Zero) }

    val motion = rememberInfiniteTransition(label = "splash")
    // An editor caret's rhythm: on for half a second, a quick fade, off, back.
    val caretOn by motion.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1_060
                1f at 500
                0f at 620
                0f at 940
            },
        ),
        label = "caret",
    )
    val breath by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val failed = error != null
    val caret = if (failed) 1f else caretOn
    val glow = if (failed) 0f else breath

    val exit = remember { Animatable(0f) }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        awaitSmoothFrames()
        exit.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
        onFinished()
    }

    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SLOW_LAUNCH_CAPTION_MS)
        slow = true
    }
    val captionAlpha by animateFloatAsState(if (slow && !ready && !failed) 1f else 0f, tween(500), label = "caption")

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .graphicsLayer { alpha = 1f - exit.value }
            .background(SplashBackground),
    ) {
        val density = LocalDensity.current
        val logoPx = systemIcon?.let { it.viewSize * ICON_VIEW_TO_LOGO } ?: with(density) { DEFAULT_LOGO_SIZE.toPx() }
        val centerY = systemIcon?.let { it.centerY - origin.y } ?: with(density) { maxHeight.toPx() / 2f }

        Canvas(Modifier.fillMaxSize()) {
            val center = systemIcon?.let { Offset(it.centerX - origin.x, it.centerY - origin.y) } ?: this.center
            val logoScale = 1f + 0.02f * glow + 0.10f * exit.value
            if (glow > 0f) {
                val radius = logoPx * 0.36f * logoScale
                drawCircle(
                    Brush.radialGradient(
                        listOf(GlowColor.copy(alpha = 0.20f * glow), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            scale(logoScale, pivot = center) {
                drawLogo(logo, body, caret, center, logoPx)
            }
        }

        // Below the logo's visible part (the inner two thirds of its canvas).
        val belowLogo = with(density) { (centerY + logoPx / 3f).toDp() + 16.dp }
        if (error != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .offset(y = belowLogo)
                    .heightIn(max = (maxHeight - belowLogo - 24.dp).coerceAtLeast(64.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("CodeAssist couldn't start", color = Color(0xFFE6E6EE), fontSize = 16.sp)
                Text(
                    error,
                    color = Color(0xFF9A9AAA),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().offset(y = belowLogo).graphicsLayer { alpha = captionAlpha }, Alignment.TopCenter) {
                Text(
                    "Getting things ready…",
                    color = Color(0xFF8E8E9C),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Returns once frames are coming at a steady rate again, or after [SETTLE_LIMIT_MS] regardless.
 *
 * The app's first composition, and the first screen's own entrance animations, land in the frames right
 * after the engine is up and can take a few hundred milliseconds each on a slow phone. A fade started into
 * them shows two or three of its frames and jumps to the end.
 */
private suspend fun awaitSmoothFrames() {
    val start = withFrameNanos { it }
    var last = start
    var smooth = 0
    while (smooth < SMOOTH_FRAMES && last - start < SETTLE_LIMIT_MS * 1_000_000) {
        val now = withFrameNanos { it }
        smooth = if (now - last <= SMOOTH_FRAME_NS) smooth + 1 else 0
        last = now
    }
}

private const val SMOOTH_FRAMES = 3
private const val SMOOTH_FRAME_NS = 24_000_000L
private const val SETTLE_LIMIT_MS = 700L

/** The logo centred on [center] at [sizePx], its caret at [caret] (1 = the launcher logo exactly, 0 = no caret). */
private fun DrawScope.drawLogo(logo: ImageBitmap, body: ImageBitmap, caret: Float, center: Offset, sizePx: Float) {
    val side = sizePx.roundToInt()
    val topLeft = IntOffset((center.x - side / 2f).roundToInt(), (center.y - side / 2f).roundToInt())
    val dst = IntSize(side, side)
    val src = IntSize(logo.width, logo.height)
    when {
        caret >= 1f -> drawImage(logo, srcSize = src, dstOffset = topLeft, dstSize = dst)
        caret <= 0f -> drawImage(body, srcSize = IntSize(body.width, body.height), dstOffset = topLeft, dstSize = dst)
        else -> drawIntoCanvas { canvas ->
            // Plus needs a transparent layer of its own, or it would add onto the background.
            canvas.saveLayer(Rect(topLeft.x.toFloat(), topLeft.y.toFloat(), (topLeft.x + side).toFloat(), (topLeft.y + side).toFloat()), Paint())
            drawImage(body, srcSize = IntSize(body.width, body.height), dstOffset = topLeft, dstSize = dst, alpha = 1f - caret, blendMode = BlendMode.Plus)
            drawImage(logo, srcSize = src, dstOffset = topLeft, dstSize = dst, alpha = caret, blendMode = BlendMode.Plus)
            canvas.restore()
        }
    }
}
