package dev.ide.ui.theme.colors

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The active scheme, already resolved for the current theme mode.
 *
 * Read it (through `Ide.editorColors`) wherever a color is looked up by attribute key — the editor canvas,
 * the semantic overlay, the scheme editor's own preview. Code that only needs the eighteen classic syntax
 * colors can keep reading `Ide.colors.syntax`, which is this projected onto that shape.
 *
 * Defaulted rather than `error(...)` so a composable rendered outside `CodeAssistTheme` — a preview, a
 * screenshot test, a plugin's own window — colors with the shipped scheme instead of crashing.
 */
val LocalEditorColors = staticCompositionLocalOf {
    ResolvedColorScheme(BuiltInColorSchemes.DEFAULT, isDark = true)
}
