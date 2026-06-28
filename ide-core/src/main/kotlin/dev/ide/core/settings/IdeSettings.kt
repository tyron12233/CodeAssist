package dev.ide.core.settings

/**
 * App-global IDE preferences — appearance, editor, completion, and analysis *behaviour*. Persisted as flat
 * key/value pairs in the preferences store (`prefs.properties`, shared with the onboarding/analytics flags).
 *
 * Deliberately **app-global**: these are the same for every project the user opens, like IntelliJ's IDE
 * settings. Per-project, project-shaped settings — the inspection profile (which checks are on + their
 * severities), the dependency conflict policy, and the custom repository list — are NOT here; they live with
 * the project in `IdeServices` (under `.platform/`) so different projects can differ.
 *
 * Every default mirrors the value the code used before settings existed (the hardcoded `110ms` completion
 * debounce, the `200`-item completion cap, the `300ms` reparse delay, dark + violet, …), so an absent or
 * partial prefs file behaves exactly like the old build.
 */
data class IdeSettings(
    // ---- appearance ----
    /** [THEME_LIGHT], [THEME_DARK], or [THEME_SYSTEM] (follow the OS). */
    val themeMode: String = THEME_DARK,
    /** [ACCENT_VIOLET], [ACCENT_TEAL], or [ACCENT_ORANGE] (the accent swaps the theme ships). */
    val accent: String = ACCENT_VIOLET,

    // ---- editor ----
    /** Code-font zoom, 1.0 = the theme's default size (also driven live by pinch / Ctrl-+ / Ctrl--). */
    val editorFontScale: Float = 1f,
    /** [CODE_FONT_JETBRAINS] (the bundled JetBrains Mono) or [CODE_FONT_MONOSPACE] (the system monospace). */
    val codeFont: String = CODE_FONT_JETBRAINS,
    /** Render programming ligatures (`->`, `!=`, `>=`, …) when the code font provides them (JetBrains Mono
     *  does). On by default — `true` leaves the font's defaults (ligatures show); `false` disables them. */
    val fontLigatures: Boolean = true,
    val inlayHints: Boolean = true,
    val semanticHighlighting: Boolean = true,
    val codeFolding: Boolean = true,
    /** Soft-wrap long lines at the viewport edge (off = one row per line + horizontal scroll). */
    val wordWrap: Boolean = false,
    /** Indent wrapped continuation rows to the line's own indent (IntelliJ-style); only when [wordWrap]. */
    val wrapIndent: Boolean = true,
    /** Free (two-axis) touch scrolling: a single drag pans both axes at once (off = orientation-locked). */
    val twoAxisScroll: Boolean = true,
    /** Two-finger pinch zooms the code font (Ctrl-+/-/0 always works regardless). */
    val pinchZoom: Boolean = true,
    /** Allow the soft keyboard's autocorrect / suggestions / auto-space in the editor. Off (default) treats
     *  the field as raw code input, so a typed `.` doesn't get an auto-inserted space and identifiers aren't
     *  "corrected". On = a normal prose keyboard (suggestion strip, glide typing). */
    val softKeyboardSuggestions: Boolean = false,

    // ---- completion ----
    /** Pop the completion list up automatically while typing; off = only on explicit trigger (Ctrl-Space). */
    val completionAutoPopup: Boolean = true,
    /** Debounce after a keystroke before the auto-popup requests completion. */
    val completionDelayMs: Int = 110,
    /** Hard ceiling on the number of completions shown after ranking. */
    val completionMaxItems: Int = 200,
    /** Offer postfix templates (`.val`, `.if`, `.notnull`, …) in completion. */
    val postfixTemplates: Boolean = true,
    /** Offer plain words already in the buffer (hippie/word completion) as a fallback. */
    val wordCompletion: Boolean = true,

    // ---- analysis ----
    /** Run diagnostics as you type. Off = the editor never auto-analyzes (errors only surface on build). */
    val analyzeOnTheFly: Boolean = true,
    /** Quiet period after the last edit before the highlighting daemon runs its passes. */
    val reparseDelayMs: Int = 300,
) {
    companion object {
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"
        const val ACCENT_VIOLET = "violet"
        const val ACCENT_TEAL = "teal"
        const val ACCENT_ORANGE = "orange"
        const val CODE_FONT_JETBRAINS = "jetbrains"
        const val CODE_FONT_MONOSPACE = "monospace"

        // Bounds the UI sliders enforce and the store clamps to (a hand-edited prefs file can't push the
        // editor into an unusable state).
        const val MIN_FONT_SCALE = 0.7f
        const val MAX_FONT_SCALE = 2.0f
        const val MIN_COMPLETION_DELAY_MS = 0
        const val MAX_COMPLETION_DELAY_MS = 1000
        const val MIN_COMPLETION_MAX_ITEMS = 10
        const val MAX_COMPLETION_MAX_ITEMS = 500
        const val MIN_REPARSE_DELAY_MS = 0
        const val MAX_REPARSE_DELAY_MS = 2000
    }
}
