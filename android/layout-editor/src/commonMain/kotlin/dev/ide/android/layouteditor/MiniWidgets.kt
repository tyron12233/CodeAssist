package dev.ide.android.layouteditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The colours a palette preview draws a widget in: the app's own theme roles where the host knows them, so a
 * Button in the palette is the app's button, else the IDE's.
 */
internal class MiniColors(
    val primary: Color,
    val onPrimary: Color,
    val container: Color,
    val onContainer: Color,
    val surface: Color,
    val onSurface: Color,
    val outline: Color,
) {
    /** A muted line, for text that is not the point of the preview. */
    val line: Color get() = onSurface.copy(alpha = 0.28f)

    companion object {
        /** From the app theme's `?attr/color*` values ([argb] by attribute name), filling gaps from the IDE's. */
        @Composable
        fun of(argb: Map<String, Int>): MiniColors {
            val m = MaterialTheme.colorScheme
            fun c(name: String, fallback: Color) = argb[name]?.let { Color(it) } ?: fallback
            return MiniColors(
                primary = c("colorPrimary", m.primary),
                onPrimary = c("colorOnPrimary", m.onPrimary),
                container = c("colorPrimaryContainer", m.primaryContainer),
                onContainer = c("colorOnPrimaryContainer", m.onPrimaryContainer),
                surface = c("colorSurface", m.surface),
                onSurface = c("colorOnSurface", m.onSurface),
                outline = c("colorOutline", m.outline),
            )
        }
    }
}

/**
 * A tiny drawing of the view [tag] makes, in [colors], on a patch of the app's surface: what a palette tile shows
 * instead of a glyph, so a Button, a FAB and a Chip do not look alike. A view it has no drawing for (a library's
 * or the project's own) shows its glyph.
 */
@Composable
internal fun MiniWidget(tag: String, colors: MiniColors, modifier: Modifier) {
    val kind = miniKind(tag.substringAfterLast('.'))
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(colors.surface), contentAlignment = Alignment.Center) {
        if (kind == null) {
            Symbol(glyphFor(tag), null, size = 22.dp, tint = colors.onSurface.copy(alpha = 0.7f))
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) { drawMini(kind, colors) }
        val label = when (kind) {
            Mini.Text -> "Aa"
            Mini.Button -> "Button"
            Mini.Chip -> "Chip"
            else -> null
        }
        if (label != null) {
            val style = when (kind) {
                Mini.Text -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Mini.Button -> TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = colors.onPrimary)
                else -> TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = colors.onSurface.copy(alpha = 0.8f))
            }
            Text(label, style = style, modifier = if (kind == Mini.Text) Modifier.align(Alignment.CenterStart).padding(start = 10.dp, bottom = 8.dp) else Modifier)
        }
    }
}

internal enum class Mini {
    Text, Button, Field, Switch, Check, Radio, Chip, List, Constraint, Linear, Frame, Grid, Fab, Image, ImageButton,
    Progress, Circular, Slider, Rating, Search, Divider, Card, Toolbar, Tabs, BottomNav, Scroll, Pager,
}

/** Which drawing a simple class name gets, or null for a glyph. */
internal fun miniKind(simple: String): Mini? = when (simple) {
    "TextView", "CheckedTextView" -> Mini.Text
    "Button", "MaterialButton", "ToggleButton" -> Mini.Button
    "EditText", "TextInputLayout", "TextInputEditText", "AutoCompleteTextView", "MultiAutoCompleteTextView" -> Mini.Field
    "Switch", "MaterialSwitch", "SwitchMaterial", "SwitchCompat" -> Mini.Switch
    "CheckBox", "MaterialCheckBox" -> Mini.Check
    "RadioButton", "RadioGroup", "MaterialRadioButton" -> Mini.Radio
    "Chip", "ChipGroup" -> Mini.Chip
    "RecyclerView", "ListView", "NavigationView" -> Mini.List
    "ConstraintLayout", "RelativeLayout", "CoordinatorLayout" -> Mini.Constraint
    "LinearLayout", "TableRow" -> Mini.Linear
    "FrameLayout", "FragmentContainerView", "View" -> Mini.Frame
    "GridLayout", "TableLayout", "CalendarView" -> Mini.Grid
    "FloatingActionButton", "ExtendedFloatingActionButton" -> Mini.Fab
    "ImageView", "ShapeableImageView", "VideoView", "WebView" -> Mini.Image
    "ImageButton" -> Mini.ImageButton
    "ProgressBar", "LinearProgressIndicator" -> Mini.Progress
    "CircularProgressIndicator" -> Mini.Circular
    "SeekBar", "Slider", "RangeSlider" -> Mini.Slider
    "RatingBar" -> Mini.Rating
    "SearchView", "SearchBar" -> Mini.Search
    "MaterialDivider", "Space" -> Mini.Divider
    "MaterialCardView", "CardView" -> Mini.Card
    "MaterialToolbar", "Toolbar", "AppBarLayout" -> Mini.Toolbar
    "TabLayout" -> Mini.Tabs
    "BottomNavigationView", "NavigationBarView" -> Mini.BottomNav
    "ScrollView", "NestedScrollView", "HorizontalScrollView" -> Mini.Scroll
    "ViewPager2", "ViewPager" -> Mini.Pager
    else -> null
}

private fun DrawScope.drawMini(kind: Mini, c: MiniColors) {
    val w = size.width
    val h = size.height
    val d = density
    val r2 = CornerRadius(2f * d)
    fun pill(x: Float, y: Float, pw: Float, ph: Float, color: Color, stroke: Boolean = false) =
        if (stroke) drawRoundRect(color, Offset(x, y), Size(pw, ph), CornerRadius(ph / 2f), style = Stroke(1.2f * d))
        else drawRoundRect(color, Offset(x, y), Size(pw, ph), CornerRadius(ph / 2f))
    fun bar(x: Float, y: Float, bw: Float, color: Color = c.line) = drawRoundRect(color, Offset(x, y), Size(bw, 3f * d), CornerRadius(1.5f * d))
    val cx = w / 2f
    val cy = h / 2f
    when (kind) {
        Mini.Text -> bar(10f * d, cy + 9f * d, w * 0.5f)
        Mini.Button -> pill(cx - 26f * d, cy - 9f * d, 52f * d, 18f * d, c.primary)
        Mini.Field -> {
            drawRoundRect(c.outline, Offset(8f * d, cy - 9f * d), Size(w - 16f * d, 18f * d), CornerRadius(3f * d), style = Stroke(1.2f * d))
            bar(14f * d, cy - 1.5f * d, w * 0.35f)
        }
        Mini.Switch -> {
            pill(cx - 14f * d, cy - 8f * d, 28f * d, 16f * d, c.primary)
            drawCircle(c.onPrimary, 5.5f * d, Offset(cx + 6f * d, cy))
        }
        Mini.Check -> {
            drawRoundRect(c.primary, Offset(cx - 20f * d, cy - 7f * d), Size(14f * d, 14f * d), r2)
            val p = Path().apply { moveTo(cx - 17f * d, cy); lineTo(cx - 14f * d, cy + 3f * d); lineTo(cx - 9f * d, cy - 3f * d) }
            drawPath(p, c.onPrimary, style = Stroke(1.8f * d))
            bar(cx, cy - 1.5f * d, 18f * d)
        }
        Mini.Radio -> for ((i, y) in listOf(cy - 8f * d, cy + 8f * d).withIndex()) {
            drawCircle(if (i == 0) c.primary else c.outline, 6f * d, Offset(cx - 14f * d, y), style = Stroke(1.5f * d))
            if (i == 0) drawCircle(c.primary, 3f * d, Offset(cx - 14f * d, y))
            bar(cx - 4f * d, y - 1.5f * d, 20f * d)
        }
        Mini.Chip -> {
            pill(cx - 20f * d, cy - 8f * d, 40f * d, 16f * d, c.container)
            pill(cx - 20f * d, cy - 8f * d, 40f * d, 16f * d, c.outline, stroke = true)
        }
        Mini.List -> for (i in 0 until 3) {
            val y = cy - 12f * d + i * 9f * d
            drawCircle(c.container, 3f * d, Offset(12f * d, y + 1.5f * d))
            bar(19f * d, y, w - 30f * d)
        }
        Mini.Constraint -> {
            val a = Offset(14f * d, cy - 8f * d)
            val b = Offset(w - 30f * d, cy + 2f * d)
            drawLine(c.primary, a + Offset(12f * d, 4f * d), b + Offset(0f, 4f * d), 1.2f * d, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * d, 2f * d)))
            drawRoundRect(c.container, a, Size(12f * d, 8f * d), r2)
            drawRoundRect(c.primary, b, Size(16f * d, 8f * d), r2)
        }
        Mini.Linear -> for (i in 0 until 3) drawRoundRect(if (i == 0) c.primary else c.container, Offset(10f * d, cy - 12f * d + i * 9f * d), Size(w - 20f * d, 6f * d), r2)
        Mini.Frame -> {
            drawRoundRect(c.outline, Offset(10f * d, cy - 12f * d), Size(w - 20f * d, 24f * d), r2, style = Stroke(1.2f * d, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * d, 2f * d))))
            drawRoundRect(c.container, Offset(cx - 9f * d, cy - 6f * d), Size(18f * d, 12f * d), r2)
        }
        Mini.Grid -> for (row in 0 until 2) for (col in 0 until 3) {
            drawRoundRect(if (row == 0 && col == 0) c.primary else c.container, Offset(cx - 19f * d + col * 13f * d, cy - 10f * d + row * 11f * d), Size(11f * d, 9f * d), r2)
        }
        Mini.Fab -> {
            drawRoundRect(c.container, Offset(cx - 11f * d, cy - 11f * d), Size(22f * d, 22f * d), CornerRadius(6f * d))
            drawLine(c.onContainer, Offset(cx - 5f * d, cy), Offset(cx + 5f * d, cy), 1.8f * d)
            drawLine(c.onContainer, Offset(cx, cy - 5f * d), Offset(cx, cy + 5f * d), 1.8f * d)
        }
        Mini.Image, Mini.ImageButton -> {
            val box = if (kind == Mini.ImageButton) 24f * d else 34f * d
            val tl = Offset(cx - box / 2f, cy - (box * 0.7f) / 2f)
            val bh = box * 0.7f
            drawRoundRect(c.container, tl, Size(box, bh), CornerRadius(3f * d))
            val m = Path().apply {
                moveTo(tl.x + box * 0.15f, tl.y + bh * 0.85f); lineTo(tl.x + box * 0.42f, tl.y + bh * 0.45f)
                lineTo(tl.x + box * 0.6f, tl.y + bh * 0.68f); lineTo(tl.x + box * 0.72f, tl.y + bh * 0.55f)
                lineTo(tl.x + box * 0.88f, tl.y + bh * 0.85f); close()
            }
            drawPath(m, c.onContainer.copy(alpha = 0.6f))
            drawCircle(c.onContainer.copy(alpha = 0.6f), box * 0.07f, Offset(tl.x + box * 0.72f, tl.y + bh * 0.28f))
        }
        Mini.Progress -> {
            drawRoundRect(c.container, Offset(10f * d, cy - 2f * d), Size(w - 20f * d, 4f * d), CornerRadius(2f * d))
            drawRoundRect(c.primary, Offset(10f * d, cy - 2f * d), Size((w - 20f * d) * 0.55f, 4f * d), CornerRadius(2f * d))
        }
        Mini.Circular -> drawArc(c.primary, -90f, 250f, false, Offset(cx - 10f * d, cy - 10f * d), Size(20f * d, 20f * d), style = Stroke(3f * d))
        Mini.Slider -> {
            drawRoundRect(c.container, Offset(10f * d, cy - 2f * d), Size(w - 20f * d, 4f * d), CornerRadius(2f * d))
            drawRoundRect(c.primary, Offset(10f * d, cy - 2f * d), Size((w - 20f * d) * 0.6f, 4f * d), CornerRadius(2f * d))
            drawCircle(c.primary, 6f * d, Offset(10f * d + (w - 20f * d) * 0.6f, cy))
        }
        Mini.Rating -> for (i in 0 until 4) {
            val sx = cx - 21f * d + i * 14f * d
            val star = Path().apply {
                for (k in 0 until 10) {
                    val rr = if (k % 2 == 0) 5.5f * d else 2.4f * d
                    val a = Math.toRadians((k * 36 - 90).toDouble())
                    val p = Offset(sx + (rr * kotlin.math.cos(a)).toFloat(), cy + (rr * kotlin.math.sin(a)).toFloat())
                    if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(star, if (i < 3) c.primary else c.line)
        }
        Mini.Search -> {
            pill(8f * d, cy - 9f * d, w - 16f * d, 18f * d, c.container)
            drawCircle(c.onContainer, 3.5f * d, Offset(17f * d, cy - 1f * d), style = Stroke(1.4f * d))
            drawLine(c.onContainer, Offset(19.5f * d, cy + 1.5f * d), Offset(22f * d, cy + 4f * d), 1.4f * d)
            bar(27f * d, cy - 1.5f * d, w * 0.3f, c.onContainer.copy(alpha = 0.4f))
        }
        Mini.Divider -> {
            bar(10f * d, cy - 9f * d, w * 0.45f)
            drawLine(c.outline, Offset(8f * d, cy), Offset(w - 8f * d, cy), 1.2f * d)
            bar(10f * d, cy + 6f * d, w * 0.6f)
        }
        Mini.Card -> {
            drawRoundRect(c.onSurface.copy(alpha = 0.10f), Offset(9f * d, cy - 11f * d), Size(w - 16f * d, 24f * d), CornerRadius(5f * d))
            drawRoundRect(c.container, Offset(8f * d, cy - 12f * d), Size(w - 16f * d, 23f * d), CornerRadius(5f * d))
            bar(13f * d, cy - 6f * d, w * 0.4f, c.onContainer.copy(alpha = 0.5f))
            bar(13f * d, cy + 1f * d, w * 0.55f, c.onContainer.copy(alpha = 0.3f))
        }
        Mini.Toolbar -> {
            drawRect(c.primary, Offset(0f, 0f), Size(w, 16f * d))
            bar(10f * d, 6.5f * d, w * 0.35f, c.onPrimary.copy(alpha = 0.85f))
            bar(10f * d, cy + 6f * d, w * 0.5f)
        }
        Mini.Tabs -> {
            for (i in 0 until 3) bar(8f * d + i * (w - 16f * d) / 3f + 4f * d, cy - 4f * d, (w - 16f * d) / 3f - 8f * d, if (i == 0) c.primary else c.line)
            drawRect(c.primary, Offset(8f * d, cy + 4f * d), Size((w - 16f * d) / 3f, 2.5f * d))
        }
        Mini.BottomNav -> {
            drawRect(c.container, Offset(0f, h - 16f * d), Size(w, 16f * d))
            for (i in 0 until 3) drawCircle(if (i == 0) c.primary else c.onContainer.copy(alpha = 0.4f), 3f * d, Offset(w * (i + 1) / 4f, h - 8f * d))
        }
        Mini.Scroll -> {
            for (i in 0 until 3) bar(10f * d, cy - 10f * d + i * 8f * d, w - 30f * d)
            drawRoundRect(c.outline, Offset(w - 12f * d, cy - 12f * d), Size(3f * d, 12f * d), CornerRadius(1.5f * d))
        }
        Mini.Pager -> {
            drawRoundRect(c.container, Offset(cx - 16f * d, cy - 13f * d), Size(32f * d, 18f * d), CornerRadius(3f * d))
            for (i in 0 until 3) drawCircle(if (i == 0) c.primary else c.line, 2f * d, Offset(cx - 6f * d + i * 6f * d, cy + 10f * d))
        }
    }
}
