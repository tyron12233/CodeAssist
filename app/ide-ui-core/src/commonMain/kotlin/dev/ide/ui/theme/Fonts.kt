package dev.ide.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.ibm_plex_sans_arabic_bold
import dev.ide.ui.generated.resources.ibm_plex_sans_arabic_light
import dev.ide.ui.generated.resources.ibm_plex_sans_arabic_medium
import dev.ide.ui.generated.resources.ibm_plex_sans_arabic_regular
import dev.ide.ui.generated.resources.ibm_plex_sans_arabic_semibold
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

/**
 * Languages whose script the platform's own UI face does not draw, so the UI supplies one.
 *
 * Roboto, San Francisco and the desktop sans faces carry no Arabic, so an Arabic UI is rendered by
 * whatever the platform happens to fall back to: Noto Naskh on Android, Geeza Pro or SF Arabic on iOS,
 * something else again per desktop. The result is a different face per device, none of them chosen, and
 * none of them matching the Latin text they sit beside.
 */
private const val ARABIC = "ar"

/**
 * IBM Plex Sans Arabic, bundled under `commonMain/composeResources/font/` (SIL OFL 1.1, see the sibling
 * `IBM_PLEX_OFL.txt`).
 *
 * The family covers Latin as well as Arabic, which is the reason for choosing it: an Arabic IDE screen
 * is full of Latin (file names, identifiers, versions, package names), and drawing the two scripts from
 * one family keeps their weight, x-height and colour matched instead of seaming at every switch.
 *
 * All five weights the type scale asks for are bundled. [expressiveTypography] leans on the light one
 * for display text, so a family missing it would silently round those titles up to regular and flatten
 * the weight contrast the scale is built on.
 */
@Composable
fun rememberIbmPlexSansArabic(): FontFamily {
    val light = Font(Res.font.ibm_plex_sans_arabic_light, FontWeight.Light, FontStyle.Normal)
    val regular = Font(Res.font.ibm_plex_sans_arabic_regular, FontWeight.Normal, FontStyle.Normal)
    val medium = Font(Res.font.ibm_plex_sans_arabic_medium, FontWeight.Medium, FontStyle.Normal)
    val semibold = Font(Res.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold, FontStyle.Normal)
    val bold = Font(Res.font.ibm_plex_sans_arabic_bold, FontWeight.Bold, FontStyle.Normal)
    return remember(light, regular, medium, semibold, bold) {
        FontFamily(light, regular, medium, semibold, bold)
    }
}

/**
 * The face the UI's own text is set in, for the language the UI is currently showing.
 *
 * Every other language keeps the platform's sans face, which is what the user's device already sets its
 * system UI in and what the rest of the type scale was drawn against. Only the scripts listed in
 * [ARABIC] get a bundled face, and only because the platform has none to offer.
 *
 * Code is unaffected: the editor is set in [rememberJetBrainsMono] whatever the UI language is.
 */
@Composable
fun rememberUiFont(): FontFamily =
    if (Locale.current.language == ARABIC) rememberIbmPlexSansArabic() else FontFamily.SansSerif
