package dev.ide.deps.impl

/**
 * The consumer attributes the resolver negotiates GMM variants against. Defaults describe an **Android**
 * compile/analysis classpath (the IDE's dominant target), which is why a KMP library resolves to its
 * `-android` variant. The Android pipeline dexes the runtime subset separately, so a compile (`java-api`)
 * usage is requested, with a `java-runtime` fallback when a library publishes no api variant.
 */
data class VariantRequest(
    val usage: String = "java-api",
    val category: String = "library",
    val platformType: String = "androidJvm",
    val jvmEnvironment: String = "android",
)

/**
 * Picks the best [GmmVariant] for a [VariantRequest] using a small slice of Gradle's attribute matching:
 * hard filters reject incompatible variants (wrong category/usage/platform/library-elements), then a soft
 * score ranks the survivors, with the load-bearing rule being `platform.type` (so `androidJvm` beats `jvm`
 * beats `common`). Faithful enough to choose `-android` over `-jvm`; it is not the full Gradle algorithm.
 */
object GmmVariantSelector {

    private const val CATEGORY = "org.gradle.category"
    private const val USAGE = "org.gradle.usage"
    private const val LIBRARY_ELEMENTS = "org.gradle.libraryelements"
    private const val PLATFORM_TYPE = "org.jetbrains.kotlin.platform.type"
    private const val JVM_ENVIRONMENT = "org.gradle.jvm.environment"

    fun select(module: GradleModule, request: VariantRequest): GmmVariant? {
        val candidates = module.variants.filter { passesHardFilters(it) }
        if (candidates.isEmpty()) return null
        // Prefer api-usage variants; fall back to runtime-only when a library publishes no api variant.
        // Prefer api over runtime usage (java or kotlin); fall back to the whole candidate set when a library
        // publishes only runtime variants. The usage *flavor* (java vs kotlin) is ranked in score().
        val apiPool = candidates.filter { it.attributes[USAGE]?.contains("-api") == true }
        val pool = apiPool.ifEmpty { candidates }
        return pool.maxWithOrNull(
            compareBy(
                { score(it, request) },
                { -nonImplicitCapabilities(it, module) },   // fewer extra capabilities = more "main"
                { it.name },                                 // deterministic final tie-break
            ),
        )
    }

    /**
     * The RUNTIME-usage counterpart of [select]: the variant carrying a module's runtime-only dependencies
     * (e.g. AppCompat's `emoji2-views-helper`, published ONLY in its `java-runtime` variant). The packaged/dex
     * closure must follow these too — GMM has no scope, so a runtime-only transitive lives solely in the
     * runtime variant's `dependencies`. Mirrors [select]'s scoring but filters to `-runtime` usages. Null when
     * the module publishes no runtime variant (then [select]'s pick already carries everything).
     */
    fun runtimeVariant(module: GradleModule, request: VariantRequest): GmmVariant? {
        val runtime = module.variants.filter { passesHardFilters(it) && it.attributes[USAGE]?.contains("-runtime") == true }
        if (runtime.isEmpty()) return null
        return runtime.maxWithOrNull(
            compareBy(
                { score(it, request) },
                { -nonImplicitCapabilities(it, module) },
                { it.name },
            ),
        )
    }

    private val JVM_USAGES = setOf("java-api", "java-runtime", "kotlin-api", "kotlin-runtime")

    /** Reject variants that can never satisfy a JVM/Android library compile request. */
    private fun passesHardFilters(v: GmmVariant): Boolean {
        v.attributes[CATEGORY]?.let { if (it != "library") return false }       // drop platform/documentation
        // Accept java-* AND kotlin-* api/runtime usages — a pure-Kotlin JVM library publishes only kotlin-*,
        // and would otherwise be rejected (falling back to a POM that may not exist).
        v.attributes[USAGE]?.let { if (it !in JVM_USAGES) return false }        // drop kotlin-metadata/klib/etc.
        v.attributes[LIBRARY_ELEMENTS]?.let { if (it != "jar" && it != "aar") return false }  // drop classes/resources secondaries
        v.attributes[PLATFORM_TYPE]?.let { if (it != "androidJvm" && it != "jvm" && it != "common") return false } // drop native/js/wasm
        return true
    }

    private fun score(v: GmmVariant, request: VariantRequest): Int {
        var s = 0
        // platform.type — the decisive rule. An absent type (a plain non-KMP JVM lib) ranks above a pure
        // `-jvm` KMP variant so non-KMP libraries are never penalized.
        s += when (v.attributes[PLATFORM_TYPE]) {
            "androidJvm" -> 100
            "jvm" -> 10
            "common" -> 5
            null -> 20
            else -> 0
        }
        when (v.attributes[JVM_ENVIRONMENT]) {
            request.jvmEnvironment -> s += 50
            "standard-jvm" -> s += 1
        }
        // usage tier: prefer api over runtime, and java-* over kotlin-* (the JVM-consumable form).
        s += when (v.attributes[USAGE]) {
            "java-api" -> 4
            "java-runtime" -> 2
            "kotlin-api" -> 1
            else -> 0   // kotlin-runtime / absent
        }
        return s
    }

    /** Capabilities other than the module's own implicit `group:name` (a feature/`-ktx` variant has one). */
    private fun nonImplicitCapabilities(v: GmmVariant, module: GradleModule): Int =
        v.capabilities.count { it.group != module.group || it.name != module.name }
}
