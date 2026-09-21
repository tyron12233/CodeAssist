package dev.ide.model.bridge

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.FacetData
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.templates.JavaLibModuleType
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiModuleConfigEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The model-to-UI bridge every host shares.
 *
 * These run on the JVM because they are quick and the logic is platform-neutral; that it also runs on
 * Kotlin/Native is proved where it matters, by the iOS host's own suite driving the same classes through a
 * real backend. What is pinned here is the CONTRACT both hosts now depend on: what a module config carries,
 * what an edit keeps, and what the model refuses.
 */
class ModelBridgeTest {

    private val dir: Path = Files.createTempDirectory("model-bridge")
    private var store: ProjectModelStore? = null

    @AfterTest
    fun cleanUp() {
        store?.close()
        dir.toFile().deleteRecursively()
    }

    /** A workspace with one `app` module, sources under `src/main/java`, saved to disk. */
    private fun workspace(): ProjectModelStore {
        val platform = PlatformCore()
        ModuleTypeRegistry(platform.extensions).register(JavaLibModuleType, PluginId("test"))
        val opened = ProjectModel.open(dir.toString(), platform, FacetCodecRegistry())
        store = opened
        opened.workspace.beginModification().apply {
            addProject("demo", BuildSystemId.NATIVE, opened.vfs.root())
            commit()
        }
        opened.workspace.projects.single().beginModification().apply {
            addModule("app", JavaLibModuleType).apply {
                languageLevel = LanguageLevel.JAVA_17
                addSourceSet(
                    SourceSetTemplate(
                        "main",
                        DependencyScope.IMPLEMENTATION,
                        mapOf("src/main/java" to setOf(ContentRole.SOURCE)),
                    ),
                )
            }
            commit()
        }
        opened.save()
        return opened
    }

    // ---- reading -------------------------------------------------------------------------------------

    @Test
    fun aModuleConfigCarriesTypeLevelAndSourceSets() {
        val bridge = ModuleConfigBridge(workspace())

        val config = assertNotNull(bridge.moduleConfig("app"))

        assertEquals("app", config.name)
        assertEquals("java-lib", config.typeId)
        assertEquals("JAVA_17", config.languageLevel)
        assertTrue(config.languageLevel in config.languageLevels)
        assertEquals(listOf("main"), config.sourceSets.map { it.name })
        assertTrue(config.sourceSets.single().roots.single().endsWith("src/main/java"))
        assertNull(config.runConfig, "the model has no opinion about what to run")
        assertNull(bridge.moduleConfig("missing"))
    }

    /**
     * A facet table with no registered codec is the iOS case and the forward-compatibility case at once: a
     * table an older build has never heard of must still render and must still survive an edit.
     */
    @Test
    fun aFacetWithNoCodecStillRendersAndSurvivesAnEdit() {
        val opened = workspace()
        val bridge = ModuleConfigBridge(opened)
        val module = assertNotNull(bridge.module("app"))
        assertNotNull(bridge.projectOf(module)).beginModification().apply {
            module(module.id).putFacetData(
                FacetData(
                    "android",
                    mapOf(
                        "namespace" to "com.example",
                        "minSdk" to 24L,
                        "isApplication" to true,
                        "proguardFiles" to listOf("proguard-rules.pro"),
                    ),
                ),
            )
            commit()
        }

        val panel = assertNotNull(bridge.facetPanels(assertNotNull(bridge.module("app"))).singleOrNull())
        assertEquals("android", panel.table)
        assertEquals("Android", panel.title)
        assertTrue(panel.fields.any { it is UiConfigField.Text && it.key == "namespace" })
        assertTrue(panel.fields.any { it is UiConfigField.Number && it.key == "minSdk" })
        assertTrue(panel.fields.any { it is UiConfigField.Bool && it.key == "isApplication" })
        assertTrue(panel.fields.any { it is UiConfigField.StringList && it.key == "proguardFiles" })

        val saved = bridge.updateModuleConfig(
            "app",
            UiModuleConfigEdit(facetValues = mapOf("android" to mapOf("minSdk" to 26L))),
        )
        assertTrue(saved.success, saved.message)

        val after = bridge.facetPanels(assertNotNull(bridge.module("app"))).single()
        assertEquals(26L, (after.fields.first { it.key == "minSdk" } as UiConfigField.Number).value)
        assertEquals(
            "com.example",
            (after.fields.first { it.key == "namespace" } as UiConfigField.Text).value,
            "an overlay, not a replacement: a key the screen never sent is a key it may not drop",
        )
    }

    /** A key another tab owns is kept out of the generic field list, and an edit still does not lose it. */
    @Test
    fun aHiddenFacetKeyIsNotRenderedAndNotLost() {
        val opened = workspace()
        val hiding = ModuleConfigBridge(opened, hiddenFacetKeys = setOf("packaging"))
        val module = assertNotNull(hiding.module("app"))
        assertNotNull(hiding.projectOf(module)).beginModification().apply {
            module(module.id).putFacetData(
                FacetData("android", mapOf("namespace" to "com.example", "packaging" to mapOf("x" to "y"))),
            )
            commit()
        }

        val panel = hiding.facetPanels(assertNotNull(hiding.module("app"))).single()
        assertEquals(listOf("namespace"), panel.fields.map { it.key })

        hiding.updateModuleConfig(
            "app",
            UiModuleConfigEdit(facetValues = mapOf("android" to mapOf("namespace" to "com.other"))),
        )
        val values = ModuleConfigBridge(opened).facetTables(assertNotNull(hiding.module("app"))).single().values
        assertEquals("com.other", values["namespace"])
        assertTrue(values.containsKey("packaging"), "the tab that owns it is the only thing that may change it")
    }

    // ---- writing -------------------------------------------------------------------------------------

    @Test
    fun theLanguageLevelIsPersistedAndAnUnknownOneIsRefused() {
        val opened = workspace()
        val bridge = ModuleConfigBridge(opened)

        assertTrue(bridge.updateModuleConfig("app", UiModuleConfigEdit(languageLevel = "JAVA_21")).success)
        assertTrue("JAVA_21" in dir.resolve("app/module.toml").toFile().readText())

        val refused = bridge.updateModuleConfig("app", UiModuleConfigEdit(languageLevel = "JAVA_99"))
        assertFalse(refused.success, refused.message)
        assertEquals("JAVA_21", assertNotNull(bridge.moduleConfig("app")).languageLevel)
    }

    @Test
    fun aSourceRootIsDeclaredBesideTheExistingOnesAndCreated() {
        val bridge = ModuleConfigBridge(workspace())

        val created = assertNotNull(
            bridge.addSourceRoot("app", "main", "resources", setOf(ContentRole.RESOURCE)),
        )

        assertTrue(created.endsWith("src/main/resources"), "beside src/main/java, not under it: $created")
        assertTrue(Path.of(created).exists(), "a declared root that is not on disk is one nothing can use")
        assertTrue(bridge.moduleConfig("app")!!.sourceSets.single().roots.any { it == created })

        assertTrue(bridge.removeSourceRoot("app", "main", created), "and it can be unmarked by its full path")
        assertTrue(Path.of(created).exists(), "the model forgot it; the directory is still the user's")
    }

    @Test
    fun aSourceSetIsAddedOnceAndModulesAreCreatedAndRemoved() {
        val bridge = ModuleConfigBridge(workspace())

        assertTrue(bridge.addSourceSet("app", "test"))
        assertFalse(bridge.addSourceSet("app", "test"), "not twice")
        assertEquals(listOf("main", "test"), bridge.moduleSourceSets("app").sorted())

        assertTrue(bridge.createModule("core", "java-lib", null, emptyMap()).success)
        assertTrue(dir.resolve("core/src/main/kotlin").exists(), "the fallback layout, created")
        assertFalse(bridge.createModule("core", "java-lib", null, emptyMap()).success, "a name is taken once")
        assertFalse(bridge.createModule("1bad", "java-lib", null, emptyMap()).success, "and must be a name")
        assertFalse(bridge.createModule("x", "no-such-type", null, emptyMap()).success)

        assertTrue(bridge.removeModule("core"))
        assertEquals(listOf("app"), bridge.configurableModules().map { it.name })
        assertFalse(bridge.removeModule("core"), "already gone")
    }

    // ---- dependencies --------------------------------------------------------------------------------

    @Test
    fun aDeclarationGoesIntoTheModuleAndComesBackWithItsScope() {
        val opened = workspace()
        val deps = DependencyModelBridge(opened)
        val config = ModuleConfigBridge(opened)
        val module = assertNotNull(config.module("app"))
        val project = assertNotNull(config.projectOf(module))
        val widget = Coordinate("com.example", "widget", "1.2.0")

        assertTrue(deps.declare(project, module, widget, "api"))

        val declared = deps.declared(assertNotNull(config.module("app"))).single()
        assertEquals(widget, declared.coordinate)
        assertEquals("api", declared.scope)
        assertTrue("com.example:widget:1.2.0" in dir.resolve("app/module.toml").toFile().readText())

        // The same artifact at another version is the same declaration, and a second one is refused.
        assertFalse(
            deps.declare(project, assertNotNull(config.module("app")), widget.copy(version = "2.0.0")),
            "one declaration per group:name",
        )
        assertTrue(deps.undeclare(project, assertNotNull(config.module("app")), widget.copy(version = "9.9")))
        assertTrue(deps.declared(assertNotNull(config.module("app"))).isEmpty(), "removed by group:name, not version")
    }

    @Test
    fun aModuleDependencyIsOfferedOnlyWhereItCannotCloseACycle() {
        val opened = workspace()
        val config = ModuleConfigBridge(opened)
        val deps = DependencyModelBridge(opened)
        assertTrue(config.createModule("core", "java-lib", null, emptyMap()).success)
        val app = assertNotNull(config.module("app"))
        val core = assertNotNull(config.module("core"))
        val project = assertNotNull(config.projectOf(app))

        assertEquals(listOf("core"), deps.moduleDependencyTargets(app).map { it.name })
        assertTrue(deps.declareModule(project, app, core))

        assertFalse(deps.declareModule(project, assertNotNull(config.module("app")), core), "not twice")
        assertEquals(
            emptyList(),
            deps.moduleDependencyTargets(assertNotNull(config.module("core"))).map { it.name },
            "core cannot depend on app: app already depends on core",
        )
        assertEquals(
            listOf(core.id),
            deps.moduleDependencies(assertNotNull(config.module("app"))).map { it.target },
        )
    }

    @Test
    fun aConfigurationLabelMapsToTheScopeItNames() {
        assertEquals(DependencyScope.API, DependencyModelBridge.scopeOf("api"))
        assertEquals(DependencyScope.IMPLEMENTATION, DependencyModelBridge.scopeOf("implementation"))
        assertEquals(DependencyScope.COMPILE_ONLY, DependencyModelBridge.scopeOf("compileOnly"))
        assertEquals(DependencyScope.COMPILE_ONLY, DependencyModelBridge.scopeOf("COMPILE_ONLY"))
        assertEquals(DependencyScope.TEST_IMPLEMENTATION, DependencyModelBridge.scopeOf("test"))
        assertEquals(DependencyScope.NATIVES, DependencyModelBridge.scopeOf("natives"))
        assertEquals(
            DependencyScope.IMPLEMENTATION,
            DependencyModelBridge.scopeOf("something nobody registered"),
            "an unknown label is the default rather than a failure",
        )
    }

    @Test
    fun aFacetValueBecomesTheControlThatEditsIt() {
        assertTrue(ModuleConfigBridge.fieldFor("a", "x") is UiConfigField.Text)
        assertTrue(ModuleConfigBridge.fieldFor("a", 1) is UiConfigField.Number)
        assertTrue(ModuleConfigBridge.fieldFor("a", 1L) is UiConfigField.Number)
        assertTrue(ModuleConfigBridge.fieldFor("a", true) is UiConfigField.Bool)
        assertTrue(ModuleConfigBridge.fieldFor("a", listOf("x")) is UiConfigField.StringList)
        assertTrue(ModuleConfigBridge.fieldFor("a", listOf(mapOf("k" to "v"))) is UiConfigField.TableList)
        assertTrue(ModuleConfigBridge.fieldFor("a", null) is UiConfigField.Text, "nothing is a blank text field")
        assertEquals("Compile Sdk", ModuleConfigBridge.humanize("compileSdk"))
        assertEquals("Application Id Suffix", ModuleConfigBridge.humanize("applicationIdSuffix"))
    }
}
