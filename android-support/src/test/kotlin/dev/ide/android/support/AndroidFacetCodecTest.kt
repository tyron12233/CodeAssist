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
        versionCode = 12,
        versionName = "3.4.1",
        isApplication = true,
        flavorDimensions = listOf("tier"),
        buildTypes = listOf(
            BuildType("debug", debuggable = true, minifyEnabled = false),
            BuildType(
                "release", debuggable = false, minifyEnabled = true, shrinkResources = true,
                proguardFiles = listOf(DefaultProguardFiles.OPTIMIZE, "proguard-rules.pro"),
                consumerProguardFiles = listOf("consumer-rules.pro"),
                proguardRules = listOf("-dontwarn com.example.**", "-keep class com.example.Api { *; }"),
                versionNameSuffix = "-rel",
                signingConfig = "release",
            ),
        ),
        productFlavors = listOf(
            ProductFlavor("free", dimension = "tier", applicationIdSuffix = ".free"),
            ProductFlavor("paid", dimension = "tier", applicationId = "com.example.paid"),
        ),
        r8FullMode = false,
        coreLibraryDesugaringEnabled = true,
        buildFeatures = BuildFeatures(viewBinding = true, compose = true),
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
        assertEquals(12L, values["versionCode"])
        assertEquals("3.4.1", values["versionName"])
        assertEquals(true, values["isApplication"])
        @Suppress("UNCHECKED_CAST")
        val bts = values["buildTypes"] as List<Map<String, Any?>>
        assertEquals("debug", bts[0]["name"])
        assertEquals(true, bts[0]["debuggable"])
        // New R8/ProGuard fields: lists stay lists, the build-wide knobs emit only when non-default.
        assertEquals(true, bts[1]["shrinkResources"])
        assertEquals(listOf(DefaultProguardFiles.OPTIMIZE, "proguard-rules.pro"), bts[1]["proguardFiles"])
        assertEquals(listOf("consumer-rules.pro"), bts[1]["consumerProguardFiles"])
        // The signing-config reference (a registry id) persists on the build type; debug has none.
        assertEquals("release", bts[1]["signingConfig"])
        assertEquals(null, bts[0]["signingConfig"])
        assertEquals(false, values["r8FullMode"])
        assertEquals(true, values["coreLibraryDesugaringEnabled"])
        // buildFeatures flatten into on-only flags on the [android] table.
        assertEquals(true, values["viewBinding"])
        assertEquals(true, values["compose"])
        // shrinkResources is always emitted (like minifyEnabled) so the Module Settings UI can render a
        // toggle to turn it ON — a key omitted when false would leave no control for it.
        assertEquals(false, bts[0]["shrinkResources"])
        // Other defaults are still omitted (no rules): absent keys, not empty.
        assertEquals(null, bts[0]["proguardFiles"])
    }

    @Test
    fun buildFeaturesOmittedWhenOff() {
        val values = AndroidFacetCodec.encode(AndroidFacet(namespace = "com.example.app", compileSdk = 34))
        // An off feature is an absent key, not `false`, so a default facet equals its reloaded form.
        assertEquals(null, values["viewBinding"])
        assertEquals(null, values["compose"])
        assertEquals(BuildFeatures(), AndroidFacetCodec.decode(values).buildFeatures)
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
