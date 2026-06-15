package dev.ide.android.support

import dev.ide.model.BuildSystemId
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidFacetCodecTest {

    private val richFacet = AndroidFacet(
        namespace = "com.example.app",
        compileSdk = 34,
        minSdk = 24,
        targetSdk = 33,
        manifest = "src/main/AndroidManifest.xml",
        isApplication = true,
        flavorDimensions = listOf("tier"),
        buildTypes = listOf(
            BuildType("debug", debuggable = true, minifyEnabled = false),
            BuildType("release", debuggable = false, minifyEnabled = true, versionNameSuffix = "-rel"),
        ),
        productFlavors = listOf(
            ProductFlavor("free", dimension = "tier", applicationIdSuffix = ".free"),
            ProductFlavor("paid", dimension = "tier", applicationId = "com.example.paid"),
        ),
    )

    @Test
    fun encodeDecodeRoundTripsInMemory() {
        val decoded = AndroidFacetCodec.decode(AndroidFacetCodec.encode(richFacet))
        assertEquals(richFacet, decoded)
    }

    @Test
    fun encodedValuesAreTomlNativeTypes() {
        val values = AndroidFacetCodec.encode(richFacet)
        // Integers must be Long (TOML's only integer) so an in-memory facet equals a reloaded one.
        assertEquals(34L, values["compileSdk"])
        assertEquals(24L, values["minSdk"])
        assertEquals(true, values["isApplication"])
        @Suppress("UNCHECKED_CAST")
        val bts = values["buildTypes"] as List<Map<String, Any?>>
        assertEquals("debug", bts[0]["name"])
        assertEquals(true, bts[0]["debuggable"])
    }

    /** Build with a facet, save, reload -> identical. */
    @Test
    fun facetSurvivesModuleTomlSaveAndReload() {
        val dir = Files.createTempDirectory("android-facet-roundtrip")
        val platform = PlatformCore()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(AndroidFacetCodec))
            ModuleTypeRegistry(platform.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
            val appType = ModuleTypeRegistry(platform.extensions).resolve("android-app")

            store.workspace.beginModification().apply { addProject("demo", BuildSystemId.NATIVE, store.vfs.root()); commit() }
            store.workspace.projects.single().beginModification().apply {
                addModule("app", appType).apply { putFacet(richFacet) }
                commit()
            }
            val before = store.data
            store.save()

            val platform2 = PlatformCore()
            try {
                val store2 = ProjectModel.open(dir, platform2, FacetCodecRegistry().register(AndroidFacetCodec))
                ModuleTypeRegistry(platform2.extensions).register(AndroidAppModuleType, AndroidSupport.PLUGIN)
                assertEquals(before, store2.data, "android module.toml must round-trip to an identical snapshot")
                val reloaded = store2.workspace.projects.single().modules.single { it.name == "app" }
                assertEquals("android-app", reloaded.type.id)
                assertEquals(richFacet, reloaded.facets.get(AndroidFacet.KEY))
            } finally {
                platform2.dispose()
            }
        } finally {
            platform.dispose(); dir.toFile().deleteRecursively()
        }
    }
}
