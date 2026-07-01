package dev.ide.deps.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GmmVariantSelectorTest {

    private fun variant(name: String, vararg attrs: Pair<String, String>, capabilities: List<GmmCapability> = emptyList()) =
        GmmVariant(name, attrs.toMap(), dependencies = emptyList(), dependencyConstraints = emptyList(), files = emptyList(), capabilities = capabilities, availableAt = null)

    private fun module(vararg variants: GmmVariant, group: String = "g", name: String = "lib") =
        GradleModule(group, name, "1.0", variants.toList())

    private fun pick(module: GradleModule) = GmmVariantSelector.select(module, VariantRequest())?.name

    @Test
    fun prefersAndroidOverJvmOverCommon() {
        val m = module(
            variant("common", "org.gradle.category" to "library", "org.gradle.usage" to "java-api", "org.jetbrains.kotlin.platform.type" to "common"),
            variant("jvm", "org.gradle.category" to "library", "org.gradle.usage" to "java-api", "org.jetbrains.kotlin.platform.type" to "jvm"),
            variant("android", "org.gradle.category" to "library", "org.gradle.usage" to "java-api", "org.jetbrains.kotlin.platform.type" to "androidJvm", "org.gradle.jvm.environment" to "android"),
        )
        assertEquals("android", pick(m))
    }

    @Test
    fun nonKmpJvmLibraryIsNotPenalizedAgainstAPureJvmKmpVariant() {
        // A plain library variant (no platform.type) ranks above a `-jvm`-only KMP variant — a non-KMP lib
        // must never lose to a pure jvm variant of a different library shape.
        val m = module(
            variant("plain", "org.gradle.category" to "library", "org.gradle.usage" to "java-api"),
            variant("jvmOnly", "org.gradle.category" to "library", "org.gradle.usage" to "java-api", "org.jetbrains.kotlin.platform.type" to "jvm"),
        )
        assertEquals("plain", pick(m))
    }

    @Test
    fun prefersApiOverRuntimeButFallsBackToRuntime() {
        val both = module(
            variant("runtime", "org.gradle.category" to "library", "org.gradle.usage" to "java-runtime", "org.jetbrains.kotlin.platform.type" to "androidJvm"),
            variant("api", "org.gradle.category" to "library", "org.gradle.usage" to "java-api", "org.jetbrains.kotlin.platform.type" to "androidJvm"),
        )
        assertEquals("api", pick(both))

        val runtimeOnly = module(
            variant("runtime", "org.gradle.category" to "library", "org.gradle.usage" to "java-runtime", "org.jetbrains.kotlin.platform.type" to "androidJvm"),
        )
        assertEquals("runtime", pick(runtimeOnly))
    }

    @Test
    fun rejectsNonLibraryAndNonJvmPlatformVariants() {
        val m = module(
            variant("native", "org.gradle.category" to "library", "org.gradle.usage" to "java-api", "org.jetbrains.kotlin.platform.type" to "native"),
            variant("bom", "org.gradle.category" to "platform", "org.gradle.usage" to "java-api"),
        )
        assertNull(pick(m), "no JVM/Android library variant → nothing selectable (caller falls back to POM)")
    }

    @Test
    fun prefersMainCapabilityOverFeatureVariant() {
        val m = module(
            variant("feature", "org.gradle.category" to "library", "org.gradle.usage" to "java-api", "org.jetbrains.kotlin.platform.type" to "androidJvm",
                capabilities = listOf(GmmCapability("g", "lib-ktx", "1.0"))),
            variant("main", "org.gradle.category" to "library", "org.gradle.usage" to "java-api", "org.jetbrains.kotlin.platform.type" to "androidJvm",
                capabilities = listOf(GmmCapability("g", "lib", "1.0"))),
        )
        assertEquals("main", pick(m))
    }
}
