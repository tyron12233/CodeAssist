package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import dev.ide.ui.backend.UiSettingControl
import dev.ide.ui.backend.UiSettingsPage
import dev.ide.ui.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A localization overlay for the built-in Settings pages. Their titles/descriptions/option labels are declared
 * in `ide-core` ([dev.ide.core.settings.BuiltInSettingsPages] + `SettingsBackend`) as plain English — `ide-core`
 * is the pure-JVM engine and can't reach Compose resources. Rather than translate there, the UI maps a built-in
 * page id / control key to a string resource here and resolves it at render time.
 *
 * The overlay is an OVERRIDE: where a resource exists it wins, otherwise the backend-supplied string is used
 * verbatim. That keeps plugin-contributed pages working (unknown ids fall through), and lets the device-specific
 * Build Runtime descriptions (they interpolate this device's heap sizes) stay backend-authored while their
 * static titles/options still localize.
 *
 * Keys mirror the [dev.ide.core.settings.BuiltInSettingsPages] constants; option keys append the option value.
 */

// Page title, by page id.
private val PAGE_TITLE: Map<String, StringResource> = mapOf(
    "appearance" to Res.string.set_page_appearance,
    "editor" to Res.string.set_page_editor,
    "completion" to Res.string.set_page_completion,
    "analysis" to Res.string.set_page_analysis,
    "preview" to Res.string.set_page_preview,
    "build" to Res.string.set_page_build,
    "buildRuntime" to Res.string.set_page_build_runtime,
    "privacy" to Res.string.set_page_privacy,
)

// Control title, keyed "pageId.controlKey".
private val CONTROL_TITLE: Map<String, StringResource> = mapOf(
    "appearance.themeMode" to Res.string.set_appearance_theme,
    "appearance.accent" to Res.string.set_appearance_accent,
    "editor.fontScale" to Res.string.set_editor_font_size,
    "editor.codeFont" to Res.string.set_editor_code_font,
    "editor.fontLigatures" to Res.string.set_editor_ligatures,
    "editor.inlayHints" to Res.string.set_editor_inlay,
    "editor.semanticHighlighting" to Res.string.set_editor_semantic,
    "editor.codeFolding" to Res.string.set_editor_folding,
    "editor.wordWrap" to Res.string.set_editor_wrap,
    "editor.wrapIndent" to Res.string.set_editor_wrap_indent,
    "editor.twoAxisScroll" to Res.string.set_editor_two_axis,
    "editor.pinchZoom" to Res.string.set_editor_pinch,
    "editor.softKeyboardSuggestions" to Res.string.set_editor_kbd_suggest,
    "completion.autoPopup" to Res.string.set_completion_auto,
    "completion.postfixTemplates" to Res.string.set_completion_postfix,
    "completion.wordCompletion" to Res.string.set_completion_word,
    "completion.delayMs" to Res.string.set_completion_delay,
    "completion.maxItems" to Res.string.set_completion_max,
    "analysis.onTheFly" to Res.string.set_analysis_otf,
    "analysis.reparseDelayMs" to Res.string.set_analysis_reparse,
    "analysis.perfLogging" to Res.string.set_analysis_perf,
    "preview.sandboxFileIo" to Res.string.set_preview_file,
    "preview.sandboxNetwork" to Res.string.set_preview_net,
    "preview.sandboxAndroidSystem" to Res.string.set_preview_android,
    "preview.sandboxProcessControl" to Res.string.set_preview_process,
    "build.conflictPolicy" to Res.string.set_build_conflict,
    "buildRuntime.separateProcess" to Res.string.set_br_separate,
    "buildRuntime.buildNotifications" to Res.string.set_br_notif,
    "buildRuntime.r8Mode" to Res.string.set_br_r8mode,
    "buildRuntime.r8MaxHeapMb" to Res.string.set_br_r8heap,
    "buildRuntime.dexOffHeapMb" to Res.string.set_br_dexoff,
    "buildRuntime.dexMergeBatch" to Res.string.set_br_dexbatch,
    "buildRuntime.dexForkConcurrency" to Res.string.set_br_dexfork,
    "privacy.analytics" to Res.string.set_privacy_analytics,
    "privacy.clearCaches" to Res.string.set_privacy_clear,
    "privacy.viewLogs" to Res.string.set_privacy_logs,
    "privacy.backup" to Res.string.set_privacy_backup,
)

// Control description, keyed "pageId.controlKey" (only where the copy is static — device-specific Build Runtime
// descriptions are intentionally absent so they fall back to the backend-authored, interpolated text).
private val CONTROL_DESC: Map<String, StringResource> = mapOf(
    "appearance.themeMode" to Res.string.set_appearance_theme_desc,
    "appearance.accent" to Res.string.set_appearance_accent_desc,
    "editor.fontLigatures" to Res.string.set_editor_ligatures_desc,
    "editor.inlayHints" to Res.string.set_editor_inlay_desc,
    "editor.semanticHighlighting" to Res.string.set_editor_semantic_desc,
    "editor.codeFolding" to Res.string.set_editor_folding_desc,
    "editor.wordWrap" to Res.string.set_editor_wrap_desc,
    "editor.wrapIndent" to Res.string.set_editor_wrap_indent_desc,
    "editor.twoAxisScroll" to Res.string.set_editor_two_axis_desc,
    "editor.pinchZoom" to Res.string.set_editor_pinch_desc,
    "editor.softKeyboardSuggestions" to Res.string.set_editor_kbd_suggest_desc,
    "completion.autoPopup" to Res.string.set_completion_auto_desc,
    "completion.postfixTemplates" to Res.string.set_completion_postfix_desc,
    "completion.wordCompletion" to Res.string.set_completion_word_desc,
    "completion.delayMs" to Res.string.set_completion_delay_desc,
    "analysis.onTheFly" to Res.string.set_analysis_otf_desc,
    "analysis.reparseDelayMs" to Res.string.set_analysis_reparse_desc,
    "analysis.perfLogging" to Res.string.set_analysis_perf_desc,
    "preview.sandboxFileIo" to Res.string.set_preview_file_desc,
    "preview.sandboxNetwork" to Res.string.set_preview_net_desc,
    "preview.sandboxAndroidSystem" to Res.string.set_preview_android_desc,
    "preview.sandboxProcessControl" to Res.string.set_preview_process_desc,
    "build.conflictPolicy" to Res.string.set_build_conflict_desc,
    "buildRuntime.separateProcess" to Res.string.set_br_separate_desc,
    "buildRuntime.buildNotifications" to Res.string.set_br_notif_desc,
    "buildRuntime.dexMergeBatch" to Res.string.set_br_dexbatch_desc,
    "privacy.analytics" to Res.string.set_privacy_analytics_desc,
    "privacy.clearCaches" to Res.string.set_privacy_clear_desc,
    "privacy.viewLogs" to Res.string.set_privacy_logs_desc,
    "privacy.backup" to Res.string.set_privacy_backup_desc,
)

// Choice option label, keyed "pageId.controlKey.optionValue".
private val OPTION_LABEL: Map<String, StringResource> = mapOf(
    "appearance.themeMode.light" to Res.string.set_appearance_theme_light,
    "appearance.themeMode.dark" to Res.string.set_appearance_theme_dark,
    "appearance.themeMode.system" to Res.string.set_appearance_theme_system,
    "appearance.accent.violet" to Res.string.set_appearance_accent_violet,
    "appearance.accent.teal" to Res.string.set_appearance_accent_teal,
    "appearance.accent.orange" to Res.string.set_appearance_accent_orange,
    "editor.codeFont.monospace" to Res.string.set_editor_code_font_monospace,
    "build.conflictPolicy.newest" to Res.string.set_build_conflict_newest,
    "build.conflictPolicy.pinned" to Res.string.set_build_conflict_pinned,
    "build.conflictPolicy.failOnConflict" to Res.string.set_build_conflict_fail,
    "buildRuntime.r8Mode.forked" to Res.string.set_br_r8mode_forked,
    "buildRuntime.r8Mode.inprocess" to Res.string.set_br_r8mode_inprocess,
)

// Action button label, keyed "pageId.controlKey".
private val ACTION_BUTTON: Map<String, StringResource> = mapOf(
    "buildRuntime.buildNotifications" to Res.string.set_br_notif_btn,
    "privacy.clearCaches" to Res.string.set_privacy_clear_btn,
    "privacy.viewLogs" to Res.string.set_privacy_logs_btn,
    "privacy.backup" to Res.string.set_privacy_backup_btn,
)

// Group sub-heading, keyed by the raw group name the backend emits.
private val GROUP_TITLE: Map<String, StringResource> = mapOf(
    "Gestures" to Res.string.set_grp_gestures,
    "Keyboard" to Res.string.set_grp_keyboard,
    "Preview sandbox" to Res.string.set_grp_preview_sandbox,
    "Privacy" to Res.string.set_grp_privacy,
    "Storage" to Res.string.set_grp_storage,
    "Debug build (dexing)" to Res.string.set_grp_debug_dexing,
)

// Slider unit words (symbol units like %, ms, MB pass through untranslated).
private val UNIT_WORD: Map<String, StringResource> = mapOf(
    "classes" to Res.string.set_unit_classes,
)

/** The localized page title for a built-in page; the backend title for a plugin page. */
@Composable
fun localizedPageTitle(page: UiSettingsPage): String =
    PAGE_TITLE[page.id]?.let { stringResource(it) } ?: page.title

/** The localized control title, falling back to the backend-supplied title. */
@Composable
fun localizedControlTitle(pageId: String, c: UiSettingControl): String =
    CONTROL_TITLE["$pageId.${c.key}"]?.let { stringResource(it) } ?: c.title

/** The localized control description, falling back to the backend-supplied description (may be null). */
@Composable
fun localizedControlDescription(pageId: String, c: UiSettingControl): String? =
    CONTROL_DESC["$pageId.${c.key}"]?.let { stringResource(it) } ?: c.description

/** The localized label for one choice option, falling back to the backend-supplied label. */
@Composable
fun localizedOptionLabel(pageId: String, key: String, option: UiSettingControl.Choice.Option): String =
    OPTION_LABEL["$pageId.$key.${option.value}"]?.let { stringResource(it) } ?: option.label

/** The localized action button label, falling back to the backend-supplied label. */
@Composable
fun localizedActionButton(pageId: String, c: UiSettingControl.Action): String =
    ACTION_BUTTON["$pageId.${c.key}"]?.let { stringResource(it) } ?: c.buttonLabel

/** The localized group sub-heading, falling back to the raw group name (null stays null). */
@Composable
fun localizedGroup(group: String?): String? =
    group?.let { GROUP_TITLE[it]?.let { r -> stringResource(r) } ?: it }

/** The localized slider unit, falling back to the raw unit (symbol units pass through; null stays null). */
@Composable
fun localizedUnit(unit: String?): String? =
    unit?.let { UNIT_WORD[it]?.let { r -> stringResource(r) } ?: it }
