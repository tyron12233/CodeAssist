package dev.ide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import dev.ide.ui.ads.BuildAdInterstitial
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAccent
import dev.ide.ui.components.OnboardingSheet
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.import_gradle_failed
import dev.ide.ui.generated.resources.import_unrecognized
import dev.ide.ui.navigation.ScreenHost
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.platform.PlatformSystemBars
import dev.ide.ui.screens.GradleImportModeDialog
import dev.ide.ui.screens.ImportSourceDialog
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.rememberJetBrainsMono
import org.jetbrains.compose.resources.stringResource

/**
 * Root of the reusable IDE UI. Hosts pick the toolkit (Compose Desktop window, Android activity) and
 * supply an [IdeBackend] plus optional brand fonts and a [FileActions] bridge; the screens, theme,
 * navigation, and state are shared.
 *
 * The shell's own state and the flows that drive it live in [CodeAssistAppState]; this body is the theme,
 * the back gesture, the screen host ([AppNavGraph]), and the app-wide overlays. Screens transition with a
 * platform-differentiated feel ([ScreenHost]); the active project's UI state is re-keyed on
 * [IdeBackend.projectEpoch] so creating/opening a project rebuilds the tree and tabs. A first-launch
 * [OnboardingSheet] introduces the IDE over the picker.
 */
@Composable
fun CodeAssistApp(
    backend: IdeBackend,
    uiFont: FontFamily = FontFamily.SansSerif,
    codeFont: FontFamily = rememberJetBrainsMono(),
    fileActions: FileActions = FileActions.None,
    /** Platform advertising bridge (AdMob on Android, [AdHost.None] on desktop). Ads render only through this. */
    adHost: AdHost = AdHost.None,
    composePreviewHost: ComposePreviewHost? = null,
    /** A `.caproj` path handed in from outside the app (Android "Open with"). When it changes to a
     *  non-null value, the import preview opens for it. Null on desktop / normal launch. */
    importPackagePath: String? = null,
) {
    // Register the UI facets of the enabled plugins, then load once. The backend reports exactly the plugins
    // whose engine half is enabled (see BuiltInPlugins' unified engine+UI declaration), so this shell code names
    // no specific plugin and a disabled plugin contributes nothing. Loaded eagerly (idempotent) so the tool-
    // window/action registries are populated before the editor composes — the top-bar toggles + side panes read
    // straight from ToolWindowRegistry.
    // noinspection RememberReturnType
    remember(Unit) {
        backend.uiPlugins().forEach { UiPluginHost.register(it) }
        UiPluginHost.ensureLoaded()
    }

    val app = rememberCodeAssistAppState(backend, fileActions, adHost)

    // The active project changes (create/open) bump the epoch; re-key per-project state on it.
    val state = remember(backend, app.epoch) {
        IdeUiState(backend, composePreviewHost, initialGradleConvertPrompt = app.pendingGradleConvert)
    }
    // Clear the one-shot after it has been baked into the (re-created) state, so navigating back to a project
    // later never re-triggers the convert prompt.
    LaunchedEffect(state) { app.consumeGradleConvertPrompt() }
    // Cancel the state's async file-read scope when it's replaced (project/backend change) or leaves composition,
    // so a slow read for an abandoned project can't resolve against the new one.
    DisposableEffect(state) { onDispose { state.dispose() } }
    // Session restore/persistence, plugin editor events, and disk sync for the project on screen.
    LaunchedEffect(state) { state.runSessionEffects() }
    // Apply settings to the active project's live editor state whenever they change (or the project swaps).
    LaunchedEffect(state, app.settings) { state.applySettings(app.settings) }
    // A `.caproj` handed in from outside the app ("Open with"): read its preview and open the import screen.
    // Keyed on the path (the host makes each hand-off a distinct path) so it fires once per inbound package.
    LaunchedEffect(importPackagePath) { importPackagePath?.let { app.openImportPackage(it) } }

    // Theme + accent + code font come from settings; the Settings screen (and the quick toggle) update them
    // live. "system" follows the OS dark-mode signal.
    val dark = isDarkTheme(app.settings, isSystemInDarkTheme())
    // Keep the system-bar icons legible against the app theme: dark icons in light mode, light icons in dark mode.
    // Reactive, so a theme toggle re-applies it (the host's one-time edge-to-edge setup can't follow the toggle).
    PlatformSystemBars(darkTheme = dark)
    val resolvedCodeFont = if (app.settings.codeFont == "monospace") FontFamily.Monospace else codeFont
    // The import notice is raised as a reason by the state holder and localized here.
    val importUnrecognizedMsg = stringResource(Res.string.import_unrecognized)
    val importGradleFailedMsg = stringResource(Res.string.import_gradle_failed)
    val importErrorMessage = when (val error = app.importError) {
        null -> null
        ImportError.Unrecognized -> importUnrecognizedMsg
        is ImportError.GradleFailed -> error.message.ifBlank { importGradleFailedMsg }
    }

    CodeAssistTheme(
        dark = dark,
        accent = accentOf(app.settings),
        // A Custom accent seeds the whole expressive theme from the user's chosen color; a "Dynamic" accent
        // follows the wallpaper; any preset applies its fixed palette (overriding wallpaper dynamic color).
        seedColor = seedColorOf(app.settings),
        useDynamic = app.settings.accent == UiAccent.Dynamic,
        uiFont = uiFont,
        codeFont = resolvedCodeFont,
    ) {
        // Route the system back gesture through in-app navigation instead of letting it close the app (#997).
        // Registered above the editor's own overlay handler, so an open sheet/dialog is closed first (the
        // deeper handler wins); this one only fires for screen-level back.
        PlatformBackHandler(enabled = app.canNavigateBack, onBack = app::navigateBack)
        // The M3 background fills the whole window edge-to-edge (behind the system bars); content is
        // then inset by `safeDrawing`. On desktop these insets are empty, so this is a no-op there.
        CompositionLocalProvider(
            LocalAds provides app.adController,
            LocalHostFileActions provides fileActions,
            LocalPluginNavigator provides app::openPluginScreen,
        ) {
            // Occasional full-screen ad over a LONG build (Android only; inert on desktop / when ads are off).
            // Renders nothing — it just observes the build state and asks the host to present an interstitial.
            BuildAdInterstitial(backend, app.adController)
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AppNavGraph(
                        app = app,
                        state = state,
                        fileActions = fileActions,
                        codeFont = codeFont,
                        dark = dark,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AppOverlays(
                    backend = backend,
                    state = state,
                    fileActions = fileActions,
                    onPicker = app.screen == Screen.Projects && app.homeTab == HomeTab.Projects,
                    showMigration = app.showMigration,
                    onBackup = app::backupAndShare,
                    onDismissMigration = app::dismissMigration,
                    showOnboarding = app.showOnboarding,
                    // Final CTA: send the user straight into the Create-Project flow.
                    onGetStarted = { app.createProject() },
                    onFinishOnboarding = app::dismissOnboarding,
                    showAnalytics = app.showAnalytics,
                    onAllowAnalytics = { app.setAnalyticsConsent(true) },
                    onDeclineAnalytics = { app.setAnalyticsConsent(false) },
                    importError = importErrorMessage,
                    onDismissImportError = app::dismissImportError,
                    importBusy = app.importBusy,
                )
                ImportSourceDialog(
                    visible = app.showImportSourceChoice,
                    canPickFolder = fileActions.canPickDirectory,
                    canPickPackage = fileActions.canPickFile,
                    onFolder = app::chooseFolderImport,
                    onPackage = app::choosePackageImport,
                    onDismiss = app::dismissImportSourceChoice,
                )
                GradleImportModeDialog(
                    visible = app.showImportModeChoice,
                    onCompat = { app.importGradleProject(convert = false) },
                    onConvert = { app.importGradleProject(convert = true) },
                    onDismiss = app::dismissImportModeChoice,
                )
            }
        }
    }
}
