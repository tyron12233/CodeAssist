package dev.ide.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.jetbrains_mono_bold
import dev.ide.ui.generated.resources.jetbrains_mono_bold_italic
import dev.ide.ui.generated.resources.jetbrains_mono_italic
import dev.ide.ui.generated.resources.jetbrains_mono_medium
import dev.ide.ui.generated.resources.jetbrains_mono_medium_italic
import dev.ide.ui.generated.resources.jetbrains_mono_regular
import dev.ide.ui.generated.resources.jetbrains_mono_semibold
import org.jetbrains.compose.resources.Font

/**
 * JetBrains Mono, bundled under `commonMain/composeResources/font/` (SIL OFL 1.1, see the sibling
 * `OFL.txt`). This is the IDE's default code face — the editor renders [CaTypography.code] with it, so
 * the regular/medium/semibold/bold weights and the italic variants cover the syntax styles the
 * highlighter emits (bold tokens, italic comments). Built once per composition and remembered.
 */
@Composable
fun rememberJetBrainsMono(): FontFamily {
    val regular = Font(Res.font.jetbrains_mono_regular, FontWeight.Normal, FontStyle.Normal)
    val italic = Font(Res.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic)
    val medium = Font(Res.font.jetbrains_mono_medium, FontWeight.Medium, FontStyle.Normal)
    val mediumItalic = Font(Res.font.jetbrains_mono_medium_italic, FontWeight.Medium, FontStyle.Italic)
    val semibold = Font(Res.font.jetbrains_mono_semibold, FontWeight.SemiBold, FontStyle.Normal)
    val bold = Font(Res.font.jetbrains_mono_bold, FontWeight.Bold, FontStyle.Normal)
    val boldItalic = Font(Res.font.jetbrains_mono_bold_italic, FontWeight.Bold, FontStyle.Italic)
    return remember(regular, italic, medium, mediumItalic, semibold, bold, boldItalic) {
        FontFamily(regular, italic, medium, mediumItalic, semibold, bold, boldItalic)
    }
}
