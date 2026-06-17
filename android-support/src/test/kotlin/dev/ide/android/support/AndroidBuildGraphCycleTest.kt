package dev.ide.android.support

import dev.ide.android.support.tools.AndroidSdk
import dev.ide.android.support.tools.SigningConfig
import dev.ide.build.BuildGoal
import dev.ide.build.BuildRequest
import dev.ide.build.CyclicTaskDependencyException
import dev.ide.build.VariantSelector
import dev.ide.build.engine.JavaCompile
import dev.ide.build.engine.JavaCompileResult
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.LanguageLevel
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.fail

/**
 * Regression for issue #993: the default demo (`app android-app → feature android-lib → core java-lib`)
 * must produce an **acyclic** build graph. A reported cycle
 * `:feature:classes → :core:jar → :core:classes → :feature:jar → :feature:classes`
 * meant a clean v3.0.0 install could not build the demo at all. This builds the graph (no SDK needed —
 * construction only stores paths) and runs the topological sort, which throws on a cycle.
 */
class AndroidBuildGraphCycleTest {

    private object JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    @Test
    fun demoGraphIsAcyclic() {
        val dir = Files.createTempDirectory("android-cycle")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            val types = ModuleTypeRegistry(platform.extensions)
            types.register(JavaLib, PluginId("java-support"))
            AndroidSupport.register(types, FacetCodecRegistry())

            SampleAndroidProject.generate(
                store,
                androidApp = types.resolve("android-app"),
                androidLib = types.resolve("android-lib"),
                javaLib = types.resolve("java-lib"),
            )
            // Mirror the device flow: the demo is generated, saved, then RELOADED from `module.toml` on the
            // next launch (ProjectManager.open). Path resolution on reload is what the IDE actually builds.
            store.save()
            val reopened = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))

            // Fake SDK / signing — graph construction only records paths, it never reads them.
            val sdk = AndroidSdk(
                androidJar = dir.resolve("fake/android.jar"),
                buildToolsDir = dir.resolve("fake/build-tools"),
            )
            val signing = SigningConfig(dir.resolve("fake/debug.ks"), "android", "android", "android")
            val buildSystem = AndroidBuildSystem.subprocess(noopJavaCompile(), sdk, signing)

            val project = reopened.workspace.projects.single { it.name == SampleAndroidProject.PROJECT }
            for (goal in listOf(BuildGoal.COMPILE_ONLY, BuildGoal.PACKAGE)) {
                val graph = buildSystem.createBuildGraph(
                    project,
                    BuildRequest(listOf(ModuleId("app")), VariantSelector("debug"), goal),
                )
                try {
                    graph.topologicalLevels()
                } catch (e: CyclicTaskDependencyException) {
                    fail("demo build graph has a cycle (goal=$goal): ${e.cycle.joinToString(" -> ") { it.value }}")
                }
            }
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    private fun noopJavaCompile(): JavaCompile = JavaCompile { _, _, _, _ -> JavaCompileResult(true, emptyList()) }
}
