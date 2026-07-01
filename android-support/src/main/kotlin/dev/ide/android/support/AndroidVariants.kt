package dev.ide.android.support

import dev.ide.model.DependencyScope
import dev.ide.model.Module
import dev.ide.model.SourceSet
import dev.ide.model.Variant
import dev.ide.model.VariantId

/**
 * Computes a module's Android [Variant]s from its [AndroidFacet]: the cross-product of build types and
 * the product-flavor combinations (one flavor per dimension, in dimension order), matching Gradle's
 * variant model. With no flavors the variants are just the build types (`debug`, `release`). A variant
 * resolves which source sets are active: `main`, each selected flavor, the build type, the combined
 * flavor name (multi-dimension), and the flavor+buildType set, keeping only those the module actually
 * declares.
 */
object AndroidVariants {

    fun compute(module: Module): List<AndroidVariant> {
        val facet = module.facets.get(AndroidFacet.KEY) ?: return emptyList()
        val byName = module.sourceSets.associateBy { it.name }
        val combos = flavorCombinations(facet)
        return facet.buildTypes.flatMap { bt ->
            combos.map { combo -> variant(module, byName, combo.map { it.name }, bt.name) }
        }
    }

    /** Resolve a single variant by its assembled name (e.g. `freeDebug`), or null if unknown. */
    fun select(module: Module, variantName: String): AndroidVariant? =
        compute(module).firstOrNull { it.name == variantName } ?: defaultVariant(module, variantName)

    /** Convenience for callers that just need some buildable variant (debug-ish), e.g. a quick run. */
    fun defaultVariant(module: Module): AndroidVariant? =
        compute(module).let { all -> all.firstOrNull { it.name.endsWith("debug", ignoreCase = true) } ?: all.firstOrNull() }

    /**
     * Pick [lib]'s variant matching the [consumer] variant of a depender whose config is [consumerFacet].
     * Build-type-first, then **flavor-dimension-aware** (a flavor matches only within the same dimension, not
     * by raw name) preferring the most shared dimensions, then a **debuggability** fallback when the lib has no
     * variant with the consumer's build type, finally the lib's default. Pure + testable. Null only when [lib]
     * declares no variants (not an Android module).
     */
    fun matchLibraryVariant(lib: Module, consumer: AndroidVariant, consumerFacet: AndroidFacet): AndroidVariant? {
        val libFacet = lib.facets.get(AndroidFacet.KEY) ?: return null
        val all = compute(lib)
        if (all.isEmpty()) return null
        all.firstOrNull { it.name == consumer.name }?.let { return it }   // exact variant name

        // The consumer's selected flavor in each dimension, and a lib flavor's dimension.
        val consumerByDim: Map<String?, String> =
            consumer.flavorNames.associateBy { fn -> consumerFacet.productFlavors.firstOrNull { it.name == fn }?.dimension }
        fun libDim(fn: String): String? = libFacet.productFlavors.firstOrNull { it.name == fn }?.dimension
        // Compatible iff for every dimension the lib variant fills, the consumer's flavor in that dimension
        // matches (or the consumer has none in that dimension → lenient, lets a more-dimensioned lib still build).
        fun flavorCompatible(v: AndroidVariant): Boolean =
            v.flavorNames.all { lf -> val cf = consumerByDim[libDim(lf)]; cf == null || cf == lf }
        fun sharedMatches(v: AndroidVariant): Int = v.flavorNames.count { lf -> consumerByDim[libDim(lf)] == lf }
        fun debuggable(facet: AndroidFacet, buildType: String): Boolean =
            facet.buildType(buildType)?.debuggable ?: buildType.equals("debug", ignoreCase = true)

        all.filter { it.buildTypeName == consumer.buildTypeName && flavorCompatible(it) }
            .maxByOrNull { sharedMatches(it) }?.let { return it }
        // No variant with the consumer's build type: fall back to one with the same debuggability.
        val consumerDebuggable = debuggable(consumerFacet, consumer.buildTypeName)
        all.filter { debuggable(libFacet, it.buildTypeName) == consumerDebuggable && flavorCompatible(it) }
            .maxByOrNull { sharedMatches(it) }?.let { return it }
        return defaultVariant(lib)
    }

    private fun defaultVariant(module: Module, requested: String): AndroidVariant? =
        if (requested == "main" || requested.isEmpty()) defaultVariant(module) else null

    private fun variant(
        module: Module,
        byName: Map<String, SourceSet>,
        flavorNames: List<String>,
        buildType: String,
    ): AndroidVariant {
        val name = assembleName(flavorNames, buildType)
        val relevant = LinkedHashSet<String>().apply {
            add("main")
            addAll(flavorNames)
            if (flavorNames.size > 1) add(camel(flavorNames))      // combined multi-flavor source set
            add(buildType)
            add(assembleName(flavorNames, buildType))               // flavor(s) + build type
        }
        val active = relevant.mapNotNull { byName[it] }
        return AndroidVariant(
            id = VariantId("${module.id.value}:$name"),
            name = name,
            buildTypeName = buildType,
            flavorNames = flavorNames,
            activeSourceSets = active,
            // The UNFILTERED candidate names (before keeping only declared source sets), so a config-qualified
            // dependency matches even when the module declares no source-set dir of that name.
            configurations = relevant,
        )
    }

    /** Cartesian product of one flavor per dimension; a single empty combo when there are no flavors. */
    private fun flavorCombinations(facet: AndroidFacet): List<List<ProductFlavor>> {
        val dims = facet.flavorDimensions.ifEmpty {
            // No explicit dimensions: treat each flavor's own dimension (or a single implicit one).
            facet.productFlavors.mapNotNull { it.dimension }.distinct()
        }
        if (facet.productFlavors.isEmpty() || dims.isEmpty()) return listOf(emptyList())
        var acc: List<List<ProductFlavor>> = listOf(emptyList())
        for (dim in dims) {
            val choices = facet.productFlavors.filter { it.dimension == dim }
            if (choices.isEmpty()) continue
            acc = acc.flatMap { prefix -> choices.map { prefix + it } }
        }
        return acc
    }

    /** Gradle-style lowerCamel name: `free` + `dev` + `Debug` -> `freeDevDebug`; no flavors -> `debug`. */
    private fun assembleName(flavorNames: List<String>, buildType: String): String =
        (camel(flavorNames) + buildType.cap()).replaceFirstChar { it.lowercase() }

    private fun camel(parts: List<String>): String =
        parts.mapIndexed { i, p -> if (i == 0) p else p.cap() }.joinToString("")

    private fun String.cap(): String = replaceFirstChar { it.uppercase() }
}

/** A resolved Android build configuration. Carries the build type + selected flavors used by the pipeline. */
class AndroidVariant(
    override val id: VariantId,
    override val name: String,
    val buildTypeName: String,
    val flavorNames: List<String>,
    override val activeSourceSets: List<SourceSet>,
    override val configurations: Set<String> = emptySet(),
) : Variant {
    override fun resolvedScopes(): Set<DependencyScope> = setOf(
        DependencyScope.API,
        DependencyScope.IMPLEMENTATION,
        DependencyScope.COMPILE_ONLY,
        DependencyScope.RUNTIME_ONLY,
    )
}
