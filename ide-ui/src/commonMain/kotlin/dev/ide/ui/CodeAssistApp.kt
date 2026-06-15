package dev.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.components.BetaInfo
import dev.ide.ui.components.MigrationNotice
import dev.ide.ui.components.OnboardingSheet
import dev.ide.ui.components.PermissionDialog
import dev.ide.ui.navigation.ScreenHost
import dev.ide.ui.screens.CreateProjectScreen
import dev.ide.ui.screens.DependenciesScreen
import dev.ide.ui.screens.EditorScreen
import dev.ide.ui.screens.ModuleConfigScreen
import dev.ide.ui.screens.ProjectPickerScreen
import dev.ide.ui.screens.SdkManagerScreen
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
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
    codeFont: FontFamily = FontFamily.Monospace,
    fileActions: FileActions = FileActions.None,
) {
    var dark by remember { mutableStateOf(true) }
    var screen by remember { mutableStateOf(Screen.Projects) }
    var depsModule by remember { mutableStateOf<String?>(null) }
    var configModule by remember { mutableStateOf<String?>(null) }
    var showMigration by remember { mutableStateOf(backend.preference("migration.acknowledged") != "true") }
    var showOnboarding by remember { mutableStateOf(backend.preference("onboarding.seen") != "true") }
    // Bumped after a project is deleted so the picker re-reads the (now-smaller) on-disk project list.
    var projectsRefresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // Create a project backup zip and hand it to the host's share/save sheet.
    val backupAndShare: suspend () -> Unit = { backend.backupProjects()?.let { fileActions.share(it) } }

    // The active project changes (create/open) bump the epoch; re-key per-project state on it.
    val epoch by backend.projectEpoch.collectAsState()
    val state = remember(backend, epoch) { IdeUiState(backend) }

    // Open a sensible first file once per project, so entering the editor lands on real code.
    LaunchedEffect(state) {
        state.defaultFile()?.let { node -> node.filePath?.let { state.open(it, node.name) } }
    }
    // A successful create/open advances the epoch — land in the editor on the new project.
    LaunchedEffect(epoch) { if (epoch > 0) screen = Screen.Editor }

    // External file writes (e.g. an "Open with" import the UI didn't drive) re-read the tree.
    val fsEpoch by backend.fileSystemEpoch.collectAsState()
    LaunchedEffect(state, fsEpoch) { if (fsEpoch > 0) state.refreshTree() }

    // Accent is fixed to violet (the brand accent).
    CodeAssistTheme(dark = dark, accent = CaAccent.Violet, uiFont = uiFont, codeFont = codeFont) {
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
                                onOpen = { p -> scope.launch { backend.openProject(p.rootPath); screen = Screen.Editor } },
                                onNewProject = { screen = Screen.CreateProject },
                                onDeleteProject = { p -> scope.launch { backend.deleteProject(p.rootPath); projectsRefresh++ } },
                                onBackup = { scope.launch { backupAndShare() } },
                                onSubmitSuggestions = if (fileActions.canOpenUrl) {
                                    { fileActions.openUrl(BetaInfo.FEEDBACK_URL) }
                                } else null,
                            )
                        }
                        Screen.CreateProject -> CreateProjectScreen(
                            backend = backend,
                            onCancel = { screen = Screen.Projects },
                            onCreated = { screen = Screen.Editor },
                        )
                        Screen.Editor -> EditorScreen(
                            state = state,
                            onToggleTheme = { dark = !dark },
                            onOpenDependencies = { module -> depsModule = module; screen = Screen.Dependencies },
                            onOpenModuleConfig = { module -> configModule = module; screen = Screen.ModuleConfig },
                            onOpenSdkManager = { screen = Screen.SdkManager },
                            onCloseProject = { screen = Screen.Projects },
                            fileActions = fileActions,
                        )
                        Screen.Dependencies -> DependenciesScreen(
                            backend = state.backend,
                            initialModule = depsModule,
                            onBack = { screen = Screen.Editor },
                            codeFont = codeFont,
                        )
                        Screen.ModuleConfig -> ModuleConfigScreen(
                            backend = state.backend,
                            initialModule = configModule,
                            onBack = { screen = Screen.Editor },
                            codeFont = codeFont,
                        )
                        Screen.SdkManager -> SdkManagerScreen(
                            backend = state.backend,
                            onBack = { screen = Screen.Editor },
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
                // Final CTA: open the bundled sample (the first project the host knows about) and land in
                // the editor — the same use case the picker's "open" runs.
                onOpenSample = {
                    backend.projects().firstOrNull()?.let { sample ->
                        scope.launch { backend.openProject(sample.rootPath); screen = Screen.Editor }
                    }
                },
                onFinish = {
                    showOnboarding = false
                    backend.setPreference("onboarding.seen", "true")
                },
            )
            // The run sandbox's permission prompt — overlays everything while a guarded program is blocked.
            PermissionDialog(backend)
        }
    }
}
