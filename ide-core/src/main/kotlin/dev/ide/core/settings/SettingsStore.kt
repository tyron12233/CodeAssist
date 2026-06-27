package dev.ide.core.settings

import dev.ide.core.settings.IdeSettings.Companion.MAX_COMPLETION_DELAY_MS
import dev.ide.core.settings.IdeSettings.Companion.MAX_COMPLETION_MAX_ITEMS
import dev.ide.core.settings.IdeSettings.Companion.MAX_FONT_SCALE
import dev.ide.core.settings.IdeSettings.Companion.MAX_REPARSE_DELAY_MS
import dev.ide.core.settings.IdeSettings.Companion.MIN_COMPLETION_DELAY_MS
import dev.ide.core.settings.IdeSettings.Companion.MIN_COMPLETION_MAX_ITEMS
import dev.ide.core.settings.IdeSettings.Companion.MIN_FONT_SCALE
import dev.ide.core.settings.IdeSettings.Companion.MIN_REPARSE_DELAY_MS

/**
 * Reads/writes [IdeSettings] over the flat key/value preference store the host wires in ([get]/[set],
 * backed by `ProjectManager`'s `prefs.properties`). One namespaced key per field; a missing or unparseable
 * value falls back to the [IdeSettings] default and numeric fields are clamped to their bounds — so a fresh
 * install, a partial file, or a hand-edited one all load to something sane.
 *
 * Stateless: the backend caches the loaded snapshot and calls [save] on change. Stdlib-only.
 */
class SettingsStore(
    private val get: (String) -> String?,
    private val set: (String, String) -> Unit,
) {
    fun load(): IdeSettings {
        val d = IdeSettings()
        return IdeSettings(
            themeMode = oneOf("appearance.themeMode", d.themeMode, IdeSettings.THEME_LIGHT, IdeSettings.THEME_DARK, IdeSettings.THEME_SYSTEM),
            accent = if (str("appearance.accent", d.accent) == IdeSettings.ACCENT_TEAL) IdeSettings.ACCENT_TEAL else IdeSettings.ACCENT_VIOLET,
            // Stored as an integer percent (100 = 1.0×) so the generic IntSlider and this typed view agree.
            editorFontScale = (int("editor.fontScale", (d.editorFontScale * 100).toInt()) / 100f).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
            codeFont = oneOf("editor.codeFont", d.codeFont, IdeSettings.CODE_FONT_JETBRAINS, IdeSettings.CODE_FONT_MONOSPACE),
            fontLigatures = bool("editor.fontLigatures", d.fontLigatures),
            inlayHints = bool("editor.inlayHints", d.inlayHints),
            semanticHighlighting = bool("editor.semanticHighlighting", d.semanticHighlighting),
            codeFolding = bool("editor.codeFolding", d.codeFolding),
            wordWrap = bool("editor.wordWrap", d.wordWrap),
            wrapIndent = bool("editor.wrapIndent", d.wrapIndent),
            twoAxisScroll = bool("editor.twoAxisScroll", d.twoAxisScroll),
            pinchZoom = bool("editor.pinchZoom", d.pinchZoom),
            completionAutoPopup = bool("completion.autoPopup", d.completionAutoPopup),
            completionDelayMs = int("completion.delayMs", d.completionDelayMs).coerceIn(MIN_COMPLETION_DELAY_MS, MAX_COMPLETION_DELAY_MS),
            completionMaxItems = int("completion.maxItems", d.completionMaxItems).coerceIn(MIN_COMPLETION_MAX_ITEMS, MAX_COMPLETION_MAX_ITEMS),
            postfixTemplates = bool("completion.postfixTemplates", d.postfixTemplates),
            wordCompletion = bool("completion.wordCompletion", d.wordCompletion),
            analyzeOnTheFly = bool("analysis.onTheFly", d.analyzeOnTheFly),
            reparseDelayMs = int("analysis.reparseDelayMs", d.reparseDelayMs).coerceIn(MIN_REPARSE_DELAY_MS, MAX_REPARSE_DELAY_MS),
        )
    }

    /** Persist every field (idempotent; only the prefs file is touched). */
    fun save(s: IdeSettings) {
        put("appearance.themeMode", s.themeMode)
        put("appearance.accent", s.accent)
        put("editor.fontScale", (s.editorFontScale * 100).toInt().toString())
        put("editor.codeFont", s.codeFont)
        put("editor.fontLigatures", s.fontLigatures.toString())
        put("editor.inlayHints", s.inlayHints.toString())
        put("editor.semanticHighlighting", s.semanticHighlighting.toString())
        put("editor.codeFolding", s.codeFolding.toString())
        put("editor.wordWrap", s.wordWrap.toString())
        put("editor.wrapIndent", s.wrapIndent.toString())
        put("editor.twoAxisScroll", s.twoAxisScroll.toString())
        put("editor.pinchZoom", s.pinchZoom.toString())
        put("completion.autoPopup", s.completionAutoPopup.toString())
        put("completion.delayMs", s.completionDelayMs.toString())
        put("completion.maxItems", s.completionMaxItems.toString())
        put("completion.postfixTemplates", s.postfixTemplates.toString())
        put("completion.wordCompletion", s.wordCompletion.toString())
        put("analysis.onTheFly", s.analyzeOnTheFly.toString())
        put("analysis.reparseDelayMs", s.reparseDelayMs.toString())
    }

    private fun key(k: String) = "$KEY_PREFIX$k"
    private fun put(k: String, v: String) = set(key(k), v)
    private fun str(k: String, def: String) = get(key(k)) ?: def
    /** A string value constrained to [allowed]; an unknown/absent value falls back to [def]. */
    private fun oneOf(k: String, def: String, vararg allowed: String): String =
        get(key(k))?.takeIf { it in allowed } ?: def
    private fun bool(k: String, def: Boolean) = get(key(k))?.toBooleanStrictOrNull() ?: def
    private fun int(k: String, def: Int) = get(key(k))?.trim()?.toIntOrNull() ?: def

    private companion object {
        // Namespaced so settings never collide with the other prefs (onboarding.seen, analytics.consent, …).
        const val KEY_PREFIX = "settings."
    }
}
