package dev.ide.ui.editor.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ide.ui.theme.Ide

/*
 * Draws a laid-out canvas. The look is the hybrid the block view settled on: the theme's category colors and
 * rounded corners, at Sketchware's denser size, with its depth cues (a hard drop shadow under each block and
 * a light bevel along the top and left edges).
 */

/** The colors a block canvas is painted with, resolved from the theme. */
class BlockInk(
    val control: Color, val data: Color, val call: Color, val ret: Color, val comment: Color,
    val method: Color, val op: Color, val text: Color, val socket: Color, val socketText: Color, val hole: Color,
    val selection: Color, val silhouette: Color, val drop: Color, val compose: Color = call,
) {
    fun of(cat: BlockCat): Color = when (cat) {
        BlockCat.Control -> control
        BlockCat.Data -> data
        BlockCat.Call -> call
        BlockCat.Return -> ret
        BlockCat.Comment, BlockCat.Opaque -> comment
        BlockCat.Method -> method
        BlockCat.Op -> op
        BlockCat.Compose -> compose
    }

    /** A literal's text color in a white socket, by the kind its shape encodes. */
    fun literal(shape: ValueShape): Color = when (shape) {
        ValueShape.Text -> LITERAL_STRING
        ValueShape.Number -> LITERAL_NUMBER
        ValueShape.Boolean -> LITERAL_KEYWORD
        else -> socketText
    }

    private companion object {
        // The socket is white in both themes, so literals use fixed dark-on-white tones.
        val LITERAL_STRING = Color(0xFF067D17)
        val LITERAL_NUMBER = Color(0xFF1750EB)
        val LITERAL_KEYWORD = Color(0xFF9B2393)
    }
}

@Composable
@ReadOnlyComposable
fun rememberBlockInk(selection: Color, onSurface: Color): BlockInk = with(Ide.colors.block) {
    BlockInk(
        control = control, data = data, call = call, ret = ret, comment = comment, method = method, op = op,
        text = text, socket = socket, socketText = socketText, hole = hole,
        selection = selection, silhouette = onSurface.copy(alpha = 0.22f), drop = selection, compose = compose,
    )
}

/**
 * Measures block text with [measurer], one style per [TextRole], caching every result. With a [language],
 * code in a value socket ([TextRole.Literal]) is syntax-highlighted in [socketSyntax] (the sockets are light).
 */
class ComposeTextMeasure(
    private val measurer: TextMeasurer, base: TextStyle, onSocket: TextStyle,
    private val language: dev.ide.ui.editor.CodeLanguage? = null,
    private val socketSyntax: dev.ide.ui.theme.SyntaxColors? = null,
) : TextMeasure {
    private val styles: Map<TextRole, TextStyle> = mapOf(
        TextRole.Keyword to base.copy(fontWeight = FontWeight.Bold),
        TextRole.Name to base.copy(fontWeight = FontWeight.SemiBold),
        TextRole.Qualifier to base,
        TextRole.Chrome to base,
        TextRole.Literal to onSocket,
        TextRole.Hint to base.copy(fontStyle = FontStyle.Italic, fontSize = (base.fontSize.value - 1.5f).sp),
        TextRole.Header to base.copy(fontWeight = FontWeight.SemiBold),
        TextRole.HeaderDim to base.copy(fontSize = (base.fontSize.value - 1.5f).sp),
    )
    private val cache = HashMap<Pair<String, TextRole>, MText>()

    override fun measure(text: String, role: TextRole): MText = cache.getOrPut(text to role) {
        if (role == TextRole.Literal && language != null && socketSyntax != null) {
            val r = measurer.measure(dev.ide.ui.editor.highlight(text, language, socketSyntax), styles.getValue(role), maxLines = 1, softWrap = false)
            return@getOrPut MText(text, role, r.size.width.toFloat(), r.size.height.toFloat(), r, colored = true)
        }
        val r = measurer.measure(text, styles.getValue(role), maxLines = 1, softWrap = false)
        MText(text, role, r.size.width.toFloat(), r.size.height.toFloat(), r)
    }
}

/** Paint state shared by one frame's drawing calls. */
class PaintState(
    val ink: BlockInk,
    val g: BlockGeometry,
    val selected: Pair<DocRef, String>? = null,
)

// ---------------------------------------------------------------------------
// Entry points.
// ---------------------------------------------------------------------------

fun DrawScope.drawLayout(layout: CanvasLayout, ps: PaintState) {
    for (stack in layout.stacks) {
        var y = stack.y
        for (b in stack.blocks) {
            drawBlock(b, stack.x, y, stack.doc, ps, 0)
            b.hatBody?.let { drawBody(it, stack.x + it.x, y + it.y, stack.doc, ps) }
            y += b.h + (b.hatBody?.h ?: 0f)
        }
        stack.body?.let { drawBody(it, stack.x, stack.y, stack.doc, ps) }
    }
}

fun DrawScope.drawBody(body: LBody, x: Float, y: Float, doc: DocRef, ps: PaintState) {
    // Bottom-up, so each block's bump lies over the block below it.
    for (i in body.blocks.indices.reversed()) drawBlock(body.blocks[i], x, y + body.offsets[i], doc, ps, 0)
}

/** A dragged run, drawn as a unit (slightly lifted). */
fun DrawScope.drawFloating(body: LBody?, value: LBlock?, at: Offset, ps: PaintState) {
    body?.let { drawBody(it, at.x, at.y, DocRef.File, ps) }
    value?.let { drawBlock(it, at.x, at.y, DocRef.File, ps, 0) }
}

/** The drop preview: the dragged top block's outline, filled translucent, where it will land. */
fun DrawScope.drawSilhouette(block: LBlock, at: Offset, ps: PaintState) {
    val path = outline(block, ps.g) ?: return
    translate(at.x, at.y) {
        drawPath(path, ps.ink.silhouette)
        drawPath(path, ps.ink.drop.copy(alpha = 0.55f), style = Stroke(width = 1.5f * ps.g.density))
    }
}

/** A socket a dragged value would go into: a bright outline round it. */
fun DrawScope.drawSocketHighlight(rect: androidx.compose.ui.geometry.Rect, shape: ValueShape, ps: PaintState) {
    val path = valuePath(shape, rect.width + 4 * ps.g.density, rect.height + 4 * ps.g.density, ps.g)
    translate(rect.left - 2 * ps.g.density, rect.top - 2 * ps.g.density) {
        drawPath(path, ps.ink.drop, style = Stroke(width = 2.5f * ps.g.density))
    }
}

// ---------------------------------------------------------------------------
// Blocks.
// ---------------------------------------------------------------------------

private fun DrawScope.drawBlock(b: LBlock, x: Float, y: Float, doc: DocRef, ps: PaintState, depth: Int) {
    val g = ps.g
    val fill = when (b.kind) {
        LKind.Literal -> ps.ink.socket
        LKind.Chip -> deepen(ps.ink.of(b.cat), 2)
        LKind.Reporter -> deepen(ps.ink.of(b.cat), depth)
        else -> ps.ink.of(b.cat)
    }
    val path = outline(b, g)
    translate(x, y) {
        if (path != null) {
            val raised = depth == 0 && b.kind != LKind.Literal
            if (raised) translate(0f, 1.3f * g.density) { drawPath(path, Color.Black.copy(alpha = 0.26f)) }
            drawPath(path, fill)
            if (raised) bevel(path, b, g)
            if (b.kind == LKind.Literal && b.shape == ValueShape.Type) drawPath(path, ps.ink.socketText.copy(alpha = 0.3f), style = Stroke(width = g.density))
            if (ps.selected != null && b.id != null && ps.selected.first == doc && ps.selected.second == b.id) {
                drawPath(path, ps.ink.selection, style = Stroke(width = 2.2f * g.density))
            }
        }
    }
    for (item in b.items) drawItem(item, x, y, doc, ps, depth, b)
    for (s in b.sections) if (s is LMouth) drawBody(s.body, x + s.body.x, y + s.body.y, doc, ps)
}

/** A light edge along the block's top and left, inside its outline: the Sketchware bevel. */
private fun DrawScope.bevel(path: Path, b: LBlock, g: BlockGeometry) {
    val edge = 1.4f * g.density
    val light = Color.White.copy(alpha = 0.24f)
    clipRect(0f, 0f, b.w, edge * 1.6f) { drawPath(path, light, style = Stroke(width = edge * 2)) }
    clipRect(0f, 0f, edge * 1.6f, b.h) { drawPath(path, light.copy(alpha = 0.16f), style = Stroke(width = edge * 2)) }
}

private fun DrawScope.drawItem(item: LItem, bx: Float, by: Float, doc: DocRef, ps: PaintState, depth: Int, owner: LBlock) {
    val x = bx + item.x
    val y = by + item.y
    when (item) {
        is LText -> drawMText(item.text, x, y, if (item.text.colored && item.tint == TextTint.OnSocket) Color.Unspecified else tintColor(item.tint, item.shape, ps))
        is LToken -> drawMText(item.text, x, y, tintColor(item.tint, ValueShape.Unknown, ps))
        is LDivider -> drawRect(ps.ink.text.copy(alpha = 0.25f), Offset(x, y), Size(item.w, item.h))
        is LArgHole -> {
            val path = valuePath(item.shape, item.w, item.h, ps.g)
            translate(x, y) {
                drawPath(path, ps.ink.hole.copy(alpha = ps.ink.hole.alpha * if (item.optional) 0.6f else 1f))
                drawPath(
                    path, ps.ink.text.copy(alpha = 0.35f),
                    style = Stroke(width = ps.g.density, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f * ps.g.density, 2.5f * ps.g.density))),
                )
            }
            val pad = ps.g.socketPadH + if (item.shape == ValueShape.Boolean) ps.g.hexPoint else 0f
            drawMText(item.hint, x + pad, y + (item.h - item.hint.height) / 2, ps.ink.text.copy(alpha = 0.6f))
        }
        is LArgChip -> {
            val r = androidx.compose.ui.geometry.CornerRadius(item.h / 2)
            val quiet = item.kind == ArgChipKind.Add
            drawRoundRect(ps.ink.text.copy(alpha = if (quiet) 0.08f else 0.12f), Offset(x, y), Size(item.w, item.h), r)
            drawMText(item.text, x + (item.w - item.text.width) / 2, y + (item.h - item.text.height) / 2, ps.ink.text.copy(alpha = if (quiet) 0.55f else 0.8f))
        }
        is LGlyph -> drawGlyph(item.glyph, x, y, item.w, if (item.onSocket) ps.ink.socketText else ps.ink.text.copy(alpha = 0.85f))
        is LSwatch -> {
            val c = Color(item.argb.toInt())
            drawCircle(Color.White.copy(alpha = 0.9f), item.w / 2, Offset(x + item.w / 2, y + item.h / 2))
            drawCircle(c, item.w / 2 - 1.2f * ps.g.density, Offset(x + item.w / 2, y + item.h / 2))
        }
        is LCaret -> {
            val p = Path().apply {
                if (item.open) { moveTo(x, y + item.h * 0.25f); lineTo(x + item.w, y + item.h * 0.25f); lineTo(x + item.w / 2, y + item.h * 0.8f) }
                else { moveTo(x + item.w * 0.25f, y); lineTo(x + item.w * 0.25f, y + item.h); lineTo(x + item.w * 0.8f, y + item.h / 2) }
                close()
            }
            drawPath(p, ps.ink.text.copy(alpha = 0.8f))
        }
        is LSocket -> {
            val child = item.child
            if (child == null) {
                val path = valuePath(item.shape, item.w, item.h, ps.g)
                translate(x, y) {
                    drawPath(path, ps.ink.hole)
                    if (item.shape == ValueShape.Type) drawPath(path, ps.ink.socket.copy(alpha = 0.5f), style = Stroke(width = ps.g.density))
                }
                item.hint?.let { h ->
                    val pad = ps.g.socketPadH + if (item.shape == ValueShape.Boolean) ps.g.hexPoint else 0f
                    drawMText(h, x + pad, y + (item.h - h.height) / 2, ps.ink.text.copy(alpha = 0.6f))
                }
            } else {
                // Depth counts pills: a statement's own values start at 0, a pill's step one deeper, and a
                // see-through grouping passes its depth through.
                val next = when (owner.kind) {
                    LKind.Statement, LKind.CBlock, LKind.Hat -> 0
                    LKind.Group -> depth
                    else -> depth + 1
                }
                drawBlock(child, x, y, doc, ps, next)
            }
        }
    }
}

private fun tintColor(t: TextTint, shape: ValueShape, ps: PaintState): Color = when (t) {
    TextTint.OnBlock, TextTint.Header -> ps.ink.text
    TextTint.OnBlockDim -> ps.ink.text.copy(alpha = 0.7f)
    TextTint.HeaderDim -> ps.ink.text.copy(alpha = 0.78f)
    TextTint.OnSocket -> ps.ink.literal(shape)
    TextTint.OnSocketDim -> ps.ink.literal(shape).copy(alpha = 0.6f)
    TextTint.Hint -> ps.ink.text.copy(alpha = 0.6f)
}

private fun DrawScope.drawMText(m: MText, x: Float, y: Float, color: Color) {
    val layout = m.handle as? TextLayoutResult ?: return
    drawText(layout, color = color, topLeft = Offset(x, y))
}

/** Darken a nested reporter's fill per [depth] so layers read without stacking shadows. */
private fun deepen(c: Color, depth: Int): Color {
    if (depth <= 0) return c
    val f = 1f - 0.07f * depth.coerceAtMost(3)
    return Color(c.red * f, c.green * f, c.blue * f, c.alpha)
}

// ---------------------------------------------------------------------------
// Geometry -> paths. Statement blocks interlock: a notch cut into the top edge, a bump hanging below the
// bottom edge, both [BlockGeometry.notchInset] from the left.
// ---------------------------------------------------------------------------

/** The outline of [b] at the origin, or null for a see-through grouping. */
fun outline(b: LBlock, g: BlockGeometry): Path? = when (b.kind) {
    LKind.Statement -> stackPath(b.w, b.h, g, notch = true, bump = !b.terminal)
    LKind.Hat -> hatPath(b.w, b.h, g, bump = b.hatBody != null)
    LKind.CBlock -> cPath(b, g)
    LKind.Reporter, LKind.Literal, LKind.Chip -> valuePath(b.shape, b.w, b.h, g)
    LKind.Group -> null
}

private fun Path.notchTop(x0: Float, y: Float, g: BlockGeometry) {
    lineTo(x0, y)
    lineTo(x0 + g.notchSlope, y + g.notchDepth)
    lineTo(x0 + g.notchWidth - g.notchSlope, y + g.notchDepth)
    lineTo(x0 + g.notchWidth, y)
}

/** A bump hanging below a bottom edge at [y], traced right-to-left. */
private fun Path.bumpBottom(x0: Float, y: Float, g: BlockGeometry) {
    lineTo(x0 + g.notchWidth, y)
    lineTo(x0 + g.notchWidth - g.notchSlope, y + g.notchDepth)
    lineTo(x0 + g.notchSlope, y + g.notchDepth)
    lineTo(x0, y)
}

fun stackPath(w: Float, h: Float, g: BlockGeometry, notch: Boolean, bump: Boolean): Path = Path().apply {
    val r = g.corner
    moveTo(0f, r)
    quadraticTo(0f, 0f, r, 0f)
    if (notch) notchTop(g.notchInset, 0f, g)
    lineTo(w - r, 0f)
    quadraticTo(w, 0f, w, r)
    lineTo(w, h - r)
    quadraticTo(w, h, w - r, h)
    if (bump) bumpBottom(g.notchInset, h, g)
    lineTo(r, h)
    quadraticTo(0f, h, 0f, h - r)
    close()
}

private fun hatPath(w: Float, h: Float, g: BlockGeometry, bump: Boolean): Path = Path().apply {
    val big = g.hatCorner
    val r = g.corner
    moveTo(0f, big)
    quadraticTo(0f, 0f, big, 0f)
    lineTo(w - big, 0f)
    quadraticTo(w, 0f, w, big)
    lineTo(w, h - r)
    quadraticTo(w, h, w - r, h)
    if (bump) bumpBottom(g.notchInset, h, g)
    lineTo(r, h)
    quadraticTo(0f, h, 0f, h - r)
    close()
}

/**
 * A C-block as one continuous outline: each row spans the full bar width; each mouth leaves only the left
 * arm, with a bump hanging from the row above into the mouth (where the first wrapped block's notch seats).
 */
private fun cPath(b: LBlock, g: BlockGeometry): Path = Path().apply {
    val r = g.corner
    val w = b.w
    val arm = g.armW
    val inset = arm + g.notchInset
    moveTo(0f, r)
    quadraticTo(0f, 0f, r, 0f)
    if (!b.asValue) notchTop(g.notchInset, 0f, g)
    lineTo(w - r, 0f)
    quadraticTo(w, 0f, w, r)
    val sections = b.sections
    var i = 0
    while (i < sections.size) {
        val s = sections[i]
        if (s is LMouth) {
            val top = s.y
            val bottom = s.y + s.h
            // Right edge of the bar above, then in along the mouth's ceiling with the inner bump.
            lineTo(w, top - r)
            quadraticTo(w, top, w - r, top)
            bumpBottom(inset, top, g)
            lineTo(arm + r, top)
            quadraticTo(arm, top, arm, top + r)
            lineTo(arm, bottom - r)
            quadraticTo(arm, bottom, arm + r, bottom)
            lineTo(w - r, bottom)
            quadraticTo(w, bottom, w, bottom + r)
        }
        i++
    }
    val h = b.h
    lineTo(w, h - r)
    quadraticTo(w, h, w - r, h)
    if (!b.asValue) bumpBottom(g.notchInset, h, g)
    lineTo(r, h)
    quadraticTo(0f, h, 0f, h - r)
    close()
}

/** A value's outline: hexagon (boolean), pill (number), sharp rect (string), tag (type), rounded (other). */
fun valuePath(shape: ValueShape, w: Float, h: Float, g: BlockGeometry): Path = Path().apply {
    when (shape) {
        ValueShape.Boolean -> {
            val p = g.hexPoint.coerceAtMost(w / 2f)
            moveTo(p, 0f); lineTo(w - p, 0f); lineTo(w, h / 2f); lineTo(w - p, h); lineTo(p, h); lineTo(0f, h / 2f); close()
        }
        ValueShape.Number -> addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(h / 2f)))
        ValueShape.Text, ValueShape.Type -> addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(3f * g.density)))
        ValueShape.Object, ValueShape.Unknown -> addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(minOf(h / 2.4f, 8f * g.density))))
    }
}

// ---------------------------------------------------------------------------
// Glyphs: one small line drawing per kind of modifier or recognized value, in a [size] square at (x, y).
// ---------------------------------------------------------------------------

fun DrawScope.drawGlyph(glyph: Glyph, x: Float, y: Float, size: Float, color: Color) {
    val s = size
    val sw = (s / 9f).coerceAtLeast(1f)
    val stroke = Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    fun p(fx: Float, fy: Float) = Offset(x + fx * s, y + fy * s)
    fun line(ax: Float, ay: Float, bx: Float, by: Float) = drawLine(color, p(ax, ay), p(bx, by), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    fun arrowH(yy: Float, x0: Float = 0.08f, x1: Float = 0.92f) {
        line(x0, yy, x1, yy); line(x0, yy, x0 + 0.18f, yy - 0.16f); line(x0, yy, x0 + 0.18f, yy + 0.16f)
        line(x1, yy, x1 - 0.18f, yy - 0.16f); line(x1, yy, x1 - 0.18f, yy + 0.16f)
    }
    fun arrowV(xx: Float, y0: Float = 0.08f, y1: Float = 0.92f) {
        line(xx, y0, xx, y1); line(xx, y0, xx - 0.16f, y0 + 0.18f); line(xx, y0, xx + 0.16f, y0 + 0.18f)
        line(xx, y1, xx - 0.16f, y1 - 0.18f); line(xx, y1, xx + 0.16f, y1 - 0.18f)
    }
    fun box(fx: Float, fy: Float, fw: Float, fh: Float, r: Float = 0.12f, fill: Boolean = false) {
        val rr = androidx.compose.ui.geometry.CornerRadius(r * s)
        if (fill) drawRoundRect(color, p(fx, fy), Size(fw * s, fh * s), rr)
        else drawRoundRect(color, p(fx, fy), Size(fw * s, fh * s), rr, style = stroke)
    }
    when (glyph) {
        Glyph.Modifier -> { box(0.1f, 0.1f, 0.8f, 0.8f, 0.2f); line(0.3f, 0.38f, 0.7f, 0.38f); line(0.3f, 0.62f, 0.6f, 0.62f) }
        Glyph.Width -> { line(0.08f, 0.2f, 0.08f, 0.8f); line(0.92f, 0.2f, 0.92f, 0.8f); arrowH(0.5f, 0.2f, 0.8f) }
        Glyph.Height -> { line(0.2f, 0.08f, 0.8f, 0.08f); line(0.2f, 0.92f, 0.8f, 0.92f); arrowV(0.5f, 0.2f, 0.8f) }
        Glyph.Size -> { box(0.1f, 0.1f, 0.8f, 0.8f); line(0.3f, 0.7f, 0.7f, 0.3f); line(0.7f, 0.3f, 0.48f, 0.3f); line(0.7f, 0.3f, 0.7f, 0.52f) }
        Glyph.FillWidth -> { box(0.06f, 0.22f, 0.88f, 0.56f); arrowH(0.5f, 0.2f, 0.8f) }
        Glyph.FillHeight -> { box(0.22f, 0.06f, 0.56f, 0.88f); arrowV(0.5f, 0.2f, 0.8f) }
        Glyph.FillSize -> { box(0.06f, 0.06f, 0.88f, 0.88f); line(0.28f, 0.28f, 0.72f, 0.72f); line(0.72f, 0.28f, 0.28f, 0.72f) }
        Glyph.Padding -> { box(0.06f, 0.06f, 0.88f, 0.88f); box(0.32f, 0.32f, 0.36f, 0.36f, 0.06f, fill = true) }
        Glyph.Offset -> { box(0.06f, 0.06f, 0.5f, 0.5f); box(0.44f, 0.44f, 0.5f, 0.5f, fill = true) }
        Glyph.Background -> box(0.1f, 0.1f, 0.8f, 0.8f, 0.18f, fill = true)
        Glyph.Border -> { box(0.1f, 0.1f, 0.8f, 0.8f, 0.18f); box(0.24f, 0.24f, 0.52f, 0.52f, 0.1f) }
        Glyph.Clip -> {
            drawArc(color, 180f, 90f, false, p(0.1f, 0.1f), Size(0.8f * s, 0.8f * s), style = stroke)
            line(0.5f, 0.1f, 0.9f, 0.1f); line(0.9f, 0.1f, 0.9f, 0.9f); line(0.9f, 0.9f, 0.1f, 0.9f); line(0.1f, 0.9f, 0.1f, 0.5f)
        }
        Glyph.Shadow -> { box(0.2f, 0.2f, 0.7f, 0.7f, 0.14f, fill = true); box(0.06f, 0.06f, 0.7f, 0.7f, 0.14f) }
        Glyph.Alpha -> {
            drawCircle(color, 0.4f * s, p(0.5f, 0.5f), style = stroke)
            drawArc(color, 90f, 180f, true, p(0.1f, 0.1f), Size(0.8f * s, 0.8f * s))
        }
        Glyph.Click -> {
            drawCircle(color, 0.16f * s, p(0.36f, 0.36f))
            drawCircle(color, 0.32f * s, p(0.36f, 0.36f), style = stroke)
            line(0.55f, 0.55f, 0.9f, 0.9f)
        }
        Glyph.Scroll -> { box(0.2f, 0.06f, 0.6f, 0.88f, 0.2f); line(0.5f, 0.3f, 0.5f, 0.5f) }
        Glyph.Weight -> { line(0.08f, 0.5f, 0.92f, 0.5f); box(0.08f, 0.3f, 0.28f, 0.4f, fill = true); box(0.44f, 0.3f, 0.48f, 0.4f) }
        Glyph.Align -> { line(0.1f, 0.08f, 0.1f, 0.92f); box(0.26f, 0.22f, 0.5f, 0.2f, 0.05f, fill = true); box(0.26f, 0.58f, 0.34f, 0.2f, 0.05f, fill = true) }
        Glyph.Transform -> { box(0.15f, 0.15f, 0.7f, 0.7f); line(0.5f, 0.02f, 0.5f, 0.2f); line(0.5f, 0.8f, 0.5f, 0.98f) }
        Glyph.Generic -> { drawCircle(color, 0.12f * s, p(0.5f, 0.5f)) }
        Glyph.ShapeRounded -> box(0.1f, 0.18f, 0.8f, 0.64f, 0.26f)
        Glyph.ShapeCut -> {
            val path = Path().apply {
                moveTo(x + 0.3f * s, y + 0.18f * s); lineTo(x + 0.7f * s, y + 0.18f * s); lineTo(x + 0.9f * s, y + 0.38f * s)
                lineTo(x + 0.9f * s, y + 0.62f * s); lineTo(x + 0.7f * s, y + 0.82f * s); lineTo(x + 0.3f * s, y + 0.82f * s)
                lineTo(x + 0.1f * s, y + 0.62f * s); lineTo(x + 0.1f * s, y + 0.38f * s); close()
            }
            drawPath(path, color, style = stroke)
        }
        Glyph.ShapeCircle -> drawCircle(color, 0.38f * s, p(0.5f, 0.5f), style = stroke)
        Glyph.ShapeRect -> box(0.1f, 0.18f, 0.8f, 0.64f, 0.02f)
        Glyph.Arrange -> { box(0.08f, 0.12f, 0.84f, 0.2f, 0.05f, fill = true); box(0.08f, 0.4f, 0.84f, 0.2f, 0.05f, fill = true); box(0.08f, 0.68f, 0.84f, 0.2f, 0.05f, fill = true) }
        Glyph.Typography -> { line(0.15f, 0.15f, 0.85f, 0.15f); line(0.5f, 0.15f, 0.5f, 0.88f); line(0.35f, 0.88f, 0.65f, 0.88f) }
        Glyph.Icon -> { drawCircle(color, 0.36f * s, p(0.5f, 0.5f), style = stroke); line(0.5f, 0.32f, 0.5f, 0.68f); line(0.32f, 0.5f, 0.68f, 0.5f) }
    }
}
