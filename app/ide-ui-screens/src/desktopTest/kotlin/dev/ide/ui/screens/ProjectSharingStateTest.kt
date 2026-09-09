package dev.ide.ui.screens

import dev.ide.ui.StubBackend
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiExportModule
import dev.ide.ui.backend.UiExportOptions
import dev.ide.ui.backend.UiExportPlan
import dev.ide.ui.backend.UiImportPreview
import dev.ide.ui.backend.UiPackagedModule
import dev.ide.ui.backend.UiProjectResult
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export/import sharing screens keep their decisions in [ExportProjectState] / [ImportPreviewState], so
 * the parts that matter — a module selection that stays buildable, and importing under a chosen name — are
 * exercised here without Compose.
 */
class ProjectSharingStateTest {

    /** `app` needs `core`, `core` needs `util`, and `extras` stands alone. */
    private val plan = UiExportPlan(
        modules = listOf(
            UiExportModule("app", "android-app", "app", 80, 8_000, listOf("core")),
            UiExportModule("core", "java-lib", "core", 30, 3_000, listOf("util")),
            UiExportModule("util", "java-lib", "util", 10, 1_000, emptyList()),
            UiExportModule("extras", "java-lib", "extras", 5, 500, emptyList()),
        ),
        bundledDepsBytes = 20_000,
    )

    private class SharingBackend(
        private val plan: UiExportPlan? = null,
        private val destination: String? = null,
    ) : StubBackend() {
        var exported: UiExportOptions? = null
        var importedAs: String? = null

        override suspend fun exportPlan(rootPath: String): UiExportPlan? = plan
        override suspend fun exportProject(rootPath: String, options: UiExportOptions): String? {
            exported = options
            return "/exports/demo.caproj"
        }

        override suspend fun importDestination(projectName: String): String? = destination?.let { "$it/$projectName" }
        override suspend fun importPackage(archivePath: String, projectName: String?): UiProjectResult {
            importedAs = projectName
            return UiProjectResult(true, "")
        }
    }

    private fun preview(name: String = "Sample") = UiImportPreview(
        name = name, description = "", author = "", createdBy = "CodeAssist", isAndroid = false,
        packageName = null, moduleCount = 1,
        modules = listOf(UiPackagedModule("app", "java-lib", 3, 128)),
        fileCount = 3, uncompressedSizeBytes = 128, hasBundledDeps = false, icon = null,
        files = emptyList(), compatible = true,
    )

    @Test
    fun droppingAModuleTakesItsDependentsWithIt() = runTest {
        val backend = SharingBackend(plan)
        val state = ExportProjectState(backend, ProjectInfo("Demo", "/ws/demo", 4), "", {}, this)
        advanceUntilIdle()

        // `util` is needed by `core`, which is needed by `app` — dropping it drops the chain above it.
        state.toggleModule("util")

        assertFalse(state.isIncluded("util"))
        assertFalse(state.isIncluded("core"), "core depends on util")
        assertFalse(state.isIncluded("app"), "app depends on core, which depends on util")
        assertTrue(state.isIncluded("extras"), "an unrelated module is untouched")
        assertEquals(setOf("extras"), state.includedModules())
    }

    @Test
    fun addingAModuleBackBringsInWhatItNeeds() = runTest {
        val backend = SharingBackend(plan)
        val state = ExportProjectState(backend, ProjectInfo("Demo", "/ws/demo", 4), "", {}, this)
        advanceUntilIdle()

        state.toggleModule("util")
        state.toggleModule("app")

        assertTrue(state.isIncluded("app"))
        assertTrue(state.isIncluded("core"), "app's dependency comes back with it")
        assertTrue(state.isIncluded("util"), "and its dependency's dependency")
        assertNull(state.includedModules(), "everything is back in, so the export packages the lot")
    }

    @Test
    fun refusesToEmptyThePackage() = runTest {
        val backend = SharingBackend(UiExportPlan(listOf(plan.modules.first { it.name == "extras" })))
        val state = ExportProjectState(backend, ProjectInfo("Demo", "/ws/demo", 1), "", {}, this)
        advanceUntilIdle()

        state.toggleModule("extras")

        assertTrue(state.isIncluded("extras"), "the only module can't be dropped")
    }

    @Test
    fun exportCarriesTheSelectionAndScreenshots() = runTest {
        val backend = SharingBackend(plan)
        val state = ExportProjectState(backend, ProjectInfo("Demo", "/ws/demo", 4), "Ada", {}, this)
        advanceUntilIdle()

        state.toggleModule("extras")
        state.addScreenshot("/pics/one.png")
        state.addScreenshot("/pics/one.png") // the same image twice is still one screenshot
        state.updateBundleDeps(true)
        state.updateDescription("A demo")
        state.export()
        advanceUntilIdle()

        val options = requireNotNull(backend.exported)
        assertEquals(setOf("app", "core", "util"), options.includedModules)
        assertEquals(listOf("/pics/one.png"), options.screenshotPaths)
        assertTrue(options.bundleDependencies)
        assertEquals("Ada", options.author)
        assertEquals("A demo", options.description)
        assertEquals(ExportPhase.Done("/exports/demo.caproj"), state.phase)
    }

    @Test
    fun estimateFollowsTheSelectionAndBundling() = runTest {
        val backend = SharingBackend(plan)
        val state = ExportProjectState(backend, ProjectInfo("Demo", "/ws/demo", 4), "", {}, this)
        advanceUntilIdle()

        assertEquals(12_500, state.estimatedBytes())
        state.updateBundleDeps(true)
        assertEquals(32_500, state.estimatedBytes(), "bundling adds the resolved dependencies")
        state.toggleModule("extras")
        assertEquals(32_000, state.estimatedBytes(), "a dropped module stops counting")
    }

    @Test
    fun importUsesTheEditedNameAndShowsWhereItLands() = runTest {
        val backend = SharingBackend(destination = "/home/projects")
        val state = ImportPreviewState(backend, "/tmp/sample.caproj", preview(), this)

        advanceUntilIdle()
        assertEquals("/home/projects/Sample", state.destination, "the package's own name to start")

        state.updateName("My Copy")
        advanceUntilIdle()
        assertEquals("/home/projects/My Copy", state.destination)

        var imported = false
        state.import { imported = true }
        advanceUntilIdle()

        assertEquals("My Copy", backend.importedAs)
        assertTrue(imported)
    }

    @Test
    fun aClearedNameFallsBackToThePackagesOwn() = runTest {
        val backend = SharingBackend(destination = "/home/projects")
        val state = ImportPreviewState(backend, "/tmp/sample.caproj", preview("Sample"), this)

        state.updateName("   ")
        state.import {}
        advanceUntilIdle()

        assertEquals("Sample", backend.importedAs)
    }
}
