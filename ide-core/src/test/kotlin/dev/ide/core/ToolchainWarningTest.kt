package dev.ide.core

import dev.ide.android.support.AndroidFacet
import dev.ide.ksp.KspProcessorCatalog
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleId
import dev.ide.testkit.TestJars
import dev.ide.testkit.withTempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The editor banner's backend: a bundled KSP processor whose generated code needs a newer runtime than the
 * module declares is reported as a [dev.ide.ui.backend.UiToolchainWarning] on the files of that module, with
 * two outcomes. The fix aligns the declared coordinate to the version the IDE bundles; "build anyway" records
 * that the user accepts it, which unblocks source generation and has to survive a reload (the build process
 * reads the same persisted facet).
 *
 * The real case: Hilt/Dagger. `dagger.internal.Provider` ships only in the runtime generation the bundled
 * 2.60.1 processor belongs to, so a project pinning 2.48 gets generated `_Factory` classes it cannot compile.
 */
class ToolchainWarningTest {

    private val hiltWarningId = "ksp-runtime:hilt"

    @Test
    fun aStaleRuntimeIsReportedForItsOwnModuleWithADirectionalFixLabel() {
        withTempDir("toolchain-warn") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                assertTrue(
                    ide.moduleService.toolchainWarnings().isEmpty(),
                    "a project with no bundled processor active must report nothing",
                )

                declareStaleHilt(ide, dir)

                // Project-wide and with NO file open: the problem is a property of the module's configuration,
                // so it is knowable as soon as the project loads.
                val warnings = ide.moduleService.toolchainWarnings()
                val hilt = assertNotNull(warnings.firstOrNull { it.id == hiltWarningId }, "expected a hilt warning: $warnings")
                assertEquals(1, warnings.size, "only the offending module is reported: $warnings")
                assertEquals("app", hilt.moduleName)
                assertTrue("app" in hilt.title, "the title names the module, since several can be listed: ${hilt.title}")
                assertTrue("dagger.internal.Provider" in hilt.detail, "names the missing symbol: ${hilt.detail}")
                // The bundled version (2.60.1) is NEWER than the declared 2.48, so the fix is an update.
                assertEquals("Update hilt-android to 2.60.1", hilt.fixLabel)
                assertTrue(hilt.acceptable, "the user may choose to build anyway")

                // The sibling modules declare no Hilt, so nothing is attributed to them.
                assertTrue(
                    warnings.none { it.moduleName != "app" },
                    "a module that declares none of the bundled processors must stay quiet: $warnings",
                )
            }
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * "Build anyway" stops the banner AND stops source generation from refusing, and must survive a reload:
     * the build runs in its own process off the persisted model, so a choice held only in memory would be
     * silently forgotten and the build would go back to refusing.
     */
    @Test
    fun acceptingIsPersistedAndUnblocksGeneration() {
        withTempDir("toolchain-accept") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                declareStaleHilt(ide, dir)
                assertTrue(ide.moduleService.toolchainWarnings().any { it.id == hiltWarningId })

                val accepted = ide.moduleService.acceptToolchainWarning("app", hiltWarningId)
                assertTrue(accepted.success, accepted.message)
                assertTrue("still expected to fail" in accepted.message, "the message must not read like a fix: ${accepted.message}")

                assertTrue(
                    ide.moduleService.toolchainWarnings().none { it.id == hiltWarningId },
                    "an accepted warning leaves the banner",
                )
                assertEquals(
                    setOf("hilt"),
                    appFacet(ide).buildFeatures.kspRuntimeMismatchAccepted,
                    "the acceptance is on the module's facet",
                )
            }
            // Reopened from disk: the persisted acceptance is what the build process will read.
            IdeServices.open(dir).use { ide ->
                assertEquals(setOf("hilt"), appFacet(ide).buildFeatures.kspRuntimeMismatchAccepted)
            }
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun anUnknownWarningIdIsRejected() {
        withTempDir("toolchain-unknown") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                assertFalse(ide.moduleService.acceptToolchainWarning("app", "ksp-runtime:nope").success)
                assertFalse(ide.moduleService.acceptToolchainWarning("app", "something-else").success)
                assertFalse(ide.moduleService.acceptToolchainWarning("no-such-module", hiltWarningId).success)
            }
            dir.toFile().deleteRecursively()
        }
    }

    /** The catalog is the source of truth for what "too old" means; the id the UI round-trips must match it. */
    @Test
    fun theWarningIdCarriesTheCatalogProcessorId() {
        val hilt = assertNotNull(KspProcessorCatalog.blessed().processors.firstOrNull { it.id == "hilt" })
        assertEquals("ksp-runtime:${hilt.id}", hiltWarningId)
        assertEquals(listOf("dagger/internal/Provider.class"), hilt.requiredRuntimeClasses)
    }

    /**
     * Put a pre-2.5x Hilt on `app`: the marker so the processor is applicable, `dagger.internal.Factory` so the
     * rest of the generated imports would resolve, and NO `dagger.internal.Provider` (the class that generation
     * of Dagger did not have). Declared directly, since the catalog only activates on a declared runtime.
     */
    private fun declareStaleHilt(ide: IdeServices, dir: Path) {
        val jar = TestJars.buildJar(dir.resolve("dagger-2.48.jar")) {
            entry("dagger/hilt/InstallIn.class", ByteArray(0))
            entry("dagger/internal/Factory.class", ByteArray(0))
        }
        val coordinate = "com.google.dagger:hilt-android:2.48"
        ide.store.workspace.libraryTable.create(coordinate).apply {
            kind = LibraryKind.JAR
            addClassesRoot(ide.store.vfs.fileFor(jar))
            commit()
        }
        ide.store.workspace.projects.first { p -> p.modules.any { it.name == "app" } }
            .beginModification().apply {
                module(ModuleId("app")).addDependency(
                    LibraryDependency(LibraryRef(coordinate), DependencyScope.IMPLEMENTATION)
                )
                commit()
            }
        ide.store.save()
    }

    private fun appFacet(ide: IdeServices): AndroidFacet =
        assertNotNull(
            ide.modules().first { it.name == "app" }.facets.get(AndroidFacet.KEY),
            "the demo app is an Android module",
        )
}
