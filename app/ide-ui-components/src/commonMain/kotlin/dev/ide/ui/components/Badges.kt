package dev.ide.ui.components

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiCompletionKind
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.theme.resolveTint

/** Trims the line-height leading and vertically centers the single glyph, so a letter badge sits centered
 *  in its square instead of low with extra padding above it. */
private val CenteredGlyph = TextStyle(
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
)

private data class KindMeta(val ch: String, val color: Color)

@Composable
private fun metaFor(kind: UiCompletionKind): KindMeta = when (kind) {
    UiCompletionKind.Class -> KindMeta("C", Color(0xFFE6C178))
    UiCompletionKind.Record -> KindMeta("R", Color(0xFFE6C178))
    UiCompletionKind.Interface -> KindMeta("I", Color(0xFF57B6C2))
    UiCompletionKind.Enum -> KindMeta("E", Color(0xFFD9A066))
    UiCompletionKind.AnnotationType -> KindMeta("@", Color(0xFFE6C178))
    UiCompletionKind.Method -> KindMeta("M", MaterialTheme.colorScheme.primary)
    UiCompletionKind.Constructor -> KindMeta("M", MaterialTheme.colorScheme.primary)
    UiCompletionKind.Field -> KindMeta("F", Color(0xFF61AFEF))
    UiCompletionKind.EnumConstant -> KindMeta("#", Color(0xFFD9A066))
    UiCompletionKind.Variable -> KindMeta("v", Color(0xFF5CCFE6))
    UiCompletionKind.Parameter -> KindMeta("v", Color(0xFF5CCFE6))
    UiCompletionKind.TypeParameter -> KindMeta("T", Color(0xFFE6C178))
    UiCompletionKind.Package -> KindMeta("p", Color(0xFFA0A1AA))
    UiCompletionKind.Keyword -> KindMeta("K", Color(0xFFCD7EE0))
    UiCompletionKind.Snippet -> KindMeta("{}", Color(0xFF98C97A))
    UiCompletionKind.Word -> KindMeta("w", Color(0xFFA0A1AA))
}

/** Completion kind badge: a rounded-6 square tinted by kind with a code-font glyph (per icons.jsx). */
@Composable
fun KindBadge(kind: UiCompletionKind, size: Int = 20) {
    val m = metaFor(kind)
    Box(
        Modifier.size(size.dp).background(m.color.copy(alpha = 0.20f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            m.ch,
            color = m.color,
            fontFamily = Ide.type.codeFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (if (m.ch.length > 1) size * 0.42 else size * 0.56).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            style = CenteredGlyph,
        )
    }
}

/** Letter-in-rounded-square glyph in [color] — the file-type badge used by the navigator tree. */
@Composable
fun LetterBadge(text: String, color: Color, size: Int = 17) {
    Box(
        Modifier.size(size.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = color,
            fontFamily = Ide.type.codeFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (if (text.length > 1) size * 0.42 else size * 0.55).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            style = CenteredGlyph,
        )
    }
}

/**
 * Any [TreeIcon] at [size] dp, [open] picking a folder's open glyph: the one place a tree icon is drawn, so
 * every surface showing a node (the navigator, breadcrumbs, pickers) draws a new kind the same way.
 */
@Composable
fun TreeIconImage(icon: TreeIcon, size: Int = 17, open: Boolean = false) {
    when (icon) {
        is TreeIcon.Glyph -> Icon(icon.image, null, Modifier.size(size.dp), tint = resolveTint(icon.tint))
        is TreeIcon.Folder -> Icon(if (open) icon.open else icon.closed, null, Modifier.size(size.dp), tint = resolveTint(icon.tint))
        is TreeIcon.Badge -> LetterBadge(icon.text, icon.color, size)
        is TreeIcon.ModuleFolder -> ModuleFolderIcon(icon, size)
    }
}

/**
 * A folder with IntelliJ's module mark: a small square at the bottom end, punched out of the folder with a gap
 * around it (a real transparent cut, so it reads on any row background, selected or not). Modules always show
 * the closed folder: the open glyph's front flap crowds the mark at tree size.
 */
@Composable
private fun ModuleFolderIcon(icon: TreeIcon.ModuleFolder, size: Int) {
    val tint = resolveTint(icon.tint)
    val folder = rememberVectorPainter(icon.closed)
    Canvas(Modifier.size(size.dp).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val s = this.size.minDimension
        with(folder) { draw(this@Canvas.size, colorFilter = ColorFilter.tint(tint)) }
        val mark = s * 0.40f
        val gap = s * 0.08f
        val origin = Offset(s - mark, s - mark)
        val corner = CornerRadius(s * 0.07f)
        // The cut: clears the folder strokes under and around the mark.
        drawRoundRect(
            Color.Black, topLeft = origin - Offset(gap, gap), size = Size(mark + gap, mark + gap),
            cornerRadius = CornerRadius(s * 0.1f), blendMode = BlendMode.Clear,
        )
        if (icon.outlinedBadge) {
            val stroke = s * 0.09f
            drawRoundRect(
                icon.badgeColor, topLeft = origin + Offset(stroke / 2, stroke / 2),
                size = Size(mark - stroke, mark - stroke), cornerRadius = corner, style = Stroke(stroke),
            )
        } else {
            drawRoundRect(icon.badgeColor, topLeft = origin, size = Size(mark, mark), cornerRadius = corner)
        }
    }
}
