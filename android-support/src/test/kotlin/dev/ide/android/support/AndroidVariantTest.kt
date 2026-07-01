package dev.ide.android.support

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import dev.ide.model.SourceSetTemplate
import dev.ide.model.VariantId
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidVariantTest {

    @Test
    fun noFlavorsYieldsOneVariantPerBuildType() {
        withModule(
            facet = AndroidFacet(namespace = "com.example.app", compileSdk = 34),
            extraSourceSets = emptyList(),
        ) { module ->
            val variants = AndroidVariants.compute(module)
            assertEquals(listOf("debug", "release"), variants.map { it.name })
            // The default module type lays down main + debug + release source sets.
            assertEquals(listOf("main", "debug"), variants.first { it.name == "debug" }.activeSourceSets.map { it.name })
        }
    }

    @Test
    fun buildTypesCrossProductWithFlavorDimension() {
        withModule(
            facet = AndroidFacet(
                namespace = "com.example.app",
                compileSdk = 34,
                flavorDimensions = listOf("tier"),
                productFlavors = listOf(
                    ProductFlavor("free", dimension = "tier"),
                    ProductFlavor("paid", dimension = "tier"),
                ),
            ),
            extraSourceSets = listOf("free", "paid", "freeDebug"),
        ) { module ->
            val variants = AndroidVariants.compute(module)
            assertEquals(listOf("freeDebug", "paidDebug", "freeRelease", "paidRelease"), variants.map { it.name })

            val freeDebug = variants.first { it.name == "freeDebug" }
            // main + free + debug + freeDebug all exist -> all active, in that order.
            assertEquals(listOf("main", "free", "debug", "freeDebug"), freeDebug.activeSourceSets.map { it.name })
            assertEquals("debug", freeDebug.buildTypeName)
            assertEquals(listOf("free"), freeDebug.flavorNames)

            // paidDebug has no `paidDebug` source set, so only the ones that exist are active.
            val paidDebug = variants.first { it.name == "paidDebug" }
            assertEquals(listOf("main", "paid", "debug"), paidDebug.activeSourceSets.map { it.name })

            assertEquals("freeDebug", AndroidVariants.select(module, "freeDebug")?.name)
            assertEquals(null, AndroidVariants.select(module, "nope"))
        }
    }

    @Test
    fun configurationsAreTheUnfilteredCandidateNames() {
        // Variant.configurations (the dependency-config filter set) must be the FULL candidate name set, even
        // for names with no declared source set — so a `debugImplementation`/flavor-qualified dependency still
        // matches the variant. `paidDebug` has no `paidDebug` source set, but `paidDebug` is still a config.
        withModule(
            facet = AndroidFacet(
                namespace = "com.example.app",
                compileSdk = 34,
                flavorDimensions = listOf("tier"),
                productFlavors = listOf(ProductFlavor("free", dimension = "tier"), ProductFlavor("paid", dimension = "tier")),
            ),
            extraSourceSets = listOf("free", "paid", "freeDebug"),
        ) { module ->
            val freeDebug = AndroidVariants.compute(module).first { it.name == "freeDebug" }
            assertEquals(setOf("main", "free", "debug", "freeDebug"), freeDebug.configurations)

            val paidDebug = AndroidVariants.compute(module).first { it.name == "paidDebug" }
            assertEquals(setOf("main", "paid", "debug", "paidDebug"), paidDebug.configurations)
            // ...even though the paidDebug source set was never declared:
            assertEquals(listOf("main", "paid", "debug"), paidDebug.activeSourceSets.map { it.name })
        }
    }

    @Test
    fun matchLibraryVariantIsDimensionAwareWithDebuggabilityFallback() {
        val dir = Files.createTempDirectory("android-match")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidLibModuleType, AndroidSupport.PLUGIN)
            val libType = ModuleTypeRegistry(platform.extensions).resolve("android-lib")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("lib", libType).apply {
                    putFacet(AndroidFacet(
                        namespace = "com.lib", compileSdk = 34, isApplication = false,
                        flavorDimensions = listOf("tier"),
                        productFlavors = listOf(ProductFlavor("free", dimension = "tier"), ProductFlavor("paid", dimension = "tier")),
                    ))
                }
                commit()
            }
            val lib = store.workspace.projects.single().modules.single { it.name == "lib" }
            // The consuming app shares the `tier` dimension and adds a debuggable `staging` build type.
            val appFacet = AndroidFacet(
                namespace = "com.app", compileSdk = 34,
                flavorDimensions = listOf("tier"),
                productFlavors = listOf(ProductFlavor("free", dimension = "tier"), ProductFlavor("paid", dimension = "tier")),
                buildTypes = listOf(BuildType("debug"), BuildType("release"), BuildType("staging", debuggable = true)),
            )
            fun consumer(name: String, bt: String, flavors: List<String>) =
                AndroidVariant(VariantId("app:$name"), name, bt, flavors, emptyList(), emptySet())

            // exact build type + dimension-matched flavor
            assertEquals("freeDebug", AndroidVariants.matchLibraryVariant(lib, consumer("freeDebug", "debug", listOf("free")), appFacet)?.name)
            assertEquals("paidRelease", AndroidVariants.matchLibraryVariant(lib, consumer("paidRelease", "release", listOf("paid")), appFacet)?.name)
            // `staging` (debuggable) isn't a lib build type → fall back to the lib's debuggable variant of the same flavor.
            assertEquals("freeDebug", AndroidVariants.matchLibraryVariant(lib, consumer("freeStaging", "staging", listOf("free")), appFacet)?.name)
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }

    // --- support ---

    private fun withModule(
        facet: AndroidFacet,
        extraSourceSets: List<String>,
        body: (Module) -> Unit,
    ) {
        val dir = Files.createTempDirectory("android-variant")
        val platform = PlatformCore()
        try {
            val store: ProjectModelStore = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")
            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply {
                    putFacet(facet)
                    extraSourceSets.forEach { name ->
                        addSourceSet(SourceSetTemplate(name, DependencyScope.IMPLEMENTATION, mapOf("src/$name/java" to setOf(ContentRole.SOURCE))))
                    }
                }
                commit()
            }
            body(store.workspace.projects.single().modules.single { it.name == "app" })
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }
}
