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
        // Emit the R8/D8 knobs only when non-default so the encoded map matches a default-valued reload.
        if (!facet.r8FullMode) put("r8FullMode", false)
        if (facet.coreLibraryDesugaringEnabled) put("coreLibraryDesugaringEnabled", true)
        // buildFeatures: flatten the on flags into the `[android]` table (off ⇒ absent), like the knobs above.
        if (facet.buildFeatures.viewBinding) put("viewBinding", true)
        if (facet.buildFeatures.compose) put("compose", true)
        if (facet.buildFeatures.parcelize) put("parcelize", true)
        // packaging: only when the user configured something (defaults are applied at build time, not stored).
        if (!facet.packaging.isDefault) put("packaging", encodePackaging(facet.packaging))
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
            r8FullMode = values["r8FullMode"] as? Boolean ?: true,
            coreLibraryDesugaringEnabled = values["coreLibraryDesugaringEnabled"] as? Boolean ?: false,
            buildFeatures = BuildFeatures(
                viewBinding = values["viewBinding"] as? Boolean ?: false,
                compose = values["compose"] as? Boolean ?: false,
                parcelize = values["parcelize"] as? Boolean ?: false,
            ),
            packaging = decodePackaging(values.table("packaging")),
        )
    }

    /**
     * `packaging` as a nested inline table `{ resources = { excludes = [...], ... }, jniLibs = { ... } }`;
     * each sub-table and each list is emitted only when non-empty so the encoded map matches a reload.
     */
    private fun encodePackaging(p: AndroidPackaging): Map<String, Any?> = buildMap {
        if (!p.resources.isEmpty) put("resources", buildMap {
            if (p.resources.excludes.isNotEmpty()) put("excludes", p.resources.excludes.toList())
            if (p.resources.pickFirsts.isNotEmpty()) put("pickFirsts", p.resources.pickFirsts.toList())
            if (p.resources.merges.isNotEmpty()) put("merges", p.resources.merges.toList())
        })
        if (!p.jniLibs.isEmpty) put("jniLibs", buildMap {
            if (p.jniLibs.excludes.isNotEmpty()) put("excludes", p.jniLibs.excludes.toList())
            if (p.jniLibs.pickFirsts.isNotEmpty()) put("pickFirsts", p.jniLibs.pickFirsts.toList())
        })
    }

    private fun decodePackaging(t: Map<String, Any?>): AndroidPackaging {
        val res = t.table("resources")
        val jni = t.table("jniLibs")
        return AndroidPackaging(
            resources = ResourcePackaging(
                excludes = res.stringSet("excludes"),
                pickFirsts = res.stringSet("pickFirsts"),
                merges = res.stringSet("merges"),
            ),
            jniLibs = JniLibsPackaging(
                excludes = jni.stringSet("excludes"),
                pickFirsts = jni.stringSet("pickFirsts"),
            ),
        )
    }

    private fun encodeBuildType(bt: BuildType): Map<String, Any?> = buildMap {
        put("name", bt.name)
        put("debuggable", bt.debuggable)
        put("minifyEnabled", bt.minifyEnabled)
        // Always emit (like minifyEnabled): the Module Settings UI derives its editable fields from the
        // encoded map, so a key omitted when false has no toggle — leaving no way to turn shrinkResources ON.
        put("shrinkResources", bt.shrinkResources)
        if (bt.proguardFiles.isNotEmpty()) put("proguardFiles", bt.proguardFiles)
        if (bt.consumerProguardFiles.isNotEmpty()) put("consumerProguardFiles", bt.consumerProguardFiles)
        if (bt.proguardRules.isNotEmpty()) put("proguardRules", bt.proguardRules)
        bt.applicationIdSuffix?.let { put("applicationIdSuffix", it) }
        bt.versionNameSuffix?.let { put("versionNameSuffix", it) }
        bt.signingConfig?.let { put("signingConfig", it) }
    }

    private fun decodeBuildType(t: Map<String, Any?>): BuildType {
        val name = t["name"] as? String ?: "debug"
        return BuildType(
            name = name,
            debuggable = t["debuggable"] as? Boolean ?: (name == "debug"),
            minifyEnabled = t["minifyEnabled"] as? Boolean ?: false,
            shrinkResources = t["shrinkResources"] as? Boolean ?: false,
            proguardFiles = t.stringList("proguardFiles"),
            consumerProguardFiles = t.stringList("consumerProguardFiles"),
            proguardRules = t.stringList("proguardRules"),
            applicationIdSuffix = t["applicationIdSuffix"] as? String,
            versionNameSuffix = t["versionNameSuffix"] as? String,
            signingConfig = t["signingConfig"] as? String,
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
    // LinkedHashSet keeps insertion order so the encoded list round-trips byte-identically.
    private fun Map<String, Any?>.stringSet(key: String): Set<String> = stringList(key).toCollection(LinkedHashSet())

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.table(key: String): Map<String, Any?> =
        (this[key] as? Map<*, *>)?.let { it as Map<String, Any?> } ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.tableList(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map { it as Map<String, Any?> } ?: emptyList()
}
