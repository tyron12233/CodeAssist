package dev.ide.core

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.sync.Detection
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalProjectModel
import dev.ide.model.sync.ExternalSourceSet
import dev.ide.model.sync.ModelOwnership
import dev.ide.model.sync.PROJECT_IMPORTER_EP
import dev.ide.model.sync.ProjectImporter
import dev.ide.model.sync.SyncOutcome
import dev.ide.model.sync.SyncRequest
import dev.ide.platform.PluginId
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A plugin brings its own build system's project model in through [PROJECT_IMPORTER_EP]: the host detects the
 * folder, applies the snapshot the importer returns, binds the project to that build system, records what was
 * read, and offers a sync once a watched file changes.
 */
class ProjectImporterExtensionTest {

    private val bazel = BuildSystemId("bazel-test")

    /**
     * A minimal importer for a made-up build system: `workspace.bzl` at the root declares one module per line
     * as `module <name> <dir>`, each with an `implementation` dependency named in the same line.
     */
    private class BzlImporter : ProjectImporter {
        override val id = BuildSystemId("bazel-test")
        override val displayName = "Bazel (test)"
        override val ownership = ModelOwnership.EXTERNAL

        override fun detect(root: Path): Detection? {
            val marker = root.resolve(MARKER)
            if (!Files.isRegularFile(marker)) return null
            return Detection(name = root.fileName?.toString() ?: "bzl", markers = listOf(marker), confidence = 5)
        }

        override fun syncFiles(): List<String> = listOf(MARKER, "**/BUILD.bzl")

        override suspend fun resolve(request: SyncRequest): SyncOutcome {
            val text = runCatching { request.root.resolve(MARKER).readText() }.getOrNull()
                ?: return SyncOutcome.failed("No $MARKER at ${request.root}")
            val modules = text.lines().mapNotNull { line ->
                val parts = line.trim().split(" ").filter { it.isNotEmpty() }
                if (parts.size < 3 || parts[0] != "module") return@mapNotNull null
                ExternalModule(
                    name = parts[1],
                    dirRelPath = parts[2],
                    typeId = "java-lib",
                    sourceSets = listOf(
                        ExternalSourceSet(
                            "main",
                            DependencyScope.IMPLEMENTATION,
                            mapOf("src/main/java" to setOf(ContentRole.SOURCE)),
                        )
                    ),
                    dependencies = parts.getOrNull(3)
                        ?.let { listOf(ExternalLibrary(it, DependencyScope.IMPLEMENTATION)) }
                        ?: emptyList(),
                )
            }
            return SyncOutcome(ExternalProjectModel("bzl", id, modules))
        }

        companion object {
            const val MARKER = "workspace.bzl"
        }
    }

    private fun writeBzlProject(dir: Path, vararg lines: String) {
        Files.createDirectories(dir)
        dir.resolve(BzlImporter.MARKER).writeText(lines.joinToString("\n", postfix = "\n"))
        for (line in lines) {
            val rel = line.trim().split(" ").getOrNull(2) ?: continue
            Files.createDirectories(dir.resolve(rel).resolve("src/main/java"))
        }
    }

    @Test
    fun importsDetectsAndSyncsAForeignProject() {
        withTempDir("importer-ep") { tmp ->
            val source = tmp.resolve("MyBzlApp")
            writeBzlProject(source, "module app apps/app com.squareup.okhttp3:okhttp:4.12.0", "module core libs/core")

            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                manager.env.platform.extensions.register(PROJECT_IMPORTER_EP, BzlImporter(), PluginId("bzl-test"))

                val ide = manager.importExternalProject(source)
                assertTrue(ide != null, "the importer should have claimed the folder")
                ide!!.use {
                    assertEquals(setOf("app", "core"), it.moduleNames().toSet())
                    assertEquals(bazel, it.store.workspace.projects.single().buildSystemId)
                    assertTrue(it.isCompatibilityMode(), "an externally-owned project reports compatibility mode")
                    assertFalse(it.isSyncStale(), "the import stamped what it read")

                    val app = it.modules().single { m -> m.name == "app" }
                    assertTrue(
                        app.dependencies.any { d -> d is LibraryDependency && d.library.name == "com.squareup.okhttp3:okhttp:4.12.0" },
                        "the snapshot's declarations reached the model",
                    )
                    // A nested module keeps the directory the snapshot gave it, not its name.
                    assertTrue(app.outputDir.path.replace('\\', '/').contains("apps/app/"), app.outputDir.path)

                    // Editing a watched file makes the model stale; a sync re-derives it and clears that.
                    val root = it.workspaceRoot
                    writeBzlProject(root, "module app apps/app com.squareup.okhttp3:okhttp:4.12.0")
                    assertTrue(it.isSyncStale(), "a changed build file marks the model out of date")

                    val outcome = runBlocking { it.syncFromBuildFiles() }
                    assertTrue(outcome.ok, outcome.message)
                    assertEquals(setOf("app"), it.moduleNames().toSet(), "an undeclared module is dropped")
                    assertFalse(it.isSyncStale(), "the sync re-stamped the build files")
                }
            } finally {
                manager.dispose()
            }
        }
    }

    @Test
    fun aFolderNoImporterClaimsIsNotImported() {
        withTempDir("importer-ep-none") { tmp ->
            val source = tmp.resolve("PlainFolder").also { Files.createDirectories(it) }
            source.resolve("README.md").writeText("nothing to import")
            val manager = ProjectManager.desktop(tmp.resolve("projects"))
            try {
                manager.env.platform.extensions.register(PROJECT_IMPORTER_EP, BzlImporter(), PluginId("bzl-test"))
                assertTrue(manager.importExternalProject(source) == null)
                assertTrue(manager.list().isEmpty(), "a failed import leaves no half-written workspace")
            } finally {
                manager.dispose()
            }
        }
    }
}
