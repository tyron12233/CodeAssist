package dev.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.components.AnalyticsConsentSheet
import dev.ide.ui.components.BetaInfo
import dev.ide.ui.components.ErrorDialog
import dev.ide.ui.components.MigrationNotice
import dev.ide.ui.components.OnboardingSheet
import dev.ide.ui.components.PermissionDialog
import dev.ide.ui.navigation.ScreenHost
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.screens.CreateProjectScreen
import dev.ide.ui.screens.EditorScreen
import dev.ide.ui.screens.ModuleConfigScreen
import dev.ide.ui.screens.ModulesTab
import dev.ide.ui.screens.ProjectPickerScreen
import dev.ide.ui.screens.RunScreen
import dev.ide.ui.screens.SdkManagerScreen
import dev.ide.ui.screens.SettingsScreen
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.rememberJetBrainsMono
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Root of the reusable IDE UI. Hosts pick the toolkit (Compose Desktop window, Android activity) and
 * supply an [IdeBackend] plus optional brand fonts and a [FileActions] bridge; the screens, theme,
 * navigation, and state are shared.
 *
 * Screens transition with a platform-differentiated feel ([ScreenHost]); the active project's UI state
 * is re-keyed on [IdeBackend.projectEpoch] so creating/opening a project rebuilds the tree + tabs. A
 * first-launch [OnboardingSheet] introduces the IDE over the picker.
 */
@Composable
fun CodeAssistApp(
    backend: IdeBackend,
    uiFont: FontFamily = FontFamily.SansSerif,
    codeFont: FontFamily = rememberJetBrainsMono(),
    fileActions: FileActions = FileActions.None,
    composePreviewHost: ComposePreviewHost? = null,
) {
    // Persisted IDE settings drive the theme (and seed the editor's live prefs). Re-read after the Settings
    // screen writes; appearance changes then take effect immediately.
    var settings by remember { mutableStateOf(backend.settings()) }
    var screen by remember { mutableStateOf(Screen.Projects) }
    var configModule by remember { mutableStateOf<String?>(null) }
    var modulesTab by remember { mutableStateOf(ModulesTab.Settings) }
    var showMigration by remember { mutableStateOf(backend.preference("migration.acknowledged") != "true") }
    var showLegacyRecovery by remember { mutableStateOf(backend.preference("legacy.recovery.seen") != "true") }
    var showOnboarding by remember { mutableStateOf(backend.preference("onboarding.seen") != "true") }
    // Opt-in analytics: prompt only when collection is available and the user hasn't decided yet (null). The
    // re-toggle lives in the editor's More menu (a settings surface), not permanently on the project picker.
    var showAnalytics by remember { mutableStateOf(backend.analyticsAvailable() && backend.analyticsConsent() == null) }
    // Bumped after a project is deleted so the picker re-reads the (now-smaller) on-disk project list.
    var projectsRefresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // Create a project backup zip and hand it to the host's share/save sheet.
    val backupAndShare: suspend () -> Unit =
        { backend.backupProjects()?.let { fileActions.share(it) } }

    // The active project changes (create/open) bump the epoch; re-key per-project state on it.
    val epoch by backend.projectEpoch.collectAsState()
    val state = remember(backend, epoch) { IdeUiState(backend, composePreviewHost) }

    // Reopen the tabs from the last session with this project; if there were none, land on a sensible first
    // file so entering the editor shows real code. Then persist tab changes (debounced) so they reopen next
    // launch — `drop(1)` skips the just-restored state, and `collectLatest` cancels the pending write when
    // another tab change lands within the debounce window.
    LaunchedEffect(state) {
        if (!state.restoreTabs()) {
            state.defaultFile()?.let { node -> node.filePath?.let { state.open(it, node.name) } }
        }
        snapshotFlow { state.openFiles.map { it.path } to state.activeIndex }.drop(1)
            .collectLatest {
                delay(300)
                state.backend.saveOpenTabs(state.tabsSnapshot())
            }
    }
    // A successful create/open advances the epoch — land in the editor on the new project.
    LaunchedEffect(epoch) { if (epoch > 0) screen = Screen.Editor }

    // Starting a console run (a `run` task) opens a fresh interactive session — keyed on its id, jump to the
    // full-screen Run terminal. Build/assemble tasks leave runConsole null and stay in the build console.
    val runConsole by backend.runConsole.collectAsState()
    LaunchedEffect(runConsole?.id, epoch) {
        if (runConsole != null && screen == Screen.Editor) screen = Screen.Run
    }

    // External file writes (e.g. an "Open with" import the UI didn't drive) re-read the tree.
    val fsEpoch by backend.fileSystemEpoch.collectAsState()
    LaunchedEffect(state, fsEpoch) { if (fsEpoch > 0) state.refreshTree() }

    // Theme + accent + code font come from settings; the Settings screen (and the quick toggle) update them
    // live. "system" follows the OS dark-mode signal.
    val dark = when (settings.themeMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val accent = if (settings.accent == dev.ide.ui.backend.UiAccent.Teal) CaAccent.Teal else CaAccent.Violet
    val resolvedCodeFont = if (settings.codeFont == "monospace") FontFamily.Monospace else codeFont
    // Apply settings to the active project's live editor state whenever they change (or the project swaps).
    LaunchedEffect(state, settings) { state.applySettings(settings) }
    CodeAssistTheme(dark = dark, accent = accent, uiFont = uiFont, codeFont = resolvedCodeFont) {
        // Route the system back gesture through in-app navigation instead of letting it close the app (#997).
        // Registered above the editor's own overlay handler, so an open sheet/dialog is closed first (the
        // deeper handler wins); this one only fires for screen-level back: pop a sub-screen to the editor, the
        // editor to the project picker, or dismiss the first-launch sheets. On the picker it stays disabled so
        // back exits the app as usual.
        PlatformBackHandler(enabled = screen != Screen.Projects || showOnboarding || showMigration || showAnalytics) {
            when {
                showOnboarding -> {
                    showOnboarding = false; backend.setPreference("onboarding.seen", "true")
                }

                showMigration -> {
                    showMigration = false; backend.setPreference("migration.acknowledged", "true")
                }

                showAnalytics -> {
                    showAnalytics = false; backend.setAnalyticsConsent(false)
                }

                screen == Screen.Run || screen == Screen.ModuleConfig || screen == Screen.SdkManager || screen == Screen.Settings -> screen =
                    Screen.Editor

                screen == Screen.CreateProject -> screen = Screen.Projects
                screen == Screen.Editor -> screen = Screen.Projects
                else -> {}
            }
        }
        // The brand background fills the whole window edge-to-edge (behind the system bars); content is
        // then inset by `safeDrawing`. On desktop these insets are empty, so this is a no-op there.
        Box(Modifier.fillMaxSize().background(Ca.colors.bg)) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                ScreenHost(screen, Modifier.fillMaxSize()) { s ->
                    when (s) {
                        Screen.Projects -> {
                            val projects = remember(epoch, projectsRefresh) { backend.projects() }
                            ProjectPickerScreen(
                                projects = projects,
                                onOpen = { p ->
                                    scope.launch {
                                        if (backend.openProject(p.rootPath)) screen = Screen.Editor
                                    }
                                },
                                onNewProject = { screen = Screen.CreateProject },
                                onDeleteProject = { p -> scope.launch { backend.deleteProject(p.rootPath); projectsRefresh++ } },
                                onBackup = { scope.launch { backupAndShare() } },
                                onSubmitSuggestions = if (fileActions.canOpenUrl) {
                                    { fileActions.openUrl(BetaInfo.FEEDBACK_URL) }
                                } else null,
                                onJoinDiscord = if (fileActions.canOpenUrl) {
                                    { fileActions.openUrl(BetaInfo.DISCORD_URL) }
                                } else null,
                                storagePath = backend.storageRootPath(),
                                onOpenInFiles = if (fileActions.canReveal) {
                                    { backend.storageRootPath()?.let { fileActions.reveal(it) } }
                                } else null,
                                showLegacyRecovery = showLegacyRecovery,
                                onDismissLegacyRecovery = {
                                    showLegacyRecovery = false
                                    backend.setPreference("legacy.recovery.seen", "true")
                                },
                            )
                        }

                        Screen.CreateProject -> CreateProjectScreen(
                            backend = backend,
                            onCancel = { screen = Screen.Projects },
                            onCreated = { screen = Screen.Editor },
                        )

                        Screen.Editor -> EditorScreen(
                            state = state,
                            onToggleTheme = {
                                // Quick toggle flips to the opposite of what's shown (an explicit light/dark,
                                // stepping out of "system" if that was active).
                                backend.setSetting("appearance", "themeMode", if (dark) "light" else "dark")
                                settings = backend.settings()
                            },
                            onOpenSettings = { screen = Screen.Settings },
                            onOpenDependencies = { module ->
                                configModule = module; modulesTab =
                                ModulesTab.Dependencies; screen = Screen.ModuleConfig
                            },
                            onOpenModuleConfig = { module ->
                                configModule = module; modulesTab = ModulesTab.Settings; screen =
                                Screen.ModuleConfig
                            },
                            onOpenSdkManager = { screen = Screen.SdkManager },
                            onCloseProject = { screen = Screen.Projects },
                            onOpenRun = { screen = Screen.Run },
                            fileActions = fileActions,
                        )

                        Screen.Run -> RunScreen(
                            backend = state.backend,
                            onBack = { screen = Screen.Editor },
                            onOpenDiagnostic = { d ->
                                d.file?.let { state.openAtLine(it, d.line, d.column); screen = Screen.Editor }
                            },
                        )

                        Screen.ModuleConfig -> ModuleConfigScreen(
                            backend = state.backend,
                            initialModule = configModule,
                            initialTab = modulesTab,
                            onBack = { screen = Screen.Editor },
                            codeFont = codeFont,
                            fileActions = fileActions,
                        )

                        Screen.SdkManager -> SdkManagerScreen(
                            backend = state.backend,
                            onBack = { screen = Screen.Editor },
                        )

                        Screen.Settings -> SettingsScreen(
                            backend = state.backend,
                            onBack = { screen = Screen.Editor },
                            onSettingsChanged = { settings = backend.settings() },
                            onOpenLogs = { state.logsOpen = true; screen = Screen.Editor },
                            codeFont = codeFont,
                            fileActions = fileActions,
                        )
                    }
                }
            }
            // Upgrade notice first (the build-system migration warning), then the feature tour — both over
            // the picker only, one at a time.
            MigrationNotice(
                visible = showMigration && screen == Screen.Projects,
                onBackup = backupAndShare,
                onDismiss = {
                    showMigration = false
                    backend.setPreference("migration.acknowledged", "true")
                },
            )
            OnboardingSheet(
                visible = showOnboarding && !showMigration && screen == Screen.Projects,
                // Final CTA: send the user straight into the Create-Project flow (the same screen the picker's
                // "New Project" card opens) so the tour ends on a concrete action.
                onGetStarted = { screen = Screen.CreateProject },
                onFinish = {
                    showOnboarding = false
                    backend.setPreference("onboarding.seen", "true")
                },
            )
            // Opt-in analytics consent — last of the first-launch sheets, after onboarding/migration.
            AnalyticsConsentSheet(
                visible = showAnalytics && !showOnboarding && !showMigration && screen == Screen.Projects,
                onAllow = { showAnalytics = false; backend.setAnalyticsConsent(true) },
                onDecline = { showAnalytics = false; backend.setAnalyticsConsent(false) },
                onLearnMore = if (fileActions.canOpenUrl) {
                    { fileActions.openUrl(BetaInfo.PRIVACY_URL) }
                } else null,
            )
            // The run sandbox's permission prompt — overlays everything while a guarded program is blocked.
            PermissionDialog(backend)
            // IntelliJ-style non-fatal error dialog — overlays everything when the engine reports an
            // unexpected error or an uncaught exception is intercepted (the app keeps running).
            ErrorDialog(backend)
        }
    }
}
