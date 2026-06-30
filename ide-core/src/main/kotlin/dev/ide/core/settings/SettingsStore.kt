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
            accent = oneOf("appearance.accent", d.accent, IdeSettings.ACCENT_VIOLET, IdeSettings.ACCENT_TEAL, IdeSettings.ACCENT_ORANGE),
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
            softKeyboardSuggestions = bool("editor.softKeyboardSuggestions", d.softKeyboardSuggestions),
            completionAutoPopup = bool("completion.autoPopup", d.completionAutoPopup),
            completionDelayMs = int("completion.delayMs", d.completionDelayMs).coerceIn(MIN_COMPLETION_DELAY_MS, MAX_COMPLETION_DELAY_MS),
            completionMaxItems = int("completion.maxItems", d.completionMaxItems).coerceIn(MIN_COMPLETION_MAX_ITEMS, MAX_COMPLETION_MAX_ITEMS),
            postfixTemplates = bool("completion.postfixTemplates", d.postfixTemplates),
            wordCompletion = bool("completion.wordCompletion", d.wordCompletion),
            analyzeOnTheFly = bool("analysis.onTheFly", d.analyzeOnTheFly),
            reparseDelayMs = int("analysis.reparseDelayMs", d.reparseDelayMs).coerceIn(MIN_REPARSE_DELAY_MS, MAX_REPARSE_DELAY_MS),
            formatOnSave = bool("codeStyle.formatOnSave", d.formatOnSave),
        )
    }

    /** The per-language code style profile (keyed `codeStyle.<lang>.<field>`); unset fields fall back to the
     *  language default and numeric fields are clamped. */
    fun loadCodeStyle(languageId: String): CodeStyleSettings {
        val d = CodeStyleSettings.default(languageId)
        fun k(name: String) = "codeStyle.$languageId.$name"
        return CodeStyleSettings(
            preset = oneOf(k("preset"), d.preset, CodeStyleSettings.PRESET_GOOGLE, CodeStyleSettings.PRESET_ANDROID, CodeStyleSettings.PRESET_KOTLIN_OFFICIAL, CodeStyleSettings.PRESET_CUSTOM),
            indentSize = int(k("indentSize"), d.indentSize).coerceIn(CodeStyleSettings.MIN_INDENT, CodeStyleSettings.MAX_INDENT),
            continuationIndent = int(k("continuationIndent"), d.continuationIndent).coerceIn(CodeStyleSettings.MIN_CONTINUATION, CodeStyleSettings.MAX_CONTINUATION),
            maxLineLength = int(k("maxLineLength"), d.maxLineLength).coerceIn(CodeStyleSettings.MIN_LINE_LENGTH, CodeStyleSettings.MAX_LINE_LENGTH),
            useTabs = bool(k("useTabs"), d.useTabs),
            braceStyle = oneOf(k("braceStyle"), d.braceStyle, CodeStyleSettings.BRACE_END_OF_LINE, CodeStyleSettings.BRACE_NEXT_LINE),
            spaceBeforeParens = bool(k("spaceBeforeParens"), d.spaceBeforeParens),
            spaceWithinParens = bool(k("spaceWithinParens"), d.spaceWithinParens),
            spaceAfterComma = bool(k("spaceAfterComma"), d.spaceAfterComma),
            spaceAroundOperators = bool(k("spaceAroundOperators"), d.spaceAroundOperators),
            spaceBeforeBrace = bool(k("spaceBeforeBrace"), d.spaceBeforeBrace),
            blankLinesToKeep = int(k("blankLinesToKeep"), d.blankLinesToKeep).coerceIn(CodeStyleSettings.MIN_BLANK, CodeStyleSettings.MAX_BLANK),
            wrapMethodParameters = wrapOf(k("wrapMethodParameters"), d.wrapMethodParameters),
            wrapMethodArguments = wrapOf(k("wrapMethodArguments"), d.wrapMethodArguments),
            wrapChainedCalls = wrapOf(k("wrapChainedCalls"), d.wrapChainedCalls),
            wrapBinaryExpressions = wrapOf(k("wrapBinaryExpressions"), d.wrapBinaryExpressions),
            blankLinesAfterImports = int(k("blankLinesAfterImports"), d.blankLinesAfterImports).coerceIn(CodeStyleSettings.MIN_BLANK, CodeStyleSettings.MAX_BLANK),
            blankLinesBeforeMethod = int(k("blankLinesBeforeMethod"), d.blankLinesBeforeMethod).coerceIn(CodeStyleSettings.MIN_BLANK, CodeStyleSettings.MAX_BLANK),
            blankLinesBeforeField = int(k("blankLinesBeforeField"), d.blankLinesBeforeField).coerceIn(CodeStyleSettings.MIN_BLANK, CodeStyleSettings.MAX_BLANK),
            blankLinesBeforeFirstMember = int(k("blankLinesBeforeFirstMember"), d.blankLinesBeforeFirstMember).coerceIn(CodeStyleSettings.MIN_BLANK, CodeStyleSettings.MAX_BLANK),
            blankLinesBetweenTypes = int(k("blankLinesBetweenTypes"), d.blankLinesBetweenTypes).coerceIn(CodeStyleSettings.MIN_BLANK, CodeStyleSettings.MAX_BLANK),
            spaceBeforeSemicolon = bool(k("spaceBeforeSemicolon"), d.spaceBeforeSemicolon),
            spaceAroundLambdaArrow = bool(k("spaceAroundLambdaArrow"), d.spaceAroundLambdaArrow),
            spaceAroundTernary = bool(k("spaceAroundTernary"), d.spaceAroundTernary),
            spaceAfterTypeCast = bool(k("spaceAfterTypeCast"), d.spaceAfterTypeCast),
            formatComments = bool(k("formatComments"), d.formatComments),
            wrapComments = bool(k("wrapComments"), d.wrapComments),
        )
    }

    /** Persist a per-language code style profile under `codeStyle.<lang>.<field>`. */
    fun saveCodeStyle(languageId: String, s: CodeStyleSettings) {
        fun p(name: String, v: String) = put("codeStyle.$languageId.$name", v)
        p("preset", s.preset)
        p("indentSize", s.indentSize.toString())
        p("continuationIndent", s.continuationIndent.toString())
        p("maxLineLength", s.maxLineLength.toString())
        p("useTabs", s.useTabs.toString())
        p("braceStyle", s.braceStyle)
        p("spaceBeforeParens", s.spaceBeforeParens.toString())
        p("spaceWithinParens", s.spaceWithinParens.toString())
        p("spaceAfterComma", s.spaceAfterComma.toString())
        p("spaceAroundOperators", s.spaceAroundOperators.toString())
        p("spaceBeforeBrace", s.spaceBeforeBrace.toString())
        p("blankLinesToKeep", s.blankLinesToKeep.toString())
        p("wrapMethodParameters", s.wrapMethodParameters)
        p("wrapMethodArguments", s.wrapMethodArguments)
        p("wrapChainedCalls", s.wrapChainedCalls)
        p("wrapBinaryExpressions", s.wrapBinaryExpressions)
        p("blankLinesAfterImports", s.blankLinesAfterImports.toString())
        p("blankLinesBeforeMethod", s.blankLinesBeforeMethod.toString())
        p("blankLinesBeforeField", s.blankLinesBeforeField.toString())
        p("blankLinesBeforeFirstMember", s.blankLinesBeforeFirstMember.toString())
        p("blankLinesBetweenTypes", s.blankLinesBetweenTypes.toString())
        p("spaceBeforeSemicolon", s.spaceBeforeSemicolon.toString())
        p("spaceAroundLambdaArrow", s.spaceAroundLambdaArrow.toString())
        p("spaceAroundTernary", s.spaceAroundTernary.toString())
        p("spaceAfterTypeCast", s.spaceAfterTypeCast.toString())
        p("formatComments", s.formatComments.toString())
        p("wrapComments", s.wrapComments.toString())
    }

    private fun wrapOf(k: String, def: String): String =
        get(key(k))?.takeIf { it in setOf(CodeStyleSettings.WRAP_NEVER, CodeStyleSettings.WRAP_IF_LONG, CodeStyleSettings.WRAP_ONE_PER_LINE) } ?: def

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
        put("editor.softKeyboardSuggestions", s.softKeyboardSuggestions.toString())
        put("completion.autoPopup", s.completionAutoPopup.toString())
        put("completion.delayMs", s.completionDelayMs.toString())
        put("completion.maxItems", s.completionMaxItems.toString())
        put("completion.postfixTemplates", s.postfixTemplates.toString())
        put("completion.wordCompletion", s.wordCompletion.toString())
        put("analysis.onTheFly", s.analyzeOnTheFly.toString())
        put("analysis.reparseDelayMs", s.reparseDelayMs.toString())
        put("codeStyle.formatOnSave", s.formatOnSave.toString())
    }

    private fun key(k: String) = "$KEY_PREFIX$k"
    private fun put(k: String, v: String) = set(key(k), v)
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
