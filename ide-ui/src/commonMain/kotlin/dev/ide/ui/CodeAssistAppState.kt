package dev.ide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import dev.ide.ui.ext.ScreenRegistry
import dev.ide.ui.ads.AdController
import dev.ide.ui.backend.UiProjectFolderKind
import dev.ide.ui.backend.AdHost
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiAccent
import dev.ide.ui.backend.UiImportPreview
import dev.ide.ui.backend.UiSettings
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.platform.ioDispatcher
import dev.ide.ui.screens.ModulesTab
import dev.ide.ui.screens.doImportGradle
import dev.ide.ui.theme.CaAccent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Preference key remembering the last author entered in the Export-project dialog (not per-project). */
internal const val EXPORT_AUTHOR_PREF = "export.author"

/** App preference holding the workspace root of the project on screen when the app was last used, so a process
 *  kill (or a normal relaunch) resumes back into it instead of the picker. Blank = the picker was showing. */
private const val LAST_PROJECT_PREF = "session.lastProject"

/** App preference gating the resume-last-project behavior. Unset/anything-but-"false" = on (the default). */
private const val REOPEN_LAST_PROJECT_PREF = "session.reopenLastProject"

private const val MIGRATION_ACK_PREF = "migration.acknowledged"
private const val LEGACY_RECOVERY_SEEN_PREF = "legacy.recovery.seen"
private const val ONBOARDING_SEEN_PREF = "onboarding.seen"

/** Why the project-import flow stopped, rendered by the host as a localized notice. */
sealed interface ImportError {
    /** A picked (or handed-in) file was not a readable `.caproj` package. */
    data object Unrecognized : ImportError

    /** Importing a Gradle folder failed; [message] is the engine's reason, blank when it gave none. */
    data class GradleFailed(val message: String) : ImportError
}

/**
 * The app shell's state and the intents that change it: which screen is showing, where each sub-screen
 * returns to, the first-launch sheets, and the project create/open/import/export flows.
 *
 * Held outside the composition (see [rememberCodeAssistAppState]) so the root composable stays a pure
 * rendering of [screen] plus the state below, and so this logic can be exercised in a plain unit test.
 * State flows out as read-only properties; the UI calls the intent functions and never assigns.
 *
 * The bridges to backend state (the active project epoch, a starting console run, session resume) are
 * started once in [init] and live in [scope], the caller's coroutine scope.
 */
@Stable
class CodeAssistAppState(
    val backend: IdeBackend,
    private val fileActions: FileActions,
    private val scope: CoroutineScope,
    adHost: AdHost = AdHost.None,
) {
    // ---- settings + theme ----

    /** Persisted IDE settings driving the theme (and seeding the editor's live prefs). Re-read after the
     *  Settings screen writes, so appearance changes take effect immediately. */
    var settings: UiSettings by mutableStateOf(backend.settings.settings())
        private set

    // ---- navigation ----

    var screen: Screen by mutableStateOf(Screen.Projects)
        private set

    /** Which plugin-contributed screen [Screen.PluginScreen] is showing, and where Back returns to. */
    var pluginScreenId: String? by mutableStateOf(null)
        private set
    private var pluginScreenReturn: Screen = Screen.Editor

    /** The home screen's selected bottom-nav tab (project picker / store / learn). Lives here, not in the
     *  per-project [IdeUiState], so it survives across the landing session and resets only on a full relaunch. */
    var homeTab: HomeTab by mutableStateOf(HomeTab.Projects)
        private set

    /** Where the Settings and Tools hub returns on Back: the picker (opened with no project) or the editor. */
    var hubReturn: Screen by mutableStateOf(Screen.Editor)
        private set

    /** Where the Keystore Manager returns on Back: the hub (its normal entry) or the module Signing tab (the
     *  "manage keystores" cross-link from project signing). */
    var keystoreReturn: Screen by mutableStateOf(Screen.Hub)
        private set

    /** Whether the Keystore Manager was reached from a project context (the editor's hub or a module's Signing
     *  tab) rather than the picker's hub. Gates the "Assign to a build" row: assignment is per-project, so it is
     *  hidden when no project is open (and must never navigate into one). NOT `epoch > 0`: that stays true after
     *  a project is closed back to the picker, which is exactly when the row must not show. */
    var keystoreInProject: Boolean by mutableStateOf(false)
        private set

    /** Where the Icon Manager returns on Back: the editor, or a module's config when opened from there. */
    var iconManagerReturn: Screen by mutableStateOf(Screen.Editor)
        private set

    /** A `res/` directory the Icon Manager should preselect, set when it was opened from a file-tree node. */
    var iconManagerResDir: String? by mutableStateOf(null)
        private set

    /** The icon the app-icon studio should open with as its foreground, when one was picked for it. */
    var appIconSeedRepoId: String? by mutableStateOf(null)
        private set

    var appIconSeedName: String? by mutableStateOf(null)
        private set

    // ---- per-screen arguments ----

    /** A template id to pre-select in the Create-Project flow when it is opened from a Store item (null = the
     *  plain New-Project gallery). */
    var createTemplateId: String? by mutableStateOf(null)
        private set

    /** The Learn destination being viewed (a track's lesson list / the step player), plus the player's start
     *  step. Bumping [learnEpoch] on return re-reads progress so the Learn tab and track reflect finished work. */
    var currentTrackId: String? by mutableStateOf(null)
        private set
    var currentLessonId: String? by mutableStateOf(null)
        private set
    var lessonInitialStep: Int by mutableStateOf(0)
        private set
    var learnEpoch: Int by mutableStateOf(0)
        private set

    /** The store item shown on the full-screen detail page (set when a card is tapped in the Explore tab). */
    var storeItem: UiStoreItem? by mutableStateOf(null)
        private set

    var configModule: String? by mutableStateOf(null)
        private set
    var modulesTab: ModulesTab by mutableStateOf(ModulesTab.Settings)
        private set
    var keystoreImportPath: String? by mutableStateOf(null)
        private set

    // ---- first-launch sheets ----

    var showMigration: Boolean by mutableStateOf(backend.settings.preference(MIGRATION_ACK_PREF) != "true")
        private set
    var showLegacyRecovery: Boolean by mutableStateOf(backend.settings.preference(LEGACY_RECOVERY_SEEN_PREF) != "true")
        private set
    var showOnboarding: Boolean by mutableStateOf(backend.settings.preference(ONBOARDING_SEEN_PREF) != "true")
        private set

    /** Opt-in analytics: prompt only when collection is available and the user has not decided yet (null). The
     *  re-toggle lives in the editor's More menu (a settings surface), not permanently on the project picker. */
    var showAnalytics: Boolean by mutableStateOf(
        backend.diagnostics.analyticsAvailable() && backend.diagnostics.analyticsConsent() == null
    )
        private set

    // ---- project sharing + import ----

    /** Bumped after a project is deleted, or on a pull-to-refresh, so Home re-reads the on-disk list. */
    var projectsRefresh: Int by mutableStateOf(0)
        private set

    /** Re-read the project list from disk (the Home screen's pull-to-refresh). */
    fun refreshProjects() {
        projectsRefresh++
    }

    /** The project whose Export dialog is open, and the picked package being previewed for import (path plus
     *  the read manifest/peek). Held here so they survive picker recompositions. */
    var exportTarget: ProjectInfo? by mutableStateOf(null)
        private set
    var importArchivePath: String? by mutableStateOf(null)
        private set
    var importPreview: UiImportPreview? by mutableStateOf(null)
        private set

    /** Non-null while the import notice is up (a picked/opened file was not a readable `.caproj`, or a Gradle
     *  folder import failed). */
    var importError: ImportError? by mutableStateOf(null)
        private set

    /** True while a picked Gradle folder is being copied and imported (a blocking, non-cancellable operation). */
    var importBusy: Boolean by mutableStateOf(false)
        private set

    /** The "which source?" chooser for Import project: a project folder, or a `.caproj` package. Shown only
     *  when the host can service BOTH — with one available there is nothing to ask. */
    var showImportSourceChoice: Boolean by mutableStateOf(false)
        private set

    /** The import-time "compatibility mode vs convert" chooser (shown after the folder pick request), and a
     *  one-shot carried into the freshly-opened editor's state when Convert was chosen. */
    var showImportModeChoice: Boolean by mutableStateOf(false)
        private set
    var pendingGradleConvert: Boolean by mutableStateOf(false)
        private set

    // ---- backend-derived state ----

    /** The active project epoch: create/open bumps it, and per-project state is re-keyed on it. */
    var epoch: Int by mutableStateOf(backend.projects.projectEpoch.value)
        private set

    /** The id of the interactive console session the last `run` task opened, or null for build-only tasks. */
    private var runConsoleId: Int? by mutableStateOf(null)

    /** The home-screen Projects Store and bottom nav is unfinished and ships dark: it appears only when its
     *  feature flag is on (or the `feature.projectsStore` preference overrides it). Off = the picker alone. */
    val storeEnabled: Boolean =
        backend.settings.preference("feature.projectsStore")?.toBooleanStrictOrNull() ?: FeatureFlags.PROJECTS_STORE

    /** Ad gating and state, shared with every screen through `LocalAds`. */
    val adController: AdController = AdController(backend, adHost)

    /** Whether the system back gesture has somewhere in-app to go. False on the picker landing, where back
     *  exits the app as usual. */
    val canNavigateBack: Boolean
        get() = screen != Screen.Projects || homeTab != HomeTab.Projects || showOnboarding || showMigration ||
            showAnalytics

    // Session resume across a process kill: the project that was on screen last run (captured up-front, before
    // any bridge runs, so the tracking below cannot clear it before the resume reads it) and whether resume is
    // enabled (default on). [lastPersistedProject] mirrors the last value written so tracking writes only on an
    // actual picker to project change; it starts at the picker sentinel so the brief "picker to resumed project"
    // startup window never rewrites (and so cannot clear) the on-disk preference.
    private val resumeProject: String? =
        backend.settings.preference(LAST_PROJECT_PREF)?.takeIf { it.isNotBlank() }
    private val reopenLast: Boolean =
        backend.settings.preference(REOPEN_LAST_PROJECT_PREF)?.toBooleanStrictOrNull() != false
    private var lastPersistedProject: String = ""

    init {
        // A successful create/open advances the epoch: land in the editor on the new project.
        scope.launch {
            backend.projects.projectEpoch.collect {
                epoch = it
                if (it > 0) screen = Screen.Editor
            }
        }
        // Starting a console run (a `run` task) opens a fresh interactive session: keyed on its id, jump to the
        // full-screen Run terminal. Build/assemble tasks leave the console null and stay in the build console.
        scope.launch { backend.build.runConsole.collect { runConsoleId = it?.id } }
        scope.launch {
            snapshotFlow { runConsoleId to epoch }.distinctUntilChanged().collect { (id, _) ->
                if (id != null && screen == Screen.Editor) screen = Screen.Run
            }
        }
        // Resume the last project on a cold launch: reopen whatever project was on screen when the app was last
        // used, so a background kill (or a normal relaunch) comes back into the editor instead of the picker.
        // One-shot; opening bumps the epoch, which lands in the editor above and reopens the saved tabs. A
        // deleted/missing project just falls through to the picker.
        scope.launch {
            val last = resumeProject
            if (!reopenLast || last == null) return@launch
            if (backend.projects.projectEpoch.value > 0) return@launch // already in a project
            val exists = withContext(ioDispatcher) { backend.projects.projects().any { it.rootPath == last } }
            if (exists) backend.projects.openProject(last)
        }
        // Track which project (if any) is on screen for the resume above: clear it on the picker (so quitting
        // from the picker reopens to the picker), otherwise record the active project's root while any of its
        // screens is shown (Editor, Settings, Run, Dependencies). Compares against the in-memory mirror so it
        // writes only on a real picker to project transition, never per navigation.
        scope.launch {
            snapshotFlow { screen to epoch }.distinctUntilChanged().collect { (current, projectEpoch) ->
                val target = when {
                    current == Screen.Projects -> ""
                    projectEpoch > 0 -> backend.project.rootPath
                    else -> return@collect
                }
                if (target != lastPersistedProject) {
                    lastPersistedProject = target
                    backend.settings.setPreference(LAST_PROJECT_PREF, target)
                }
            }
        }
    }

    // ---- settings intents ----

    /** Re-read the persisted settings after the Settings screen (or a quick toggle) wrote them. */
    fun reloadSettings() {
        settings = backend.settings.settings()
    }

    /** Quick theme toggle: flip to the opposite of what is shown ([currentlyDark]), stepping out of "system"
     *  if that was active. */
    fun toggleTheme(currentlyDark: Boolean) {
        backend.settings.setSetting("appearance", "themeMode", if (currentlyDark) "light" else "dark")
        reloadSettings()
    }

    // ---- navigation intents ----

    /** Go to [target] with no extra state to carry (the plain forward/back moves between screens). */
    fun navigateTo(target: Screen) {
        screen = target
    }

    fun selectHomeTab(tab: HomeTab) {
        homeTab = tab
    }

    /**
     * Open a plugin-contributed screen ([ScreenRegistry]) and remember where to return to. A plugin panel
     * navigates here for a detail view that needs the whole window, and any action's `Navigate` effect
     * resolves to the same route.
     *
     * An id nothing has registered (a disabled plugin, a stale action) is ignored rather than navigated to:
     * the destination would render nothing, and recovering from it in the route would have to re-enter
     * navigation from inside a screen that is already animating away.
     */
    fun openPluginScreen(id: String) {
        if (ScreenRegistry.find(id) == null) return
        if (screen != Screen.PluginScreen) pluginScreenReturn = screen
        pluginScreenId = id
        screen = Screen.PluginScreen
    }

    /** Open the Settings and Tools hub, remembering [from] as its Back destination. */
    fun openHub(from: Screen) {
        hubReturn = from
        screen = Screen.Hub
    }

    /** Open the Keystore Manager, remembering its Back destination and whether it was reached with a project
     *  open (which gates the per-project signing assignment row). */
    fun openKeystoreManager(returnTo: Screen, inProject: Boolean) {
        keystoreReturn = returnTo
        keystoreInProject = inProject
        screen = Screen.KeystoreManager
    }

    /** Open the hub's Keystore Manager entry: a project context only when the hub itself came from the editor. */
    fun openKeystoreManagerFromHub() = openKeystoreManager(Screen.Hub, inProject = hubReturn == Screen.Editor)

    /**
     * Open the Icon Manager, remembering where Back goes. [resDir] preselects an import target, which is how
     * the file tree's "New Image Asset" entry scopes the screen to the folder that was tapped.
     */
    fun openIconManager(returnTo: Screen = Screen.Editor, resDir: String? = null) {
        iconManagerReturn = returnTo
        iconManagerResDir = resDir
        screen = Screen.IconManager
    }

    /**
     * Open the app-icon studio, optionally seeded with the icon the user picked in the Icon Manager. Back from
     * the studio returns to the manager, so choosing a different icon is a round trip rather than a dead end.
     */
    fun openAppIconStudio(repoId: String? = null, iconName: String? = null) {
        appIconSeedRepoId = repoId
        appIconSeedName = iconName
        screen = Screen.AppIconStudio
    }

    fun openKeystoreImport(path: String) {
        keystoreImportPath = path
        screen = Screen.KeystoreImport
    }

    /** The keystore manager's "assign to a build" jump: one android-app module goes straight to its Signing
     *  tab, otherwise the module list opens on Signing. */
    fun openSigningAssignment() {
        openModuleConfig(backend.signing.signableModules().singleOrNull(), ModulesTab.Signing)
    }

    fun openModuleConfig(module: String?, tab: ModulesTab) {
        configModule = module
        modulesTab = tab
        screen = Screen.ModuleConfig
    }

    // ---- project intents ----

    fun openProject(project: ProjectInfo) {
        scope.launch { if (backend.projects.openProject(project.rootPath)) screen = Screen.Editor }
    }

    fun deleteProject(project: ProjectInfo) {
        scope.launch {
            backend.projects.deleteProject(project.rootPath)
            projectsRefresh++
        }
    }

    /** Open the Create-Project gallery, optionally pre-selecting [templateId] (a Store item's template). */
    /**
     * Install a store item, or route it to Create-Project when it is a bundled scaffold.
     *
     * An item with a `templateId` is already on the device, so creating it locally is both faster and works
     * offline: it never downloads. Everything else goes through the real download/verify/unpack path.
     *
     * Progress and failures are reported through the engine's install-progress flow, which every shelf
     * reads, so the download says so wherever the item appears rather than only where it was tapped. The
     * install counter is bumped by the engine on success, never here: counting a button press would inflate
     * the very numbers the trending chart ranks on.
     */
    fun installStoreItem(item: UiStoreItem) {
        val template = item.templateId
        if (template != null) {
            createProject(template)
            return
        }
        if (!item.available) {
            // Nothing to download and no local scaffold: the detail page is all there is to offer.
            openStoreItem(item)
            return
        }
        scope.launch {
            val result = runCatching { backend.store.install(item.id) }.getOrNull()
            // The unpacked project is on disk now, so Home has to be told to look again.
            if (result?.success == true) refreshProjects()
        }
    }

    fun createProject(templateId: String? = null) {
        createTemplateId = templateId
        screen = Screen.CreateProject
    }

    /** Create a project backup zip and hand it to the host's share/save sheet. */
    suspend fun backupAndShare() {
        backend.projects.backupProjects()?.let { fileActions.share(it) }
    }

    /** [backupAndShare] as a fire-and-forget intent, for callers that are not already in a coroutine. */
    fun backupProjects() {
        scope.launch { backupAndShare() }
    }

    // ---- project sharing ----

    fun startExport(project: ProjectInfo) {
        exportTarget = project
        screen = Screen.ExportProject
    }

    fun finishExport() {
        exportTarget = null
        screen = Screen.Projects
    }

    fun exportAuthor(): String = backend.settings.preference(EXPORT_AUTHOR_PREF) ?: ""

    fun rememberExportAuthor(author: String) = backend.settings.setPreference(EXPORT_AUTHOR_PREF, author)

    /** Ask the host for a `.caproj` file and open its import preview. */
    fun pickProjectPackage() {
        fileActions.pickFile(listOf("caproj")) { path ->
            if (path != null) scope.launch { openImportPackage(path) }
        }
    }

    /** Read the `.caproj` at [path] and open the import preview for it, or raise the unrecognized-file notice. */
    suspend fun openImportPackage(path: String) {
        val preview = backend.projects.previewImportPackage(path)
        if (preview != null) {
            importArchivePath = path
            importPreview = preview
            screen = Screen.ImportProject
        } else {
            importError = ImportError.Unrecognized
        }
    }

    fun cancelImportPreview() = leaveImportPreview(Screen.Projects)

    fun finishImportPreview() = leaveImportPreview(Screen.Editor)

    private fun leaveImportPreview(next: Screen) {
        importPreview = null
        importArchivePath = null
        screen = next
    }

    // ---- project import ----

    /** The folder the user picked, held while the compatibility/convert question is answered for it. */
    private var pendingImportPath: String? = null

    /**
     * The picker's "Import project" action. Picks the folder FIRST and asks what it is, because the only
     * follow-up question -- compatibility vs convert -- applies to a foreign build system and not to a
     * CodeAssist workspace. Asking first (as this did) meant a CodeAssist project folder had to answer a
     * question about Gradle conversion that meant nothing for it, before being rejected anyway.
     */
    fun requestProjectImport() {
        // The two sources sit behind different host APIs (pickDirectory vs pickFile), so which picker to
        // launch has to be answered first. With only one available, don't ask — go straight to it.
        val folder = fileActions.canPickDirectory
        val pkg = fileActions.canPickFile
        when {
            folder && pkg -> showImportSourceChoice = true
            folder -> pickProjectFolder()
            pkg -> pickProjectPackage()
        }
    }

    fun dismissImportSourceChoice() {
        showImportSourceChoice = false
    }

    /** Chose "Project folder" at the source prompt. */
    fun chooseFolderImport() {
        showImportSourceChoice = false
        pickProjectFolder()
    }

    /** Chose "Project package" at the source prompt — hands off to the existing `.caproj` preview flow. */
    fun choosePackageImport() {
        showImportSourceChoice = false
        pickProjectPackage()
    }

    private fun pickProjectFolder() {
        fileActions.pickDirectory { path ->
            if (path.isNullOrBlank()) return@pickDirectory
            importBusy = true
            scope.launch {
                when (backend.projects.inspectProjectFolder(path)) {
                    // Nothing to translate: adopt it and open it.
                    UiProjectFolderKind.CODE_ASSIST -> runImport(path, convert = false)
                    // Foreign build system: it still has a mode to choose.
                    // The question is the user's to answer, so drop the overlay while it is on screen.
                    UiProjectFolderKind.GRADLE -> {
                        importBusy = false
                        pendingImportPath = path
                        showImportModeChoice = true
                    }
                    UiProjectFolderKind.UNKNOWN -> {
                        importBusy = false
                        importError = ImportError.GradleFailed("")
                    }
                }
            }
        }
    }

    fun dismissImportModeChoice() {
        showImportModeChoice = false
        pendingImportPath = null
    }

    /** Answer to the compatibility/convert prompt, for the folder already picked. */
    fun importGradleProject(convert: Boolean) {
        val path = pendingImportPath
        showImportModeChoice = false
        pendingImportPath = null
        if (path != null) {
            importBusy = true
            scope.launch { runImport(path, convert) }
        }
    }

    /** Import [path] and open it; [convert] flags the freshly-opened editor to run the Gradle convert flow. */
    private suspend fun runImport(path: String, convert: Boolean) {
        val result = backend.projects.importExternalProject(path)
        importBusy = false
        if (result.success) {
            if (convert) pendingGradleConvert = true
            screen = Screen.Editor
        } else {
            importError = ImportError.GradleFailed(result.message)
        }
    }

    /** Clear the convert one-shot once it has been baked into the (re-created) per-project state, so navigating
     *  back to a project later never re-triggers the convert prompt. */
    fun consumeGradleConvertPrompt() {
        pendingGradleConvert = false
    }

    fun dismissImportError() {
        importError = null
    }

    // ---- Learn ----

    fun openTrack(trackId: String) {
        currentTrackId = trackId
        screen = Screen.LessonTrack
    }

    fun openLesson(lessonId: String, initialStep: Int = 0) {
        currentLessonId = lessonId
        lessonInitialStep = initialStep
        screen = Screen.LessonPlayer
    }

    /** The Learn tab's "continue where you left off" card: straight into the player at the saved step. */
    fun resumeLesson(trackId: String, lessonId: String, step: Int) {
        currentTrackId = trackId
        openLesson(lessonId, step)
    }

    /** Leave the lesson player for its track (or the Learn tab when it was opened without one), re-reading
     *  progress so the just-finished work shows. */
    fun exitLessonPlayer() {
        learnEpoch++
        screen = if (currentTrackId != null) Screen.LessonTrack else Screen.Projects
    }

    fun exitLessonTrack() {
        learnEpoch++
        screen = Screen.Projects
    }

    fun openStoreItem(item: UiStoreItem) {
        storeItem = item
        screen = Screen.StoreItem
    }

    /** The publish flow. Full screen, not a sheet: it is a form with a file listing above it. */
    fun openSubmitProject() {
        screen = Screen.SubmitProject
    }

    /** What publishing does, before committing to it. Previously this link opened Settings and explained nothing. */
    fun openPublishingGuide() {
        screen = Screen.PublishingGuide
    }

    /**
     * Act on a tapped notification.
     *
     * A target that no longer resolves is a no-op rather than an error: a project can be deleted and a
     * store item withdrawn between the notification being posted and the user opening it, and neither is
     * worth an error dialog. The notification stays in the list so it is not silently swallowed.
     */
    fun openNotificationTarget(notification: dev.ide.ui.backend.UiNotification) {
        when (val target = notification.target) {
            null -> Unit
            is dev.ide.ui.backend.UiNotificationTarget.Project -> {
                val match = backend.projects.projects().firstOrNull { it.rootPath == target.rootPath }
                if (match != null) openProject(match)
            }
            is dev.ide.ui.backend.UiNotificationTarget.StoreItem -> {
                scope.launch {
                    val item = runCatching { backend.store.feed()?.allItems?.firstOrNull { it.id == target.itemId } }
                        .getOrNull()
                    if (item != null) openStoreItem(item)
                }
            }
            dev.ide.ui.backend.UiNotificationTarget.Submissions -> openSubmitProject()
            is dev.ide.ui.backend.UiNotificationTarget.Screen -> {
                Screen.entries.firstOrNull { it.name == target.route }?.let { navigateTo(it) }
            }
        }
    }

    // ---- first-launch sheets ----

    fun dismissMigration() {
        showMigration = false
        backend.settings.setPreference(MIGRATION_ACK_PREF, "true")
    }

    fun dismissOnboarding() {
        showOnboarding = false
        backend.settings.setPreference(ONBOARDING_SEEN_PREF, "true")
    }

    fun dismissLegacyRecovery() {
        showLegacyRecovery = false
        backend.settings.setPreference(LEGACY_RECOVERY_SEEN_PREF, "true")
    }

    fun setAnalyticsConsent(granted: Boolean) {
        showAnalytics = false
        backend.diagnostics.setAnalyticsConsent(granted)
    }

    // ---- back navigation ----

    /**
     * The system back gesture routed through in-app navigation instead of letting it close the app. The host
     * registers this above the editor's own overlay handler, so an open sheet/dialog is closed first (the
     * deeper handler wins) and this only sees screen-level back: pop a sub-screen to the editor, the editor to
     * the project picker, or dismiss the first-launch sheets.
     */
    fun navigateBack() {
        when {
            showOnboarding -> dismissOnboarding()
            showMigration -> dismissMigration()
            showAnalytics -> setAnalyticsConsent(false)

            // The keystore Create/Import sub-screens step back to their manager, not all the way out.
            screen == Screen.KeystoreCreate || screen == Screen.KeystoreImport -> screen = Screen.KeystoreManager
            // The hub's sub-screens step back to the hub; the keystore manager honours its entry origin.
            screen == Screen.SdkManager || screen == Screen.Settings || screen == Screen.CodeStyle ||
                screen == Screen.EditorSymbols || screen == Screen.Plugins || screen == Screen.Storage ->
                screen = Screen.Hub
            screen == Screen.KeystoreManager -> screen = keystoreReturn
            screen == Screen.AppIconStudio -> screen = Screen.IconManager
            screen == Screen.IconManager -> screen = iconManagerReturn
            // The hub returns to wherever it was opened from (picker or editor).
            screen == Screen.Hub -> screen = hubReturn

            screen == Screen.Run || screen == Screen.ModuleConfig -> screen = Screen.Editor

            // A plugin screen returns to wherever it was opened from.
            screen == Screen.PluginScreen -> {
                pluginScreenId = null
                screen = pluginScreenReturn
            }

            // The lesson player steps back to its track; the track steps back to the Learn tab (picker).
            screen == Screen.LessonPlayer -> exitLessonPlayer()
            screen == Screen.LessonTrack -> exitLessonTrack()
            // The store item detail returns to the Explore tab (still selected on Projects).
            screen == Screen.StoreItem -> screen = Screen.Projects
            screen == Screen.SubmitProject -> screen = Screen.Projects
            screen == Screen.PublishingGuide -> screen = Screen.Projects

            screen == Screen.CreateProject -> screen = Screen.Projects
            screen == Screen.ImportProject -> cancelImportPreview()
            screen == Screen.ExportProject -> finishExport()
            screen == Screen.Editor -> screen = Screen.Projects
            // On the home screen, a Store/Learn tab steps back to the project picker before exiting.
            screen == Screen.Projects && homeTab != HomeTab.Projects -> homeTab = HomeTab.Projects
            else -> {}
        }
    }
}

/**
 * Creates (and remembers) the [CodeAssistAppState] for this composition, tied to a scope that is cancelled
 * when the app leaves it. Re-created only if the backend or a host bridge is swapped.
 */
@Composable
fun rememberCodeAssistAppState(
    backend: IdeBackend,
    fileActions: FileActions = FileActions.None,
    adHost: AdHost = AdHost.None,
    scope: CoroutineScope = rememberCoroutineScope(),
): CodeAssistAppState = remember(backend, fileActions, adHost, scope) {
    CodeAssistAppState(backend, fileActions, scope, adHost)
}

/** The accent palette a settings profile selects. */
internal fun accentOf(settings: UiSettings): CaAccent = when (settings.accent) {
    UiAccent.Teal -> CaAccent.Teal
    UiAccent.Orange -> CaAccent.Orange
    UiAccent.Violet -> CaAccent.Violet
    else -> CaAccent.Lime
}

/** The color a Custom accent seeds the whole expressive theme from, or null for every other accent. */
internal fun seedColorOf(settings: UiSettings): Color? =
    if (settings.accent == UiAccent.Custom) Color(settings.customAccentColor) else null

/** Whether a settings profile resolves to the dark theme, given the platform's current dark-mode signal. */
internal fun isDarkTheme(settings: UiSettings, systemDark: Boolean): Boolean = when (settings.themeMode) {
    "light" -> false
    "system" -> systemDark
    else -> true
}
