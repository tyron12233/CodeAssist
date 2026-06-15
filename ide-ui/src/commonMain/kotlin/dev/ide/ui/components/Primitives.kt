package dev.ide.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion

/** The three liquid-glass materials (translucent fill plus saturation/blur; here a token-based fill). */
enum class GlassMaterial { Thin, Regular, Thick }

@Composable
fun glassFill(material: GlassMaterial): Color = when (material) {
    GlassMaterial.Thin -> Ca.colors.glassThin
    GlassMaterial.Regular -> Ca.colors.glassReg
    GlassMaterial.Thick -> Ca.colors.glassThick
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
    val edgeTop = Ca.colors.glassEdgeTop
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
 * Scale-to-0.96 press feedback driven by an interaction source. Uses a bouncy spring (the
 * design's liquid-overshoot `ease-spring`) so releasing a press settles back past 1.0 for the
 * "liquid" button feel.
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
 * One-shot slide-up entrance (the `ca-fade-up` keyframe — transform-only: `translateY(9px) → 0`,
 * `base`/`quiet`). Content is fully opaque throughout so it stays visible if motion is disabled.
 * Pass [delayMillis] to stagger a list (the design's `animationDelay: i * 60ms`).
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
 * One-shot pop entrance (the `ca-pop` keyframe — `scale(0.96) translateY(5px) → none`,
 * `base`/`spring`). Used for popovers like the completion list.
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

/** Square icon button (≥44dp tap target); accent-soft fill + accent tint when [active]. */
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
    val interaction = remember { MutableInteractionSource() }
    val resolvedTint = tint ?: if (active) Ca.colors.accent else Ca.colors.textSecondary
    Box(
        modifier
            .size(boxSize.dp)
            .pressScale(interaction)
            .background(
                if (active) Ca.colors.accentSoft else Color.Transparent,
                RoundedCornerShape(Ca.radius.sm),
            )
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize.dp), tint = resolvedTint)
    }
}

/**
 * Accent-filled primary button (e.g. Run), 38dp tall, with an optional leading icon. When [iconOnly]
 * (and [icon] is set) it collapses to a fixed-width square — the label is dropped but kept as the
 * accessibility description — so it can't be squeezed below its content on a narrow bar.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconOnly: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val collapsed = iconOnly && icon != null
    Row(
        modifier
            .height(38.dp)
            .then(if (collapsed) Modifier.width(44.dp) else Modifier)
            .pressScale(interaction)
            .background(Ca.colors.accent, RoundedCornerShape(Ca.radius.control))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (collapsed) 0.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, if (collapsed) text else null, Modifier.size(16.dp), tint = Ca.colors.textOnAccent)
        if (!collapsed) Text(text, color = Ca.colors.textOnAccent, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
    }
}

/** A soft pill/chip with a translucent fill (used for status, hints, meta). */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    fill: Color = Ca.colors.surface2,
    textColor: Color = Ca.colors.textSecondary,
) {
    Box(
        modifier
            .defaultMinSize(minHeight = 22.dp)
            .background(fill, RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, style = Ca.type.caption2, fontWeight = FontWeight.Medium)
    }
}
