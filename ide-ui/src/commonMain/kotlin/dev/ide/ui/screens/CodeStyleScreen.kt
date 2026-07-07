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
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.codestyle_after_comma
import dev.ide.ui.generated.resources.codestyle_after_comma_desc
import dev.ide.ui.generated.resources.codestyle_after_imports
import dev.ide.ui.generated.resources.codestyle_after_type_cast
import dev.ide.ui.generated.resources.codestyle_after_type_cast_desc
import dev.ide.ui.generated.resources.codestyle_around_lambda_arrow
import dev.ide.ui.generated.resources.codestyle_around_lambda_arrow_desc
import dev.ide.ui.generated.resources.codestyle_around_operators
import dev.ide.ui.generated.resources.codestyle_around_operators_desc
import dev.ide.ui.generated.resources.codestyle_around_ternary
import dev.ide.ui.generated.resources.codestyle_around_ternary_desc
import dev.ide.ui.generated.resources.codestyle_before_brace
import dev.ide.ui.generated.resources.codestyle_before_brace_desc
import dev.ide.ui.generated.resources.codestyle_before_field
import dev.ide.ui.generated.resources.codestyle_before_first_member
import dev.ide.ui.generated.resources.codestyle_before_method
import dev.ide.ui.generated.resources.codestyle_before_parens
import dev.ide.ui.generated.resources.codestyle_before_parens_desc
import dev.ide.ui.generated.resources.codestyle_before_semicolon
import dev.ide.ui.generated.resources.codestyle_before_semicolon_desc
import dev.ide.ui.generated.resources.codestyle_between_types
import dev.ide.ui.generated.resources.codestyle_binary_expressions
import dev.ide.ui.generated.resources.codestyle_blank_lines
import dev.ide.ui.generated.resources.codestyle_brace_placement
import dev.ide.ui.generated.resources.codestyle_brace_end_of_line
import dev.ide.ui.generated.resources.codestyle_brace_next_line
import dev.ide.ui.generated.resources.codestyle_chained_calls
import dev.ide.ui.generated.resources.codestyle_comments
import dev.ide.ui.generated.resources.codestyle_continuation_indent
import dev.ide.ui.generated.resources.codestyle_continuation_indent_desc
import dev.ide.ui.generated.resources.codestyle_format_comments
import dev.ide.ui.generated.resources.codestyle_format_comments_desc
import dev.ide.ui.generated.resources.codestyle_indent_size
import dev.ide.ui.generated.resources.codestyle_indent_size_desc
import dev.ide.ui.generated.resources.codestyle_indent_with_tabs
import dev.ide.ui.generated.resources.codestyle_indent_with_tabs_desc
import dev.ide.ui.generated.resources.codestyle_indentation
import dev.ide.ui.generated.resources.codestyle_keep_at_most
import dev.ide.ui.generated.resources.codestyle_keep_at_most_desc
import dev.ide.ui.generated.resources.codestyle_kotlin_note
import dev.ide.ui.generated.resources.codestyle_lang_java
import dev.ide.ui.generated.resources.codestyle_lang_kotlin
import dev.ide.ui.generated.resources.codestyle_language
import dev.ide.ui.generated.resources.codestyle_language_desc
import dev.ide.ui.generated.resources.codestyle_line_width
import dev.ide.ui.generated.resources.codestyle_line_width_desc
import dev.ide.ui.generated.resources.codestyle_method_arguments
import dev.ide.ui.generated.resources.codestyle_method_parameters
import dev.ide.ui.generated.resources.codestyle_preset_android
import dev.ide.ui.generated.resources.codestyle_preset_custom
import dev.ide.ui.generated.resources.codestyle_preset_google
import dev.ide.ui.generated.resources.codestyle_preset_kotlin_official
import dev.ide.ui.generated.resources.codestyle_preview
import dev.ide.ui.generated.resources.codestyle_preview_no_project
import dev.ide.ui.generated.resources.codestyle_reformat_on_save
import dev.ide.ui.generated.resources.codestyle_reformat_on_save_desc
import dev.ide.ui.generated.resources.codestyle_spaces
import dev.ide.ui.generated.resources.codestyle_style
import dev.ide.ui.generated.resources.codestyle_style_desc
import dev.ide.ui.generated.resources.codestyle_title
import dev.ide.ui.generated.resources.codestyle_within_parens
import dev.ide.ui.generated.resources.codestyle_within_parens_desc
import dev.ide.ui.generated.resources.codestyle_wrap_comments
import dev.ide.ui.generated.resources.codestyle_wrap_comments_desc
import dev.ide.ui.generated.resources.codestyle_wrap_if_long
import dev.ide.ui.generated.resources.codestyle_wrap_never
import dev.ide.ui.generated.resources.codestyle_wrap_one_per_line
import dev.ide.ui.generated.resources.codestyle_wrapping_and_braces
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

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
            IconButtonCa(CaIcons.chevronLeft, stringResource(Res.string.back), onBack, boxSize = iconBox)
            Text(stringResource(Res.string.codestyle_title), style = Ca.type.title3, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary, modifier = Modifier.weight(1f))
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsCard(null) {
                SettingsChoiceRow(
                    stringResource(Res.string.codestyle_language), stringResource(Res.string.codestyle_language_desc), language,
                    listOf(LANG_JAVA to stringResource(Res.string.codestyle_lang_java), LANG_KOTLIN to stringResource(Res.string.codestyle_lang_kotlin)),
                ) { language = it }
                SettingsDivider()
                SettingsChoiceRow(
                    stringResource(Res.string.codestyle_style), stringResource(Res.string.codestyle_style_desc), style.preset,
                    presetOptions(language),
                ) { picked -> update(if (picked == PRESET_CUSTOM) display.copy(preset = PRESET_CUSTOM) else presetDefaults(picked)) }
                SettingsDivider()
                SettingsToggleRow(stringResource(Res.string.codestyle_reformat_on_save), stringResource(Res.string.codestyle_reformat_on_save_desc), formatOnSave) {
                    formatOnSave = it
                    backend.settings.setPreference(FORMAT_ON_SAVE_KEY, it.toString())
                }
            }

            PreviewCard(preview, language, hasProject)

            if (!javaOnly) {
                Text(
                    stringResource(Res.string.codestyle_kotlin_note),
                    color = Ca.colors.textTertiary, style = Ca.type.caption2,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (custom) {
                SettingsCard(stringResource(Res.string.codestyle_indentation)) {
                    SettingsSliderRow(stringResource(Res.string.codestyle_indent_size), stringResource(Res.string.codestyle_indent_size_desc), display.indentSize, 1, 8, 1, null) { edit { copy(indentSize = it) } }
                    if (javaOnly) {
                        SettingsDivider()
                        SettingsSliderRow(stringResource(Res.string.codestyle_continuation_indent), stringResource(Res.string.codestyle_continuation_indent_desc), display.continuationIndent, 1, 16, 1, null) { edit { copy(continuationIndent = it) } }
                        SettingsDivider()
                        SettingsSliderRow(stringResource(Res.string.codestyle_line_width), stringResource(Res.string.codestyle_line_width_desc), display.maxLineLength, 60, 200, 10, null) { edit { copy(maxLineLength = it) } }
                    }
                    SettingsDivider()
                    SettingsToggleRow(stringResource(Res.string.codestyle_indent_with_tabs), stringResource(Res.string.codestyle_indent_with_tabs_desc), display.useTabs) { edit { copy(useTabs = it) } }
                }

                if (javaOnly) {
                    SettingsCard(stringResource(Res.string.codestyle_wrapping_and_braces)) {
                        SettingsChoiceRow(stringResource(Res.string.codestyle_brace_placement), null, display.braceStyle, listOf("endOfLine" to stringResource(Res.string.codestyle_brace_end_of_line), "nextLine" to stringResource(Res.string.codestyle_brace_next_line))) { edit { copy(braceStyle = it) } }
                        SettingsDivider()
                        SettingsChoiceRow(stringResource(Res.string.codestyle_method_parameters), null, display.wrapMethodParameters, wrapOptions()) { edit { copy(wrapMethodParameters = it) } }
                        SettingsDivider()
                        SettingsChoiceRow(stringResource(Res.string.codestyle_method_arguments), null, display.wrapMethodArguments, wrapOptions()) { edit { copy(wrapMethodArguments = it) } }
                        SettingsDivider()
                        SettingsChoiceRow(stringResource(Res.string.codestyle_chained_calls), null, display.wrapChainedCalls, wrapOptions()) { edit { copy(wrapChainedCalls = it) } }
                        SettingsDivider()
                        SettingsChoiceRow(stringResource(Res.string.codestyle_binary_expressions), null, display.wrapBinaryExpressions, wrapOptions()) { edit { copy(wrapBinaryExpressions = it) } }
                    }
                }

                // Inline spacing. The first five rules are honored by Kotlin too (token-level rewriting); the
                // rest need Java's full formatter, so they are Java-only.
                SettingsCard(stringResource(Res.string.codestyle_spaces)) {
                    SettingsToggleRow(stringResource(Res.string.codestyle_within_parens), stringResource(Res.string.codestyle_within_parens_desc), display.spaceWithinParens) { edit { copy(spaceWithinParens = it) } }
                    SettingsDivider()
                    SettingsToggleRow(stringResource(Res.string.codestyle_after_comma), stringResource(Res.string.codestyle_after_comma_desc), display.spaceAfterComma) { edit { copy(spaceAfterComma = it) } }
                    SettingsDivider()
                    SettingsToggleRow(stringResource(Res.string.codestyle_around_operators), stringResource(Res.string.codestyle_around_operators_desc), display.spaceAroundOperators) { edit { copy(spaceAroundOperators = it) } }
                    SettingsDivider()
                    SettingsToggleRow(stringResource(Res.string.codestyle_before_brace), stringResource(Res.string.codestyle_before_brace_desc), display.spaceBeforeBrace) { edit { copy(spaceBeforeBrace = it) } }
                    SettingsDivider()
                    SettingsToggleRow(stringResource(Res.string.codestyle_around_lambda_arrow), stringResource(Res.string.codestyle_around_lambda_arrow_desc), display.spaceAroundLambdaArrow) { edit { copy(spaceAroundLambdaArrow = it) } }
                    if (javaOnly) {
                        SettingsDivider()
                        SettingsToggleRow(stringResource(Res.string.codestyle_before_parens), stringResource(Res.string.codestyle_before_parens_desc), display.spaceBeforeParens) { edit { copy(spaceBeforeParens = it) } }
                        SettingsDivider()
                        SettingsToggleRow(stringResource(Res.string.codestyle_before_semicolon), stringResource(Res.string.codestyle_before_semicolon_desc), display.spaceBeforeSemicolon) { edit { copy(spaceBeforeSemicolon = it) } }
                        SettingsDivider()
                        SettingsToggleRow(stringResource(Res.string.codestyle_around_ternary), stringResource(Res.string.codestyle_around_ternary_desc), display.spaceAroundTernary) { edit { copy(spaceAroundTernary = it) } }
                        SettingsDivider()
                        SettingsToggleRow(stringResource(Res.string.codestyle_after_type_cast), stringResource(Res.string.codestyle_after_type_cast_desc), display.spaceAfterTypeCast) { edit { copy(spaceAfterTypeCast = it) } }
                    }
                }

                SettingsCard(stringResource(Res.string.codestyle_blank_lines)) {
                    SettingsSliderRow(stringResource(Res.string.codestyle_keep_at_most), stringResource(Res.string.codestyle_keep_at_most_desc), display.blankLinesToKeep, 0, 5, 1, null) { edit { copy(blankLinesToKeep = it) } }
                    if (javaOnly) {
                        SettingsDivider()
                        SettingsSliderRow(stringResource(Res.string.codestyle_after_imports), null, display.blankLinesAfterImports, 0, 5, 1, null) { edit { copy(blankLinesAfterImports = it) } }
                        SettingsDivider()
                        SettingsSliderRow(stringResource(Res.string.codestyle_before_method), null, display.blankLinesBeforeMethod, 0, 5, 1, null) { edit { copy(blankLinesBeforeMethod = it) } }
                        SettingsDivider()
                        SettingsSliderRow(stringResource(Res.string.codestyle_before_field), null, display.blankLinesBeforeField, 0, 5, 1, null) { edit { copy(blankLinesBeforeField = it) } }
                        SettingsDivider()
                        SettingsSliderRow(stringResource(Res.string.codestyle_before_first_member), null, display.blankLinesBeforeFirstMember, 0, 5, 1, null) { edit { copy(blankLinesBeforeFirstMember = it) } }
                        SettingsDivider()
                        SettingsSliderRow(stringResource(Res.string.codestyle_between_types), null, display.blankLinesBetweenTypes, 0, 5, 1, null) { edit { copy(blankLinesBetweenTypes = it) } }
                    }
                }

                if (javaOnly) {
                    SettingsCard(stringResource(Res.string.codestyle_comments)) {
                        SettingsToggleRow(stringResource(Res.string.codestyle_format_comments), stringResource(Res.string.codestyle_format_comments_desc), display.formatComments) { edit { copy(formatComments = it) } }
                        SettingsDivider()
                        SettingsToggleRow(stringResource(Res.string.codestyle_wrap_comments), stringResource(Res.string.codestyle_wrap_comments_desc), display.wrapComments) { edit { copy(wrapComments = it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(preview: String, language: String, hasProject: Boolean) {
    SettingsCard(stringResource(Res.string.codestyle_preview)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 320.dp)
                .background(Ca.colors.editorBg, RoundedCornerShape(Ca.radius.control))
                .verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            if (!hasProject) {
                Text(
                    stringResource(Res.string.codestyle_preview_no_project),
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

@Composable
private fun presetOptions(language: String): List<Pair<String, String>> = when (language) {
    LANG_KOTLIN -> listOf(
        "kotlin_official" to stringResource(Res.string.codestyle_preset_kotlin_official),
        "android" to stringResource(Res.string.codestyle_preset_android),
        PRESET_CUSTOM to stringResource(Res.string.codestyle_preset_custom),
    )
    else -> listOf(
        "google" to stringResource(Res.string.codestyle_preset_google),
        "android" to stringResource(Res.string.codestyle_preset_android),
        PRESET_CUSTOM to stringResource(Res.string.codestyle_preset_custom),
    )
}

@Composable
private fun wrapOptions(): List<Pair<String, String>> =
    listOf(
        "never" to stringResource(Res.string.codestyle_wrap_never),
        "ifLong" to stringResource(Res.string.codestyle_wrap_if_long),
        "onePerLine" to stringResource(Res.string.codestyle_wrap_one_per_line),
    )

/** What the controls display: a named preset shows its canonical values; Custom shows the stored fields. */
private fun displayStyle(style: UiCodeStyle): UiCodeStyle =
    if (style.preset == PRESET_CUSTOM) style else presetDefaults(style.preset)

/** The canonical values for a named preset (presets differ only in indentation; the rest are shared). */
private fun presetDefaults(preset: String): UiCodeStyle = when (preset) {
    "android" -> UiCodeStyle(preset = "android", indentSize = 4, continuationIndent = 8)
    "kotlin_official" -> UiCodeStyle(preset = "kotlin_official", indentSize = 4, continuationIndent = 8)
    else -> UiCodeStyle(preset = "google", indentSize = 2, continuationIndent = 4)
}
