package dev.ide.android.support

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import dev.ide.model.SourceSetTemplate
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
