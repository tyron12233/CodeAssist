package dev.ide.core

import kotlinx.coroutines.runBlocking

import dev.ide.android.support.templates.JetpackComposeAppTemplate
import dev.ide.lang.kotlin.compile.SerializationCompilerPlugin
import dev.ide.model.LibraryDependency
import dev.ide.model.template.TemplateArgs
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectManagerTest {

    /** Create a Java console app from the template, reopen it, and confirm the model round-trips + runs. */
    @Test
    fun createsListsAndReopensJavaConsoleProject() {
        val root = Files.createTempDirectory("cm-test")
        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            assertTrue(manager.isEmpty(), "a fresh projects root has no projects")

            manager.create("java-console", mapOf("name" to "Demo CLI", "packageName" to "com.acme.demo")).use { ide ->
                assertEquals(listOf("app"), ide.moduleNames())
                assertEquals("Demo CLI", ide.projectDisplayName())
                // a Main with `static void main` produces a `run:` task offered.
                assertTrue(ide.build.runTasks().any { it.id.startsWith("run:") }, "expected a runnable main")
            }

            val listed = manager.list()
            assertEquals(1, listed.size)
            assertEquals("Demo CLI", listed.first().name)
            assertEquals(1, listed.first().moduleCount)

            // Reopen the saved workspace: identical module snapshot.
            manager.open(listed.first().rootPath).use { reopened ->
                assertEquals(listOf("app"), reopened.moduleNames())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** A backup zips project sources (and skips build outputs). */
    @Test
    fun backsUpProjectSourcesToZip() {
        val root = Files.createTempDirectory("cm-backup")
        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            manager.create("java-console", mapOf("name" to "Zip Me", "packageName" to "com.acme.zip")).use { }
            val zip = manager.exportBackup()
            assertTrue(Files.exists(zip), "backup zip was created")
            java.util.zip.ZipFile(zip.toFile()).use { zf ->
                val names = zf.entries().asSequence().map { it.name }.toList()
                assertTrue(names.any { it.endsWith("Main.java") }, "sources in backup; got $names")
                assertTrue(names.none { it.contains("/build/") }, "build outputs excluded")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Projects left in a previous storage location (e.g. internal app storage before the move to external)
     * are recovered into the active projects root so they reappear in the picker, exactly once, and open.
     */
    @Test
    fun importsLegacyProjectsFromAPreviousStorageLocation() {
        val tmp = Files.createTempDirectory("cm-legacy")
        try {
            // A previous internal-storage home with one project under its `projects/` dir.
            val legacyHome = tmp.resolve("internal/codeassist")
            ProjectManager.desktop(legacyHome.resolve("projects"))
                .create("java-console", mapOf("name" to "Old App", "packageName" to "com.acme.old")).use { }

            // The new external-storage manager sees none of them until it imports.
            val newManager = ProjectManager.desktop(
                tmp.resolve("external/codeassist/projects"),
                legacyDataDirs = listOf(legacyHome),
            )
            assertTrue(newManager.isEmpty(), "fresh external root starts empty")

            assertEquals(1, newManager.importLegacyProjects(), "one legacy project recovered")
            assertEquals(listOf("Old App"), newManager.list().map { it.name })

            // Idempotent: a second call (flag set) imports nothing and doesn't duplicate.
            assertEquals(0, newManager.importLegacyProjects())
            assertEquals(1, newManager.list().size)

            // The recovered project actually opens.
            newManager.open(newManager.list().first().rootPath).use { ide ->
                assertEquals(listOf("app"), ide.moduleNames())
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** The built-in template gallery exposes the shipped Java, Kotlin, and Android templates. */
    @Test
    fun exposesBuiltInTemplates() {
        val root = Files.createTempDirectory("cm-templates")
        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            manager.create("java-library", mapOf("name" to "Lib", "packageName" to "com.acme.lib")).use { ide ->
                val ids = ide.projectTemplates().map { it.id.value }.toSet()
                assertTrue(
                    ids.containsAll(
                        setOf(
                            "java-console", "java-library",
                            "kotlin-console", "kotlin-library",
                            "android-app", "android-library",
                        ),
                    ),
                    "got $ids",
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** A valid `.kt` file must NOT be analyzed by the Java/JDT pipeline (which would report bogus
     *  "Syntax error / insert ';'" on `package`/`println`). Kotlin routes to its own tolerant parser. */
    @Test
    fun kotlinFileGetsNoJavaDiagnostics() {
        val root = Files.createTempDirectory("cm-kdiag")
        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            manager.create("kotlin-console", mapOf("name" to "KDiag", "packageName" to "com.acme.kt")).use { ide ->
                val main = java.nio.file.Path.of(manager.list().first().rootPath)
                    .resolve("app/src/main/kotlin/com/acme/kt/Main.kt")
                val text = Files.readString(main)
                val diags = runBlocking { ide.analyzeDiagnostics(main, text) }
                assertTrue(
                    diags.none { it.severity == dev.ide.lang.dom.Severity.ERROR },
                    "valid Kotlin should produce no error diagnostics; got $diags",
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Create a Kotlin console project: a `src/main/kotlin` tree with a top-level `fun main()`. */
    @Test
    fun createsKotlinConsoleProject() {
        val root = Files.createTempDirectory("cm-kotlin")
        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            manager.create("kotlin-console", mapOf("name" to "KDemo", "packageName" to "com.acme.kt")).use { ide ->
                assertEquals(listOf("app"), ide.moduleNames())
                assertEquals("KDemo", ide.projectDisplayName())
            }
            val proj = manager.list().first()
            val main = java.nio.file.Path.of(proj.rootPath).resolve("app/src/main/kotlin/com/acme/kt/Main.kt")
            assertTrue(Files.exists(main), "Main.kt generated at $main")
            val text = Files.readString(main)
            assertTrue("fun main" in text, "has a top-level main(); got:\n$text")
            assertTrue(text.startsWith("package com.acme.kt"), "kotlin package header (no semicolon); got:\n$text")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Regression: a template's declared dependencies must be written to `module.toml` (the declared source of
     * truth) at creation, INDEPENDENT of Maven resolution. They were previously persisted only as a side
     * effect of a *successful* resolve, so on a slow/offline first run a Jetpack Compose app kept just the
     * deps that happened to resolve (kotlin-stdlib + activity-compose) and silently dropped the rest of the
     * Compose graph — which then never came back, since reconciliation only re-resolves what's declared.
     */
    @Test
    fun templateDependenciesAreDeclaredInModuleTomlIndependentOfResolution() {
        val root = Files.createTempDirectory("cm-template-deps")
        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            // The Compose template's own declared set is the source of truth for what must end up in module.toml.
            val expected = JetpackComposeAppTemplate.dependencies(TemplateArgs(emptyMap()))
                .map { it.coordinate }.toSet()
            assertTrue(expected.size >= 5, "the Compose template declares its AAR graph; got $expected")

            fun declaredLibs(ide: IdeServices): Set<String> = ide.modules().first { it.name == "app" }
                .dependencies.filterIsInstance<LibraryDependency>().map { it.library.name }.toSet()

            manager.create("compose-app", mapOf("name" to "ComposeDemo", "packageName" to "com.acme.compose")).use { ide ->
                // No background resolution has run (the editor never started it), yet every declared dep is present.
                val declared = declaredLibs(ide)
                assertTrue(declared.containsAll(expected), "every template dep declared at creation; missing ${expected - declared}")
                assertTrue("kotlin-stdlib" in declared, "the implicit kotlin-stdlib is declared for the Kotlin module")
            }

            // And they round-trip through module.toml on reopen (persisted, not merely held in memory).
            manager.open(manager.list().first().rootPath).use { reopened ->
                assertTrue(declaredLibs(reopened).containsAll(expected), "declared deps survive a reopen via module.toml")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** A Compose template ships with the Compose compiler plugin already on (the toggle reflects reality, and
     *  the build/preview don't depend solely on the classpath probe). Compose is a compiler plugin, so its
     *  toggle lives on the Compiler-plugins tab ([ModuleService.getCompilerPlugins]), not Build Features. */
    @Test
    fun composeTemplateEnablesComposeBuildFeature() {
        val root = Files.createTempDirectory("cm-compose-feature")
        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            fun composeOn(ide: IdeServices): Boolean =
                ide.moduleService.getCompilerPlugins("app")
                    ?.plugins?.firstOrNull { it.id == dev.ide.lang.kotlin.compile.ComposeCompilerPlugin.pluginId }
                    ?.enabled == true

            manager.create("compose-app", mapOf("name" to "ComposeDemo", "packageName" to "com.acme.compose")).use { ide ->
                assertTrue(composeOn(ide), "the Jetpack Compose template enables the Compose compiler plugin")
            }
            // And it survives a reopen (persisted to module.toml, not just in memory).
            manager.open(manager.list().first().rootPath).use { reopened ->
                assertTrue(composeOn(reopened), "the Compose compiler plugin round-trips through module.toml")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Toggling a compiler plugin on through the backend persists to `module.toml` and round-trips a reopen.
     *  (Serialization starts OFF, so it's a clean on-toggle.) Dependency resolution may fail offline — the
     *  toggle still succeeds and the enable-state persists, which is what this asserts. */
    @Test
    fun compilerPluginToggleRoundTrips() {
        val root = Files.createTempDirectory("cm-serialization-toggle")
        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            fun serializationOn(ide: IdeServices): Boolean =
                ide.moduleService.getCompilerPlugins("app")
                    ?.plugins?.firstOrNull { it.id == SerializationCompilerPlugin.pluginId }
                    ?.enabled == true

            manager.create("compose-app", mapOf("name" to "SerDemo", "packageName" to "com.acme.ser")).use { ide ->
                assertFalse(serializationOn(ide), "serialization starts off")
                val r = runBlocking { ide.moduleService.setCompilerPlugin("app", SerializationCompilerPlugin.pluginId, true) }
                assertTrue(r.success, "toggling serialization on should succeed: ${r.message}")
                assertTrue(serializationOn(ide), "serialization is on after the toggle")
            }
            manager.open(manager.list().first().rootPath).use { reopened ->
                assertTrue(serializationOn(reopened), "the serialization toggle round-trips through module.toml")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

}
