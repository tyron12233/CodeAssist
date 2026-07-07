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
import dev.ide.ui.components.RunConflictDialog
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.settings_title
import dev.ide.ui.navigation.ScreenHost
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.screens.CreateProjectScreen
import dev.ide.ui.screens.CodeStyleScreen
import dev.ide.ui.screens.EditorScreen
import dev.ide.ui.screens.HomeScreen
import dev.ide.ui.screens.LearnScreen
import dev.ide.ui.screens.ProjectsStoreScreen
import dev.ide.ui.screens.KeystoreCreateScreen
import dev.ide.ui.screens.KeystoreImportScreen
import dev.ide.ui.screens.KeystoreManagerScreen
import dev.ide.ui.screens.ModuleConfigScreen
import dev.ide.ui.screens.ModulesTab
import dev.ide.ui.screens.ProjectPickerScreen
import dev.ide.ui.screens.RunScreen
import dev.ide.ui.screens.SdkManagerScreen
import dev.ide.ui.screens.SettingsHubScreen
import dev.ide.ui.screens.SettingsScreen
import dev.ide.ui.screens.SettingsView
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.rememberJetBrainsMono
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

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
    var settings by remember { mutableStateOf(backend.settings.settings()) }
    var screen by remember { mutableStateOf(Screen.Projects) }
    // The home screen's selected bottom-nav tab (project picker / store / learn). Lives here, not in the
    // per-project [IdeUiState], so it survives across the landing session and resets only on a full relaunch.
    var homeTab by remember { mutableStateOf(HomeTab.Projects) }
    // A template id to pre-select in the Create-Project flow when it's opened from a Store item (null = the
    // plain New-Project gallery).
    var createTemplateId by remember { mutableStateOf<String?>(null) }
    // The home-screen Projects Store + bottom nav is WIP and ships dark: it appears only when its feature flag
    // is on (or the `feature.projectsStore` preference overrides it). Off ⇒ the picker shows on its own.
    val storeEnabled = remember {
        backend.settings.preference("feature.projectsStore")?.toBooleanStrictOrNull() ?: FeatureFlags.PROJECTS_STORE
    }
    var configModule by remember { mutableStateOf<String?>(null) }
    var modulesTab by remember { mutableStateOf(ModulesTab.Settings) }
    var keystoreImportPath by remember { mutableStateOf<String?>(null) }
    // Where the Settings & Tools hub returns on Back — the picker (opened with no project) or the editor.
    var hubReturn by remember { mutableStateOf(Screen.Editor) }
    // Where the Keystore Manager returns on Back — the hub (its normal entry) or the module Signing tab (the
    // "manage keystores" cross-link from project signing).
    var keystoreReturn by remember { mutableStateOf(Screen.Hub) }
    // Whether the Keystore Manager was reached from a project context (the editor's hub or a module's Signing
    // tab) rather than the picker's hub. Gates the "Assign to a build" row — assignment is per-project, so it's
    // hidden when no project is open (and must never navigate into one). NOT `epoch > 0`: that stays true after
    // a project is closed back to the picker, which is exactly when the row must not show.
    var keystoreInProject by remember { mutableStateOf(false) }
    var showMigration by remember { mutableStateOf(backend.settings.preference("migration.acknowledged") != "true") }
    var showLegacyRecovery by remember { mutableStateOf(backend.settings.preference("legacy.recovery.seen") != "true") }
    var showOnboarding by remember { mutableStateOf(backend.settings.preference("onboarding.seen") != "true") }
    // Opt-in analytics: prompt only when collection is available and the user hasn't decided yet (null). The
    // re-toggle lives in the editor's More menu (a settings surface), not permanently on the project picker.
    var showAnalytics by remember { mutableStateOf(backend.diagnostics.analyticsAvailable() && backend.diagnostics.analyticsConsent() == null) }
    // Bumped after a project is deleted so the picker re-reads the (now-smaller) on-disk project list.
    var projectsRefresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // Create a project backup zip and hand it to the host's share/save sheet.
    val backupAndShare: suspend () -> Unit =
        { backend.projects.backupProjects()?.let { fileActions.share(it) } }

    // The active project changes (create/open) bump the epoch; re-key per-project state on it.
    val epoch by backend.projects.projectEpoch.collectAsState()
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
                delay(300.milliseconds)
                state.backend.projects.saveOpenTabs(state.tabsSnapshot())
            }
    }

    // Persist the file-tree expansion (debounced) so the tree reopens the same way next launch — keyed per
    // project + view mode. `drop(1)` skips the seeded initial state; `collectLatest` coalesces rapid toggles.
    LaunchedEffect(state) {
        snapshotFlow { state.treeMode to state.expandedTreeSnapshot() }.drop(1)
            .collectLatest { (mode, ids) ->
                delay(300.milliseconds)
                state.backend.files.saveExpandedTreeState(mode, ids.toList())
            }
    }
    // A successful create/open advances the epoch — land in the editor on the new project.
    LaunchedEffect(epoch) { if (epoch > 0) screen = Screen.Editor }

    // Starting a console run (a `run` task) opens a fresh interactive session — keyed on its id, jump to the
    // full-screen Run terminal. Build/assemble tasks leave runConsole null and stay in the build console.
    val runConsole by backend.build.runConsole.collectAsState()
    LaunchedEffect(runConsole?.id, epoch) {
        if (runConsole != null && screen == Screen.Editor) screen = Screen.Run
    }

    // External file writes (e.g. an "Open with" import the UI didn't drive) re-read the tree.
    val fsEpoch by backend.files.fileSystemEpoch.collectAsState()
    LaunchedEffect(state, fsEpoch) { if (fsEpoch > 0) state.refreshTree() }

    // Theme + accent + code font come from settings; the Settings screen (and the quick toggle) update them
    // live. "system" follows the OS dark-mode signal.
    val dark = when (settings.themeMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }



    val accent = when (settings.accent) {
        dev.ide.ui.backend.UiAccent.Teal -> CaAccent.Teal
        dev.ide.ui.backend.UiAccent.Orange -> CaAccent.Orange
        else -> CaAccent.Violet
    }
    val resolvedCodeFont = if (settings.codeFont == "monospace") FontFamily.Monospace else codeFont
    // Apply settings to the active project's live editor state whenever they change (or the project swaps).
    LaunchedEffect(state, settings) { state.applySettings(settings) }
    CodeAssistTheme(dark = dark, accent = accent, uiFont = uiFont, codeFont = resolvedCodeFont) {
        // Route the system back gesture through in-app navigation instead of letting it close the app (#997).
        // Registered above the editor's own overlay handler, so an open sheet/dialog is closed first (the
        // deeper handler wins); this one only fires for screen-level back: pop a sub-screen to the editor, the
        // editor to the project picker, or dismiss the first-launch sheets. On the picker it stays disabled so
        // back exits the app as usual.
        PlatformBackHandler(enabled = screen != Screen.Projects || homeTab != HomeTab.Projects || showOnboarding || showMigration || showAnalytics) {
            when {
                showOnboarding -> {
                    showOnboarding = false; backend.settings.setPreference("onboarding.seen", "true")
                }

                showMigration -> {
                    showMigration = false; backend.settings.setPreference("migration.acknowledged", "true")
                }

                showAnalytics -> {
                    showAnalytics = false; backend.diagnostics.setAnalyticsConsent(false)
                }

                // The keystore Create/Import sub-screens step back to their manager, not all the way out.
                screen == Screen.KeystoreCreate || screen == Screen.KeystoreImport -> screen = Screen.KeystoreManager
                // The hub's sub-screens step back to the hub; the keystore manager honours its entry origin.
                screen == Screen.SdkManager || screen == Screen.Settings || screen == Screen.CodeStyle -> screen = Screen.Hub
                screen == Screen.KeystoreManager -> screen = keystoreReturn
                // The hub returns to wherever it was opened from (picker or editor).
                screen == Screen.Hub -> screen = hubReturn

                screen == Screen.Run || screen == Screen.ModuleConfig -> screen = Screen.Editor

                screen == Screen.CreateProject -> screen = Screen.Projects
                screen == Screen.Editor -> screen = Screen.Projects
                // On the home screen, a Store/Learn tab steps back to the project picker before exiting.
                screen == Screen.Projects && homeTab != HomeTab.Projects -> homeTab = HomeTab.Projects
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
                            val projects = remember(epoch, projectsRefresh) { backend.projects.projects() }
                            val picker: @Composable () -> Unit = {
                                    ProjectPickerScreen(
                                        projects = projects,
                                        onOpen = { p ->
                                            scope.launch {
                                                if (backend.projects.openProject(p.rootPath)) screen = Screen.Editor
                                            }
                                        },
                                        onNewProject = { createTemplateId = null; screen = Screen.CreateProject },
                                        onDeleteProject = { p -> scope.launch { backend.projects.deleteProject(p.rootPath); projectsRefresh++ } },
                                        onBackup = { scope.launch { backupAndShare() } },
                                        onOpenHub = { hubReturn = Screen.Projects; screen = Screen.Hub },
                                        onSubmitSuggestions = if (fileActions.canOpenUrl) {
                                            { fileActions.openUrl(BetaInfo.FEEDBACK_URL) }
                                        } else null,
                                        onJoinDiscord = if (fileActions.canOpenUrl) {
                                            { fileActions.openUrl(BetaInfo.DISCORD_URL) }
                                        } else null,
                                        onSponsor = if (fileActions.canOpenUrl) {
                                            { fileActions.openUrl(BetaInfo.SPONSOR_URL) }
                                        } else null,
                                        onStarOnGitHub = if (fileActions.canOpenUrl) {
                                            { fileActions.openUrl(BetaInfo.REPO_URL) }
                                        } else null,
                                        storagePath = backend.projects.storageRootPath(),
                                        onOpenInFiles = if (fileActions.canReveal) {
                                            { backend.projects.storageRootPath()?.let { fileActions.reveal(it) } }
                                        } else null,
                                        showLegacyRecovery = showLegacyRecovery,
                                        onDismissLegacyRecovery = {
                                            showLegacyRecovery = false
                                            backend.settings.setPreference("legacy.recovery.seen", "true")
                                        },
                                        loadIcon = { backend.projects.projectIcon(it.rootPath) },
                                    )
                            }
                            // Store + Learn tabs only when the WIP flag is on; otherwise the picker stands alone.
                            if (storeEnabled) {
                                HomeScreen(
                                    tab = homeTab,
                                    onSelectTab = { homeTab = it },
                                    projectsContent = picker,
                                    storeContent = {
                                        ProjectsStoreScreen(
                                            backend = backend,
                                            onCreateFromTemplate = { id -> createTemplateId = id; screen = Screen.CreateProject },
                                            onOpenHub = { hubReturn = Screen.Projects; screen = Screen.Hub },
                                        )
                                    },
                                    learnContent = {
                                        LearnScreen(
                                            onOpenDocs = if (fileActions.canOpenUrl) {
                                                { fileActions.openUrl(BetaInfo.REPO_URL) }
                                            } else null,
                                            onJoinDiscord = if (fileActions.canOpenUrl) {
                                                { fileActions.openUrl(BetaInfo.DISCORD_URL) }
                                            } else null,
                                            onBrowseSamples = { homeTab = HomeTab.Store },
                                        )
                                    },
                                )
                            } else {
                                picker()
                            }
                        }

                        Screen.CreateProject -> CreateProjectScreen(
                            backend = backend,
                            onCancel = { screen = Screen.Projects },
                            onCreated = { screen = Screen.Editor },
                            initialTemplateId = createTemplateId,
                        )

                        Screen.Editor -> EditorScreen(
                            state = state,
                            onToggleTheme = {
                                // Quick toggle flips to the opposite of what's shown (an explicit light/dark,
                                // stepping out of "system" if that was active).
                                backend.settings.setSetting("appearance", "themeMode", if (dark) "light" else "dark")
                                settings = backend.settings.settings()
                            },
                            onOpenHub = { hubReturn = Screen.Editor; screen = Screen.Hub },
                            onOpenDependencies = { module ->
                                configModule = module; modulesTab =
                                ModulesTab.Dependencies; screen = Screen.ModuleConfig
                            },
                            onOpenModuleConfig = { module ->
                                configModule = module; modulesTab = ModulesTab.Settings; screen =
                                Screen.ModuleConfig
                            },
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
                            onOpenKeystoreManager = { keystoreReturn = Screen.ModuleConfig; keystoreInProject = true; screen = Screen.KeystoreManager },
                            codeFont = codeFont,
                            fileActions = fileActions,
                        )

                        Screen.SdkManager -> SdkManagerScreen(
                            backend = state.backend,
                            onBack = { screen = Screen.Hub },
                        )

                        Screen.CodeStyle -> CodeStyleScreen(
                            backend = state.backend,
                            // The live formatter preview is engine-backed: available when the hub (hence Code
                            // Style) was opened from the editor, not from the project picker.
                            hasProject = hubReturn == Screen.Editor,
                            onBack = { screen = Screen.Hub },
                        )

                        Screen.KeystoreManager -> KeystoreManagerScreen(
                            backend = state.backend,
                            onBack = { screen = keystoreReturn },
                            onCreate = { screen = Screen.KeystoreCreate },
                            onImport = { path -> keystoreImportPath = path; screen = Screen.KeystoreImport },
                            // Signing assignment is per-project — only offered when the manager was opened from a
                            // project context (the editor's hub or a module's Signing tab), never from the picker's
                            // hub. So it stays hidden with no project open and can't navigate into one.
                            onManageSigning = if (keystoreInProject) ({
                                // Smart jump: one android-app → straight to its Signing tab; otherwise the module list.
                                configModule = state.backend.signing.signableModules().singleOrNull()
                                modulesTab = ModulesTab.Signing
                                screen = Screen.ModuleConfig
                            }) else null,
                            fileActions = fileActions,
                        )

                        Screen.KeystoreCreate -> KeystoreCreateScreen(
                            backend = state.backend,
                            onBack = { screen = Screen.KeystoreManager },
                            onDone = { screen = Screen.KeystoreManager },
                        )

                        Screen.KeystoreImport -> {
                            val path = keystoreImportPath
                            if (path == null) {
                                screen = Screen.KeystoreManager
                            } else {
                                KeystoreImportScreen(
                                    backend = state.backend,
                                    path = path,
                                    onBack = { screen = Screen.KeystoreManager },
                                    onDone = { screen = Screen.KeystoreManager },
                                )
                            }
                        }

                        Screen.Hub -> SettingsHubScreen(
                            onBack = { screen = hubReturn },
                            onOpenGlobalSettings = { screen = Screen.Settings },
                            onOpenCodeStyle = { screen = Screen.CodeStyle },
                            onOpenSdkManager = { screen = Screen.SdkManager },
                            // The hub reached from the editor is a project context; from the picker it isn't.
                            onOpenKeystoreManager = { keystoreReturn = Screen.Hub; keystoreInProject = hubReturn == Screen.Editor; screen = Screen.KeystoreManager },
                        )

                        // Settings — reached from the hub. With a project open (hub entered from the editor) the
                        // project-scoped pages (dependency conflicts, inspections) merge in; from the picker only
                        // the global app pages show.
                        Screen.Settings -> SettingsScreen(
                            backend = state.backend,
                            onBack = { screen = Screen.Hub },
                            onSettingsChanged = { settings = backend.settings.settings() },
                            // The logs viewer is an editor overlay; only meaningful with a project open.
                            onOpenLogs = { if (epoch > 0) { state.logsOpen = true; screen = Screen.Editor } },
                            view = if (hubReturn == Screen.Editor) SettingsView.All else SettingsView.Global,
                            title = stringResource(Res.string.settings_title),
                            codeFont = codeFont,
                            fileActions = fileActions,
                        )
                    }
                }
            }
            // Upgrade notice first (the build-system migration warning), then the feature tour — both over
            // the picker only, one at a time.
            MigrationNotice(
                visible = showMigration && screen == Screen.Projects && homeTab == HomeTab.Projects,
                onBackup = backupAndShare,
                onDismiss = {
                    showMigration = false
                    backend.settings.setPreference("migration.acknowledged", "true")
                },
            )
            OnboardingSheet(
                visible = showOnboarding && !showMigration && screen == Screen.Projects && homeTab == HomeTab.Projects,
                // Final CTA: send the user straight into the Create-Project flow (the same screen the picker's
                // "New Project" card opens) so the tour ends on a concrete action.
                onGetStarted = { screen = Screen.CreateProject },
                onFinish = {
                    showOnboarding = false
                    backend.settings.setPreference("onboarding.seen", "true")
                },
            )
            // Opt-in analytics consent — last of the first-launch sheets, after onboarding/migration.
            AnalyticsConsentSheet(
                visible = showAnalytics && !showOnboarding && !showMigration && screen == Screen.Projects && homeTab == HomeTab.Projects,
                onAllow = { showAnalytics = false; backend.diagnostics.setAnalyticsConsent(true) },
                onDecline = { showAnalytics = false; backend.diagnostics.setAnalyticsConsent(false) },
                onLearnMore = if (fileActions.canOpenUrl) {
                    { fileActions.openUrl(BetaInfo.PRIVACY_URL) }
                } else null,
            )
            // The run sandbox's permission prompt — overlays everything while a guarded program is blocked.
            PermissionDialog(backend)
            // "Already running" confirmation — raised when a new Run is requested while a build/program is
            // still in flight (e.g. a runaway loop); offers Stop-and-Run with a remembered choice.
            RunConflictDialog(state)
            // IntelliJ-style non-fatal error dialog — overlays everything when the engine reports an
            // unexpected error or an uncaught exception is intercepted (the app keeps running).
            ErrorDialog(backend)
        }
    }
}
