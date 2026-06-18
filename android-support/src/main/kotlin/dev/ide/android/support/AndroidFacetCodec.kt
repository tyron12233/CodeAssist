package dev.ide.android.support

import dev.ide.model.impl.FacetCodec

/**
 * Round-trips [AndroidFacet] through the `[android]` table of `module.toml`. Persistence stores a facet
 * as `(tomlTable, values)` and the loader reconstructs `values` straight from the parsed TOML, so the
 * map this [encode]s must use only TOML-native shapes — String, Long, Boolean, lists, and inline tables
 * (maps). In particular integers go out as [Long] (TOML's only integer) so an in-memory facet and the
 * same facet saved-then-reloaded compare structurally equal. Build types and product flavors are stored
 * as arrays of inline tables under `buildTypes`/`productFlavors`; optional fields are omitted when unset
 * so the encoded map matches what the loader produces (absent keys, not nulls).
 */
object AndroidFacetCodec : FacetCodec<AndroidFacet> {
    override val key get() = AndroidFacet.KEY
    override val tomlTable = "android"

    override fun encode(facet: AndroidFacet): Map<String, Any?> = buildMap {
        put("namespace", facet.namespace)
        put("compileSdk", facet.compileSdk.toLong())
        put("minSdk", facet.minSdk.toLong())
        put("targetSdk", facet.targetSdk.toLong())
        put("manifest", facet.manifest)
        put("versionCode", facet.versionCode.toLong())
        put("versionName", facet.versionName)
        put("isApplication", facet.isApplication)
        if (facet.flavorDimensions.isNotEmpty()) put("flavorDimensions", facet.flavorDimensions)
        put("buildTypes", facet.buildTypes.map { encodeBuildType(it) })
        if (facet.productFlavors.isNotEmpty()) put("productFlavors", facet.productFlavors.map { encodeFlavor(it) })
    }

    override fun decode(values: Map<String, Any?>): AndroidFacet {
        val minSdk = values.int("minSdk") ?: 21
        return AndroidFacet(
            namespace = values["namespace"] as? String ?: "",
            compileSdk = values.int("compileSdk") ?: values.int("targetSdk") ?: 34,
            minSdk = minSdk,
            targetSdk = values.int("targetSdk") ?: minSdk,
            manifest = values["manifest"] as? String ?: "src/main/AndroidManifest.xml",
            versionCode = values.int("versionCode") ?: 1,
            versionName = values["versionName"] as? String ?: "1.0",
            isApplication = values["isApplication"] as? Boolean ?: true,
            flavorDimensions = values.stringList("flavorDimensions"),
            buildTypes = values.tableList("buildTypes").map { decodeBuildType(it) }
                .ifEmpty { AndroidFacet.DEFAULT_BUILD_TYPES },
            productFlavors = values.tableList("productFlavors").map { decodeFlavor(it) },
        )
    }

    private fun encodeBuildType(bt: BuildType): Map<String, Any?> = buildMap {
        put("name", bt.name)
        put("debuggable", bt.debuggable)
        put("minifyEnabled", bt.minifyEnabled)
        bt.applicationIdSuffix?.let { put("applicationIdSuffix", it) }
        bt.versionNameSuffix?.let { put("versionNameSuffix", it) }
    }

    private fun decodeBuildType(t: Map<String, Any?>): BuildType {
        val name = t["name"] as? String ?: "debug"
        return BuildType(
            name = name,
            debuggable = t["debuggable"] as? Boolean ?: (name == "debug"),
            minifyEnabled = t["minifyEnabled"] as? Boolean ?: false,
            applicationIdSuffix = t["applicationIdSuffix"] as? String,
            versionNameSuffix = t["versionNameSuffix"] as? String,
        )
    }

    private fun encodeFlavor(pf: ProductFlavor): Map<String, Any?> = buildMap {
        put("name", pf.name)
        pf.dimension?.let { put("dimension", it) }
        pf.applicationId?.let { put("applicationId", it) }
        pf.applicationIdSuffix?.let { put("applicationIdSuffix", it) }
        pf.versionName?.let { put("versionName", it) }
    }

    private fun decodeFlavor(t: Map<String, Any?>): ProductFlavor = ProductFlavor(
        name = t["name"] as? String ?: "",
        dimension = t["dimension"] as? String,
        applicationId = t["applicationId"] as? String,
        applicationIdSuffix = t["applicationIdSuffix"] as? String,
        versionName = t["versionName"] as? String,
    )

    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (this[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.tableList(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map { it as Map<String, Any?> } ?: emptyList()
}
