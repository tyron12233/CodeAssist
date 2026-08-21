package dev.ide.ui.screens

import dev.ide.ui.StubBackend
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.UiAddResult
import dev.ide.ui.backend.UiArtifactHit
import dev.ide.ui.backend.UiBuildFeature
import dev.ide.ui.backend.UiBuildFeatures
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.backend.UiDepKind
import dev.ide.ui.backend.UiDependencyNode
import dev.ide.ui.backend.UiKeystoreResult
import dev.ide.ui.backend.UiKeystoreSpec
import dev.ide.ui.backend.UiLogEntry
import dev.ide.ui.backend.UiModuleDeps
import dev.ide.ui.backend.UiStorageCategory
import dev.ide.ui.backend.UiStorageReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screen state holders own the loading, mutation, and confirmation logic that used to live inside the
 * screen composables, so it runs here without Compose UI: a fake backend, a test scheduler, and the holder.
 */
class ScreenStateTest {

    private val scopes = ArrayList<CoroutineScope>()

    @AfterTest
    fun cancelScopes() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun TestScope.newScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler)).also { scopes += it }

    private fun node(coordinate: String, exclusions: List<String> = emptyList()) = UiDependencyNode(
        coordinate = coordinate,
        group = coordinate.substringBefore(':'),
        name = coordinate.split(':').getOrElse(1) { "" },
        version = coordinate.substringAfterLast(':'),
        kind = UiDepKind.Jar,
        declared = true,
        exclusions = exclusions,
    )

    // ---- dependencies ----

    private open class DepsBackend : StubBackend() {
        var moduleDeps = UiModuleDeps("app", "codeassist", true, emptyList(), emptyList())
        var loads = 0
        val removed = ArrayList<String>()
        val exclusions = ArrayList<Pair<String, List<String>>>()
        var addResult = UiAddResult(true, "Added")

        override suspend fun moduleDependencies(moduleName: String): UiModuleDeps {
            loads++
            return moduleDeps
        }
        override fun removeDependency(moduleName: String, coordinate: String): Boolean {
            removed += coordinate
            return true
        }
        override suspend fun setDependencyExclusions(
            moduleName: String,
            coordinate: String,
            exclusions: List<String>,
        ): UiAddResult {
            this.exclusions += coordinate to exclusions
            return addResult
        }
    }

    @Test
    fun dependencyPaneLoadsOnceAndReloadsAfterAChange() = runTest {
        val backend = DepsBackend()
        val state = DependenciesPaneState(backend, "app", newScope())
        runCurrent()
        assertEquals(1, backend.loads)
        assertEquals(backend.moduleDeps, state.deps)
        assertFalse(state.loading)

        state.askRemove("com.example:lib:1.0")
        assertEquals("com.example:lib:1.0", state.pendingRemove)
        state.confirmRemove()
        runCurrent()
        assertEquals(listOf("com.example:lib:1.0"), backend.removed)
        assertNull(state.pendingRemove)
        assertEquals(2, backend.loads) // the removal re-read the module
        assertEquals(ToastMsg("Removed lib:1.0", error = false), state.toast)
    }

    @Test
    fun excludingATransitiveAppendsItToTheRootExclusions() = runTest {
        val backend = DepsBackend()
        val state = DependenciesPaneState(backend, "app", newScope())
        runCurrent()

        state.excludeTransitive(node("com.example:lib:1.0", listOf("org.old:old")), node("org.bad:bad:2.0"))
        runCurrent()
        assertEquals(
            listOf("com.example:lib:1.0" to listOf("org.old:old", "org.bad:bad")),
            backend.exclusions,
        )
        assertEquals(ToastMsg("Excluded org.bad:bad from lib", error = false), state.toast)
    }

    @Test
    fun aFailedExclusionToastsTheReasonAndDoesNotReload() = runTest {
        val backend = DepsBackend()
        backend.addResult = UiAddResult(false, "Not declared here")
        val state = DependenciesPaneState(backend, "app", newScope())
        runCurrent()
        val loadsBefore = backend.loads

        state.removeExclusion(node("com.example:lib:1.0", listOf("org.bad:bad")), "org.bad:bad")
        runCurrent()
        assertEquals(ToastMsg("Not declared here", error = true), state.toast)
        assertEquals(loadsBefore, backend.loads)
    }

    @Test
    fun addingADependencyClosesTheFlowAndReReadsTheModule() = runTest {
        val backend = DepsBackend()
        val state = DependenciesPaneState(backend, "app", newScope())
        runCurrent()
        state.openAdd()
        assertTrue(state.addOpen)

        state.onDependencyAdded(UiAddResult(true, "Added lib"))
        runCurrent()
        assertFalse(state.addOpen)
        assertEquals(2, backend.loads)
        assertEquals(ToastMsg("Added lib", error = false), state.toast)

        // A failed add leaves the flow open so the user can correct it.
        state.openAdd()
        state.onDependencyAdded(UiAddResult(false, "No such artifact"))
        runCurrent()
        assertTrue(state.addOpen)
    }

    @Test
    fun addFlowSearchesOnlyRealQueriesAndAddsWithTheChosenConfiguration() = runTest {
        val backend = object : DepsBackend() {
            val searches = ArrayList<String>()
            val added = ArrayList<Triple<String, String, String?>>()
            override suspend fun searchArtifacts(query: String, moduleName: String): List<UiArtifactHit> {
                searches += query
                return emptyList()
            }
            override suspend fun addDependency(
                moduleName: String,
                coordinate: String,
                scope: String,
                exclusions: List<String>,
                variant: String?,
            ): UiAddResult {
                added += Triple(coordinate, scope, variant)
                return UiAddResult(true, "Added")
            }
        }
        val pane = DependenciesPaneState(backend, "app", newScope())
        val state = AddDependencyState(pane, FileActions.None, newScope())
        advanceUntilIdle()
        assertTrue(backend.searches.isEmpty()) // the empty query never hits the network

        state.updateQuery("a") // below the 2-character floor
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertTrue(backend.searches.isEmpty())
        assertFalse(state.searching)

        state.updateQuery("okhttp")
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertEquals(listOf("okhttp"), backend.searches)

        state.selectConfiguration("api")
        state.add("com.squareup.okhttp3:okhttp:4.12.0")
        assertTrue(state.busy)
        advanceUntilIdle()
        assertFalse(state.busy)
        assertEquals(listOf(Triple("com.squareup.okhttp3:okhttp:4.12.0", "api", null as String?)), backend.added)
    }

    // ---- module configuration ----

    private class FeaturesBackend : StubBackend() {
        var features = UiBuildFeatures("app", listOf(UiBuildFeature("viewBinding", "View Binding", "", false)))
        var loads = 0
        val toggles = ArrayList<Pair<String, Boolean>>()
        var result = UiConfigResult(true, "Enabled View Binding")

        override suspend fun getBuildFeatures(moduleName: String): UiBuildFeatures {
            loads++
            return features
        }
        override suspend fun setBuildFeature(moduleName: String, feature: String, enabled: Boolean): UiConfigResult {
            toggles += feature to enabled
            return result
        }
    }

    @Test
    fun togglingABuildFeatureBlocksTheOthersUntilItLands() = runTest {
        val backend = FeaturesBackend()
        val state = ModuleTogglesState<UiBuildFeatures>(
            newScope(),
            load = { backend.getBuildFeatures("app") },
            toggle = { id, enabled -> backend.setBuildFeature("app", id, enabled) },
        )
        runCurrent()
        assertEquals(1, backend.loads)
        assertEquals(backend.features, state.model)
        assertTrue(state.idle)

        state.setEnabled("viewBinding", true)
        assertEquals("viewBinding", state.busyId)
        assertFalse(state.idle)
        state.setEnabled("compose", true) // ignored while one is in flight
        runCurrent()

        assertEquals(listOf("viewBinding" to true), backend.toggles)
        assertNull(state.busyId)
        assertEquals(2, backend.loads)
        assertEquals(ConfigToast.Message("Enabled View Binding", error = false), state.toast)
    }

    @Test
    fun aFailedToggleReportsTheReasonWithoutReReading() = runTest {
        val backend = FeaturesBackend()
        backend.result = UiConfigResult(false, "Not an Android module")
        val state = ModuleTogglesState<UiBuildFeatures>(
            newScope(),
            load = { backend.getBuildFeatures("app") },
            toggle = { id, enabled -> backend.setBuildFeature("app", id, enabled) },
        )
        runCurrent()

        state.setEnabled("viewBinding", true)
        runCurrent()
        assertEquals(1, backend.loads)
        assertEquals(ConfigToast.Message("Not an Android module", error = true), state.toast)
    }

    @Test
    fun removingAModuleNamesItInTheToast() = runTest {
        val backend = object : StubBackend() {
            val removed = ArrayList<String>()
            override fun removeModule(name: String): Boolean {
                removed += name
                return true
            }
        }
        val state = ModulesListState(backend, newScope())
        state.askRemove("app")
        state.confirmRemove()
        runCurrent()
        assertEquals(listOf("app"), backend.removed)
        assertEquals(ConfigToast.Removed("app"), state.toast)
        assertNull(state.pendingRemove)
    }

    // ---- storage ----

    @Test
    fun clearAllCachesSkipsTheDestructiveCategoryAndSumsWhatItFreed() = runTest {
        val backend = object : StubBackend() {
            val cleared = ArrayList<String>()
            override suspend fun storageReport() = UiStorageReport(
                storageRootPath = "/storage",
                totalBytes = 3_000,
                categories = listOf(
                    UiStorageCategory("caches", 1_000, "blue", clearable = true, destructive = false),
                    UiStorageCategory("indexes", 500, "green", clearable = true, destructive = false),
                    UiStorageCategory("sdk", 1_500, "red", clearable = true, destructive = true),
                    UiStorageCategory("source", 0, "grey", clearable = false, destructive = false),
                ),
                projects = emptyList(),
                openProjectRootPath = null,
            )
            override suspend fun clearStorageCategory(id: String): Boolean {
                cleared += id
                return true
            }
        }
        val state = StorageScreenState(backend, newScope())
        advanceUntilIdle()

        state.clearAllCaches()
        advanceUntilIdle()
        assertEquals(listOf("caches", "indexes"), backend.cleared)
        assertEquals(1_500L, state.freedBytes)
        state.dismissFreedToast()
        assertNull(state.freedBytes)
    }

    @Test
    fun clearingTheSdkGoesThroughItsConfirmation() = runTest {
        val backend = object : StubBackend() {
            val cleared = ArrayList<String>()
            override suspend fun storageReport() = UiStorageReport("/storage", 0, emptyList(), emptyList(), null)
            override suspend fun clearStorageCategory(id: String): Boolean {
                cleared += id
                return true
            }
        }
        val state = StorageScreenState(backend, newScope())
        advanceUntilIdle()
        val sdk = UiStorageCategory("sdk", 2_000, "red", clearable = true, destructive = true)

        state.askClearSdk(sdk)
        assertEquals(sdk, state.pendingSdkClear)
        state.cancelClearSdk()
        advanceUntilIdle()
        assertTrue(backend.cleared.isEmpty()) // cancelling clears nothing

        state.askClearSdk(sdk)
        state.confirmClearSdk()
        advanceUntilIdle()
        assertEquals(listOf("sdk"), backend.cleared)
        assertEquals(2_000L, state.freedBytes)
    }

    // ---- keystores ----

    @Test
    fun creatingAKeystoreTrimsTheFormAndFillsInTheDefaults() = runTest {
        val backend = object : StubBackend() {
            var spec: UiKeystoreSpec? = null
            override suspend fun createKeystore(spec: UiKeystoreSpec): UiKeystoreResult {
                this.spec = spec
                return UiKeystoreResult(true, "Created")
            }
        }
        val state = KeystoreCreateState(backend, newScope())
        state.updateName("  release  ")
        state.updateAlias("   ")
        state.updateCommonName(" Tyron ")
        state.updateOrganization("  ")
        state.updateValidity("3x0") // digits only
        var done = false

        state.create { done = true }
        advanceUntilIdle()
        val spec = backend.spec
        assertEquals("release", spec?.name)
        assertEquals("key0", spec?.keyAlias) // blank falls back to the default alias
        assertEquals("Tyron", spec?.commonName)
        assertNull(spec?.organization) // blank means "not set", not an empty field
        assertEquals(30, spec?.validityYears)
        assertTrue(done)
        assertFalse(state.busy)
    }

    @Test
    fun aFailedKeystoreCreateKeepsTheFormOpenWithTheReason() = runTest {
        val backend = object : StubBackend() {
            override suspend fun createKeystore(spec: UiKeystoreSpec) = UiKeystoreResult(false, "Password too short")
        }
        val state = KeystoreCreateState(backend, newScope())
        var done = false

        state.create { done = true }
        advanceUntilIdle()
        assertFalse(done)
        assertEquals("Password too short", state.error)
        assertFalse(state.busy)
    }

    // ---- logs ----

    @Test
    fun logFiltersNarrowByLevelSourceAndText() = runTest {
        val entries = listOf(
            UiLogEntry("INFO", "Index", "indexed 40 files", 1, "1", "main"),
            UiLogEntry("ERROR", "Build", "compile failed", 2, "2", "main", source = "kotlin"),
            UiLogEntry("WARN", "Build", "deprecated api", 3, "3", "main", source = "kotlin"),
        )
        val backend = object : StubBackend() {
            override fun recentLogs(): List<UiLogEntry> = entries
        }
        val state = LogsScreenState(backend, newScope())
        // Pause the live tail: it re-arms itself forever, which the test scheduler's virtual clock would
        // chase indefinitely. The buffer is already seeded, which is what the filters run over.
        state.togglePaused()

        assertEquals(3, state.shown.size)
        assertEquals("deprecated api", state.shown.first().message) // newest first

        state.selectFilter(LogFilter.Errors)
        assertEquals(listOf("compile failed"), state.shown.map { it.message })

        state.selectFilter(LogFilter.All)
        state.selectSource("kotlin")
        assertEquals(listOf("deprecated api", "compile failed"), state.shown.map { it.message })

        state.updateQuery("compile")
        assertEquals(listOf("compile failed"), state.shown.map { it.message })

        // A source that has aged out of the buffer is ignored rather than emptying the list.
        state.updateQuery("")
        state.selectSource("gone")
        assertNull(state.activeSource)
        assertEquals(3, state.shown.size)
    }
}
