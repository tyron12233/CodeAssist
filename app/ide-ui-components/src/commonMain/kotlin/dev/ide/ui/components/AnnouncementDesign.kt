package dev.ide.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiAnnouncement
import dev.ide.ui.backend.UiAnnouncementAction
import dev.ide.ui.backend.UiAnnouncementAlign
import dev.ide.ui.backend.UiAnnouncementArrange
import dev.ide.ui.backend.UiAnnouncementAt
import dev.ide.ui.backend.UiAnnouncementBlock
import dev.ide.ui.backend.UiAnnouncementButton
import dev.ide.ui.backend.UiAnnouncementButtonStyle
import dev.ide.ui.backend.UiAnnouncementColor
import dev.ide.ui.backend.UiAnnouncementFit
import dev.ide.ui.backend.UiAnnouncementFontWeight
import dev.ide.ui.backend.UiAnnouncementHideOn
import dev.ide.ui.backend.UiAnnouncementPaint
import dev.ide.ui.backend.UiAnnouncementProps
import dev.ide.ui.backend.UiAnnouncementStyle
import dev.ide.ui.backend.UiAnnouncementVAlign
import dev.ide.ui.backend.UiAnnouncementWidth
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Where Home places an announcement card: how far a full-bleed card reaches past the slot's side margin, and
 * whether Home is in its wide layout (null: decided from the window width, with Home's own 600 dp breakpoint).
 */
@Immutable
data class AnnouncementHost(val bleed: Dp = 0.dp, val wide: Boolean? = null)

val LocalAnnouncementHost = staticCompositionLocalOf { AnnouncementHost() }

/** Home's phone/wide breakpoint, which `hideOn` follows. */
val AnnouncementWideBreakpoint: Dp = 600.dp

/** The text colours `auto` chooses between: the app's on-surface roles in its light and dark themes. */
private val OnLight = Color(0xFF1B1C15)
private val OnDark = Color(0xFFE3E3D6)

/** Everything a designed block needs to resolve its colours, its images and its taps. */
@Immutable
internal class DesignContext(
    /** The app's own scheme, which theme tokens read. */
    val app: ColorScheme,
    /** The scheme with the announcement's accent applied, which `accent`, `onAccent` and button defaults read. */
    val accent: ColorScheme,
    val dark: Boolean,
    val rtl: Boolean,
    val wide: Boolean,
    /** True when the card's content colour is `auto` or unset, so a block with its own panel recomputes it. */
    val autoContent: Boolean,
    val loadImage: suspend (String) -> ImageBitmap?,
    val run: (UiAnnouncementAction) -> Unit,
) {
    fun color(c: UiAnnouncementColor, behind: Color): Color = when (c) {
        is UiAnnouncementColor.Fixed -> Color((if (dark) c.dark else c.light).toInt())
        is UiAnnouncementColor.Token -> token(c.name)
        UiAnnouncementColor.Auto -> readableOn(behind)
    }

    private fun token(name: String): Color = when (name) {
        "primary" -> app.primary
        "onPrimary" -> app.onPrimary
        "primaryContainer" -> app.primaryContainer
        "onPrimaryContainer" -> app.onPrimaryContainer
        "secondary" -> app.secondary
        "secondaryContainer" -> app.secondaryContainer
        "tertiary" -> app.tertiary
        "tertiaryContainer" -> app.tertiaryContainer
        "surface" -> app.surface
        "surfaceVariant" -> app.surfaceVariant
        "onSurface" -> app.onSurface
        "onSurfaceVariant" -> app.onSurfaceVariant
        "outline" -> app.outline
        "error" -> app.error
        "accent" -> accent.primary
        "onAccent" -> accent.onPrimary
        else -> app.onSurface
    }

    /**
     * The one colour a paint reads as, for choosing `auto` text over it: a solid's colour, a gradient's
     * average, an image's scrim colour (or dark, when there is none).
     */
    fun estimate(p: UiAnnouncementPaint, behind: Color): Color = when (p) {
        is UiAnnouncementPaint.Solid -> color(p.color, behind).over(behind)
        is UiAnnouncementPaint.Linear -> average(p.colors.map { color(it, behind) }).over(behind)
        is UiAnnouncementPaint.Radial -> average(p.colors.map { color(it, behind) }).over(behind)
        is UiAnnouncementPaint.Image -> if (p.scrim > 0f) (p.scrimColor?.let { color(it, behind) } ?: Color.Black).copy(alpha = 1f) else Color.Black
    }
}

/** The text colour, light or dark, that contrasts more with [bg]. */
internal fun readableOn(bg: Color): Color = if (contrast(OnLight, bg) >= contrast(OnDark, bg)) OnLight else OnDark

/** The WCAG contrast ratio of two opaque colours. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

private fun average(colors: List<Color>): Color {
    var r = 0f; var g = 0f; var b = 0f; var a = 0f
    for (c in colors) { r += c.red; g += c.green; b += c.blue; a += c.alpha }
    val n = colors.size.coerceAtLeast(1)
    return Color(r / n, g / n, b / n, a / n)
}

/** [this] composited over an opaque [behind], so a translucent colour is judged by what it looks like. */
private fun Color.over(behind: Color): Color {
    if (alpha >= 1f) return this
    val a = alpha
    return Color(red * a + behind.red * (1 - a), green * a + behind.green * (1 - a), blue * a + behind.blue * (1 - a), 1f)
}

/**
 * A linear gradient at [angle] degrees, 0 running start to end and 90 top to bottom, spanning the box the way
 * CSS does: the gradient line passes through the centre and is long enough to reach both far corners. Under
 * RTL the horizontal component flips, so "start to end" stays start to end.
 */
private class AngleGradient(private val colors: List<Color>, angle: Float, rtl: Boolean) : ShaderBrush() {
    private val dx = cos(angle * PI / 180.0).toFloat().let { if (rtl) -it else it }
    private val dy = sin(angle * PI / 180.0).toFloat()

    override fun createShader(size: Size): Shader {
        val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        return LinearGradientShader(Offset(cx - dx * half, cy - dy * half), Offset(cx + dx * half, cy + dy * half), colors)
    }
}

/** A radial gradient centred at fractions of the size (x from the start edge), [radius] times the larger side. */
private class FractionRadial(
    private val colors: List<Color>,
    private val cx: Float,
    private val cy: Float,
    private val radius: Float,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader = RadialGradientShader(
        center = Offset(size.width * cx, size.height * cy),
        radius = (radius * max(size.width, size.height)).coerceAtLeast(1f),
        colors = colors,
    )
}

/**
 * [content] on [paint], clipped to [shape]. A solid or a gradient is a background; an image is drawn under the
 * content at the box's size (it never sizes the box), with its scrim over it.
 */
@Composable
internal fun PaintedBox(
    paint: UiAnnouncementPaint?,
    shape: Shape,
    ctx: DesignContext,
    behind: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    when (paint) {
        null -> Box(modifier.clip(shape), content = content)
        is UiAnnouncementPaint.Solid -> {
            val c = ctx.color(paint.color, behind)
            Box(modifier.clip(shape).background(c, shape), content = content)
        }
        is UiAnnouncementPaint.Linear -> {
            val colors = paint.colors.map { ctx.color(it, behind) }
            val brush = remember(colors, paint.angle, ctx.rtl) { AngleGradient(colors, paint.angle, ctx.rtl) }
            Box(modifier.clip(shape).background(brush, shape), content = content)
        }
        is UiAnnouncementPaint.Radial -> {
            val colors = paint.colors.map { ctx.color(it, behind) }
            val cx = if (ctx.rtl) 1f - paint.centerX else paint.centerX
            val brush = remember(colors, cx, paint.centerY, paint.radius) { FractionRadial(colors, cx, paint.centerY, paint.radius) }
            Box(modifier.clip(shape).background(brush, shape), content = content)
        }
        is UiAnnouncementPaint.Image -> {
            val bitmap by produceState<ImageBitmap?>(null, paint.src) { value = runCatching { ctx.loadImage(paint.src) }.getOrNull() }
            val scrim = (paint.scrimColor?.let { ctx.color(it, behind) } ?: Color.Black).copy(alpha = paint.scrim)
            Box(modifier.clip(shape).background(behind)) {
                bitmap?.let {
                    Image(
                        it, contentDescription = null, modifier = Modifier.matchParentSize(),
                        contentScale = if (paint.fit == UiAnnouncementFit.CONTAIN) ContentScale.Fit else ContentScale.Crop,
                    )
                }
                if (paint.scrim > 0f) Box(Modifier.matchParentSize().background(scrim))
                content()
            }
        }
    }
}

/**
 * A version 2 announcement: the card's own design (background, content colour, corner, padding, border, minimum
 * height, full bleed, elevation), with what it leaves out taken from the style preset, and the blocks laid out
 * by their own properties.
 */
@Composable
internal fun DesignedAnnouncement(
    a: UiAnnouncement,
    run: (UiAnnouncementAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
    loadImage: suspend (String) -> ImageBitmap?,
    app: ColorScheme,
) {
    val accent = MaterialTheme.colorScheme
    val design = a.design
    val host = LocalAnnouncementHost.current
    val density = LocalDensity.current
    val windowWide = with(density) { LocalWindowInfo.current.containerSize.width.toDp() } >= AnnouncementWideBreakpoint
    val ctx = DesignContext(
        app = app,
        accent = accent,
        dark = dev.ide.ui.theme.Ide.colors.isDark,
        rtl = LocalLayoutDirection.current == LayoutDirection.Rtl,
        wide = host.wide ?: windowWide,
        autoContent = design?.content == null || design.content == UiAnnouncementColor.Auto,
        loadImage = loadImage,
        run = run,
    )

    // The preset's defaults; anything the design states wins.
    val presetBackground: UiAnnouncementPaint = when (a.style) {
        UiAnnouncementStyle.BANNER -> UiAnnouncementPaint.Solid(UiAnnouncementColor.Fixed(argb(accent.primaryContainer), argb(accent.primaryContainer)))
        else -> UiAnnouncementPaint.Solid(UiAnnouncementColor.Fixed(argb(app.surfaceContainerHigh), argb(app.surfaceContainerHigh)))
    }
    val background = design?.background ?: presetBackground
    val fullBleed = design?.fullBleed == true
    val corner = if (fullBleed) 0f else design?.corner ?: if (a.style == UiAnnouncementStyle.BANNER) 20f else 28f
    val padding = design?.padding ?: if (a.style == UiAnnouncementStyle.BANNER) 12f else 16f
    val shape = if (corner == 0f) RectangleShape else RoundedCornerShape(corner.dp)
    val page = app.background
    val surfaceBehind = ctx.estimate(background, page)
    val content = design?.content?.let { ctx.color(it, surfaceBehind) } ?: readableOn(surfaceBehind)
    // The preset outline only frames the preset's own surface; a designed background stands on its own.
    val border = when {
        fullBleed -> null
        design?.border != null -> design.border!!.let { it.width.dp to ctx.color(it.color, surfaceBehind) }
        design?.background == null && a.style != UiAnnouncementStyle.BANNER -> 1.dp to app.outlineVariant
        else -> null
    }

    var outer = modifier.fillMaxWidth()
    if (fullBleed && host.bleed > 0.dp) outer = outer.bleed(host.bleed)
    design?.elevation?.takeIf { it > 0f }?.let { outer = outer.shadow(it.dp, shape, clip = false) }
    design?.minHeight?.takeIf { it > 0f }?.let { outer = outer.heightIn(min = it.dp) }
    border?.let { (w, c) -> if (w > 0.dp) outer = outer.border(w, c, shape) }
    a.action?.let { action -> outer = outer.clickable { run(action) } }

    // Hero: a leading image block (with no padding of its own) runs edge to edge across the top.
    val cover = if (a.style == UiAnnouncementStyle.HERO) {
        (a.blocks.firstOrNull() as? UiAnnouncementBlock.Image)?.takeIf { it.props.pad == null && visible(it.props, ctx) }
    } else null
    val blocks = if (cover != null) a.blocks.drop(1) else a.blocks

    CompositionLocalProvider(LocalContentColor provides content) {
        PaintedBox(background, shape, ctx, page, outer) {
            Column {
                if (cover != null) {
                    Box {
                        ImageBlock(cover, ctx, Modifier.fillMaxWidth(), clipCorner = 0f)
                        if (a.dismissible) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.45f)),
                            ) { CloseButton(onDismiss, Color.White) }
                        }
                    }
                }
                // The close button: in line at the end of a banner's row; elsewhere over the top-end corner, with
                // the first block kept clear of it.
                val close = a.dismissible && cover == null
                val inlineClose = close && a.style == UiAnnouncementStyle.BANNER
                Row(
                    Modifier.fillMaxWidth().padding(
                        start = padding.dp, top = padding.dp, bottom = padding.dp,
                        end = if (inlineClose) (padding - 8f).coerceAtLeast(4f).dp else padding.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = if (a.style == UiAnnouncementStyle.BANNER) Alignment.CenterVertically else Alignment.Top,
                ) {
                    a.icon?.takeIf { a.style != UiAnnouncementStyle.HERO }?.let { name ->
                        val tile = if (a.style == UiAnnouncementStyle.BANNER) 0.dp else 40.dp
                        if (tile > 0.dp) {
                            Box(
                                Modifier.size(tile).background(accent.primaryContainer, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) { AnnouncementIcon(name, accent.onPrimaryContainer, 20.dp) }
                        } else {
                            AnnouncementIcon(name, content, 20.dp)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DEFAULT_GAP.dp)) {
                        if (a.style == UiAnnouncementStyle.HERO) a.icon?.let { AnnouncementIcon(it, accent.primary, 26.dp) }
                        val first = blocks.indexOfFirst { visible(it.props, ctx) }
                        blocks.forEachIndexed { i, b ->
                            ColumnChild(b, ctx, surfaceBehind, a.style, endInset = if (close && !inlineClose && i == first && a.icon == null) CLOSE_CLEARANCE.dp else 0.dp)
                        }
                    }
                    if (inlineClose) CloseButton(onDismiss, content)
                    if (close && !inlineClose && a.icon != null) Spacer(Modifier.width((CLOSE_CLEARANCE - 12f).dp))
                }
            }
            if (a.dismissible && cover == null && a.style != UiAnnouncementStyle.BANNER) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = (padding - 6f).coerceAtLeast(4f).dp, end = (padding - 8f).coerceAtLeast(4f).dp)) {
                    CloseButton(onDismiss, content)
                }
            }
        }
    }
}

/** Widens the layout by [by] on both sides, so a full-bleed card reaches the screen edges past its slot's margin. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    if (!constraints.hasBoundedWidth) return@layout measurable.measure(constraints).let { p -> layout(p.width, p.height) { p.place(0, 0) } }
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

private fun argb(c: Color): Long = c.toArgb().toLong() and 0xFFFFFFFFL

private fun visible(p: UiAnnouncementProps, ctx: DesignContext): Boolean = when (p.hideOn) {
    UiAnnouncementHideOn.PHONE -> ctx.wide
    UiAnnouncementHideOn.WIDE -> !ctx.wide
    null -> true
}

/** The width a block takes when it does not say: fill for text, images, dividers and containers; wrap otherwise. */
private fun fillsByDefault(b: UiAnnouncementBlock): Boolean = when (b) {
    is UiAnnouncementBlock.Buttons, is UiAnnouncementBlock.Chips, is UiAnnouncementBlock.Icon, is UiAnnouncementBlock.Badge -> false
    else -> true
}

private fun UiAnnouncementAlign?.horizontal(): Alignment.Horizontal = when (this) {
    UiAnnouncementAlign.CENTER -> Alignment.CenterHorizontally
    UiAnnouncementAlign.END -> Alignment.End
    else -> Alignment.Start
}

/** A block inside a column (the card, or a `column` block): width, alignment, then its panel and content. */
@Composable
private fun ColumnScope.ColumnChild(
    b: UiAnnouncementBlock,
    ctx: DesignContext,
    behind: Color,
    style: UiAnnouncementStyle,
    endInset: Dp = 0.dp,
) {
    if (!visible(b.props, ctx)) return
    val fill = when (b.props.width) {
        UiAnnouncementWidth.FILL -> true
        UiAnnouncementWidth.WRAP -> false
        null -> fillsByDefault(b)
    }
    val base = if (endInset > 0.dp) Modifier.padding(end = endInset) else Modifier
    val m = if (fill) base.fillMaxWidth() else Modifier.align(b.props.align.horizontal()).then(base)
    Panel(b, ctx, behind, m, style, inRow = false)
}

/** A block inside a row: its weight shares the remaining width; without one it takes its natural width. */
@Composable
private fun RowScope.RowChild(b: UiAnnouncementBlock, ctx: DesignContext, behind: Color, style: UiAnnouncementStyle) {
    if (!visible(b.props, ctx)) return
    val m = b.props.weight?.let { Modifier.weight(it) } ?: Modifier
    Panel(b, ctx, behind, m, style, inRow = true)
}

/** A block inside a stack, placed by its `at`. */
@Composable
private fun BoxScope.StackChild(b: UiAnnouncementBlock, ctx: DesignContext, behind: Color, style: UiAnnouncementStyle) {
    if (!visible(b.props, ctx)) return
    val at = when (b.props.at) {
        UiAnnouncementAt.TOP -> Alignment.TopCenter
        UiAnnouncementAt.TOP_END -> Alignment.TopEnd
        UiAnnouncementAt.START -> Alignment.CenterStart
        UiAnnouncementAt.CENTER -> Alignment.Center
        UiAnnouncementAt.END -> Alignment.CenterEnd
        UiAnnouncementAt.BOTTOM_START -> Alignment.BottomStart
        UiAnnouncementAt.BOTTOM -> Alignment.BottomCenter
        UiAnnouncementAt.BOTTOM_END -> Alignment.BottomEnd
        UiAnnouncementAt.TOP_START, null -> Alignment.TopStart
    }
    val fill = b.props.width == UiAnnouncementWidth.FILL
    Panel(b, ctx, behind, Modifier.align(at).then(if (fill) Modifier.fillMaxWidth() else Modifier), style, inRow = false)
}

/**
 * The block's panel (its `background` clipped to its `corner`, with `pad` inside it) around its content. A badge
 * takes a pill in the accent when it states no background; chips draw their background on each chip instead.
 */
@Composable
private fun Panel(b: UiAnnouncementBlock, ctx: DesignContext, behind: Color, modifier: Modifier, style: UiAnnouncementStyle, inRow: Boolean) {
    val p = b.props
    val badge = b is UiAnnouncementBlock.Badge
    val background = when {
        b is UiAnnouncementBlock.Chips -> null
        badge -> p.background ?: UiAnnouncementPaint.Solid(UiAnnouncementColor.Token("accent"))
        else -> p.background
    }
    val corner = p.corner ?: if (badge) 999f else if (b is UiAnnouncementBlock.Image) 14f else 0f
    val pad = p.pad ?: if (badge) listOf(2f, 8f, 2f, 8f) else null
    val padding = pad?.let { PaddingValues(start = it[3].dp, top = it[0].dp, end = it[1].dp, bottom = it[2].dp) }
    if (background == null) {
        Box(if (padding != null) modifier.padding(padding) else modifier) { BlockContent(b, ctx, behind, style, inRow) }
        return
    }
    val shape = RoundedCornerShape(corner.coerceAtMost(999f).dp)
    val panelBehind = ctx.estimate(background, behind)
    val inner = if (ctx.autoContent) readableOn(panelBehind) else LocalContentColor.current
    PaintedBox(background, shape, ctx, behind, modifier) {
        CompositionLocalProvider(LocalContentColor provides if (badge && p.background == null) ctx.accent.onPrimary else inner) {
            Box(if (padding != null) Modifier.padding(padding) else Modifier) { BlockContent(b, ctx, panelBehind, style, inRow) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockContent(b: UiAnnouncementBlock, ctx: DesignContext, behind: Color, style: UiAnnouncementStyle, inRow: Boolean) {
    val content = LocalContentColor.current
    when (b) {
        is UiAnnouncementBlock.Title -> DesignedText(
            b.text, b.style, ctx, behind,
            defaultSize = when (style) {
                UiAnnouncementStyle.HERO -> 22f
                UiAnnouncementStyle.BANNER -> 15f
                UiAnnouncementStyle.CARD -> 18f
            },
            defaultWeight = if (style == UiAnnouncementStyle.BANNER) FontWeight.SemiBold else FontWeight.Bold,
        )
        is UiAnnouncementBlock.Text -> DesignedText(b.text, b.style, ctx, behind, defaultSize = 15f, defaultWeight = FontWeight.Normal)
        is UiAnnouncementBlock.Image -> ImageBlock(b, ctx, Modifier.fillMaxWidth(), clipCorner = b.props.corner ?: 14f)
        is UiAnnouncementBlock.Icon -> AnnouncementIcon(b.name, b.color?.let { ctx.color(it, behind) } ?: content, (b.size ?: 24f).dp)
        is UiAnnouncementBlock.Badge -> Text(
            b.text, color = b.color?.let { ctx.color(it, behind) } ?: content,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1,
        )
        is UiAnnouncementBlock.Chips -> {
            val fill = b.props.background
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                b.items.forEach { label ->
                    val chipBehind = fill?.let { ctx.estimate(it, behind) } ?: ctx.accent.secondaryContainer
                    val ink = b.color?.let { ctx.color(it, chipBehind) } ?: if (fill == null) ctx.accent.onSecondaryContainer else readableOn(chipBehind)
                    val shape = RoundedCornerShape((b.props.corner ?: 999f).dp)
                    PaintedBox(fill ?: UiAnnouncementPaint.Solid(UiAnnouncementColor.Fixed(argb(chipBehind), argb(chipBehind))), shape, ctx, behind) {
                        Text(
                            label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = ink,
                            style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        )
                    }
                }
            }
        }
        is UiAnnouncementBlock.Buttons -> ButtonRow(b, ctx, behind)
        is UiAnnouncementBlock.Divider -> {
            val c = b.color?.let { ctx.color(it, behind) } ?: content.copy(alpha = 0.22f)
            HorizontalDivider(Modifier.padding(vertical = 2.dp), thickness = (b.thickness ?: 1f).dp, color = c)
        }
        is UiAnnouncementBlock.Spacer -> {
            val s = (b.size ?: 8f).dp
            Spacer(if (inRow) Modifier.width(s) else Modifier.height(s))
        }
        is UiAnnouncementBlock.Row -> {
            val gap = (b.gap ?: DEFAULT_GAP).dp
            val arrangement = when (b.arrange) {
                UiAnnouncementArrange.CENTER -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
                UiAnnouncementArrange.END -> Arrangement.spacedBy(gap, Alignment.End)
                UiAnnouncementArrange.SPACE_BETWEEN -> Arrangement.SpaceBetween
                else -> Arrangement.spacedBy(gap)
            }
            val v = when (b.valign) {
                UiAnnouncementVAlign.TOP -> Alignment.Top
                UiAnnouncementVAlign.BOTTOM -> Alignment.Bottom
                else -> Alignment.CenterVertically
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement, verticalAlignment = v) {
                b.children.forEach { RowChild(it, ctx, behind, style) }
            }
        }
        is UiAnnouncementBlock.Column -> Column(verticalArrangement = Arrangement.spacedBy((b.gap ?: DEFAULT_GAP).dp)) {
            b.children.forEach { ColumnChild(it, ctx, behind, style) }
        }
        is UiAnnouncementBlock.Stack -> Box {
            b.children.forEach { StackChild(it, ctx, behind, style) }
        }
    }
}

@Composable
private fun DesignedText(
    text: String,
    s: dev.ide.ui.backend.UiAnnouncementTextStyle,
    ctx: DesignContext,
    behind: Color,
    defaultSize: Float,
    defaultWeight: FontWeight,
) {
    val size = s.size ?: defaultSize
    Text(
        text,
        color = s.color?.let { ctx.color(it, behind) } ?: LocalContentColor.current,
        fontSize = size.sp,
        lineHeight = (size * 1.3f).sp,
        fontWeight = when (s.weight) {
            UiAnnouncementFontWeight.REGULAR -> FontWeight.Normal
            UiAnnouncementFontWeight.MEDIUM -> FontWeight.Medium
            UiAnnouncementFontWeight.SEMIBOLD -> FontWeight.SemiBold
            UiAnnouncementFontWeight.BOLD -> FontWeight.Bold
            null -> defaultWeight
        },
        fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = when (s.textAlign) {
            UiAnnouncementAlign.CENTER -> TextAlign.Center
            UiAnnouncementAlign.END -> TextAlign.End
            else -> TextAlign.Start
        },
        maxLines = s.maxLines ?: Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ImageBlock(b: UiAnnouncementBlock.Image, ctx: DesignContext, modifier: Modifier, clipCorner: Float) {
    val bitmap by produceState<ImageBitmap?>(null, b.src) { value = runCatching { ctx.loadImage(b.src) }.getOrNull() }
    val shape = if (clipCorner <= 0f) RectangleShape else RoundedCornerShape(clipCorner.dp)
    BoxWithConstraints(modifier) {
        val height = b.height?.dp ?: (maxWidth / b.aspect).coerceAtMost(MAX_DESIGNED_IMAGE_HEIGHT)
        val frame = Modifier.fillMaxWidth().height(height).clip(shape)
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bmp, contentDescription = b.alt, modifier = frame,
                contentScale = if (b.fit == UiAnnouncementFit.CONTAIN) ContentScale.Fit else ContentScale.Crop,
            )
        } else {
            Box(frame.background(LocalContentColor.current.copy(alpha = 0.08f)))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(b: UiAnnouncementBlock.Buttons, ctx: DesignContext, behind: Color) {
    when (b.arrange) {
        UiAnnouncementArrange.STACKED -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            b.items.forEach { DesignedButton(it, ctx, behind, Modifier.fillMaxWidth()) }
        }
        UiAnnouncementArrange.SPACED -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            b.items.forEach { DesignedButton(it, ctx, behind, Modifier.weight(1f)) }
        }
        else -> {
            val h = when (b.arrange) {
                UiAnnouncementArrange.CENTER -> Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                UiAnnouncementArrange.END -> Arrangement.spacedBy(8.dp, Alignment.End)
                else -> Arrangement.spacedBy(8.dp)
            }
            val anyFull = b.items.any { it.fullWidth } || b.arrange == UiAnnouncementArrange.CENTER || b.arrange == UiAnnouncementArrange.END
            FlowRow(
                if (anyFull) Modifier.fillMaxWidth() else Modifier.wrapContentWidth(),
                horizontalArrangement = h,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                b.items.forEach { DesignedButton(it, ctx, behind, if (it.fullWidth) Modifier.fillMaxWidth() else Modifier) }
            }
        }
    }
}

@Composable
private fun DesignedButton(button: UiAnnouncementButton, ctx: DesignContext, behind: Color, modifier: Modifier) {
    val a = ctx.accent
    val container = button.color?.let { ctx.color(it, behind) } ?: when (button.style) {
        UiAnnouncementButtonStyle.PRIMARY -> a.primary
        UiAnnouncementButtonStyle.SECONDARY -> a.secondaryContainer
        UiAnnouncementButtonStyle.TEXT -> Color.Transparent
    }
    val labelBehind = if (container.alpha == 0f) behind else container.over(behind)
    val label = button.content?.let { ctx.color(it, labelBehind) } ?: when {
        button.color != null -> readableOn(labelBehind)
        button.style == UiAnnouncementButtonStyle.PRIMARY -> a.onPrimary
        button.style == UiAnnouncementButtonStyle.SECONDARY -> a.onSecondaryContainer
        else -> if (contrast(a.primary, behind) >= 3f) a.primary else readableOn(behind)
    }
    Button(
        onClick = { ctx.run(button.action) },
        modifier = modifier.heightIn(min = 40.dp),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = label),
        contentPadding = PaddingValues(horizontal = if (button.style == UiAnnouncementButtonStyle.TEXT) 12.dp else 18.dp),
    ) {
        button.icon?.let {
            AnnouncementIcon(it, label, 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(button.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** How much room the first block leaves at its end for the close button over the corner. */
private const val CLOSE_CLEARANCE = 36f

/** The space between blocks in the card and in a row or column that states no gap. */
private const val DEFAULT_GAP = 8f

private val MAX_DESIGNED_IMAGE_HEIGHT = 240.dp

/** A version 2 block inside a version 1 card, drawn on the card's own surface with the card's colours. */
@Composable
internal fun V1Fallback(b: UiAnnouncementBlock, run: (UiAnnouncementAction) -> Unit, loadImage: suspend (String) -> ImageBitmap?) {
    val scheme = MaterialTheme.colorScheme
    val ctx = DesignContext(
        app = scheme, accent = scheme, dark = dev.ide.ui.theme.Ide.colors.isDark,
        rtl = LocalLayoutDirection.current == LayoutDirection.Rtl,
        wide = LocalAnnouncementHost.current.wide ?: false,
        autoContent = false, loadImage = loadImage, run = run,
    )
    CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
        Box { Panel(b, ctx, scheme.surfaceContainerHigh, Modifier, UiAnnouncementStyle.CARD, inRow = false) }
    }
}
