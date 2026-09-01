package dev.ide.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.CaMotion
import dev.ide.ui.theme.CaShapes
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.TonalPair
import dev.ide.ui.theme.tonalPair

/**
 * The small shared vocabulary the Home / Explore / Learn redesign is assembled from.
 *
 * These are not generic wrappers around M3 components. Each one encodes a rule from the design that a
 * bare `Card` or `FilterChip` would let a call site get wrong: that text on a tonal container uses that
 * container's own `on*` role, that a supporting line is exactly 70% opacity, that a watermark glyph is
 * exactly 13%, that a pressed card scales to 0.98 and nothing else moves.
 */

/**
 * The all-caps eyebrow label ("ABOUT", "SORT BY", "CONTINUE").
 *
 * Uppercasing happens here rather than in the string resources: a translator should be given a normal
 * sentence-case string, and not every language has a meaningful uppercase form.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) = Text(
    text = text.uppercase(),
    style = MaterialTheme.typography.labelSmall,
    color = color,
    modifier = modifier,
)

/**
 * Supporting text on a tonal container: the container's own `on*` role at 70%.
 *
 * The alpha is fixed rather than a parameter on purpose — 70% is the one step the design sanctions here,
 * and it is the tightest contrast pair in the palette, so it is the value to re-check after any color
 * edit rather than one to tune per call site.
 */
@Composable
fun SupportingOnContainer(
    text: String,
    onContainer: Color,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    text = text,
    style = style,
    color = onContainer.copy(alpha = 0.70f),
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
)

/**
 * An oversized glyph bled off the corner of a tonal surface at 13% of its `on*` role.
 *
 * Purely decorative, so it is cleared from the semantics tree: a screen reader announcing "layers" for
 * every card's wallpaper would drown the card's actual label. Place it as the FIRST child of a clipping
 * [Box] and draw content over it.
 */
@Composable
fun WatermarkGlyph(
    glyph: Char,
    onContainer: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) = Symbol(
    glyph = glyph,
    contentDescription = null,
    size = size,
    tint = onContainer.copy(alpha = 0.13f),
    modifier = modifier,
)

/** Scale-on-press feedback. The design moves nothing else: no elevation change, no color shift. */
@Composable
fun Modifier.pressScale(
    interaction: MutableInteractionSource,
    pressed: Float = 0.98f,
): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = CaMotion.fastSpatial(),
        label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * A filter chip carrying a count badge — Home's `Projects · Updates · Saved` row.
 *
 * When selected the badge **inverts**: its background becomes the chip's foreground and vice versa. That
 * inversion is what keeps the count readable once the chip itself is filled, and it is why this is a
 * bespoke chip rather than a `FilterChip` with a trailing icon.
 */
@Composable
fun CountFilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val bg = if (selected) c.primaryContainer else Color.Transparent
    val fg = if (selected) c.onPrimaryContainer else c.onSurfaceVariant
    val badgeBg = if (selected) c.onPrimaryContainer else c.surfaceContainerHigh
    val badgeFg = if (selected) c.primaryContainer else c.onSurfaceVariant
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        contentColor = fg,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, c.outline),
        modifier = modifier.height(38.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Box(
                Modifier.defaultMinSize(minWidth = 20.dp).height(20.dp)
                    .clip(CircleShape).background(badgeBg)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeFg,
                )
            }
        }
    }
}

/** A selectable pill with no count — the Learn track row and the detail screen's tab row. */
@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingGlyph: Char? = null,
    height: Dp = 36.dp,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) c.primaryContainer else Color.Transparent,
        contentColor = if (selected) c.onPrimaryContainer else c.onSurfaceVariant,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, c.outline),
        modifier = modifier.height(height),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (leadingGlyph != null && selected) {
                Symbol(leadingGlyph, contentDescription = null, size = 16.dp)
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A monospace metadata chip: a path fragment, a version, a tech-stack entry, a language tag. */
@Composable
fun MonoChip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    glyph: Char? = null,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content, modifier = modifier.height(26.dp)) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (glyph != null) Symbol(glyph, contentDescription = null, size = 14.dp)
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = dev.ide.ui.theme.Ca.type.codeFamily,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Home's 64 dp primary call to action, and the mirrored square button beside it.
 *
 * The two shapes are deliberate opposites ([CaShapes.PrimaryAction] and [CaShapes.SquareAction]): placed
 * side by side they read as one cut piece rather than two unrelated buttons.
 */
@Composable
fun PrimaryActionButton(
    label: String,
    glyph: Char,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = CaShapes.PrimaryAction,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        interactionSource = interaction,
        modifier = modifier.height(64.dp).pressScale(interaction),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol(glyph, contentDescription = null, size = 26.dp)
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** The 64 dp square tonal button that pairs with [PrimaryActionButton]. */
@Composable
fun SquareToneButton(
    glyph: Char,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CaShapes.SquareAction,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 64.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = shape,
        color = container,
        contentColor = content,
        interactionSource = interaction,
        modifier = modifier.size(size).pressScale(interaction, pressed = 0.96f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Symbol(glyph, contentDescription = contentDescription, size = (size.value * 0.375f).dp)
        }
    }
}

/** One destination in [AppNavBar]. */
data class NavDestination(val id: String, val label: String, val glyph: Char)

/**
 * The 82 dp bottom navigation.
 *
 * Hand-built rather than an M3 [androidx.compose.material3.NavigationBar] for one reason: the selected
 * indicator **animates its width** from 52 to 76 dp, and the stock indicator is a fixed size with no hook
 * to drive it. Everything else follows the component's contract — a `selectableGroup` of `Tab`-role
 * items, labels always visible, the filled glyph variant for the selected state.
 */
@Composable
fun AppNavBar(
    destinations: List<NavDestination>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.height(82.dp).padding(horizontal = 8.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { d ->
                val selected = d.id == selectedId
                val pillWidth by animateDpAsState(
                    targetValue = if (selected) 76.dp else 52.dp,
                    animationSpec = CaMotion.defaultSpatial(),
                    label = "navPill",
                )
                Column(
                    Modifier.weight(1f)
                        .selectable(
                            selected = selected,
                            onClick = { onSelect(d.id) },
                            role = Role.Tab,
                        )
                        .padding(top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier.width(pillWidth).height(34.dp)
                            .clip(CircleShape)
                            .background(if (selected) c.primaryContainer else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Symbol(
                            glyph = d.glyph,
                            contentDescription = null,
                            size = 24.dp,
                            filled = selected,
                            tint = if (selected) c.onPrimaryContainer else c.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = d.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (selected) c.onSurface else c.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A tonal icon tile: the square/circle/diagonal element that fronts a card or list row. */
@Composable
fun TonalTile(
    glyph: Char,
    pair: TonalPair,
    shape: Shape,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    contentDescription: String? = null,
) = Box(
    modifier.size(size).clip(shape).background(pair.container),
    contentAlignment = Alignment.Center,
) {
    Symbol(glyph, contentDescription, size = (size.value * 0.52f).dp, tint = pair.onContainer)
}

/** A three-up figure row: a bold number over a quiet label. Used by the detail hero and publisher page. */
@Composable
fun StatFigure(
    value: String,
    label: String,
    onContainer: Color,
    modifier: Modifier = Modifier,
    trailingGlyph: Char? = null,
) = Column(modifier) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = onContainer,
        )
        if (trailingGlyph != null) {
            Symbol(trailingGlyph, contentDescription = null, size = 15.dp, tint = onContainer, filled = true)
        }
    }
    SupportingOnContainer(label, onContainer, Modifier.padding(top = 1.dp))
}

/** Pulls the rotation helper into this file's namespace so call sites need one import, not two. */
@Composable
internal fun rotatedPair(index: Int): TonalPair = tonalPair(index)

/**
 * A dashed rounded-rect outline.
 *
 * `Modifier.border` can only draw a solid stroke, and the empty state's border is dashed by design — the
 * broken line is what says "a slot waiting to be filled" rather than "an empty card". Drawn as an
 * [androidx.compose.ui.graphics.Outline] so the corner radius is honoured and the dashes run around it.
 */
fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp = 2.dp,
    dash: Dp = 8.dp,
    gap: Dp = 6.dp,
): Modifier = drawBehind {
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
        width = strokeWidth.toPx(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
            floatArrayOf(dash.toPx(), gap.toPx()),
        ),
    )
    val inset = strokeWidth.toPx() / 2
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx()),
        style = stroke,
    )
}
