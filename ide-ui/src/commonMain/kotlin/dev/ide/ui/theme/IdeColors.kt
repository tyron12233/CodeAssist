package dev.ide.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * IDE-domain colors that have no Material role: the editor/console surfaces, gutter + current-line tints,
 * the git-gutter status colors, the translucent "glass" fills for floating chrome, and the code
 * [SyntaxColors] / block-editor [BlockColors] palettes. These are tuned for code legibility rather than the
 * Material tonal system, so they live alongside the M3 [androidx.compose.material3.ColorScheme] rather than
 * being derived from it. Read through the [Ide] accessor (`Ide.colors.syntax`, `Ide.colors.editorBg`).
 */
@Immutable
class IdeColors(
    val isDark: Boolean,
    val editorBg: Color,
    val consoleBg: Color,
    val gutterText: Color,
    val currentLine: Color,
    /** Accent-tinted editor selection highlight (follows the active M3 primary). */
    val selection: Color,
    // Status colors outside the M3 primary/error set (green success/run, amber warning, cyan info).
    val success: Color,
    val run: Color,
    val warning: Color,
    val info: Color,
    // Git gutter markers.
    val gitAdded: Color,
    val gitModified: Color,
    val gitDeleted: Color,
    val gitUntracked: Color,
    // Translucent fills + edges for floating chrome (solid fallback = the fills themselves over the surface).
    val glassThin: Color,
    val glassReg: Color,
    val glassThick: Color,
    val glassEdge: Color,
    val glassEdgeTop: Color,
    val scrim: Color,
    val syntax: SyntaxColors,
    val block: BlockColors,
)

val LocalIdeColors = staticCompositionLocalOf<IdeColors> { error("CodeAssistTheme not applied") }

/** Accessor for the IDE-domain colors: `Ide.colors`. Complements Material's `MaterialTheme.colorScheme`. */
object Ide {
    val colors: IdeColors
        @Composable @ReadOnlyComposable get() = LocalIdeColors.current
}
