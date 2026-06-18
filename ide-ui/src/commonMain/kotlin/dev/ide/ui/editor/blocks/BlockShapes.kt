package dev.ide.ui.editor.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme

/**
 * The Sketchware/Blockly puzzle geometry and palette. Solid category-colored blocks interlock by a top
 * notch and a bottom bump; C-blocks wrap their body behind a left arm; values are white recessed sockets
 * or solid reporter pills. Colors come from the theme (`Ca.colors.block`) so they track light/dark.
 */
object BlockMetrics {
    val corner = 8.dp        // rounded block corners
    val hatCorner = 12.dp    // the big top radius of a method hat
    val connDepth = 4.dp     // notch depth / bump protrusion (also the stack overlap)
    val connInset = 14.dp    // distance from the left edge to the connector
    val connWidth = 20.dp    // connector mouth width
    val connSlope = 4.dp     // the trapezoid's slanted shoulders
    val arm = 12.dp          // the left bar of a C-block's mouth
    val footer = 12.dp       // the closing arm height of a C-block
    val socketRadius = 11.dp
    val stackGap = 0.dp      // vertical space between stacked blocks (the bump bridges it)
    val hexPoint = 8.dp      // a boolean hexagon's point depth (also the extra pad so text clears it)
}

/** A block category — drives the solid fill color and which shape a block gets. */
enum class BlockCat { Control, Data, Call, Return, Comment, Method, Op, Opaque }

/**
 * The Scratch shape language for value sockets and the value blocks filling them: hexagon = boolean,
 * pill = number, sharp rectangle = string, tag chip = type names, soft-rounded = object/unknown.
 * Derived from the `valueKind` the projection puts on slots/nodes via [valueShapeOf].
 */
enum class ValueShape { Boolean, Number, Text, Object, Type, Unknown }

/** Map a slot/node `valueKind` string ("boolean" | "number" | "string" | …) to its [ValueShape]. */
fun valueShapeOf(valueKind: String): ValueShape = when (valueKind) {
    "boolean" -> ValueShape.Boolean
    "number" -> ValueShape.Number
    "string" -> ValueShape.Text
    "type" -> ValueShape.Type
    "object" -> ValueShape.Object
    else -> ValueShape.Unknown
}

/** Extra horizontal content padding a [ValueShape] needs — a hexagon's text must clear its points. */
fun valueShapePadding(shape: ValueShape): Dp = if (shape == ValueShape.Boolean) BlockMetrics.hexPoint else 0.dp

/**
 * The clip/fill [Shape] for a [ValueShape]: Boolean is a flat-topped hexagon pointed left/right
 * ([BlockMetrics.hexPoint] deep), Number a full pill, Text a sharp rectangle, Type a tag chip
 * (the caller draws its 1dp outline), Object/Unknown the neutral [BlockMetrics.socketRadius] round.
 */
@Composable
fun rememberValueShape(shape: ValueShape): Shape {
    val d = LocalDensity.current
    return remember(shape, d) {
        when (shape) {
            ValueShape.Boolean -> with(d) {
                val point = BlockMetrics.hexPoint.toPx()
                GenericShape { size, _ ->
                    val p = point.coerceAtMost(size.width / 2f)
                    moveTo(p, 0f)
                    lineTo(size.width - p, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width - p, size.height)
                    lineTo(p, size.height)
                    lineTo(0f, size.height / 2f)
                    close()
                }
            }
            ValueShape.Number -> RoundedCornerShape(50)
            ValueShape.Text -> RoundedCornerShape(3.dp)
            ValueShape.Type -> RoundedCornerShape(4.dp)
            ValueShape.Object, ValueShape.Unknown -> RoundedCornerShape(BlockMetrics.socketRadius)
        }
    }
}

@Composable
@ReadOnlyComposable
fun blockColor(cat: BlockCat): Color = with(Ca.colors.block) {
    when (cat) {
        BlockCat.Control -> control
        BlockCat.Data -> data
        BlockCat.Call -> call
        BlockCat.Return -> ret
        BlockCat.Comment -> comment
        BlockCat.Method -> method
        BlockCat.Op -> op
        BlockCat.Opaque -> comment
    }
}

/**
 * A stackable statement block: a rounded rect with an optional top notch and bottom bump. [topRadius]
 * (px) is larger for a method hat. The bump of one block drops into the notch of the next when the stack
 * overlaps them by [BlockMetrics.connDepth]. [bumpInset] (px, ≥ 0) moves the bottom bump away from the
 * default [BlockMetrics.connInset] — used by C-blocks to poke the bump into the *mouth* (see
 * [rememberCBlockHeaderShape]) instead of the block's own left edge.
 */
@Composable
fun rememberBlockShape(
    notchTop: Boolean = true,
    bumpBottom: Boolean = true,
    topRadius: Float = -1f,
    bumpInset: Float = -1f,
): Shape {
    val d = LocalDensity.current
    return remember(notchTop, bumpBottom, topRadius, bumpInset, d) {
        with(d) {
            val corner = if (topRadius >= 0f) topRadius else BlockMetrics.corner.toPx()
            val bcorner = BlockMetrics.corner.toPx()
            val inset = BlockMetrics.connInset.toPx()
            val binset = if (bumpInset >= 0f) bumpInset else inset
            val nw = BlockMetrics.connWidth.toPx()
            val depth = BlockMetrics.connDepth.toPx()
            val slope = BlockMetrics.connSlope.toPx()
            GenericShape { size, _ ->
                val w = size.width
                val mb = if (bumpBottom) size.height - depth else size.height
                moveTo(0f, corner)
                quadraticTo(0f, 0f, corner, 0f)
                if (notchTop) {
                    lineTo(inset, 0f)
                    lineTo(inset + slope, depth)
                    lineTo(inset + nw - slope, depth)
                    lineTo(inset + nw, 0f)
                }
                lineTo(w - corner, 0f)
                quadraticTo(w, 0f, w, corner)
                lineTo(w, mb - bcorner)
                quadraticTo(w, mb, w - bcorner, mb)
                if (bumpBottom) {
                    lineTo(binset + nw, mb)
                    lineTo(binset + nw - slope, mb + depth)
                    lineTo(binset + slope, mb + depth)
                    lineTo(binset, mb)
                }
                lineTo(bcorner, mb)
                quadraticTo(0f, mb, 0f, mb - bcorner)
                close()
            }
        }
    }
}

/**
 * The whole outline of a C-block (if/for/while/try) as one continuous path: header bar, left arm, and
 * closing footer are a single piece (no stitched-together rectangles), so the corners flow into each
 * other and the mouth is carved cleanly out of the right side. Drawn against the block's measured size,
 * so the mouth height tracks the wrapped body automatically. Lay out the header content in the top
 * [headerHeightPx], the body indented past [armWidthPx] in the mouth, and leave [footerHeightPx] at the
 * bottom for the closing arm.
 *
 * Connectors match plain stack blocks ([rememberBlockShape]): a top notch ([notchTop]) clips it under the
 * previous block; a downward inner notch at [mouthInsetPx] pokes into the mouth so the first wrapped
 * block's top notch interlocks (the cue that blocks nest inside); and a footer bottom bump ([bumpBottom])
 * links the whole C-block to whatever stacks below it.
 */
@Composable
fun rememberCBlockShape(
    headerHeightPx: Float,
    footerHeightPx: Float,
    armWidthPx: Float,
    mouthInsetPx: Float,
    notchTop: Boolean = true,
    bumpBottom: Boolean = true,
): Shape {
    val d = LocalDensity.current
    return remember(headerHeightPx, footerHeightPx, armWidthPx, mouthInsetPx, notchTop, bumpBottom, d) {
        with(d) {
            val corner = BlockMetrics.corner.toPx()
            val inset = BlockMetrics.connInset.toPx()
            val nw = BlockMetrics.connWidth.toPx()
            val depth = BlockMetrics.connDepth.toPx()
            val slope = BlockMetrics.connSlope.toPx()
            val arm = armWidthPx.coerceAtLeast(corner)   // must clear the concave corner radius
            GenericShape { size, _ ->
                val w = size.width
                val h = size.height
                val ceil = (headerHeightPx - depth).coerceIn(corner, h)   // flat bottom of the header (mouth ceiling)
                val footTop = (h - footerHeightPx).coerceIn(ceil + corner, h)  // top of the footer arm
                val footMb = if (bumpBottom) h - depth else h            // footer bump baseline
                // Top-left corner + optional top notch.
                moveTo(0f, corner)
                quadraticTo(0f, 0f, corner, 0f)
                if (notchTop) {
                    lineTo(inset, 0f)
                    lineTo(inset + slope, depth)
                    lineTo(inset + nw - slope, depth)
                    lineTo(inset + nw, 0f)
                }
                // Top edge → top-right corner → down to the header's bottom-right.
                lineTo(w - corner, 0f)
                quadraticTo(w, 0f, w, corner)
                lineTo(w, ceil - corner)
                quadraticTo(w, ceil, w - corner, ceil)
                // Header bottom (mouth ceiling) running left, with the downward inner notch in the mouth.
                lineTo(mouthInsetPx + nw, ceil)
                lineTo(mouthInsetPx + nw - slope, ceil + depth)
                lineTo(mouthInsetPx + slope, ceil + depth)
                lineTo(mouthInsetPx, ceil)
                // Inner (concave) top corner of the mouth, then down the arm's right edge.
                lineTo(arm + corner, ceil)
                quadraticTo(arm, ceil, arm, ceil + corner)
                lineTo(arm, footTop - corner)
                // Inner (concave) bottom corner, then right along the footer top to the right edge.
                quadraticTo(arm, footTop, arm + corner, footTop)
                lineTo(w - corner, footTop)
                quadraticTo(w, footTop, w, footTop + corner)
                // Down the footer's right edge to its bottom, then the closing bottom bump.
                lineTo(w, footMb - corner)
                quadraticTo(w, footMb, w - corner, footMb)
                if (bumpBottom) {
                    lineTo(inset + nw, footMb)
                    lineTo(inset + nw - slope, footMb + depth)
                    lineTo(inset + slope, footMb + depth)
                    lineTo(inset, footMb)
                }
                lineTo(corner, footMb)
                quadraticTo(0f, footMb, 0f, footMb - corner)
                // Single continuous left edge all the way up (header + arm + footer share it).
                lineTo(0f, corner)
                close()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Preview — the raw puzzle geometry/palette, independent of the projection engine.
// ---------------------------------------------------------------------------

/** One labeled, category-colored block clipped to a [rememberBlockShape]. */
@Composable
private fun ShapeSwatch(label: String, cat: BlockCat, notchTop: Boolean, bumpBottom: Boolean, topRadius: Float = -1f, notchStartOffset: Float = 0f) {
    val color = blockColor(cat)
    val shape = rememberBlockShape(
        notchTop = notchTop,
        bumpBottom = bumpBottom,
        topRadius = topRadius,
    )
    Box(
        Modifier.widthIn(220.dp).height(if (bumpBottom) 42.dp else 36.dp)
            .clip(shape)
            .background(color, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = Ca.colors.block.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = Ca.type.codeFamily)
    }
}

/** A mini C-block drawn as one continuous [rememberCBlockShape] — header bar, arm, and footer in a single
 *  piece — with the label in the bar and one child tucked into the carved mouth, so the geometry is easy
 *  to eyeball. Header height is fixed here (40.dp); [CBlock] measures it from real content. */
@Composable
private fun CBlockSwatch(label: String) {
    val color = blockColor(BlockCat.Control)
    val d = LocalDensity.current
    val headerH = 40.dp
    val shape = rememberCBlockShape(
        headerHeightPx = with(d) { headerH.toPx() },
        footerHeightPx = with(d) { (BlockMetrics.footer + BlockMetrics.connDepth).toPx() },
        armWidthPx = with(d) { BlockMetrics.arm.toPx() },
        mouthInsetPx = with(d) { (BlockMetrics.arm + 0.dp + BlockMetrics.connInset).toPx() },
    )
    Column(Modifier.width(220.dp).background(color, shape)) {   // no clip: the child sits in the carved mouth
        Box(Modifier.height(headerH).padding(start = 16.dp, top = 8.dp)) {
            Text(label, color = Ca.colors.block.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = Ca.type.codeFamily)
        }
        // The child sits in the mouth, pulled up by connDepth so its top notch meets the header's inner notch.
        Box(Modifier.offset(y = -BlockMetrics.connDepth).padding(start = BlockMetrics.arm)) {
            ShapeSwatch("doStep()", BlockCat.Call, notchTop = true, bumpBottom = true,
                    notchStartOffset = -1f
                )
        }

        Spacer(modifier = Modifier.height(BlockMetrics.connDepth * 6))
    }
}

@Composable
private fun ShapesSample(dark: Boolean) {
    val d = LocalDensity.current
    val hatPx = with(d) { BlockMetrics.hatCorner.toPx() }
    CodeAssistTheme(dark = dark) {
        // An interlocking stack: hat on top (no notch), statements bump-into-notch, last block flat,
        // then a C-block whose header notch shows that other blocks nest inside its mouth.
        Column(
            modifier = Modifier.background(Ca.colors.editorBg).padding(16.dp)) {
            ShapeSwatch("void onCreate()", BlockCat.Method, notchTop = false, bumpBottom = true, topRadius = hatPx)
            ShapeSwatch("count = count + 1", BlockCat.Data, notchTop = true, bumpBottom = true)
            ShapeSwatch("render()", BlockCat.Call, notchTop = true, bumpBottom = true)
            CBlockSwatch("if (ready)")
            ShapeSwatch("return count", BlockCat.Return, notchTop = true, bumpBottom = false)
        }
    }
}

@Preview
@Composable
private fun PreviewBlockShapesDark() = ShapesSample(dark = true)

@Preview
@Composable
private fun PreviewBlockShapesLight() = ShapesSample(dark = false)
