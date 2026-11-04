package dev.ide.ui

import androidx.compose.runtime.snapshots.Snapshot
import dev.ide.ui.backend.FileActions
import dev.ide.ui.ext.ScreenBackRegistry
import dev.ide.ui.ext.ScreenContribution
import dev.ide.ui.ext.ScreenRegistry
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.UiImportPreview
import dev.ide.ui.backend.UiInstallProgress
import dev.ide.ui.backend.UiInstallState
import dev.ide.ui.backend.UiStoreInstallResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiPackagedModule
import dev.ide.ui.backend.UiProjectFolderKind
import dev.ide.ui.backend.UiArchiveProject
import dev.ide.ui.backend.UiProjectArchive
import dev.ide.ui.backend.UiProjectResult
import dev.ide.ui.backend.UiSettings
import dev.ide.ui.screens.ModulesTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app shell's navigation, first-launch sheets, and project flows live in [CodeAssistAppState] rather than
 * in the root composable, so they run headlessly here: no Compose UI, no backend, just the state holder and a
 * fake [dev.ide.ui.backend.IdeBackend].
 */
class CodeAssistAppStateTest {

    /** A backend with an in-memory preference store and a drivable project epoch. */
    private class AppBackend(
        prefs: Map<String, String> = emptyMap(),
        val importPreview: UiImportPreview? = null,
        val importResult: UiProjectResult = UiProjectResult(true, ""),
        /** What the picked folder turns out to be — drives which follow-up the import flow asks for. */
        val folderKind: UiProjectFolderKind = UiProjectFolderKind.GRADLE,
        /** The Projects Store this backend serves. Unsupported unless a test exercises the store. */
        private val storeService: StoreService = StoreService.Unsupported,
        /** Learn: when set, opening a lesson workspace succeeds and becomes the open project. */
        val workspaceOpens: Boolean? = null,
    ) : StubBackend() {
        var projectRoot = "/stub"
        override val project: ProjectInfo get() = ProjectInfo("p", projectRoot, 0)
        val workspaceRequests = ArrayList<String>()
        override val learn: dev.ide.ui.backend.LearnService = object : dev.ide.ui.backend.LearnService {
            override fun learnAvailable() = true
            override suspend fun openWorkspace(lessonId: String): Boolean {
                workspaceRequests += lessonId
                if (workspaceOpens != true) return false
                projectRoot = "/home/learn-workspaces/kotlin"
                epochFlow.value++
                return true
            }
        }
        /** What a picked `.zip` unpacks to; null = not a zip. */
        var archive: UiProjectArchive? = null
        var discarded = 0
        val imported = ArrayList<String>()
        override suspend fun extractProjectArchive(archivePath: String): UiProjectArchive? = archive
        override suspend fun discardProjectArchive() { discarded++ }
        override val store: StoreService get() = storeService

        val prefs = HashMap(prefs)
        val epochFlow = MutableStateFlow(0)
        val deleted = ArrayList<String>()
        val opened = ArrayList<String>()
        var analyticsConsent: Boolean? = null

        override val projectEpoch: StateFlow<Int> get() = epochFlow
        override fun preference(key: String): String? = prefs[key]
        override fun setPreference(key: String, value: String) { prefs[key] = value }
        override fun settings(): UiSettings = UiSettings()
        override fun projects(): List<ProjectInfo> = listOf(ProjectInfo("app", "/ws/app", 1))
        override suspend fun inspectProjectFolder(path: String): UiProjectFolderKind = folderKind
        override suspend fun openProject(rootPath: String): Boolean {
            opened += rootPath
            projectRoot = rootPath
            epochFlow.value++
            return true
        }
        override suspend fun deleteProject(rootPath: String): Boolean {
            deleted += rootPath
            return true
        }
        override suspend fun previewImportPackage(archivePath: String): UiImportPreview? = importPreview
        override suspend fun importExternalProject(sourceRootPath: String): UiProjectResult {
            imported += sourceRootPath
            // A successful import opens the project, as the real one does (it swaps the engine in).
            if (importResult.success) epochFlow.value++
            return importResult
        }
        override fun analyticsAvailable(): Boolean = true
        override fun analyticsConsent(): Boolean? = analyticsConsent
        override fun setAnalyticsConsent(granted: Boolean) { analyticsConsent = granted }
    }

    /** Preferences of an app that is past its first launch (no migration/onboarding/consent sheets). */
    private fun settledPrefs() = mapOf(
        "migration.acknowledged" to "true",
        "onboarding.seen" to "true",
        "legacy.recovery.seen" to "true",
    )

    /** A backend past its first launch, with the analytics prompt already answered. */
    private fun settled(
        importPreview: UiImportPreview? = null,
        importResult: UiProjectResult = UiProjectResult(true, ""),
        folderKind: UiProjectFolderKind = UiProjectFolderKind.GRADLE,
    ) = AppBackend(settledPrefs(), importPreview, importResult, folderKind).apply { analyticsConsent = false }

    private val scopes = ArrayList<CoroutineScope>()

    @AfterTest
    fun cancelStateScopes() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    /**
     * A state holder on the test scheduler: its bridges (and every intent that launches) run when the test
     * advances, and the scope is cancelled after the test so no collector outlives it.
     */
    private fun TestScope.appState(
        backend: AppBackend,
        fileActions: FileActions = FileActions.None,
    ): CodeAssistAppState {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        scopes += scope
        return CodeAssistAppState(backend, fileActions, scope)
    }

    /**
     * A store whose install can be observed, and whose progress map can be pre-loaded with an item the
     * device already has.
     */
    private class StubStore(installed: Map<String, UiInstallProgress> = emptyMap()) : StoreService {
        val installs = ArrayList<String>()
        private val progress = MutableStateFlow(installed)

        override fun installProgress(): StateFlow<Map<String, UiInstallProgress>> = progress
        override suspend fun install(id: String, args: Map<String, String>): UiStoreInstallResult {
            installs += id
            return UiStoreInstallResult(true, "Added to your projects", "/ws/$id")
        }
    }

    private fun communityItem(id: String) = UiStoreItem(
        id = id,
        kind = UiStoreItemKind.Community,
        title = "Some project",
        summary = "s",
        category = "Kotlin",
    )

    /**
     * An item already on the device opens; it does NOT download a second copy.
     *
     * The row's button reads "Open" in this state, and it used to run the install path anyway, so the one
     * action the label promised was the one thing it did not do.
     */
    @Test
    fun openingAnInstalledItemOpensItInsteadOfInstallingAgain() = runTest {
        val item = communityItem("some.project")
        val store = StubStore(
            mapOf(
                item.id to UiInstallProgress(
                    itemId = item.id,
                    state = UiInstallState.INSTALLED,
                    fraction = 1f,
                    rootPath = "/ws/some.project",
                ),
            ),
        )
        val backend = AppBackend(settledPrefs(), storeService = store)
        val app = appState(backend)
        advanceUntilIdle()

        app.installStoreItem(item)
        advanceUntilIdle()

        assertEquals(emptyList(), store.installs, "an installed item must not be downloaded again")
        assertEquals(listOf("/ws/some.project"), backend.opened)
        assertEquals(Screen.Editor, app.screen)
    }

    /** An item the device does not have still installs, and Home is told to re-list afterwards. */
    @Test
    fun anItemThatIsNotInstalledStillInstalls() = runTest {
        val item = communityItem("other.project")
        val store = StubStore()
        val backend = AppBackend(settledPrefs(), storeService = store)
        val app = appState(backend)
        advanceUntilIdle()

        app.installStoreItem(item)
        advanceUntilIdle()

        assertEquals(listOf("other.project"), store.installs)
        assertEquals(emptyList(), backend.opened, "installing must not navigate into the editor")
    }

    /**
     * Lets the cold-launch resume settle. It checks the project still exists on the real IO dispatcher, which
     * the test scheduler cannot advance, so this alternates real-time waits with scheduler advances until an
     * open lands (or [timeoutMs] passes, the expected outcome when resume is off).
     */
    private fun TestScope.settleResume(backend: AppBackend, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        advanceUntilIdle()
        while (backend.opened.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            advanceUntilIdle()
        }
    }

    /** By default a cold launch resumes into the project that was on screen last run. */
    @Test
    fun coldLaunchReopensTheLastProject() = runTest {
        val backend = AppBackend(settledPrefs() + ("session.lastProject" to "/ws/app")).apply { analyticsConsent = false }
        val app = appState(backend)
        settleResume(backend)

        assertEquals(listOf("/ws/app"), backend.opened)
        assertEquals(Screen.Editor, app.screen)
    }

    /**
     * A closed project stays closed. Closing lands on the picker but keeps the engine loaded, and going on to
     * any other screen (New Project, Settings, the Store) used to record the closed project again, so the next
     * launch reopened it.
     */
    @Test
    fun aClosedProjectIsNotReopenedAfterVisitingOtherScreens() = runTest {
        val backend = AppBackend(settledPrefs() + ("session.lastProject" to "/ws/app")).apply { analyticsConsent = false }
        val app = appState(backend)
        settleResume(backend)
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals(Screen.Editor, app.screen)
        assertTrue(backend.prefs["session.lastProject"].orEmpty().isNotBlank(), "the open project is recorded")

        // Each step is its own frame, as in the app: snapshotFlow only sees a state once it has been applied.
        fun go(next: Screen) {
            app.navigateTo(next)
            Snapshot.sendApplyNotifications()
            advanceUntilIdle()
        }
        go(Screen.Projects) // Close project
        assertEquals("", backend.prefs["session.lastProject"], "closing clears it")
        for (next in listOf(Screen.CreateProject, Screen.Settings, Screen.Projects, Screen.StoreItem)) go(next)
        assertEquals("", backend.prefs["session.lastProject"], "the closed project is not recorded again")
    }

    /** With "Reopen last project on launch" off, a cold launch stays on the picker. */
    @Test
    fun reopenToggleOffLandsOnThePicker() = runTest {
        val backend = AppBackend(
            settledPrefs() + ("session.lastProject" to "/ws/app") + ("settings.appearance.reopenLastProject" to "false"),
        ).apply { analyticsConsent = false }
        val app = appState(backend)
        settleResume(backend, timeoutMs = 300)

        assertEquals(emptyList(), backend.opened)
        assertEquals(Screen.Projects, app.screen)
    }

    @Test
    fun pickerLandingLeavesBackToTheSystem() = runTest {
        val backend = settled() // the consent sheet is already answered
        val app = appState(backend)
        advanceUntilIdle()
        assertEquals(Screen.Projects, app.screen)
        assertFalse(app.canNavigateBack)
    }

    @Test
    fun hubReturnsWhereItWasOpenedFrom() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.openHub(Screen.Projects)
        assertEquals(Screen.Hub, app.screen)
        assertTrue(app.canNavigateBack)
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)

        backend.epochFlow.value++ // a project open, for the hub to be opened over
        advanceUntilIdle()
        app.openHub(Screen.Editor)
        // A hub sub-screen steps back to the hub, which then steps back to the editor it was opened from.
        app.navigateTo(Screen.Settings)
        app.navigateBack()
        assertEquals(Screen.Hub, app.screen)
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)
    }

    /** Back from a Settings page lands on the row the user opened, not at the top of the list. */
    @Test
    fun theSettingsListKeepsItsPlaceUntilSettingsIsLeft() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.openHub(Screen.Projects)
        val scroll = app.hubListScroll
        app.navigateTo(Screen.Storage)
        app.navigateBack()
        assertSame(scroll, app.hubListScroll, "kept through a visit to one of its pages")
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
        assertNotSame(scroll, app.hubListScroll, "a fresh list next time Settings is opened")
    }

    @Test
    fun wideHubLeavesFromAnyPaneOnBack() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        app.hubTwoPane = true
        backend.epochFlow.value++ // a project open, for the hub to be opened over
        advanceUntilIdle()

        // The wide hub and its destinations are one surface: stepping "back to the hub" would show the same
        // sidebar again, so Back leaves to where the hub was opened from.
        app.openHub(Screen.Editor)
        app.navigateTo(Screen.Settings)
        app.navigateTo(Screen.Storage)
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)

        app.openHub(Screen.Projects)
        app.openKeystoreManagerFromHub()
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)

        // Its create sub-screen still steps back to the manager, in the pane.
        app.openHub(Screen.Projects)
        app.openKeystoreManagerFromHub()
        app.navigateTo(Screen.KeystoreCreate)
        app.navigateBack()
        assertEquals(Screen.KeystoreManager, app.screen)
    }

    // ---- the editor needs an open project ----

    /** With no project ever loaded, the editor's backend throws "No project is open": a stale way in goes Home. */
    @Test
    fun theEditorIsNoDestinationBeforeAnyProjectIsOpen() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        assertFalse(app.projectOpen)

        app.navigateTo(Screen.Editor)
        assertEquals(Screen.Projects, app.screen)

        // The hub's default return is the editor; reached from Home without setting it, Back still lands Home.
        app.navigateTo(Screen.Hub)
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
    }

    /**
     * Closing a project only leaves the editor: its engine stays loaded behind the picker, so `epoch > 0` still
     * holds. A stale Back target or a link from Home (a plugin's Logs row) used to reopen the closed project,
     * and the session resume then recorded it as the project to reopen on the next launch.
     */
    @Test
    fun aClosedProjectIsNotReopenedByAStaleWayIntoTheEditor() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        app.openProject(ProjectInfo("app", "/ws/app", 1))
        advanceUntilIdle()
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals(Screen.Editor, app.screen)
        assertTrue(app.projectOpen)
        assertEquals("/ws/app", backend.prefs["session.lastProject"])

        app.navigateTo(Screen.Projects) // the editor's close
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertFalse(app.projectOpen)
        assertEquals(1, app.epoch, "the engine stays loaded behind the picker")
        assertEquals("", backend.prefs["session.lastProject"])

        // A Logs row / plugin file link from Home.
        app.navigateTo(Screen.Editor)
        advanceUntilIdle()
        assertEquals(Screen.Projects, app.screen)
        // A Back target left pointing at the editor.
        app.openHub(Screen.Editor)
        app.navigateBack()
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals(Screen.Projects, app.screen)
        assertEquals("", backend.prefs["session.lastProject"], "the closed project is not recorded for resume")

        // Opening it again from the picker is a real way in.
        app.openProject(ProjectInfo("app", "/ws/app", 1))
        advanceUntilIdle()
        assertEquals(Screen.Editor, app.screen)
        assertTrue(app.projectOpen)
    }

    /** The AI tab opens a project behind Home and its buttons take the user in, even after a visit elsewhere. */
    @Test
    fun theAiTabsOpenEditorEntersAProjectItLoadedBehindHome() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        app.selectHomeTab(HomeTab.Ai)
        backend.epochFlow.value++
        advanceUntilIdle()
        assertEquals(Screen.Projects, app.screen, "stays in the chat")

        app.openGetPlugins()
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
        app.enterEditor()
        assertEquals(Screen.Editor, app.screen)
        assertTrue(app.projectOpen)
    }

    /** A lesson's workspace is not a project the user opened: once the lesson ends, no way back leads into it. */
    @Test
    fun aFinishedLessonsWorkspaceIsNoEditorDestination() = runTest {
        val backend = AppBackend(settledPrefs(), workspaceOpens = true).apply { analyticsConsent = false }
        val app = appState(backend)
        advanceUntilIdle()
        app.openTrack("kotlin-basics")
        app.openLesson("kt-basics-capstone")
        app.openLessonInEditor("kt-basics-capstone", 1)
        advanceUntilIdle()
        assertEquals(Screen.Editor, app.screen)

        app.navigateBack()
        assertEquals(Screen.LessonTrack, app.screen)
        assertFalse(app.projectOpen)
        app.navigateTo(Screen.Editor)
        assertEquals(Screen.Projects, app.screen)
    }

    /** Settings over a project shows its pages; from Home (a project closed behind it or not) only the app's. */
    @Test
    fun settingsIsProjectScopedOnlyOverAnOpenProject() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        backend.epochFlow.value++
        advanceUntilIdle()

        app.openHub(Screen.Editor)
        assertTrue(app.hubInProject)
        app.openKeystoreManagerFromHub()
        assertTrue(app.keystoreInProject)
        app.navigateBack()
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)

        app.navigateTo(Screen.Projects) // close
        app.openHub(Screen.Projects)
        assertFalse(app.hubInProject)
        app.openKeystoreManagerFromHub()
        assertFalse(app.keystoreInProject)
    }

    // ---- links and notifications that arrive mid-edit ----

    /**
     * A store link (or a tapped notification) opening a store item over the editor: Back returns to the editor.
     * It used to leave for Home, closing the project without the close confirmation.
     */
    @Test
    fun aStoreItemOpenedOverTheEditorStepsBackToIt() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        backend.epochFlow.value++
        advanceUntilIdle()
        assertEquals(Screen.Editor, app.screen)

        app.openStoreItem(communityItem("some.project"))
        assertEquals(Screen.StoreItem, app.screen)
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)
        assertTrue(app.projectOpen)

        // The run's base is spent with it: a store item opened from Home goes back Home.
        app.navigateTo(Screen.Projects)
        app.openStoreItem(communityItem("some.project"))
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
    }

    /** The daily challenge's push names `challenges`, a home tab: its tap used to match no screen and do nothing. */
    @Test
    fun theDailyChallengeNotificationOpensTheChallengesTab() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.openNotificationTarget(dev.ide.ui.backend.UiNotificationTarget.Screen("challenges"))
        assertEquals(Screen.Projects, app.screen)
        assertEquals(HomeTab.Challenges, app.homeTab)
    }

    /** A `screen` route reaches only destinations that stand on their own, never one that needs a project. */
    @Test
    fun notificationRoutesReachOnlyStandAloneDestinations() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        for (route in listOf("ModuleConfig", "Run", "KeystoreImport", "IconManager", "PluginScreen", "Editor", "nope")) {
            app.openNotificationTarget(dev.ide.ui.backend.UiNotificationTarget.Screen(route))
            assertEquals(Screen.Projects, app.screen, route)
        }
        app.openNotificationTarget(dev.ide.ui.backend.UiNotificationTarget.Screen("You"))
        assertEquals(Screen.You, app.screen)
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
    }

    /** A notification tapped at launch wins over reopening the last project behind it. */
    @Test
    fun aNotificationTappedAtLaunchWinsOverSessionResume() = runTest {
        val backend = AppBackend(settledPrefs() + ("session.lastProject" to "/ws/app")).apply { analyticsConsent = false }
        val app = appState(backend)
        app.openNotificationTarget(dev.ide.ui.backend.UiNotificationTarget.Submissions)
        // The resume reads the project list on a real IO thread: give it the time it would have taken.
        repeat(30) {
            advanceUntilIdle()
            Thread.sleep(10)
        }
        advanceUntilIdle()
        assertTrue(backend.opened.isEmpty(), "resume stood down")
        assertEquals(Screen.You, app.screen)
    }

    // ---- Home's "Get plugins" entry ----

    @Test
    fun getPluginsReturnsHomeThroughAPluginPage() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        withScreens("vcs.history") {
            app.openGetPlugins()
            assertEquals(dev.ide.ui.screens.PluginsTab.Get, app.pluginsStartTab)
            app.openPluginScreen("vcs.history")
            app.navigateBack()
            assertEquals(Screen.Plugins, app.screen)
            assertEquals(Screen.Projects, app.pluginsReturn, "a plugin's page is part of the visit")
            assertEquals(dev.ide.ui.screens.PluginsTab.Get, app.pluginsStartTab)
            app.navigateBack()
            assertEquals(Screen.Projects, app.screen)
        }
    }

    /**
     * Leaving Get plugins sideways (another sidebar destination in the wide layout) kept its entry, so the
     * hub's Plugins tool later opened on the Get tab and its Back left an open project for Home.
     */
    @Test
    fun getPluginsEntryEndsWhenTheScreenIsLeftAnyWay() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        app.hubTwoPane = true

        app.openGetPlugins()
        app.openSettingsPage("appearance") // the sidebar's other destination
        assertEquals(Screen.Hub, app.pluginsReturn)
        assertNull(app.pluginsStartTab)
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)

        backend.epochFlow.value++
        advanceUntilIdle()
        app.openHub(Screen.Editor)
        app.navigateTo(Screen.Plugins)
        assertNull(app.pluginsStartTab, "the hub's Plugins tool opens on its usual tab")
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)

        // The phone: Plugins steps back to the hub, then the editor.
        app.hubTwoPane = false
        app.openHub(Screen.Editor)
        app.navigateTo(Screen.Plugins)
        app.navigateBack()
        assertEquals(Screen.Hub, app.screen)
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)
    }

    // ---- plugin-contributed screens ----

    /** Register a contributed screen for the duration of [block], so navigation to it is possible. */
    private fun withScreens(vararg ids: String, block: () -> Unit) {
        val handles = ids.map { id -> ScreenRegistry.register(ScreenContribution(id, id) { }) }
        try {
            block()
        } finally {
            handles.forEach { it.dispose() }
        }
    }

    @Test
    fun oneBackFromAPluginScreenReturnsToTheEditorAndKeepsTheProjectOpen() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        backend.epochFlow.value++ // opening a project lands in the editor
        advanceUntilIdle()
        assertEquals(Screen.Editor, app.screen)

        withScreens("vcs.history") {
            app.openPluginScreen("vcs.history")
            assertEquals(Screen.PluginScreen, app.screen)
            assertEquals("vcs.history", app.pluginScreenId)

            // One back returns to the editor and clears the id. It must NOT reach the picker: the screen used
            // to pop twice for one press, which closed the project.
            app.navigateBack()
            assertEquals(Screen.Editor, app.screen)
            assertNull(app.pluginScreenId)

            // Only a second back asks to leave the project, as it does from the editor anywhere else.
            app.navigateBack()
            assertEquals(Screen.Editor, app.screen)
            assertTrue(app.confirmCloseProject)
        }
    }

    @Test
    fun oneBackFromAPluginScreenOpenedAtThePickerReturnsToThePicker() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        assertEquals(Screen.Projects, app.screen)

        withScreens("vcs.clone") {
            app.openPluginScreen("vcs.clone")
            assertEquals(Screen.PluginScreen, app.screen)
            app.navigateBack()
            assertEquals(Screen.Projects, app.screen)
        }
    }

    /**
     * A contributed screen opening another is a push, not a replacement.
     *
     * They share one route, so this used to swap the id underneath and Back left the whole run at once: a
     * panel that opened a detail view of its own could not be returned to. Only the bottom of the run steps
     * out to the screen that opened the first one.
     */
    @Test
    fun steppingBetweenPluginScreensUnwindsOneAtATime() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        backend.epochFlow.value++
        advanceUntilIdle()

        withScreens("vcs.diff", "vcs.history") {
            app.openPluginScreen("vcs.diff")
            // A diff opening that file's history stays on the same destination, over the diff rather than
            // instead of it.
            app.openPluginScreen("vcs.history")
            assertEquals("vcs.history", app.pluginScreenId)

            app.navigateBack()
            assertEquals(Screen.PluginScreen, app.screen)
            assertEquals("vcs.diff", app.pluginScreenId, "Back returns to the screen that opened this one")

            app.navigateBack()
            assertEquals(Screen.Editor, app.screen)
            assertNull(app.pluginScreenId)
        }
    }

    /**
     * A screen's argument rides the back stack with it. It used to travel in a plugin-side global that the next
     * navigation overwrote, so Diff(A) -> History -> Diff(B) -> Back -> Back showed B's diff instead of A's.
     */
    @Test
    fun backRestoresTheArgumentEachScreenWasOpenedWith() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        backend.epochFlow.value++
        advanceUntilIdle()

        val context = object : dev.ide.ui.ext.ScreenContext {
            override val backend = backend
            override fun back() = app.navigateBack()
            override fun openScreen(id: String) = app.openPluginScreen(id)
        }
        withScreens("vcs.diff", "vcs.history") {
            context.openScreen("vcs.diff", "A.kt")
            context.openScreen("vcs.history", "A.kt")
            context.openScreen("vcs.diff", "B.kt")
            assertEquals("B.kt", app.pluginScreenArgument)

            app.navigateBack()
            assertEquals("vcs.history", app.pluginScreenId)
            assertEquals("A.kt", app.pluginScreenArgument)

            app.navigateBack()
            assertEquals("vcs.diff", app.pluginScreenId)
            assertEquals("A.kt", app.pluginScreenArgument)

            // An id-only navigation never inherits an argument left over from an earlier one.
            context.openScreen("vcs.history")
            assertNull(app.pluginScreenArgument)
        }
    }

    /** A contributed screen opened from a listing returns to that listing, not to the tab underneath it. */
    @Test
    fun aPluginScreenOpenedFromAStoreScreenReturnsToIt() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        val item = communityItem("some.project")
        app.openStoreItem(item)
        withScreens("vcs.history") {
            app.openPluginScreen("vcs.history")
            assertEquals(Screen.PluginScreen, app.screen)

            app.navigateBack()
            assertEquals(Screen.StoreItem, app.screen)
            assertEquals(item, app.storeItem)
            assertNull(app.pluginScreenId, "the contributed screen's id does not outlive it")
        }
    }

    /**
     * A contributed screen with somewhere of its own to go answers Back itself.
     *
     * Without this a plugin's wizard, detail pane or unsaved form was torn down whole on the first press,
     * and the plugin had no way to say otherwise.
     */
    @Test
    fun aPluginScreenCanClaimBackForItsOwnState() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        backend.epochFlow.value++
        advanceUntilIdle()

        withScreens("vcs.wizard") {
            app.openPluginScreen("vcs.wizard")
            var step = 2
            val claim = ScreenBackRegistry.register(enabled = { step > 0 }) { step-- }
            try {
                app.navigateBack()
                assertEquals(1, step)
                assertEquals(Screen.PluginScreen, app.screen, "a claimed press never reaches the host")

                app.navigateBack()
                assertEquals(0, step)
                assertEquals(Screen.PluginScreen, app.screen)

                // The claim is off at step 0, so the next press is the host's and pops the screen.
                app.navigateBack()
                assertEquals(Screen.Editor, app.screen)
            } finally {
                claim.dispose()
            }
        }
    }

    /** A claim registered by a screen that is no longer showing cannot swallow the host's Back. */
    @Test
    fun aClaimOnlyHoldsBackWhileItsScreenIsShowing() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        backend.epochFlow.value++
        advanceUntilIdle()

        var handled = 0
        val claim = ScreenBackRegistry.register { handled++ }
        try {
            // The editor is not a contributed screen, so the claim is not consulted.
            app.navigateBack()
            assertEquals(0, handled)
            assertTrue(app.confirmCloseProject)
        } finally {
            claim.dispose()
        }
    }

    @Test
    fun anUnregisteredPluginScreenIsNotNavigatedTo() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        backend.epochFlow.value++
        advanceUntilIdle()

        // A disabled plugin (or a stale action) names a screen nothing registered. Navigating anyway would
        // land on an empty destination, which is what the route used to try to recover from by popping again.
        app.openPluginScreen("nothing.registered")
        assertEquals(Screen.Editor, app.screen)
        assertNull(app.pluginScreenId)
    }

    @Test
    fun keystoreScreensStepBackThroughTheirManager() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.openKeystoreManager(Screen.ModuleConfig, inProject = true)
        app.openKeystoreImport("/tmp/release.jks")
        assertEquals(Screen.KeystoreImport, app.screen)
        assertEquals("/tmp/release.jks", app.keystoreImportPath)
        app.navigateBack()
        assertEquals(Screen.KeystoreManager, app.screen)
        app.navigateBack()
        assertEquals(Screen.ModuleConfig, app.screen)

        // Opened from the picker's hub there is no project, so signing assignment is not offered.
        app.openHub(Screen.Projects)
        app.openKeystoreManagerFromHub()
        assertFalse(app.keystoreInProject)
    }

    @Test
    fun aProjectOpenedFromTheAiTabKeepsTheUserInTheChat() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        // The AI tab opens (or the agent creates) a project in the background: no jump to the editor.
        app.selectHomeTab(HomeTab.Ai)
        backend.openProject("/ws/app")
        advanceUntilIdle()
        assertEquals(Screen.Projects, app.screen)

        // Anywhere else, opening a project still lands in the editor.
        app.selectHomeTab(HomeTab.Projects)
        backend.openProject("/ws/app")
        advanceUntilIdle()
        assertEquals(Screen.Editor, app.screen)
    }

    @Test
    fun homeTabStepsBackToThePickerBeforeExiting() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.selectHomeTab(HomeTab.Learn)
        assertTrue(app.canNavigateBack)
        app.navigateBack()
        assertEquals(HomeTab.Projects, app.homeTab)
        assertFalse(app.canNavigateBack)
    }

    @Test
    fun firstLaunchSheetsAreDismissedBeforeAnyScreen() = runTest {
        val backend = AppBackend() // nothing acknowledged yet
        val app = appState(backend)
        advanceUntilIdle()
        assertTrue(app.showOnboarding)
        assertTrue(app.showMigration)
        assertTrue(app.showAnalytics)

        app.navigateBack()
        assertFalse(app.showOnboarding)
        assertEquals("true", backend.prefs["onboarding.seen"])
        app.navigateBack()
        assertFalse(app.showMigration)
        assertEquals("true", backend.prefs["migration.acknowledged"])
        app.navigateBack()
        assertFalse(app.showAnalytics)
        assertEquals(false, backend.analyticsConsent)
        // The sheets never moved the user off the picker.
        assertEquals(Screen.Projects, app.screen)
        assertFalse(app.canNavigateBack)
    }

    @Test
    fun openingAProjectLandsInTheEditor() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.openProject(ProjectInfo("app", "/ws/app", 1))
        advanceUntilIdle()
        assertEquals(listOf("/ws/app"), backend.opened)
        assertEquals(Screen.Editor, app.screen)
        assertEquals(1, app.epoch)
        // Back on the editor asks before leaving the project; it stays open until the user confirms.
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)
        assertTrue(app.confirmCloseProject)
    }

    @Test
    fun deletingAProjectRefreshesThePicker() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        val before = app.projectsRefresh

        app.deleteProject(ProjectInfo("app", "/ws/app", 1))
        advanceUntilIdle()
        assertEquals(listOf("/ws/app"), backend.deleted)
        assertEquals(before + 1, app.projectsRefresh)
    }

    @Test
    fun unreadablePackageRaisesTheUnrecognizedNotice() = runTest {
        val backend = settled(importPreview = null)
        val app = appState(backend)
        advanceUntilIdle()

        app.openImportPackage("/tmp/not-a-project.txt")
        assertEquals(ImportError.Unrecognized, app.importError)
        assertEquals(Screen.Projects, app.screen)
        app.dismissImportError()
        assertNull(app.importError)
    }

    @Test
    fun readablePackageOpensTheImportPreview() = runTest {
        val preview = UiImportPreview(
            name = "Sample", description = "", author = "", createdBy = "", isAndroid = false,
            packageName = null, moduleCount = 1,
            modules = listOf(UiPackagedModule("app", "android-app", fileCount = 3, sizeBytes = 128)), fileCount = 3,
            uncompressedSizeBytes = 128, hasBundledDeps = false, icon = null, files = emptyList(),
            compatible = true,
        )
        val backend = settled(importPreview = preview)
        val app = appState(backend)
        advanceUntilIdle()

        app.openImportPackage("/tmp/sample.caproj")
        assertEquals(Screen.ImportProject, app.screen)
        assertEquals("/tmp/sample.caproj", app.importArchivePath)
        assertEquals(preview, app.importPreview)
        // Cancelling drops the preview so a later picker visit can't reopen the stale screen.
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
        assertNull(app.importPreview)
        assertNull(app.importArchivePath)
    }

    /** A package handed in mid-edit: Cancel goes back to the editor, not Home (which closed the project). */
    @Test
    fun cancellingAPackageHandedInMidEditReturnsToTheEditor() = runTest {
        val preview = UiImportPreview(
            name = "Sample", description = "", author = "", createdBy = "", isAndroid = false,
            packageName = null, moduleCount = 1,
            modules = listOf(UiPackagedModule("app", "android-app", fileCount = 3, sizeBytes = 128)), fileCount = 3,
            uncompressedSizeBytes = 128, hasBundledDeps = false, icon = null, files = emptyList(),
            compatible = true,
        )
        val backend = settled(importPreview = preview)
        val app = appState(backend)
        advanceUntilIdle()
        backend.epochFlow.value++
        advanceUntilIdle()
        assertEquals(Screen.Editor, app.screen)

        app.openImportPackage("/tmp/sample.caproj")
        assertEquals(Screen.ImportProject, app.screen)
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)
        assertTrue(app.projectOpen)

        // The next one, from Home, cancels back Home.
        app.navigateTo(Screen.Projects)
        app.openImportPackage("/tmp/sample.caproj")
        app.cancelImportPreview()
        assertEquals(Screen.Projects, app.screen)
    }

    /** A host that always "picks" the same Gradle folder. */
    private class PickingActions(
        private val path: String?,
        private val pickDir: Boolean = true,
        private val pickFile: Boolean = false,
    ) : FileActions {
        override val canImport = false
        override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) = Unit
        override val canShare = false
        override fun share(path: String) = Unit
        override val canPickDirectory = pickDir
        override fun pickDirectory(onPicked: (String?) -> Unit) = onPicked(path)
        override val canPickFile = pickFile
        override fun pickFile(extensions: List<String>, onPicked: (String?) -> Unit) = onPicked(path)
    }

    @Test
    fun gradleImportBlocksThenOpensTheConvertedProject() = runTest {
        val backend = settled(importResult = UiProjectResult(true, ""))
        val app = appState(backend, PickingActions("/external/gradle-app"))
        advanceUntilIdle()

        app.requestProjectImport()
        advanceUntilIdle()
        assertTrue(app.showImportModeChoice, "a Gradle folder still has a compatibility/convert choice to make")
        app.importGradleProject(convert = true)
        assertFalse(app.showImportModeChoice)
        assertTrue(app.importBusy) // blocking overlay is up while the copy + import runs

        advanceUntilIdle()
        assertFalse(app.importBusy)
        assertEquals(Screen.Editor, app.screen)
        // The convert prompt is a one-shot: the editor's state consumes it and it never fires again.
        assertTrue(app.pendingGradleConvert)
        app.consumeGradleConvertPrompt()
        assertFalse(app.pendingGradleConvert)
    }

    /**
     * A CodeAssist workspace is adopted as-is: no importer translates it, and the compatibility/convert
     * question is meaningless for it. Before this it was rejected as "not an importable Gradle project", so a
     * project folder that had dropped out of the picker could not be brought back.
     */
    @Test
    fun importingACodeAssistFolderSkipsTheGradleModeQuestion() = runTest {
        val backend = settled(folderKind = UiProjectFolderKind.CODE_ASSIST)
        val app = appState(backend, PickingActions("/external/my-project"))
        advanceUntilIdle()

        app.requestProjectImport()
        advanceUntilIdle()

        assertFalse(app.showImportModeChoice, "nothing to convert — the mode prompt must not appear")
        assertFalse(app.pendingGradleConvert)
        assertFalse(app.importBusy)
        assertEquals(Screen.Editor, app.screen)
    }

    /** A folder that is neither says so, rather than the flow quietly doing nothing. */
    @Test
    fun importingAnUnrecognisedFolderReportsIt() = runTest {
        val backend = settled(folderKind = UiProjectFolderKind.UNKNOWN)
        val app = appState(backend, PickingActions("/external/random"))
        advanceUntilIdle()

        app.requestProjectImport()
        advanceUntilIdle()

        assertFalse(app.showImportModeChoice)
        assertEquals(Screen.Projects, app.screen)
        assertTrue(app.importError != null, "an unrecognised folder must be reported")
    }

    /**
     * With both sources available the entry has to ask which one — a folder and a `.caproj` sit behind
     * different host APIs, so no picker can be launched until that is answered.
     */
    @Test
    fun importAsksWhichSourceWhenTheHostCanDoBoth() = runTest {
        val backend = settled(folderKind = UiProjectFolderKind.CODE_ASSIST)
        val app = appState(backend, PickingActions("/external/my-project", pickDir = true, pickFile = true))
        advanceUntilIdle()

        app.requestProjectImport()
        assertTrue(app.showImportSourceChoice, "both sources available — the entry must ask which")
        assertEquals(Screen.Projects, app.screen, "nothing is picked until the question is answered")

        app.chooseFolderImport()
        advanceUntilIdle()
        assertFalse(app.showImportSourceChoice)
        assertEquals(Screen.Editor, app.screen)
    }

    /** With only one source there is nothing to ask, so the entry goes straight to that picker. */
    @Test
    fun importSkipsTheSourceQuestionWhenOnlyOneIsAvailable() = runTest {
        val backend = settled(folderKind = UiProjectFolderKind.CODE_ASSIST)
        val app = appState(backend, PickingActions("/external/my-project", pickDir = true, pickFile = false))
        advanceUntilIdle()

        app.requestProjectImport()
        advanceUntilIdle()
        assertFalse(app.showImportSourceChoice, "only folders available — don't ask")
        assertEquals(Screen.Editor, app.screen)
    }

    /** Choosing the package route hands off to the existing `.caproj` preview screen. */
    @Test
    fun importCanTakeACaprojPackage() = runTest {
        val preview = UiImportPreview(
            name = "Shared", description = "", author = "", createdBy = "", isAndroid = false,
            packageName = null, moduleCount = 0, modules = emptyList(), fileCount = 0,
            uncompressedSizeBytes = 0, hasBundledDeps = false, icon = null, files = emptyList(),
            compatible = true,
        )
        val backend = settled(importPreview = preview)
        val app = appState(backend, PickingActions("/external/Shared.caproj", pickDir = true, pickFile = true))
        advanceUntilIdle()

        app.requestProjectImport()
        assertTrue(app.showImportSourceChoice)
        app.choosePackageImport()
        advanceUntilIdle()

        assertFalse(app.showImportSourceChoice)
        assertEquals(Screen.ImportProject, app.screen, "a .caproj opens the import preview")
    }

    /** A zip holding one Gradle project goes straight to the compatibility/convert question for it. */
    @Test
    fun aZipWithOneProjectImportsIt() = runTest {
        val backend = settled().apply {
            archive = UiProjectArchive(listOf(UiArchiveProject("/staging/app/App-main", "App-main", UiProjectFolderKind.GRADLE)))
        }
        val app = appState(backend, PickingActions("/external/App-main.zip", pickDir = true, pickFile = true))
        advanceUntilIdle()

        app.requestProjectImport()
        app.choosePackageImport()
        advanceUntilIdle()
        assertTrue(app.showImportModeChoice, "a Gradle project in the zip still chooses compatibility vs convert")
        app.importGradleProject(convert = false)
        advanceUntilIdle()
        assertEquals(listOf("/staging/app/App-main"), backend.imported)
        assertEquals(Screen.Editor, app.screen)
    }

    /** A zip holding several projects asks which; cancelling drops the unpacked copy. */
    @Test
    fun aZipWithSeveralProjectsAsksWhich() = runTest {
        val jetchat = UiArchiveProject("/staging/s/samples-main/Jetchat", "samples-main/Jetchat", UiProjectFolderKind.GRADLE)
        val mine = UiArchiveProject("/staging/s/samples-main/Mine", "samples-main/Mine", UiProjectFolderKind.CODE_ASSIST)
        val backend = settled().apply { archive = UiProjectArchive(listOf(jetchat, mine)) }
        val app = appState(backend, PickingActions("/external/samples.zip", pickDir = false, pickFile = true))
        advanceUntilIdle()

        app.requestProjectImport()
        advanceUntilIdle()
        assertEquals(listOf(jetchat, mine), app.archiveChoices)
        app.dismissArchiveChoice()
        advanceUntilIdle()
        assertEquals(null, app.archiveChoices)
        assertEquals(1, backend.discarded, "a cancelled choice drops the unpacked copy")

        app.requestProjectImport()
        advanceUntilIdle()
        app.chooseArchiveProject(mine)
        advanceUntilIdle()
        assertFalse(app.showImportModeChoice, "a CodeAssist project has no mode to choose")
        assertEquals(listOf(mine.path), backend.imported)
        assertEquals(Screen.Editor, app.screen)
    }

    /** A zip with no project in it, or that cannot be unpacked, says so; a file that is not a zip is unrecognised. */
    @Test
    fun aZipWithoutAProjectIsReported() = runTest {
        val backend = settled().apply { archive = UiProjectArchive(emptyList()) }
        val app = appState(backend, PickingActions("/external/notes.zip", pickDir = false, pickFile = true))
        advanceUntilIdle()
        app.requestProjectImport()
        advanceUntilIdle()
        assertEquals(ImportError.ArchiveFailed(), app.importError)

        backend.archive = UiProjectArchive(emptyList(), error = "The archive unpacks to more than 2 GB")
        app.dismissImportError()
        app.requestProjectImport()
        advanceUntilIdle()
        assertEquals(ImportError.ArchiveFailed("The archive unpacks to more than 2 GB"), app.importError)

        backend.archive = null
        app.dismissImportError()
        app.requestProjectImport()
        advanceUntilIdle()
        assertEquals(ImportError.Unrecognized, app.importError)
    }

    @Test
    fun failedGradleImportReportsTheEngineReason() = runTest {
        val backend = settled(importResult = UiProjectResult(false, "No settings.gradle"))
        val app = appState(backend, PickingActions("/external/not-gradle"))
        advanceUntilIdle()

        app.requestProjectImport()
        advanceUntilIdle()
        app.importGradleProject(convert = false)
        advanceUntilIdle()
        assertFalse(app.importBusy)
        assertEquals(Screen.Projects, app.screen)
        assertEquals(ImportError.GradleFailed("No settings.gradle"), app.importError)
    }

    @Test
    fun cancelledGradleImportStaysOnThePicker() = runTest {
        val backend = settled()
        val app = appState(backend, PickingActions(null))
        advanceUntilIdle()

        app.importGradleProject(convert = false)
        advanceUntilIdle()
        assertFalse(app.importBusy)
        assertNull(app.importError)
        assertEquals(Screen.Projects, app.screen)
    }

    @Test
    fun lessonPlayerStepsBackThroughItsTrack() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.openTrack("android-basics")
        assertEquals(Screen.LessonTrack, app.screen)
        app.openLesson("first-app")
        assertEquals(Screen.LessonPlayer, app.screen)
        assertEquals(0, app.lessonInitialStep)

        val epochBefore = app.learnEpoch
        app.navigateBack()
        assertEquals(Screen.LessonTrack, app.screen)
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
        // Returning re-reads progress so the just-finished steps show.
        assertTrue(app.learnEpoch > epochBefore)
    }

    @Test
    fun aTrackOpenedFromAPathStepsBackToThePath() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.openPath("path-android")
        assertEquals(Screen.LearnPath, app.screen)
        app.openTrackFromPath("kotlin-basics")
        assertEquals(Screen.LessonTrack, app.screen)
        app.openLesson("kt-hello")
        app.navigateBack()
        assertEquals(Screen.LessonTrack, app.screen)
        app.navigateBack()
        assertEquals(Screen.LearnPath, app.screen)
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)

        // A track opened from the Learn tab still goes straight back to the tab.
        app.openTrack("kotlin-basics")
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
    }

    @Test
    fun aLessonWithExercisesMovesIntoTheEditorAndBackOut() = runTest {
        val backend = AppBackend(settledPrefs(), workspaceOpens = true).apply { analyticsConsent = false }
        val app = appState(backend)
        advanceUntilIdle()

        app.openTrack("kotlin-basics")
        app.openLesson("kt-basics-capstone")
        assertEquals(Screen.LessonPlayer, app.screen)
        // The player hands a lesson with exercises over to the editor.
        app.openLessonInEditor("kt-basics-capstone", 1)
        advanceUntilIdle()
        assertEquals(listOf("kt-basics-capstone"), backend.workspaceRequests)
        assertEquals(Screen.Editor, app.screen)
        assertTrue(app.lessonInEditor)
        assertEquals(1, app.lessonInitialStep)
        // The workspace is never remembered as the project to reopen on the next launch.
        assertNotEquals("/home/learn-workspaces/kotlin", backend.prefs["session.lastProject"])

        // Back leaves the lesson for its track, without the close-project prompt.
        app.navigateBack()
        assertEquals(Screen.LessonTrack, app.screen)
        assertFalse(app.lessonInEditor)
        assertFalse(app.confirmCloseProject)
    }

    @Test
    fun openingAnotherProjectEndsTheLesson() = runTest {
        val backend = AppBackend(settledPrefs(), workspaceOpens = true).apply { analyticsConsent = false }
        val app = appState(backend)
        advanceUntilIdle()
        app.openLessonInEditor("kt-basics-capstone", 0)
        advanceUntilIdle()
        assertTrue(app.lessonInEditor)
        app.openProject(ProjectInfo("app", "/ws/app", 1))
        advanceUntilIdle()
        assertFalse(app.lessonInEditor, "the banner belongs to the lesson's workspace only")
        assertEquals(Screen.Editor, app.screen)
    }

    @Test
    fun aWorkspaceThatWillNotOpenLeavesTheLessonInThePlayer() = runTest {
        val backend = AppBackend(settledPrefs(), workspaceOpens = false).apply { analyticsConsent = false }
        val app = appState(backend)
        advanceUntilIdle()
        app.openLesson("kt-basics-capstone")
        app.openLessonInEditor("kt-basics-capstone", 0)
        advanceUntilIdle()
        assertFalse(app.lessonInEditor)
        assertTrue(app.lessonEditorUnavailable)
        assertEquals(Screen.LessonPlayer, app.screen)
    }

    @Test
    fun aDiscussionOpenedFromALessonReturnsToIt() = runTest {
        val backend = AppBackend(settledPrefs(), workspaceOpens = true).apply { analyticsConsent = false }
        val app = appState(backend)
        advanceUntilIdle()
        app.openLessonInEditor("kt-basics-capstone", 1)
        advanceUntilIdle()
        assertEquals(Screen.Editor, app.screen)

        app.openDiscussion("kt-basics-capstone", "kt-cap-i")
        assertEquals(Screen.LearnDiscussion, app.screen)
        app.openThread("t1")
        assertEquals(Screen.LearnThread, app.screen)
        app.navigateBack()
        assertEquals(Screen.LearnDiscussion, app.screen)
        assertEquals("kt-cap-i", app.communityStepId)
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen, "back to the lesson, not the home screen")
        assertTrue(app.lessonInEditor, "the lesson is still running")
    }

    @Test
    fun askingReplacesTheFormWithTheNewQuestion() = runTest {
        val backend = AppBackend(settledPrefs(), workspaceOpens = true).apply { analyticsConsent = false }
        val app = appState(backend)
        advanceUntilIdle()
        app.openLessonInEditor("kt-basics-capstone", 1)
        advanceUntilIdle()
        app.openAsk("kt-basics-capstone", "kt-cap-i", code = "fun main() {}", errorText = "Test 1 of 4 failed")
        assertEquals(Screen.LearnAsk, app.screen)
        assertEquals("fun main() {}", app.askPrefill?.code)
        app.questionPosted("t9")
        assertEquals(Screen.LearnThread, app.screen)
        assertEquals("t9", app.communityThreadId)
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen, "Back skips the posted form")
    }

    @Test
    fun anAnsweredQuestionPushOpensItsThread() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()
        app.openNotificationTarget(
            dev.ide.ui.backend.UiNotification(
                id = "n1", kind = dev.ide.ui.backend.UiNotificationKind.LEARN, title = "New answer to your question",
                target = dev.ide.ui.backend.UiNotificationTarget.Screen("learnThread:abc-123"),
            ),
        )
        assertEquals(Screen.LearnThread, app.screen)
        assertEquals("abc-123", app.communityThreadId)
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
    }

    @Test
    fun aFirstTimeUserIsOfferedTheCodeAssistTourOnce() = runTest {
        val backend = AppBackend(mapOf("migration.acknowledged" to "true", "legacy.recovery.seen" to "true"))
            .apply { analyticsConsent = false }
        val app = appState(backend)
        advanceUntilIdle()
        assertTrue(app.showOnboarding)
        app.dismissOnboarding()
        assertTrue(app.showLearnTourOffer)
        app.startLearnTour()
        assertFalse(app.showLearnTourOffer)
        assertEquals(Screen.LessonPlayer, app.screen)
        assertEquals("ca-ed-lightbulb", app.currentLessonId)
        assertEquals("true", backend.prefs["learn.tourOffered"])

        // Never again, even if onboarding is somehow dismissed a second time.
        val again = appState(backend)
        advanceUntilIdle()
        again.dismissOnboarding()
        assertFalse(again.showLearnTourOffer)
    }

    @Test
    fun learnWorkspacePathsAreRecognised() {
        assertTrue(isLearnWorkspace("/data/user/0/app/files/learn-workspaces/kotlin"))
        assertTrue(isLearnWorkspace("C:\\Users\\me\\.codeassist\\learn-workspaces\\java\\"))
        assertFalse(isLearnWorkspace("/sdcard/CodeAssist/projects/learn-workspaces-demo"))
        assertFalse(isLearnWorkspace("/ws/app"))
    }

    @Test
    fun resumingALessonJumpsStraightToItsStep() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.resumeLesson("android-basics", "first-app", step = 4)
        assertEquals(Screen.LessonPlayer, app.screen)
        assertEquals("android-basics", app.currentTrackId)
        assertEquals("first-app", app.currentLessonId)
        assertEquals(4, app.lessonInitialStep)
    }

    // ---- the store back stack ----

    /**
     * The reported bug: a listing opened from Explore, then its publisher's page, then Back landed on
     * Explore rather than on the listing. Every store screen returned to a fixed parent, so the one in
     * between was skipped.
     */
    @Test
    fun backFromAPublisherReturnsToTheListingItWasOpenedFrom() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.selectHomeTab(HomeTab.Store)
        val item = communityItem("some.project")
        app.openStoreItem(item)
        assertEquals(Screen.StoreItem, app.screen)

        app.openPublisher("tyron")
        assertEquals(Screen.PublisherProfile, app.screen)

        app.navigateBack()
        assertEquals(Screen.StoreItem, app.screen)
        assertEquals(item, app.storeItem, "the listing comes back with its own item, not an empty page")
        assertTrue(app.navigatingBack, "a pop animates the way the user came in")

        // One more step leaves the family for the Explore tab it was entered from.
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
        assertEquals(HomeTab.Store, app.homeTab)
    }

    /** A chain deeper than one step unwinds one screen at a time, each with the arguments it was showing. */
    @Test
    fun theStoreStackUnwindsOneScreenAtATime() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        val first = communityItem("first.project")
        val second = communityItem("second.project")
        app.openStoreItem(first)
        app.openPublisher("tyron")
        app.openStoreItem(second)
        assertEquals(second, app.storeItem)

        app.navigateBack()
        assertEquals(Screen.PublisherProfile, app.screen)
        assertEquals("tyron", app.publisherHandle)

        app.navigateBack()
        assertEquals(Screen.StoreItem, app.screen)
        assertEquals(first, app.storeItem, "the first listing is restored, not the one opened after it")

        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
    }

    /** Reached straight from Explore, Back still leaves for Explore: there is nothing under it. */
    @Test
    fun aStoreScreenEnteredFromExploreStillStepsBackToExplore() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.selectHomeTab(HomeTab.Store)
        app.openYou()
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
        assertEquals(HomeTab.Store, app.homeTab)
    }

    /** The review queue steps back to the profile that opened it, and that profile still steps out. */
    @Test
    fun moderationStepsBackThroughTheProfileThatOpenedIt() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.openYou()
        app.openModeration()
        app.navigateBack()
        assertEquals(Screen.You, app.screen)
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
    }

    /**
     * A sent submission replaces the form rather than stacking on it: Back from the You screen must not
     * walk into a page offering to send the same project again.
     */
    @Test
    fun aSentSubmissionDoesNotLeaveTheFormOnTheStack() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.openYou()
        app.openSubmitProject(ProjectInfo("app", "/ws/app", 1))
        assertEquals(Screen.SubmitProject, app.screen)

        app.finishSubmission()
        assertEquals(Screen.You, app.screen)
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
    }

    /**
     * Leaving the family for anywhere else drops the stack. Without this, opening a project from a listing
     * left the listing underneath, and Back out of the editor reopened the store instead of the picker.
     */
    @Test
    fun leavingTheStoreDropsWhatWasUnderIt() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        app.openStoreItem(communityItem("some.project"))
        app.openPublisher("tyron")
        app.openInstalledProject("/ws/app")
        advanceUntilIdle()
        assertEquals(Screen.Editor, app.screen)

        // Back is the editor's own (the close confirmation), not a pop to the listing that opened it.
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)
        assertTrue(app.confirmCloseProject)
    }

    @Test
    fun moduleConfigCarriesTheTabItWasOpenedOn() = runTest {
        val backend = settled()
        val app = appState(backend)
        advanceUntilIdle()

        backend.epochFlow.value++ // module config is opened from a project's editor
        advanceUntilIdle()
        app.openModuleConfig("app", ModulesTab.Dependencies)
        assertEquals(Screen.ModuleConfig, app.screen)
        assertEquals("app", app.configModule)
        assertEquals(ModulesTab.Dependencies, app.modulesTab)
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)
    }

    @Test
    fun themeModeFollowsTheSystemOnlyWhenAskedTo() {
        assertTrue(isDarkTheme(UiSettings(themeMode = "dark"), systemDark = false))
        assertFalse(isDarkTheme(UiSettings(themeMode = "light"), systemDark = true))
        assertTrue(isDarkTheme(UiSettings(themeMode = "system"), systemDark = true))
        assertFalse(isDarkTheme(UiSettings(themeMode = "system"), systemDark = false))
    }
}
