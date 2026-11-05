package dev.ide.core

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.BuildFeatures
import dev.ide.android.support.BuildType
import dev.ide.android.support.ProductFlavor
import dev.ide.model.ANDROID_MODULES
import dev.ide.model.AndroidModules
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `platform.androidModules`, the read view of the open project's Android configuration, over the demo project
 * (`app` and `feature` are Android, `core` is plain Java). What a plugin gets is what the build uses: resolved
 * application ids per variant, AGP's defaults reading as "not set", and the variant the editor analyses.
 */
class AndroidModulesSpiTest {

    private val root = createTempDirectory("android-modules-spi")
    private val services: IdeServices = IdeServices.bootstrapDemo(root)

    @AfterTest
    fun tearDown() {
        services.close()
        root.toFile().deleteRecursively()
    }

    private val android: AndroidModules
        get() = services.store.workspace.service(ANDROID_MODULES)

    private fun setAppFacet(facet: AndroidFacet) {
        val app = services.modules().first { it.name == "app" }
        val project = services.store.workspace.projects.first { p -> p.modules.any { it.name == "app" } }
        project.beginModification().apply {
            module(app.id).putFacet(facet)
            commit()
        }
    }

    @Test
    fun `lists the Android modules and answers null for the rest`() {
        assertEquals(setOf("app", "feature"), android.all().map { it.moduleName }.toSet())
        assertNull(android.info("core"), "a plain Java module is not an Android module")
        assertNull(android.info("no-such-module"))
        val feature = assertNotNull(android.info("feature"))
        assertFalse(feature.isApplication)
        assertNull(feature.applicationId, "a library has no application id")
        assertTrue(feature.variants.all { it.applicationId == null })
    }

    @Test
    fun `reads the configuration as the build resolves it`() {
        setAppFacet(
            AndroidFacet(
                namespace = "com.example.demo",
                compileSdk = 35,
                minSdk = 24,
                targetSdk = 34,
                manifestPlaceholders = mapOf("hostName" to "example.com"),
                flavorDimensions = listOf("tier"),
                productFlavors = listOf(
                    ProductFlavor("free", dimension = "tier", applicationIdSuffix = ".free"),
                    ProductFlavor("pro", dimension = "tier", applicationId = "com.example.pro"),
                ),
                buildTypes = listOf(
                    BuildType("debug", debuggable = true, applicationIdSuffix = ".debug"),
                    BuildType("release", debuggable = false, minifyEnabled = true, shrinkResources = true),
                ),
                buildFeatures = BuildFeatures(compose = true, viewBinding = true),
                coreLibraryDesugaringEnabled = true,
            ),
        )
        val app = assertNotNull(android.info("app"))
        assertTrue(app.isApplication)
        assertEquals("com.example.demo", app.namespace)
        assertEquals("com.example.demo", app.applicationId, "no applicationId set: it follows the namespace")
        assertEquals(Triple(35, 24, 34), Triple(app.compileSdk, app.minSdk, app.targetSdk))
        assertNull(app.versionCode, "AGP's default reads as not set, since the manifest's value then wins")
        assertNull(app.versionName)
        assertEquals(mapOf("hostName" to "example.com"), app.manifestPlaceholders)
        assertTrue(app.coreLibraryDesugaring)
        assertTrue(app.buildFeatures.compose && app.buildFeatures.viewBinding && !app.buildFeatures.parcelize)
        assertEquals(listOf("tier"), app.flavorDimensions)
        assertEquals(listOf("debug", "release"), app.buildTypes.map { it.name })
        assertTrue(app.buildTypes.single { it.name == "release" }.let { it.minifyEnabled && it.shrinkResources })

        val byName = app.variants.associateBy { it.name }
        assertEquals(setOf("freeDebug", "freeRelease", "proDebug", "proRelease"), byName.keys)
        assertEquals("com.example.demo.free.debug", byName.getValue("freeDebug").applicationId)
        assertEquals("com.example.pro", byName.getValue("proRelease").applicationId, "a flavor's own id wins")
        assertEquals(listOf("pro"), byName.getValue("proRelease").flavors)
        assertTrue(byName.getValue("freeDebug").debuggable)
        assertTrue(byName.getValue("proRelease").minifyEnabled)
        assertTrue(app.activeVariant in byName.keys, "the active variant is one of the module's own")
    }

    @Test
    fun `follows the active variant the user picks`() {
        setAppFacet(
            AndroidFacet(
                namespace = "com.example.demo",
                compileSdk = 34,
                flavorDimensions = listOf("tier"),
                productFlavors = listOf(ProductFlavor("free", dimension = "tier"), ProductFlavor("pro", dimension = "tier")),
            ),
        )
        services.setActiveVariant("app", "proRelease")
        assertEquals("proRelease", android.info("app")?.activeVariant)
    }

    @Test
    fun `a version set in the build file is reported`() {
        setAppFacet(AndroidFacet(namespace = "com.example.demo", compileSdk = 34, versionCode = 7, versionName = "1.2.3"))
        val app = assertNotNull(android.info("app"))
        assertEquals(7, app.versionCode)
        assertEquals("1.2.3", app.versionName)
    }
}
