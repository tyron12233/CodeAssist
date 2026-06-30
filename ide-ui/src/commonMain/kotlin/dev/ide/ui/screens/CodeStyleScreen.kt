package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCodeStyle
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.SettingsCard
import dev.ide.ui.components.SettingsChoiceRow
import dev.ide.ui.components.SettingsDivider
import dev.ide.ui.components.SettingsSliderRow
import dev.ide.ui.components.SettingsToggleRow
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.highlight
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LANG_JAVA = "java"
private const val LANG_KOTLIN = "kotlin"
private const val PRESET_CUSTOM = "custom"
private const val FORMAT_ON_SAVE_KEY = "settings.codeStyle.formatOnSave"

/**
 * The dedicated Code Style screen: a per-language formatting profile (Java / Kotlin), a preset, the detailed
 * options grouped by category, and a live preview that runs the real formatter on a fixed sample as you tune.
 * Java honors every option; the Kotlin re-indenter honors only indentation / tabs / blank-lines, so the
 * Java-only groups are hidden on the Kotlin tab.
 *
 * The live preview runs the real formatter, which needs an open project's engine; with [hasProject] false
 * (the screen reached from the project picker's hub) it's disabled and the card shows a hint instead. Editing
 * + persisting the profile still works either way.
 */
@Composable
fun CodeStyleScreen(backend: IdeBackend, hasProject: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val iconBox = if (isMobilePlatform) 42 else 34

    var language by remember { mutableStateOf(LANG_JAVA) }
    var style by remember { mutableStateOf(UiCodeStyle()) }
    var formatOnSave by remember { mutableStateOf(backend.settings.settings().formatOnSave) }
    var preview by remember { mutableStateOf("") }

    // Load the persisted profile when the language changes.
    LaunchedEffect(language) { style = backend.settings.codeStyle(language) }

    // Live preview: re-format the sample shortly after the profile settles (key change cancels the prior run,
    // which debounces rapid slider drags). The formatter is engine-backed, so it's skipped with no project open.
    LaunchedEffect(language, style, hasProject) {
        if (!hasProject) { preview = ""; return@LaunchedEffect }
        delay(120)
        preview = runCatching { backend.settings.formatStylePreview(language, style) }.getOrDefault("")
    }

    // Persist + adopt a new profile (any control edit flips the preset to Custom so the change takes effect).
    fun update(next: UiCodeStyle) {
        style = next
        backend.settings.setCodeStyle(language, next)
    }

    fun edit(transform: UiCodeStyle.() -> UiCodeStyle) {
        val base = displayStyle(style)
        update(base.transform().copy(preset = PRESET_CUSTOM))
    }

    val display = displayStyle(style)
    val javaOnly = language == LANG_JAVA
    val custom = style.preset == PRESET_CUSTOM

    Column(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButtonCa(CaIcons.chevronLeft, "Back", onBack, boxSize = iconBox)
            Text("Code Style", style = Ca.type.title3, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary, modifier = Modifier.weight(1f))
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsCard(null) {
                SettingsChoiceRow(
                    "Language", "Each language keeps its own profile", language,
                    listOf(LANG_JAVA to "Java", LANG_KOTLIN to "Kotlin"),
                ) { language = it }
                SettingsDivider()
                SettingsChoiceRow(
                    "Style", "A preset starting point; changing any option below switches to Custom", style.preset,
                    presetOptions(language),
                ) { picked -> update(if (picked == PRESET_CUSTOM) display.copy(preset = PRESET_CUSTOM) else presetDefaults(picked)) }
                SettingsDivider()
                SettingsToggleRow("Reformat on save", "Reformat the file each time you save it", formatOnSave) {
                    formatOnSave = it
                    backend.settings.setPreference(FORMAT_ON_SAVE_KEY, it.toString())
                }
            }

            PreviewCard(preview, language, hasProject)

            if (!javaOnly) {
                Text(
                    "Kotlin formatting normalizes indentation, tabs, blank lines, and inline spacing. Brace placement and line wrapping follow the code as written.",
                    color = Ca.colors.textTertiary, style = Ca.type.caption2,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (custom) {
                SettingsCard("Indentation") {
                    SettingsSliderRow("Indent size", "Spaces per level", display.indentSize, 1, 8, 1, null) { edit { copy(indentSize = it) } }
                    if (javaOnly) {
                        SettingsDivider()
                        SettingsSliderRow("Continuation indent", "Extra indent for a wrapped line", display.continuationIndent, 1, 16, 1, null) { edit { copy(continuationIndent = it) } }
                        SettingsDivider()
                        SettingsSliderRow("Line width", "Column the formatter wraps at", display.maxLineLength, 60, 200, 10, null) { edit { copy(maxLineLength = it) } }
                    }
                    SettingsDivider()
                    SettingsToggleRow("Indent with tabs", "Use tabs instead of spaces", display.useTabs) { edit { copy(useTabs = it) } }
                }

                if (javaOnly) {
                    SettingsCard("Wrapping & braces") {
                        SettingsChoiceRow("Brace placement", null, display.braceStyle, listOf("endOfLine" to "End of line", "nextLine" to "Next line")) { edit { copy(braceStyle = it) } }
                        SettingsDivider()
                        SettingsChoiceRow("Method parameters", null, display.wrapMethodParameters, wrapOptions()) { edit { copy(wrapMethodParameters = it) } }
                        SettingsDivider()
                        SettingsChoiceRow("Method arguments", null, display.wrapMethodArguments, wrapOptions()) { edit { copy(wrapMethodArguments = it) } }
                        SettingsDivider()
                        SettingsChoiceRow("Chained calls", null, display.wrapChainedCalls, wrapOptions()) { edit { copy(wrapChainedCalls = it) } }
                        SettingsDivider()
                        SettingsChoiceRow("Binary expressions", null, display.wrapBinaryExpressions, wrapOptions()) { edit { copy(wrapBinaryExpressions = it) } }
                    }
                }

                // Inline spacing. The first five rules are honored by Kotlin too (token-level rewriting); the
                // rest need Java's full formatter, so they are Java-only.
                SettingsCard("Spaces") {
                    SettingsToggleRow("Within parentheses", "`( x )` instead of `(x)`", display.spaceWithinParens) { edit { copy(spaceWithinParens = it) } }
                    SettingsDivider()
                    SettingsToggleRow("After comma", "`a, b` instead of `a,b`", display.spaceAfterComma) { edit { copy(spaceAfterComma = it) } }
                    SettingsDivider()
                    SettingsToggleRow("Around operators", "`a + b`, `x = 1`", display.spaceAroundOperators) { edit { copy(spaceAroundOperators = it) } }
                    SettingsDivider()
                    SettingsToggleRow("Before brace", "`) {` instead of `){`", display.spaceBeforeBrace) { edit { copy(spaceBeforeBrace = it) } }
                    SettingsDivider()
                    SettingsToggleRow("Around lambda arrow", "`a -> b`", display.spaceAroundLambdaArrow) { edit { copy(spaceAroundLambdaArrow = it) } }
                    if (javaOnly) {
                        SettingsDivider()
                        SettingsToggleRow("Before parentheses", "`if (` `for (` `while (`", display.spaceBeforeParens) { edit { copy(spaceBeforeParens = it) } }
                        SettingsDivider()
                        SettingsToggleRow("Before semicolon", "`x ;` instead of `x;`", display.spaceBeforeSemicolon) { edit { copy(spaceBeforeSemicolon = it) } }
                        SettingsDivider()
                        SettingsToggleRow("Around ternary", "`c ? a : b`", display.spaceAroundTernary) { edit { copy(spaceAroundTernary = it) } }
                        SettingsDivider()
                        SettingsToggleRow("After type cast", "`(Foo) x` instead of `(Foo)x`", display.spaceAfterTypeCast) { edit { copy(spaceAfterTypeCast = it) } }
                    }
                }

                SettingsCard("Blank lines") {
                    SettingsSliderRow("Keep at most", "Maximum consecutive blank lines", display.blankLinesToKeep, 0, 5, 1, null) { edit { copy(blankLinesToKeep = it) } }
                    if (javaOnly) {
                        SettingsDivider()
                        SettingsSliderRow("After imports", null, display.blankLinesAfterImports, 0, 5, 1, null) { edit { copy(blankLinesAfterImports = it) } }
                        SettingsDivider()
                        SettingsSliderRow("Before method", null, display.blankLinesBeforeMethod, 0, 5, 1, null) { edit { copy(blankLinesBeforeMethod = it) } }
                        SettingsDivider()
                        SettingsSliderRow("Before field", null, display.blankLinesBeforeField, 0, 5, 1, null) { edit { copy(blankLinesBeforeField = it) } }
                        SettingsDivider()
                        SettingsSliderRow("Before first member", null, display.blankLinesBeforeFirstMember, 0, 5, 1, null) { edit { copy(blankLinesBeforeFirstMember = it) } }
                        SettingsDivider()
                        SettingsSliderRow("Between types", null, display.blankLinesBetweenTypes, 0, 5, 1, null) { edit { copy(blankLinesBetweenTypes = it) } }
                    }
                }

                if (javaOnly) {
                    SettingsCard("Comments") {
                        SettingsToggleRow("Format comments", "Reindent Javadoc / block / line comments", display.formatComments) { edit { copy(formatComments = it) } }
                        SettingsDivider()
                        SettingsToggleRow("Wrap comments", "Wrap comment text at the line width", display.wrapComments) { edit { copy(wrapComments = it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(preview: String, language: String, hasProject: Boolean) {
    SettingsCard("Preview") {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 320.dp)
                .background(Ca.colors.editorBg, RoundedCornerShape(Ca.radius.control))
                .verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            if (!hasProject) {
                Text(
                    "Open a project to preview formatting.",
                    color = Ca.colors.textTertiary,
                    style = Ca.type.codeSmall,
                )
            } else if (preview.isEmpty()) {
                Text("…", color = Ca.colors.textTertiary, style = Ca.type.codeSmall)
            } else {
                val codeLang = if (language == LANG_KOTLIN) CodeLanguage.Kotlin else CodeLanguage.Java
                val syntax = Ca.colors.syntax
                val highlighted = remember(preview, codeLang, syntax) { highlight(preview, codeLang, syntax) }
                Text(
                    highlighted,
                    style = Ca.type.codeSmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun presetOptions(language: String): List<Pair<String, String>> = when (language) {
    LANG_KOTLIN -> listOf("kotlin_official" to "Kotlin official", "android" to "Android", PRESET_CUSTOM to "Custom")
    else -> listOf("google" to "Google", "android" to "Android", PRESET_CUSTOM to "Custom")
}

private fun wrapOptions(): List<Pair<String, String>> =
    listOf("never" to "Never", "ifLong" to "If long", "onePerLine" to "One per line")

/** What the controls display: a named preset shows its canonical values; Custom shows the stored fields. */
private fun displayStyle(style: UiCodeStyle): UiCodeStyle =
    if (style.preset == PRESET_CUSTOM) style else presetDefaults(style.preset)

/** The canonical values for a named preset (presets differ only in indentation; the rest are shared). */
private fun presetDefaults(preset: String): UiCodeStyle = when (preset) {
    "android" -> UiCodeStyle(preset = "android", indentSize = 4, continuationIndent = 8)
    "kotlin_official" -> UiCodeStyle(preset = "kotlin_official", indentSize = 4, continuationIndent = 8)
    else -> UiCodeStyle(preset = "google", indentSize = 2, continuationIndent = 4)
}
