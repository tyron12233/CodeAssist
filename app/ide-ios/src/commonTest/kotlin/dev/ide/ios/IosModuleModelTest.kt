@file:OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)

package dev.ide.ios

import dev.ide.deps.impl.ArtifactFetcher
import dev.ide.ios.store.IosPreferences
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiDepKind
import dev.ide.ui.backend.UiModuleConfigEdit
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Module settings and per-module dependencies on iOS, against the real project model.
 *
 * The point of every test here is that this host now edits the SAME model the desktop and Android hosts do:
 * a declaration lands in `module.toml`, a setting round-trips through a transaction and a save, a facet
 * table written by a host this one cannot even build for survives being edited here. Nothing below is an
 * iOS-shaped answer to an iOS-shaped question.
 */
class IosModuleModelTest {

    private val root = IosFiles.join(NSTemporaryDirectory().trimEnd('/'), "ios-module-test-${nowSuffix()}")
    private val suite = "ios-module-test-${nowSuffix()}"
    private val prefs = IosPreferences(NSUserDefaults(suiteName = suite))

    /** Offline by construction: an unreachable fetcher answers 404 for everything, and nothing downloads. */
    private val backend = IosBackend(root, prefs).apply {
        dependenciesFor = { IosDependencies(it, ArtifactFetcher { null }) }
    }

    @AfterTest
    fun cleanUp() {
        IosFiles.delete(root)
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }

    /** A Kotlin console project: one module, `app`, with `src/main/kotlin` under it. */
    private suspend fun createProject(name: String): String {
        val created = backend.createProject("kotlin-console", mapOf("name" to name, "packageName" to "demo"))
        return assertNotNull(created.rootPath, created.message)
    }

    private fun moduleToml(projectRoot: String, module: String = "app"): String =
        IosFiles.readText(IosFiles.join(projectRoot, "$module/module.toml"))

    // ---- what the Modules screen is looking at -------------------------------------------------------

    @Test
    fun theModulesScreenListsTheModelsModulesNotTheProject() = runTest {
        createProject("Listing")

        val modules = backend.modules.configurableModules()

        assertEquals(listOf("app"), modules.map { it.name }, "the template's module, not the project name")
        assertEquals("Java Library", modules.single().typeDisplay)
    }

    @Test
    fun aModulesConfigurationIsReadFromTheModel() = runTest {
        createProject("Config")

        val config = assertNotNull(backend.modules.getModuleConfig("app"))

        assertEquals("app", config.name)
        assertEquals("java-lib", config.typeId)
        assertEquals("JAVA_17", config.languageLevel)
        assertTrue(config.languageLevel in config.languageLevels, "the current level is one of the options")
        assertEquals(listOf("main"), config.sourceSets.map { it.name })
        assertTrue(
            config.sourceSets.single().roots.single().endsWith("app/src/main/kotlin"),
            config.sourceSets.single().roots.toString(),
        )
        assertNull(backend.modules.getModuleConfig("nope"), "a module that is not there has no config")
    }

    @Test
    fun editingTheLanguageLevelPersistsToModuleToml() = runTest {
        val projectRoot = createProject("Level")

        val saved = backend.modules.updateModuleConfig("app", UiModuleConfigEdit(languageLevel = "JAVA_21"))

        assertTrue(saved.success, saved.message)
        assertTrue("JAVA_21" in moduleToml(projectRoot), moduleToml(projectRoot))
        // And it is what the project says after being read back from disk, not just what memory holds.
        assertTrue(backend.openProject(projectRoot))
        assertEquals("JAVA_21", assertNotNull(backend.modules.getModuleConfig("app")).languageLevel)
    }

    @Test
    fun anUnknownLanguageLevelIsRefusedRatherThanWritten() = runTest {
        val projectRoot = createProject("Bogus")

        val saved = backend.modules.updateModuleConfig("app", UiModuleConfigEdit(languageLevel = "JAVA_99"))

        assertFalse(saved.success, saved.message)
        assertFalse("JAVA_99" in moduleToml(projectRoot), "nothing unrecognised reaches the file")
    }

    /**
     * The facet case this host exists to get right.
     *
     * An `[android]` table is written by a codec that lives in `:android-support`, which is JVM-only: there
     * is no codec here to decode it, and `Module.facets.all` drops it. It still has to render as an editable
     * panel, and an edit must leave the keys this host never displayed exactly as it found them, or opening
     * a project on a phone quietly truncates the configuration the host that builds it reads.
     */
    @Test
    fun aForeignFacetTableRendersAndRoundTripsThroughAnEdit() = runTest {
        val projectRoot = createProject("Facets")
        val toml = IosFiles.join(projectRoot, "app/module.toml")
        IosFiles.writeText(
            toml,
            IosFiles.readText(toml) + """

            [android]
            namespace = "com.example.app"
            minSdk = 24
            isApplication = true
            proguardFiles = ["proguard-rules.pro"]
            """.trimIndent() + "\n",
        )
        assertTrue(backend.openProject(projectRoot))

        val panel = assertNotNull(
            backend.modules.getModuleConfig("app")?.facets?.firstOrNull { it.table == "android" },
            "a table with no codec still renders as a panel",
        )
        assertTrue(panel.fields.any { it is UiConfigField.Text && it.key == "namespace" }, "namespace to Text")
        assertTrue(panel.fields.any { it is UiConfigField.Number && it.key == "minSdk" }, "minSdk to Number")
        assertTrue(panel.fields.any { it is UiConfigField.Bool && it.key == "isApplication" }, "a Bool")
        assertTrue(panel.fields.any { it is UiConfigField.StringList && it.key == "proguardFiles" }, "a list")

        // Edit ONE field, the way the screen sends it back.
        val saved = backend.modules.updateModuleConfig(
            "app",
            UiModuleConfigEdit(facetValues = mapOf("android" to mapOf("minSdk" to 26L))),
        )
        assertTrue(saved.success, saved.message)

        assertTrue(backend.openProject(projectRoot))
        val after = assertNotNull(
            backend.modules.getModuleConfig("app")?.facets?.firstOrNull { it.table == "android" },
        )
        assertEquals(26L, (after.fields.first { it.key == "minSdk" } as UiConfigField.Number).value)
        assertEquals(
            "com.example.app",
            (after.fields.first { it.key == "namespace" } as UiConfigField.Text).value,
            "a key this host did not send back is not a key it may drop",
        )
        assertTrue(after.fields.any { it.key == "proguardFiles" }, "nor a list it does not understand")
    }

    // ---- source sets, and adding and removing modules ------------------------------------------------

    @Test
    fun aSourceRootIsDeclaredAndCreatedOnDisk() = runTest {
        val projectRoot = createProject("Roots")

        val created = assertNotNull(
            backend.modules.addSourceRoot("app", "main", "resources", dev.ide.ui.backend.UiSourceRootRole.Resource),
        )

        assertTrue(created.endsWith("app/src/main/resources"), created)
        assertTrue(IosFiles.isDirectory(created), "a declared root that is not on disk is one nothing can use")
        assertTrue("resources" in moduleToml(projectRoot), moduleToml(projectRoot))
    }

    @Test
    fun aSecondModuleIsCreatedWithItsSourceTree() = runTest {
        val projectRoot = createProject("Multi")

        val created = backend.modules.createModule("core", "java-lib", "JAVA_17", emptyMap())

        assertTrue(created.success, created.message)
        assertEquals(listOf("app", "core"), backend.modules.configurableModules().map { it.name })
        assertTrue(IosFiles.isDirectory(IosFiles.join(projectRoot, "core/src/main/kotlin")), "its sources exist")
        assertTrue(IosFiles.exists(IosFiles.join(projectRoot, "core/module.toml")), "and its own module.toml")

        assertFalse(backend.modules.createModule("core", "java-lib", null, emptyMap()).success, "not twice")
        assertFalse(backend.modules.createModule("9lives", "java-lib", null, emptyMap()).success, "nor a bad name")
    }

    @Test
    fun removingAModuleLeavesItsFilesAlone() = runTest {
        val projectRoot = createProject("Prune")
        assertTrue(backend.modules.createModule("core", "java-lib", null, emptyMap()).success)

        assertTrue(backend.modules.removeModule("core"))

        assertEquals(listOf("app"), backend.modules.configurableModules().map { it.name })
        assertTrue(
            IosFiles.isDirectory(IosFiles.join(projectRoot, "core/src/main/kotlin")),
            "the model forgot the module; the directory is still the user's",
        )
    }

    // ---- dependencies, per module --------------------------------------------------------------------

    @Test
    fun aDeclarationIsWrittenIntoTheModulesOwnToml() = runTest {
        val projectRoot = createProject("Declare")

        val added = backend.deps.addDependency("app", "com.example:widget:1.2.0", "api")

        assertTrue(added.success, added.message)
        assertTrue("com.example:widget:1.2.0" in moduleToml(projectRoot), moduleToml(projectRoot))
        // `api`, not the default: the configuration the user picked is part of the declaration.
        val declared = assertNotNull(backend.deps.moduleDependencies("app")).declared.single()
        assertEquals("api", declared.scope)
    }

    @Test
    fun twoModulesHaveTheirOwnDependencies() = runTest {
        createProject("Split")
        assertTrue(backend.modules.createModule("core", "java-lib", null, emptyMap()).success)

        assertTrue(backend.deps.addDependency("core", "com.example:widget:1.2.0", "implementation").success)

        assertEquals(
            listOf("app", "core"),
            backend.deps.dependencyModules().map { it.name },
            "both modules are editable, not one row standing for the project",
        )
        assertEquals(
            emptyList(),
            assertNotNull(backend.deps.moduleDependencies("app")).declared.map { it.coordinate },
            "a declaration belongs to the module it was made on",
        )
        assertEquals(
            listOf("com.example:widget:1.2.0"),
            assertNotNull(backend.deps.moduleDependencies("core")).declared.map { it.coordinate },
        )
    }

    @Test
    fun aModuleCanDependOnAnotherWithoutClosingACycle() = runTest {
        createProject("Graph")
        assertTrue(backend.modules.createModule("core", "java-lib", null, emptyMap()).success)

        assertEquals(listOf("core"), backend.deps.moduleDependencyTargets("app"))
        assertTrue(backend.deps.addModuleDependency("app", "core", "implementation").success)

        val declared = assertNotNull(backend.deps.moduleDependencies("app")).declared
        assertEquals(listOf("core"), declared.map { it.coordinate })
        assertEquals(UiDepKind.Module, declared.single().kind)
        assertEquals(
            emptyList(),
            backend.deps.moduleDependencyTargets("core"),
            "app already depends on core, so offering the reverse would offer a cycle",
        )
        assertFalse(backend.deps.addModuleDependency("app", "core", "implementation").success, "not twice")

        assertTrue(backend.deps.removeDependency("app", "core"))
        assertEquals(emptyList(), assertNotNull(backend.deps.moduleDependencies("app")).declared)
    }

    // ---- what happens to a project that predates any of this -----------------------------------------

    /**
     * A folder with no model at all: what every project made by an earlier build of this app is, and what
     * anything unpacked from the store arrives as.
     *
     * Adopting it on open is what lets everything above assume a module graph. The alternative was a second,
     * model-less path through dependencies and settings for exactly these projects.
     */
    @Test
    fun aFolderWithNoModelIsAdoptedOnOpen() = runTest {
        val projectRoot = IosFiles.join(root, "Legacy")
        IosFiles.writeText(IosFiles.join(projectRoot, "src/Main.kt"), "fun main() {}\n")

        assertTrue(backend.openProject(projectRoot))

        assertEquals(listOf("Legacy"), backend.modules.configurableModules().map { it.name })
        val config = assertNotNull(backend.modules.getModuleConfig("Legacy"))
        assertTrue(
            config.sourceSets.single().roots.single().endsWith("Legacy/src"),
            "the source directory it actually has, not the convention it does not: ${config.sourceSets}",
        )
        assertTrue(IosFiles.exists(IosFiles.join(projectRoot, ".platform/workspace.json")), "and it persisted")
        assertTrue(IosFiles.exists(IosFiles.join(projectRoot, "module.toml")), "at the project root")
    }

    /**
     * The flat `.platform/dependencies` file this host kept before the model arrived.
     *
     * It is the only copy of something a user typed, so it is migrated rather than ignored, and renamed
     * rather than deleted. `.platform` is hidden from the tree, so what is left behind is invisible.
     */
    @Test
    fun theOldFlatDeclarationFileIsMigratedIntoTheModel() = runTest {
        val projectRoot = IosFiles.join(root, "Carried")
        IosFiles.writeText(IosFiles.join(projectRoot, "src/Main.kt"), "fun main() {}\n")
        IosFiles.writeText(
            IosFiles.join(projectRoot, ".platform/dependencies"),
            "# CodeAssist dependencies\nimplementation com.example:widget:1.2.0\napi com.example:core:0.9\nnonsense\n",
        )

        assertTrue(backend.openProject(projectRoot))

        val declared = assertNotNull(backend.deps.moduleDependencies("Carried")).declared
        // A set, because the model normalises declaration order by scope (`api` before `implementation`);
        // what matters here is that both survived and the unparseable line did not.
        assertEquals(
            setOf("com.example:widget:1.2.0", "com.example:core:0.9"),
            declared.map { it.coordinate }.toSet(),
        )
        assertEquals("implementation", declared.first { "widget" in it.coordinate }.scope)
        assertEquals("api", declared.first { "core" in it.coordinate }.scope, "the configuration came too")
        assertTrue("com.example:widget:1.2.0" in IosFiles.readText(IosFiles.join(projectRoot, "module.toml")))
        assertFalse(
            IosFiles.exists(IosFiles.join(projectRoot, ".platform/dependencies")),
            "the file is consumed, so a second open does not re-declare what the user has since removed",
        )
        assertTrue(IosFiles.exists(IosFiles.join(projectRoot, ".platform/dependencies.migrated")), "but kept")
    }

    // ---- opening a project this host cannot build ----------------------------------------------------

    /**
     * An Android project, as it arrives from a desktop or from the store: an `android-app` module with an
     * `[android]` facet and an `.aar` dependency.
     *
     * What opening it means here is worth being precise about, because it is not "Android support". The
     * MODEL opens (a module type no plugin on this host claims resolves to a stand-in rather than failing
     * the load), the facet renders and round-trips, and the dependency resolves to the classes inside the
     * `.aar` so library code is on the editor's classpath. What is absent is the Android FRAMEWORK: there is
     * no `android.jar` and no SDK to fetch one from, so `android.*` types do not resolve, and there is no
     * build, no resource model and no `R`.
     */
    @Test
    fun anAndroidProjectOpensWithItsFacetAndItsLibraryClasses() = runTest {
        val repo = FixtureMaven()
        // The AAR carries a REAL jar: the stdlib fixture, so what it contributes is verifiable by resolving
        // something only that jar declares. Nothing else publishes it, so `println` can only come from here.
        repo.publishAar("androidx.example", "widget", "1.0", Base64.decode(StdlibFixture.JAR_BASE64))
        val backend = IosBackend(root, prefs).apply { dependenciesFor = { IosDependencies(it, repo) } }
        val projectRoot = assertNotNull(
            backend.createProject("kotlin-console", mapOf("name" to "Droid", "packageName" to "demo")).rootPath,
        )
        // Make it the project a desktop would have written: an Android module type, an `[android]` facet and
        // a dependency on the library. Editing `module.toml` by hand is what a user can do too.
        val toml = IosFiles.join(projectRoot, "app/module.toml")
        IosFiles.writeText(
            toml,
            IosFiles.readText(toml).replace("\"java-lib\"", "\"android-app\"") + """

            [dependencies]
            implementation = ["androidx.example:widget:1.0"]

            [android]
            namespace = "com.example.droid"
            minSdk = 24
            compileSdk = 36
            """.trimIndent() + "\n",
        )

        assertTrue(backend.openProject(projectRoot), "the model opens with a type nothing here provides")

        assertEquals(listOf("app"), backend.modules.configurableModules().map { it.name })
        assertEquals(
            "Android Application",
            backend.modules.configurableModules().single().typeDisplay,
            "recognised by name, even though nothing here builds one",
        )
        assertEquals(
            listOf("java-lib"),
            backend.modules.availableModuleTypes().map { it.id },
            "recognising a type is not offering to create one",
        )
        val config = assertNotNull(backend.modules.getModuleConfig("app"))
        assertEquals("android-app", config.typeId, "the type is carried, not rewritten")
        val android = assertNotNull(config.facets.firstOrNull { it.table == "android" })
        assertEquals(
            "com.example.droid",
            (android.fields.first { it.key == "namespace" } as UiConfigField.Text).value,
        )

        val deps = assertNotNull(backend.deps.moduleDependencies("app"))
        assertEquals(listOf("androidx.example:widget:1.0"), deps.declared.map { it.coordinate })
        assertEquals(emptyList(), deps.unresolved.filter { "widget" in it }, "an .aar is not unresolvable")
        assertTrue(
            backend.analysisClasspath().any { it.endsWith("classes.jar") },
            "the classes inside the .aar are what goes on the classpath: ${backend.analysisClasspath()}",
        )

        // And they reach the editor: `println` exists only inside that jar's `@kotlin.Metadata`.
        val file = IosFiles.join(projectRoot, "app/src/main/kotlin/demo/Main.kt")
        val text = "package demo\n\nfun use() {\n    println(\"hi\")\n}\n"
        val completed = backend.complete(file, text, text.indexOf("println") + 5)
        assertTrue(
            completed.items.any { it.label.startsWith("println") },
            "got ${completed.items.take(10).map { it.label }}",
        )
    }

    /** A project created by the templates has a model already, so nothing is adopted over the top of it. */
    @Test
    fun aTemplateProjectIsNotAdoptedOnTopOf() = runTest {
        val projectRoot = createProject("Clean")

        assertTrue(backend.openProject(projectRoot))

        assertEquals(listOf("app"), backend.modules.configurableModules().map { it.name })
        assertFalse(
            IosFiles.exists(IosFiles.join(projectRoot, "module.toml")),
            "the module lives at app/module.toml; a root one would mean a second, invented module",
        )
    }
}

/** A per-instance suffix so concurrently-run test classes cannot share a directory. */
private var moduleTestCounter = 0
private fun nowSuffix(): String = "${++moduleTestCounter}-${IosFiles.modifiedMs(NSTemporaryDirectory())}"
