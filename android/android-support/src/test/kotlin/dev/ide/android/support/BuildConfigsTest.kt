package dev.ide.android.support

import dev.ide.model.VariantId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The generated `BuildConfig`: AGP's built-in fields, then the custom ones by AGP's precedence. */
class BuildConfigsTest {

    private val facet = AndroidFacet(
        namespace = "com.example.app", compileSdk = 36, minSdk = 24,
        buildConfigFields = listOf(
            BuildConfigField("String", "API_URL", "\"https://api.example.com\""),
            BuildConfigField("boolean", "LOGGING", "false"),
        ),
        buildTypes = listOf(
            BuildType("debug", debuggable = true, buildConfigFields = listOf(BuildConfigField("boolean", "LOGGING", "true"))),
            BuildType("release", debuggable = false, applicationIdSuffix = null),
        ),
        flavorDimensions = listOf("tier"),
        productFlavors = listOf(
            ProductFlavor("free", dimension = "tier", applicationIdSuffix = ".free",
                buildConfigFields = listOf(BuildConfigField("String", "API_URL", "\"https://free.example.com\""))),
            ProductFlavor("paid", dimension = "tier"),
        ),
    )

    private fun variant(name: String): AndroidVariant {
        val buildType = listOf("debug", "release").first { name.endsWith(it, ignoreCase = true) }
        val flavor = name.removeSuffix(buildType.replaceFirstChar { it.uppercaseChar() }).takeIf { it != buildType && it.isNotEmpty() }
        return AndroidVariant(VariantId("app:$name"), name, buildType, listOfNotNull(flavor), emptyList())
    }

    @Test
    fun builtInsThenCustomFieldsWithTheBuildTypeWinning() {
        val fields = BuildConfigs.fields(facet, variant("freeDebug"), versionCode = 7, versionName = "1.2").associateBy { it.name }
        assertEquals("Boolean.parseBoolean(\"true\")", fields.getValue("DEBUG").value)
        assertEquals("\"com.example.app.free\"", fields.getValue("APPLICATION_ID").value, "the flavor's suffix applies")
        assertEquals("\"debug\"", fields.getValue("BUILD_TYPE").value)
        assertEquals("\"free\"", fields.getValue("FLAVOR").value)
        assertEquals("7", fields.getValue("VERSION_CODE").value)
        assertEquals("\"https://free.example.com\"", fields.getValue("API_URL").value, "a flavor overrides defaultConfig")
        assertEquals("true", fields.getValue("LOGGING").value, "the build type overrides both")

        val paidRelease = BuildConfigs.fields(facet, variant("paidRelease"), 7, "1.2").associateBy { it.name }
        assertEquals("Boolean.parseBoolean(\"false\")", paidRelease.getValue("DEBUG").value)
        assertEquals("\"https://api.example.com\"", paidRelease.getValue("API_URL").value)
        assertEquals("false", paidRelease.getValue("LOGGING").value)
    }

    @Test
    fun aLibraryNamesItsPackageAndHasNoVersion() {
        val lib = facet.copy(isApplication = false, productFlavors = emptyList(), flavorDimensions = emptyList())
        val names = BuildConfigs.fields(lib, variant("debug"), 1, "1.0").map { it.name }
        assertTrue("LIBRARY_PACKAGE_NAME" in names && "APPLICATION_ID" !in names && "VERSION_CODE" !in names, "$names")
    }

    @Test
    fun rendersTheJavaAgpWrites() {
        val source = BuildConfigs.render("com.example.app", BuildConfigs.fields(facet, variant("paidRelease"), 3, "2.0"))
        assertContains(source, "package com.example.app;")
        assertContains(source, "public final class BuildConfig {")
        assertContains(source, "  public static final String API_URL = \"https://api.example.com\";")
        assertContains(source, "  // Field from default config.")
    }

    @Test
    fun fieldsRoundTripThroughModuleToml() {
        assertEquals(facet, AndroidFacetCodec.decode(AndroidFacetCodec.encode(facet)))
        assertEquals(
            BuildConfigField("String", "URL", "\"a = b\""),
            BuildConfigField.decode("String URL = \"a = b\""),
            "only the first = separates the value",
        )
        val off = facet.copy(buildFeatures = BuildFeatures(buildConfig = false))
        assertEquals(false, AndroidFacetCodec.encode(off)["buildConfig"])
        assertEquals(off, AndroidFacetCodec.decode(AndroidFacetCodec.encode(off)))
    }
}
