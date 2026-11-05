package dev.ide.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.Motion

/** The three liquid-glass materials (translucent fill plus saturation/blur; here a token-based fill). */
enum class GlassMaterial { Thin, Regular, Thick }

@Composable
fun glassFill(material: GlassMaterial): Color = when (material) {
    GlassMaterial.Thin -> Ide.colors.glassThin
    GlassMaterial.Regular -> Ide.colors.glassReg
    GlassMaterial.Thick -> Ide.colors.glassThick
}

/**
 * A chrome surface in glass: translucent fill + a 1px top edge highlight and side/bottom hairline,
 * the token recipe for "liquid glass". (True backdrop blur is a desktop RenderEffect enhancement; the
 * fill alone over the app background already reads as frosted and is the spec's solid fallback.)
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.Regular,
    shape: Shape = RoundedCornerShape(0.dp),
    content: @Composable () -> Unit,
) {
    val fill = glassFill(material)
    val edgeTop = Ide.colors.glassEdgeTop
    Box(
        modifier
            .background(fill, shape)
            .drawBehind {
                // top edge highlight
                drawLine(edgeTop, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
            },
    ) { content() }
}

/**
 * An opaque chrome surface for tool windows (the side and bottom panes, the phone's drawers): the theme's
 * container colour with the same 1px top edge as [GlassSurface]. Tool windows sit over the editor and its
 * code, which a translucent fill lets show through.
 */
@Composable
fun ToolWindowSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val edgeTop = Ide.colors.glassEdgeTop
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .drawBehind { drawLine(edgeTop, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) },
    ) { content() }
}

/**
 * Scale-to-0.96 press feedback driven by an interaction source. Uses a bouncy spring (the expressive
 * spring feel) so releasing a press settles back past 1.0 for a lively button response.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * One-shot slide-up entrance (transform-only: `translateY(9px) → 0`). Content is fully opaque throughout so
 * it stays visible if motion is disabled. Pass [delayMillis] to stagger a list.
 */
@Composable
fun Modifier.entranceSlideUp(delayMillis: Int = 0): Modifier {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0) delay(delayMillis.toLong())
        appeared = true
    }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.BASE, easing = Motion.quiet),
        label = "entranceSlideUp",
    )
    return this.graphicsLayer { translationY = (1f - progress) * 9.dp.toPx() }
}

/**
 * One-shot entrance for a screen's parts: fade in while rising [rise], each [order] starting
 * [ENTRANCE_STAGGER_MS] after the one before (capped at [ENTRANCE_MAX_ORDER], so a long list does not keep
 * the last item waiting). Give a screen's pieces increasing orders, top to bottom, to stagger them.
 *
 * The wait is the tween's own `delayMillis`, not a coroutine `delay`, so it runs on the frame clock and
 * scales with the system's animation speed: with animations turned off (MotionDurationScale 0, Android's
 * animator scale or iOS Reduce Motion) everything is simply there on the first frame. Read in
 * `graphicsLayer`, so the animation redraws the layer without recomposing what it wraps. It replays when
 * the element re-enters composition (a tab revisited), never on recomposition.
 */
@Composable
fun Modifier.entranceReveal(order: Int = 0, rise: Dp = 14.dp): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            tween(Motion.BASE, delayMillis = order.coerceIn(0, ENTRANCE_MAX_ORDER) * ENTRANCE_STAGGER_MS, easing = Motion.quiet),
        )
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * rise.toPx()
    }
}

private const val ENTRANCE_STAGGER_MS = 30
private const val ENTRANCE_MAX_ORDER = 6

/**
 * One-shot pop entrance (`scale(0.96) translateY(5px) → none`, expressive spring). Used for popovers like
 * the completion list.
 */
@Composable
fun Modifier.entrancePop(): Modifier {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "entrancePop",
    )
    return this.graphicsLayer {
        val s = 0.96f + 0.04f * progress
        scaleX = s
        scaleY = s
        translationY = (1f - progress) * 5.dp.toPx()
    }
}

/**
 * Square icon button (a compact toolbar control, denser than M3's 48dp default so it fits the top bar).
 * Reads Material roles: an [active] control gets a `secondaryContainer` fill + `onSecondaryContainer` tint;
 * inactive is transparent with an `onSurfaceVariant` glyph.
 */
@Composable
fun IconButtonCa(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    iconSize: Int = 20,
    boxSize: Int = 34,
    tint: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val resolvedTint = tint ?: if (active) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    Box(
        modifier
            .size(boxSize.dp)
            .pressScale(interaction)
            .background(
                if (active) scheme.secondaryContainer else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize.dp), tint = resolvedTint)
    }
}

/**
 * Filled primary button (e.g. Run), a native M3 [Button] on the expressive shape scale, 38dp tall with an
 * optional leading icon. When [iconOnly] (and [icon] is set) it collapses to a fixed-width square
 * [FilledIconButton] — the label is dropped but kept as the accessibility description.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconOnly: Boolean = false,
) {
    val collapsed = iconOnly && icon != null
    if (collapsed) {
        FilledIconButton(
            onClick = onClick,
            modifier = modifier.size(width = 44.dp, height = 38.dp),
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(icon!!, text, Modifier.size(18.dp))
        }
    } else {
        Button(
            onClick = onClick,
            modifier = modifier.height(38.dp),
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A soft pill/chip with a tonal fill (used for status, hints, meta). Denser than an M3 AssistChip. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier
            .defaultMinSize(minHeight = 22.dp)
            .background(fill, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * While [busy], a gradient arc circles the element (a tool window's icon whose work is still running, like the AI
 * agent mid-reply), so it reads as "still working" from anywhere in the app, even with its panel closed. Drawn
 * behind the content, inset to the element's bounds; nothing is drawn or animated when idle.
 */
@Composable
fun Modifier.busyRing(busy: Boolean): Modifier {
    if (!busy) return this
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "busyRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "busyRingAngle",
    )
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(Color.Transparent, scheme.tertiary, scheme.primary, Color.Transparent)
    return this.drawBehind {
        val stroke = 2.dp.toPx()
        val radius = size.minDimension / 2f - stroke / 2f
        rotate(angle) {
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.sweepGradient(colors, center),
                radius = radius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}
