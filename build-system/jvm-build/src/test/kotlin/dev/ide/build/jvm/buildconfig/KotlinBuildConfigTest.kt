package dev.ide.build.jvm.buildconfig

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The Kotlin `BuildConfig` object gmazzo's plugin writes, and its `module.toml` form. */
class KotlinBuildConfigTest {

    private val facet = KotlinBuildConfigFacet(
        packageName = "org.company.app",
        fields = listOf(
            KotlinBuildConfigFacet.Field("String", "API_URL", "\"https://api.example.com\""),
            KotlinBuildConfigFacet.Field("Boolean", "LOGGING", "true"),
            KotlinBuildConfigFacet.Field("List<String>", "HOSTS", "listOf(\"a\", \"b\")"),
        ),
    )

    @Test
    fun rendersAnObjectOfConstants() {
        val source = KotlinBuildConfigSource.render(facet)
        assertContains(source, "package org.company.app")
        assertContains(source, "internal object BuildConfig {")
        assertContains(source, "  internal const val API_URL: String = \"https://api.example.com\"")
        assertContains(source, "  internal const val LOGGING: Boolean = true")
        assertContains(source, "  internal val HOSTS: List<String> = listOf(\"a\", \"b\")", message = "no const where Kotlin forbids it")
        assertContains(KotlinBuildConfigSource.render(facet.copy(internalVisibility = false)), "public object BuildConfig {")
    }

    @Test
    fun roundTripsThroughModuleToml() {
        assertEquals(facet, KotlinBuildConfigFacetCodec.decode(KotlinBuildConfigFacetCodec.encode(facet)))
        val custom = facet.copy(className = "AppConfig", internalVisibility = false)
        assertEquals(custom, KotlinBuildConfigFacetCodec.decode(KotlinBuildConfigFacetCodec.encode(custom)))
    }
}
