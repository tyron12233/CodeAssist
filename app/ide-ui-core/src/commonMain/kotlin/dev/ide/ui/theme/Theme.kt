package dev.ide.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.platform.dynamicColorSchemeOrNull
import dev.ide.ui.theme.colors.AttributeStyle
import dev.ide.ui.theme.colors.BuiltInColorSchemes
import dev.ide.ui.theme.colors.ColorAttributes
import dev.ide.ui.theme.colors.ColorKeys
import dev.ide.ui.theme.colors.EditorColorScheme
import dev.ide.ui.theme.colors.LocalEditorColors
import dev.ide.ui.theme.colors.ResolvedColorScheme
import dev.ide.ui.theme.colors.SchemeDefaults

/**
 * The CodeAssist design system, now built on **Material 3 Expressive**. [CodeAssistTheme] installs a real
 * [MaterialTheme] — an expressive tonal [ColorScheme] (Material You dynamic color on Android 12+, else a
 * fixed palette seeded from the chosen accent), the expressive type scale, and the rounder expressive shape
 * scale — so native M3 components render correctly. IDE-domain colors with no Material role (editor
 * surfaces, syntax, blocks, git, glass) are provided separately through [Ide].
 *
 * App UI (screens, components, shell) reads Material roles directly (`MaterialTheme.colorScheme` /
 * `MaterialTheme.typography`) plus [Ide] for the IDE-domain tokens. The [Ca] accessor is retained as the
 * **editor rendering subsystem's palette** (`CodeEditor` / the render state consume a whole
 * [CodeAssistColors] / [CaTypography]); its generic fields are M3-derived from the active [ColorScheme] via
 * [bridgedTo], so the editor tracks the expressive theme while its code-specific tones stay bespoke.
 */

/**
 * The preset accent palettes. [Lime] is the default and the one the app's identity is built on: unlike
 * the other three it brings its own warm neutral ramp rather than tinting the shared one.
 */
enum class CaAccent { Lime, Violet, Teal, Orange }

@Immutable
data class SyntaxColors(
    val default: Color,
    val keyword: Color,
    val storage: Color,
    val string: Color,
    val number: Color,
    val func: Color,
    val type: Color,
    val comment: Color,
    val property: Color,
    val variable: Color,
    val punctuation: Color,
    val constant: Color,
    val annotation: Color,
    /** A Kotlin label (`loop@`, `return@loop`, `this@Outer`) — a bronze marker, rendered italic. */
    val label: Color,
    // semantic-highlight modifier accents (layered over the base kind color; see SemanticStyles)
    val composable: Color,
    val extension: Color,
    val mutableVar: Color,
    val suspendFn: Color,
)

/** Solid puzzle-block palette (`--blk-*`) for the projectional block editor. */
@Immutable
data class BlockColors(
    val control: Color,
    val data: Color,
    val call: Color,
    val ret: Color,
    val comment: Color,
    val method: Color,
    val op: Color,
    val text: Color,
    val socket: Color,
    val socketText: Color,
    val hole: Color,
)

private val DarkBlocks = BlockColors(
    data = Color(0xFF8A5FD0),
    control = Color(0xFFB67E2C),
    call = Color(0xFF4A7FC1),
    ret = Color(0xFFC25B5B),
    comment = Color(0xFF5E626B),
    method = Color(0xFF2C8F80),
    op = Color(0xFF3E9E63),
    text = Color(0xFFFFFFFF),
    socket = Color.White.copy(alpha = 0.92f),
    socketText = Color(0xFF26282C),
    hole = Color.Black.copy(alpha = 0.28f),
)

private val LightBlocks = BlockColors(
    control = Color(0xFF7E4EC4), data = Color(0xFFA8701D), call = Color(0xFF3A6FB5), ret = Color(0xFFB44C4C),
    comment = Color(0xFF83868D), method = Color(0xFF1F8A77), op = Color(0xFF2F8F56), text = Color(0xFFFFFFFF),
    socket = Color.White.copy(alpha = 0.94f), socketText = Color(0xFF26282C), hole = Color.Black.copy(alpha = 0.26f),
)

@Immutable
data class CodeAssistColors(
    val isDark: Boolean,
    // surfaces
    val bg: Color,
    val editorBg: Color,
    val consoleBg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    // text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnAccent: Color,
    // lines
    val separator: Color,
    val separatorStrong: Color,
    val hairline: Color,
    val gutterText: Color,
    val currentLine: Color,
    val selection: Color,
    // accent
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    // semantic
    val success: Color,
    val run: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val gitAdded: Color,
    val gitModified: Color,
    val gitDeleted: Color,
    val gitUntracked: Color,
    // glass (translucent fills + edges; solid fallback = the fills themselves over bg)
    val glassThin: Color,
    val glassReg: Color,
    val glassThick: Color,
    val glassEdge: Color,
    val glassEdgeTop: Color,
    val scrim: Color,
    val syntax: SyntaxColors,
    val block: BlockColors,
)

private val DarkSyntax = SyntaxColors(
    default = Color(0xFFC7C8CF),
    keyword = Color(0xFFCD7EE0),
    storage = Color(0xFFCD7EE0),
    string = Color(0xFF98C97A),
    number = Color(0xFFD9A066),
    func = Color(0xFF61AFEF),
    type = Color(0xFFE6C178),
    comment = Color(0xFF6C7078),
    property = Color(0xFF57B6C2),
    variable = Color(0xFFE08C84),
    punctuation = Color(0xFF8B8D96),
    constant = Color(0xFFD9A066),
    annotation = Color(0xFFE6C178),
    label = Color(0xFFB5895B),
    composable = Color(0xFF4FC1A6),
    extension = Color(0xFF82AAFF),
    mutableVar = Color(0xFFE0918A),
    suspendFn = Color(0xFFD9A0C9),
)

private val LightSyntax = SyntaxColors(
    default = Color(0xFF34363D),
    keyword = Color(0xFFA32FB0),
    storage = Color(0xFFA32FB0),
    string = Color(0xFF3F9C45),
    number = Color(0xFFB9690B),
    func = Color(0xFF3A6FE0),
    type = Color(0xFF9A6700),
    comment = Color(0xFFA3A4AA),
    property = Color(0xFF0A86A8),
    variable = Color(0xFFC0473F),
    punctuation = Color(0xFF6B6C73),
    constant = Color(0xFFB9690B),
    annotation = Color(0xFF9A6700),
    label = Color(0xFF8A5A2B),
    composable = Color(0xFF1F8A77),
    extension = Color(0xFF3A6FB5),
    mutableVar = Color(0xFFC0473F),
    suspendFn = Color(0xFF9C4E8A),
)

private fun darkColors(accent: Color, accentStrong: Color) = CodeAssistColors(
    isDark = true,
    bg = Color(0xFF161719),
    editorBg = Color(0xFF1B1C1F),
    consoleBg = Color(0xFF1B1C1F),
    surface = Color(0xFF232428),
    surface2 = Color(0xFF2B2C31),
    surface3 = Color(0xFF34353B),
    textPrimary = Color(0xFFE9E9EC),
    textSecondary = Color(0xFFA0A1AA),
    textTertiary = Color(0xFF6F7079),
    textOnAccent = Color(0xFFFFFFFF),
    separator = Color.White.copy(alpha = 0.09f),
    separatorStrong = Color.White.copy(alpha = 0.14f),
    hairline = Color.White.copy(alpha = 0.07f),
    gutterText = Color(0xFF5B5C64),
    currentLine = Color.White.copy(alpha = 0.045f),
    selection = accent.copy(alpha = 0.34f),
    accent = accent,
    accentStrong = accentStrong,
    accentSoft = accent.copy(alpha = 0.16f),
    success = Color(0xFF34D058),
    run = Color(0xFF34D058),
    warning = Color(0xFFFFB340),
    error = Color(0xFFFF6B63),
    info = Color(0xFF5AC8E0),
    gitAdded = Color(0xFF34D058),
    gitModified = Color(0xFFFFC44D),
    gitDeleted = Color(0xFFFF6B63),
    gitUntracked = Color(0xFF5AC8E0),
    glassThin = Color(0xFF1C1D21).copy(alpha = 0.55f),
    glassReg = Color(0xFF1E1F24).copy(alpha = 0.72f),
    glassThick = Color(0xFF18191C).copy(alpha = 0.86f),
    glassEdge = Color.White.copy(alpha = 0.10f),
    glassEdgeTop = Color.White.copy(alpha = 0.16f),
    scrim = Color.Black.copy(alpha = 0.5f),
    syntax = DarkSyntax,
    block = DarkBlocks,
)

private fun lightColors(accent: Color, accentStrong: Color) = CodeAssistColors(
    isDark = false,
    bg = Color(0xFFECEBE7),
    editorBg = Color(0xFFFAF9F6),
    consoleBg = Color(0xFFFAF9F6),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF4F3EF),
    surface3 = Color(0xFFE8E7E1),
    textPrimary = Color(0xFF1D1E22),
    textSecondary = Color(0xFF62636B),
    textTertiary = Color(0xFF97989F),
    textOnAccent = Color(0xFFFFFFFF),
    separator = Color.Black.copy(alpha = 0.09f),
    separatorStrong = Color.Black.copy(alpha = 0.14f),
    hairline = Color.Black.copy(alpha = 0.06f),
    gutterText = Color(0xFFB3B2AD),
    currentLine = Color.Black.copy(alpha = 0.035f),
    selection = accent.copy(alpha = 0.24f),
    accent = accent,
    accentStrong = accentStrong,
    accentSoft = accent.copy(alpha = 0.12f),
    success = Color(0xFF29A847),
    run = Color(0xFF29A847),
    warning = Color(0xFFD98300),
    error = Color(0xFFDF4A45),
    info = Color(0xFF2399B8),
    gitAdded = Color(0xFF29A847),
    gitModified = Color(0xFFC98A00),
    gitDeleted = Color(0xFFDF4A45),
    gitUntracked = Color(0xFF2399B8),
    glassThin = Color.White.copy(alpha = 0.55f),
    glassReg = Color(0xFFFCFBF9).copy(alpha = 0.72f),
    glassThick = Color(0xFFF8F7F4).copy(alpha = 0.88f),
    glassEdge = Color.Black.copy(alpha = 0.08f),
    glassEdgeTop = Color.White.copy(alpha = 0.9f),
    scrim = Color(0xFF1E1C18).copy(alpha = 0.32f),
    syntax = LightSyntax,
    block = LightBlocks,
)

fun caColors(dark: Boolean, accent: CaAccent): CodeAssistColors = when {
    // Lime: the editor takes the scheme's primary, which reads as a bright chartreuse caret/selection on
    // dark and a deep olive on light.
    dark && accent == CaAccent.Lime -> darkColors(Color(0xFFCDF14A), Color(0xFFB4D93A))
    !dark && accent == CaAccent.Lime -> lightColors(Color(0xFF465700), Color(0xFF354200))
    dark && accent == CaAccent.Violet -> darkColors(Color(0xFFB487F7), Color(0xFFA06BFF))
    dark && accent == CaAccent.Teal -> darkColors(Color(0xFF5CCFE6), Color(0xFF3FBDD9))
    // Legacy CodeAssist orange (the Darcula `#CC7832` from the classic `<>` logo).
    dark && accent == CaAccent.Orange -> darkColors(Color(0xFFD98A3D), Color(0xFFCC7832))
    !dark && accent == CaAccent.Violet -> lightColors(Color(0xFF8B5CF6), Color(0xFF7C3AED))
    !dark && accent == CaAccent.Orange -> lightColors(Color(0xFFC16A1C), Color(0xFFA85614))
    else -> lightColors(Color(0xFF1C9BBD), Color(0xFF137E9C))
}

// ---- Spacing (4-pt scale) & radius — theme-independent ----
@Immutable
object Spacing {
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp; val s5 = 20.dp
    val s6 = 24.dp; val s7 = 28.dp; val s8 = 32.dp; val s10 = 40.dp; val s12 = 48.dp
}

@Immutable
object Radius {
    val xs = 6.dp; val sm = 9.dp; val control = 12.dp; val md = 14.dp
    val lg = 18.dp; val xl = 24.dp; val sheet = 26.dp; val pill = 999.dp
}

// ---- Typography ----
@Immutable
class CaTypography(ui: FontFamily, code: FontFamily) {
    val caption2 = TextStyle(fontFamily = ui, fontSize = 11.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontFamily = ui, fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val footnote = TextStyle(fontFamily = ui, fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val subhead = TextStyle(fontFamily = ui, fontSize = 15.sp, fontWeight = FontWeight.Normal)
    val body = TextStyle(fontFamily = ui, fontSize = 16.sp, fontWeight = FontWeight.Normal)
    val headline = TextStyle(fontFamily = ui, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val title3 = TextStyle(fontFamily = ui, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val title2 = TextStyle(fontFamily = ui, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    val title1 = TextStyle(fontFamily = ui, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    val large = TextStyle(fontFamily = ui, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
    val code = TextStyle(fontFamily = code, fontSize = 13.5f.sp, lineHeight = 22.sp)
    val codeSmall = TextStyle(fontFamily = code, fontSize = 12.5f.sp, lineHeight = 20.sp)
    val uiFamily = ui
    val codeFamily = code
}

val LocalCaColors = staticCompositionLocalOf<CodeAssistColors> { error("CodeAssistTheme not applied") }
val LocalCaType = staticCompositionLocalOf<CaTypography> { error("CodeAssistTheme not applied") }

/** Token accessor: `Ca.colors`, `Ca.type`, `Ca.spacing`, `Ca.radius`. */
object Ca {
    val colors: CodeAssistColors
        @Composable @ReadOnlyComposable get() = LocalCaColors.current
    val type: CaTypography
        @Composable @ReadOnlyComposable get() = LocalCaType.current
    val spacing get() = Spacing
    val radius get() = Radius
}

/**
 * Bridge the bespoke [CodeAssistColors] onto the active M3 [ColorScheme]: the generic chrome fields
 * (surfaces, text, accent, separators, error) are re-pointed at Material roles so every screen still on the
 * `Ca` token accessor reskins to the expressive scheme automatically, while the IDE-domain fields
 * (editor/console surfaces, gutter, git, glass, syntax, blocks) keep their tuned bespoke values. Migration
 * scaffold — call sites move to `MaterialTheme.colorScheme` / `Ide.colors` and this is deleted last.
 */
private fun CodeAssistColors.bridgedTo(scheme: ColorScheme): CodeAssistColors = copy(
    bg = scheme.background,
    surface = scheme.surface,
    surface2 = scheme.surfaceContainerHigh,
    surface3 = scheme.surfaceContainerHighest,
    textPrimary = scheme.onSurface,
    textSecondary = scheme.onSurfaceVariant,
    textTertiary = scheme.outline,
    textOnAccent = scheme.onPrimary,
    separator = scheme.outlineVariant,
    separatorStrong = scheme.outline,
    accent = scheme.primary,
    accentStrong = scheme.primary,
    accentSoft = scheme.primaryContainer,
    selection = scheme.primary.copy(alpha = if (isDark) 0.34f else 0.24f),
    error = scheme.error,
)

/**
 * Project the IDE-domain fields (unchanged by the bridge) into the [Ide] provider, letting the active
 * editor color scheme override the ones it speaks for.
 *
 * The scheme wins where it has an opinion and the theme shows through where it does not, which is what
 * keeps an untouched install tracking the Material accent while Solarized brings its own paper. The syntax
 * palette rides along unchanged: the caller already replaced it with the scheme's projection onto the
 * classic eighteen fields (see [toSyntaxColors]), so every non-editor surface that colors code (sample
 * cards, the Code Style preview, the block chips) follows the user's scheme without knowing schemes exist.
 */
private fun CodeAssistColors.toIdeColors(editor: ResolvedColorScheme): IdeColors = IdeColors(
    isDark = isDark,
    editorBg = editor.background(editorBg), consoleBg = consoleBg,
    gutterText = editor.gutterText(gutterText),
    currentLine = editor.currentLine(currentLine),
    selection = editor.selection(selection),
    success = success, run = run, warning = editor.warning(warning), info = editor.info(info),
    gitAdded = gitAdded, gitModified = gitModified, gitDeleted = gitDeleted, gitUntracked = gitUntracked,
    glassThin = glassThin, glassReg = glassReg, glassThick = glassThick,
    glassEdge = glassEdge, glassEdgeTop = glassEdgeTop, scrim = scrim,
    syntax = syntax, block = block,
)

/**
 * The live theme's answer for every chrome attribute a scheme leaves unset.
 *
 * These are the expressions the editor draws with when nothing overrides them (`accent` for the caret,
 * `accent` at 30% for the selection, `warning` at 28% for a search hit). Stating them as scheme values is
 * what lets a scheme replace them one at a time, and what lets the scheme editor show an untouched entry as
 * the color it actually renders rather than an empty swatch. The editor passes the same expressions as its
 * own last-resort fallbacks, for the case where a composable renders outside [CodeAssistTheme].
 */
private fun CodeAssistColors.schemeDefaults(): SchemeDefaults = SchemeDefaults(
    buildMap {
        put(ColorKeys.EDITOR_BACKGROUND, AttributeStyle.bg(editorBg))
        put(ColorKeys.EDITOR_CARET, AttributeStyle.fg(accent))
        put(ColorKeys.EDITOR_SELECTION, AttributeStyle.bg(accent.copy(alpha = 0.30f)))
        put(ColorKeys.EDITOR_CURRENT_LINE, AttributeStyle.bg(currentLine))
        put(ColorKeys.EDITOR_INDENT_GUIDE, AttributeStyle.fg(hairline))
        put(ColorKeys.EDITOR_FIND_MATCH, AttributeStyle.bg(warning.copy(alpha = 0.28f)))
        put(ColorKeys.EDITOR_FIND_CURRENT, AttributeStyle.bg(accent.copy(alpha = 0.5f)))
        put(ColorKeys.EDITOR_OCCURRENCE, AttributeStyle.bg(textSecondary.copy(alpha = 0.18f)))
        put(ColorKeys.EDITOR_TEMPLATE_FIELD, AttributeStyle.bg(accent.copy(alpha = 0.16f)))
        put(ColorKeys.EDITOR_COMPOSING, AttributeStyle.fg(textSecondary))
        put(ColorKeys.EDITOR_INLAY_HINT, AttributeStyle.fg(textTertiary))
        // A faint chip behind a fold's `...`: a low-alpha overlay, NOT `hairline.copy(alpha = …)`, which
        // would replace the hairline's own alpha and paint a near-opaque box in dark mode.
        put(
            ColorKeys.EDITOR_FOLD_PLACEHOLDER,
            AttributeStyle(
                foreground = textTertiary,
                background = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f),
            ),
        )
        put(ColorKeys.GUTTER_TEXT, AttributeStyle.fg(gutterText))
        put(ColorKeys.GUTTER_CURRENT, AttributeStyle.fg(textSecondary))
        put(ColorKeys.GUTTER_BORDER, AttributeStyle.fg(separator))
        put(ColorKeys.DIAGNOSTIC_ERROR, AttributeStyle.fg(error))
        put(ColorKeys.DIAGNOSTIC_WARNING, AttributeStyle.fg(warning))
        put(ColorKeys.DIAGNOSTIC_INFO, AttributeStyle.fg(info))
    }
)

@Composable
fun CodeAssistTheme(
    dark: Boolean = true,
    accent: CaAccent = CaAccent.Lime,
    /** A user-chosen custom seed color. When set it overrides the preset [accent] palette and wallpaper
     *  dynamic color — the whole expressive scheme is generated from this seed. */
    seedColor: Color? = null,
    /** Follow the device wallpaper (Material You). Only consulted when [seedColor] is null; on a platform
     *  without dynamic color (desktop, pre-12 Android) it falls back to the [accent] preset. */
    useDynamic: Boolean = false,
    /** The UI's own text face. Defaults to the platform sans, or a bundled one for a script the
     *  platform does not draw: see [rememberUiFont]. The editor's face is [codeFont]. */
    uiFont: FontFamily = rememberUiFont(),
    codeFont: FontFamily = FontFamily.Monospace,
    /** The user's editor color scheme. The shipped default overrides nothing, so it renders as the theme. */
    editorColorScheme: EditorColorScheme = BuiltInColorSchemes.DEFAULT,
    content: @Composable () -> Unit,
) {
    // Precedence: an explicit custom seed wins; else wallpaper dynamic color IF the user opted into it; else
    // the fixed expressive palette seeded from the chosen preset accent (so picking a preset always applies).
    val wallpaper = dynamicColorSchemeOrNull(dark)
    val fromSeed = remember(seedColor, dark) { seedColor?.let { expressiveColorSchemeFromSeed(it, dark) } }
    val fallback = remember(dark, accent) { expressiveColorScheme(accent, dark) }
    val scheme = fromSeed ?: (if (useDynamic) wallpaper else null) ?: fallback

    val base = remember(dark, accent) { caColors(dark, accent) }
    val bridgedBase = remember(base, scheme) { base.bridgedTo(scheme) }
    // Resolving the scheme is a walk up every attribute's fallback chain, so it happens here, once per
    // theme change, and never in the editor's draw path. The registry version is a key so attributes
    // registered after the first composition are picked up on the next one rather than at the next launch.
    val defaults = remember(bridgedBase) { bridgedBase.schemeDefaults() }
    val editorColors = remember(editorColorScheme, dark, defaults, ColorAttributes.version) {
        ResolvedColorScheme(editorColorScheme, dark, defaults)
    }
    val syntaxColors = remember(editorColors) { editorColors.toSyntaxColors() }
    val bridged = remember(bridgedBase, editorColors, syntaxColors) {
        bridgedBase.copy(
            editorBg = editorColors.background(bridgedBase.editorBg),
            currentLine = editorColors.currentLine(bridgedBase.currentLine),
            selection = editorColors.selection(bridgedBase.selection),
            gutterText = editorColors.gutterText(bridgedBase.gutterText),
            syntax = syntaxColors,
        )
    }
    val ide = remember(bridged, editorColors) { bridged.toIdeColors(editorColors) }
    val caType = remember(uiFont, codeFont) { CaTypography(uiFont, codeFont) }
    val typography = remember(uiFont) { expressiveTypography(uiFont) }

    MaterialTheme(colorScheme = scheme, typography = typography, shapes = ExpressiveShapes) {
        CompositionLocalProvider(
            LocalCaColors provides bridged,
            LocalCaType provides caType,
            LocalIdeColors provides ide,
            LocalEditorColors provides editorColors,
            content = content,
        )
    }
}
