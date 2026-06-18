package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.theme.Ca

private data class KindMeta(val ch: String, val color: Color)

@Composable
private fun metaFor(kind: UiCompletionKind): KindMeta = when (kind) {
    UiCompletionKind.Class -> KindMeta("C", Color(0xFFE6C178))
    UiCompletionKind.Record -> KindMeta("R", Color(0xFFE6C178))
    UiCompletionKind.Interface -> KindMeta("I", Color(0xFF57B6C2))
    UiCompletionKind.Enum -> KindMeta("E", Color(0xFFD9A066))
    UiCompletionKind.AnnotationType -> KindMeta("@", Color(0xFFE6C178))
    UiCompletionKind.Method -> KindMeta("M", Ca.colors.accent)
    UiCompletionKind.Constructor -> KindMeta("M", Ca.colors.accent)
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
            fontFamily = Ca.type.codeFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (if (m.ch.length > 1) size * 0.42 else size * 0.56).sp,
            textAlign = TextAlign.Center,
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
            fontFamily = Ca.type.codeFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = (if (text.length > 1) size * 0.42 else size * 0.55).sp,
        )
    }
}
