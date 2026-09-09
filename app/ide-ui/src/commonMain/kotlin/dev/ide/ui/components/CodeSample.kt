package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.highlight
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide

/*
 * Lifted out of screens/LessonBlocks.kt: a syntax-highlighted, read-only code card is a presentational
 * widget, and the editor's lesson preview renders one too -- which had the editor importing a screen.
 */

/** A read-only, syntax-highlighted code sample on a soft editor-toned card; scrolls horizontally if wide. */
@Composable
fun CodeSample(code: String, language: String = "kotlin", modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Ca.radius.md)
    val highlighted = highlight(code, codeLanguageOf(language), Ide.colors.syntax)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ide.colors.editorBg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(highlighted, style = Ide.type.code)
    }
}

/** Map a lesson block's language string to the editor's [CodeLanguage] for highlighting. */
fun codeLanguageOf(language: String): CodeLanguage = when {
    language.startsWith("java") -> CodeLanguage.Java
    language.startsWith("kotlin") || language == "kt" -> CodeLanguage.Kotlin
    language == "xml" -> CodeLanguage.Xml
    else -> CodeLanguage.Plain
}
