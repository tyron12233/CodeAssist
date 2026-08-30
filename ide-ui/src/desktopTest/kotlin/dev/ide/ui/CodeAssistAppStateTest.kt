package dev.ide.ui

import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiImportPreview
import dev.ide.ui.backend.UiPackagedModule
import dev.ide.ui.backend.UiProjectFolderKind
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
import kotlin.test.assertFalse
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
    ) : StubBackend() {
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
            epochFlow.value++
            return true
        }
        override suspend fun deleteProject(rootPath: String): Boolean {
            deleted += rootPath
            return true
        }
        override suspend fun previewImportPackage(archivePath: String): UiImportPreview? = importPreview
        override suspend fun importExternalProject(sourceRootPath: String): UiProjectResult = importResult
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

        app.openHub(Screen.Editor)
        // A hub sub-screen steps back to the hub, which then steps back to the editor it was opened from.
        app.navigateTo(Screen.Settings)
        app.navigateBack()
        assertEquals(Screen.Hub, app.screen)
        app.navigateBack()
        assertEquals(Screen.Editor, app.screen)
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
        // Back out of the editor and the picker re-reads the project list.
        app.navigateBack()
        assertEquals(Screen.Projects, app.screen)
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

    @Test
    fun moduleConfigCarriesTheTabItWasOpenedOn() = runTest {
        val backend = settled()
        val app = appState(backend)
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
