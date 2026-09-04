package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Facet
import dev.ide.model.FacetCodec
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.FacetKey
import dev.ide.model.FacetTemplate
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.PlatformKind
import dev.ide.model.SourceSetTemplate
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.withTempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The project model as a plugin for a language laid out unlike a JVM module sees it. Everything here is
 * declared the way a `:project-model-api` consumer would declare it — no impl types, no host privileges —
 * and the assertions are that it survives a save and a reopen.
 *
 * The five vocabularies were enums until the SPI's `2.0.0`, which made this impossible to express: a Python
 * module had to claim a Java language level, its source roots had to pass as `SOURCE`, and a wheel had to
 * call itself a jar.
 */
class PluginVocabularyTest {

    // --- what the imaginary plugin declares -------------------------------------------------------------

    private object Py {
        val PLUGIN = PluginId("python-support")

        val PLATFORM = PlatformKind("PYTHON")
        val LEVEL = LanguageLevel("PYTHON_3_12")
        val WHEEL = LibraryKind("WHEEL")

        val PACKAGE_ROOT = ContentRole("python-package")
        val STUBS = ContentRole("python-stubs")

        /** Available on the runtime path but never on the compile one: the C++/Python `link`-time shape. */
        val RUNTIME_REQUIRES = DependencyScope.register(
            DependencyScope("RUNTIME_REQUIRES", "runtimeRequires", onCompile = false, onRuntime = true, onTest = true),
        )

        val FACET_KEY = FacetKey<Facet>("python")
    }

    private data class PythonFacet(val interpreter: String, val venv: String?) : Facet {
        override val key: FacetKey<*> get() = Py.FACET_KEY
    }

    private object PythonFacetCodec : FacetCodec<Facet> {
        override val key: FacetKey<Facet> = Py.FACET_KEY
        override val tomlTable: String = "python"

        override fun encode(facet: Facet): Map<String, Any?> {
            val f = facet as PythonFacet
            return linkedMapOf("interpreter" to f.interpreter, "venv" to f.venv)
        }

        override fun decode(values: Map<String, Any?>): Facet =
            PythonFacet(values["interpreter"] as? String ?: "python3", values["venv"] as? String)
    }

    private class PythonModuleType : ModuleType {
        override val id: String = "python-app"
        override val displayName: String = "Python Application"
        override val platform: PlatformKind get() = Py.PLATFORM
        override fun defaultSourceSets(): List<SourceSetTemplate> = listOf(
            SourceSetTemplate(
                "main", DependencyScope.IMPLEMENTATION,
                mapOf("src" to setOf(Py.PACKAGE_ROOT), "stubs" to setOf(Py.STUBS)),
            ),
        )
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    // --- the test ---------------------------------------------------------------------------------------

    @Test
    fun aPluginsOwnVocabularySurvivesSaveAndReopen() = withTempDir("codeassist-plugin-vocab") { dir ->
        val platform = PlatformCore()
        try {
            // Exactly what a plugin's `register` does: a module type and a facet codec, on the two EPs.
            ModuleTypeRegistry(platform.extensions).register(PythonModuleType(), Py.PLUGIN)
            val codecs = FacetCodecRegistry(platform.extensions).register(PythonFacetCodec, Py.PLUGIN)

            val store = ProjectModel.open(dir, platform, codecs)
            val pythonApp = ModuleTypeRegistry(platform.extensions).resolve("python-app")
            assertEquals(Py.PLATFORM, pythonApp.platform, "the module type keeps its own platform kind")

            store.workspace.beginModification().apply {
                addProject("tool", BuildSystemId.NATIVE, store.vfs.root())
                commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("tool", pythonApp).apply {
                    languageLevel = Py.LEVEL
                    addSourceSet(
                        SourceSetTemplate(
                            "main", DependencyScope.IMPLEMENTATION,
                            mapOf("src" to setOf(Py.PACKAGE_ROOT), "stubs" to setOf(Py.STUBS)),
                        ),
                    )
                    addDependency(LibraryDependency(LibraryRef("requests:requests:2.32.3"), Py.RUNTIME_REQUIRES))
                    putFacet(PythonFacet("python3.12", venv = ".venv"))
                }
                commit()
            }
            store.save()

            // Reopen against a fresh platform, with the plugin loaded as it would be on the next launch.
            val platform2 = PlatformCore()
            try {
                ModuleTypeRegistry(platform2.extensions).register(PythonModuleType(), Py.PLUGIN)
                val store2 = ProjectModel.open(
                    dir, platform2, FacetCodecRegistry(platform2.extensions).register(PythonFacetCodec, Py.PLUGIN),
                )
                val module = store2.workspace.projects.single().modules.single()

                assertEquals("python-app", module.type.id)
                assertEquals(Py.LEVEL, module.languageLevel, "a non-Java language level round-trips")

                val roots = module.sourceSets.single { it.name == "main" }.contentRoots
                assertEquals(
                    setOf(Py.PACKAGE_ROOT), roots.single { it.dir.path.endsWith("src") }.roles,
                    "a plugin's own content role round-trips",
                )
                assertEquals(setOf(Py.STUBS), roots.single { it.dir.path.endsWith("stubs") }.roles)

                val dep = module.dependencies.filterIsInstance<LibraryDependency>().single()
                assertEquals(Py.RUNTIME_REQUIRES, dep.scope, "a plugin's own dependency scope round-trips")
                assertTrue(dep.scope.onRuntime && !dep.scope.onCompile, "and keeps its classpath semantics")

                val facet = assertNotNull(module.facets.get(Py.FACET_KEY), "the plugin's facet round-trips")
                assertEquals(PythonFacet("python3.12", ".venv"), facet)
            } finally {
                platform2.dispose()
            }
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun builtInSpellingsOnDiskAreUnchanged() = withTempDir("codeassist-vocab-ondisk") { dir ->
        val platform = PlatformCore()
        platform.registerTestTypes()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
            store.workspace.beginModification().apply {
                addProject("app", BuildSystemId.NATIVE, store.vfs.root())
                commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", ModuleTypeRegistry(platform.extensions).resolve("java-lib")).apply {
                    languageLevel = LanguageLevel.JAVA_17
                    addSourceSet(
                        SourceSetTemplate(
                            "main", DependencyScope.IMPLEMENTATION,
                            mapOf(
                                "src/main/java" to setOf(ContentRole.SOURCE),
                                "src/main/resources" to setOf(ContentRole.RESOURCE),
                            ),
                        ),
                    )
                    addDependency(LibraryDependency(LibraryRef("g:a:1"), DependencyScope.API))
                }
                commit()
            }
            store.save()

            val toml = dir.resolve("app").resolve("module.toml").toFile().readText()
            // The built-ins keep the Gradle source-directory spellings and the enum-name scope they have
            // always been written under, so opening the five vocabularies is not a format migration.
            assertTrue("""java = [ "src/main/java" ]""" in toml || "java = " in toml, "SOURCE persists as `java`:\n$toml")
            assertTrue("resources = " in toml, "RESOURCE persists as `resources`:\n$toml")
            assertTrue("""scope = "IMPLEMENTATION"""" in toml, "a source set's scope persists as its name:\n$toml")
            assertTrue("languageLevel = \"JAVA_17\"" in toml, "the language level persists as its name:\n$toml")
            assertTrue("[dependencies]" in toml && "api = " in toml, "a dependency groups under `api`:\n$toml")
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun kindsAndLevelsParseBackFromTheirPersistedNames() {
        assertEquals(LibraryKind.JAR, LibraryKind.valueOf("JAR"))
        assertEquals(PlatformKind.ANDROID, PlatformKind.valueOf("ANDROID"))
        assertEquals(LanguageLevel.JAVA_21, LanguageLevel.valueOf("JAVA_21"))
        assertEquals(DependencyScope.COMPILE_ONLY, DependencyScope.valueOf("COMPILE_ONLY"))

        // Open, so an unrecognized name is a plugin's value rather than a failure.
        assertEquals(Py.WHEEL, LibraryKind.valueOf("WHEEL"))
        assertEquals(Py.LEVEL, LanguageLevel.valueOf("PYTHON_3_12"))
        assertEquals(Py.RUNTIME_REQUIRES, DependencyScope.valueOf("RUNTIME_REQUIRES"))

        // A registered scope keeps its real semantics; an unregistered one is re-derived permissively so
        // that the dependency stays visible rather than vanishing off every classpath.
        assertTrue(!DependencyScope.valueOf("RUNTIME_REQUIRES").onCompile)
        assertTrue(DependencyScope.valueOf("NEVER_REGISTERED").onCompile)

        // The Java reading of a level, which the JVM build paths format into -source/-target.
        assertEquals(17, LanguageLevel.JAVA_17.javaVersion)
        assertEquals(8, LanguageLevel.JAVA_8.javaVersion)
        assertEquals(LanguageLevel.DEFAULT.javaVersion, Py.LEVEL.javaVersion)
    }
}
