package dev.ide.android.support

import dev.ide.model.Facet
import dev.ide.model.FacetKey

/**
 * The Android domain configuration attached to an `android-app` / `android-lib` [dev.ide.model.Module].
 * The core model knows nothing about Android; this facet, contributed by the android-support plugin,
 * carries the manifest location, the SDK levels, the R-class/`applicationId` namespace, and the
 * definitions of build types and product flavors from which the active variant set is computed
 * ([AndroidVariants]).
 *
 * Persisted to the `[android]` table of `module.toml` by [AndroidFacetCodec]. Values that survive a
 * save/reload must be TOML-representable; build types and flavors are stored as arrays of inline tables.
 */
data class AndroidFacet(
    /** R-class package + default `applicationId` base, e.g. `com.example.app`. */
    val namespace: String,
    /** The compile SDK API level (the `android.jar` the module compiles against). */
    val compileSdk: Int,
    /** Minimum supported API level (D8 `--min-api`, manifest `minSdkVersion`). */
    val minSdk: Int = 21,
    /** Target API level; defaults to [minSdk] when unset. */
    val targetSdk: Int = minSdk,
    /** Manifest path relative to the module dir. */
    val manifest: String = "src/main/AndroidManifest.xml",
    /** true == `android-app` (produces an APK); false == `android-lib` (produces an AAR). */
    val isApplication: Boolean = true,
    /** Flavor dimension order; a variant picks one flavor per dimension in this order. */
    val flavorDimensions: List<String> = emptyList(),
    /** Build types; defaults to the conventional `debug` + `release`. */
    val buildTypes: List<BuildType> = DEFAULT_BUILD_TYPES,
    /** Product flavors; empty for the common single-variant-per-build-type project. */
    val productFlavors: List<ProductFlavor> = emptyList(),
) : Facet {
    override val key: FacetKey<AndroidFacet> get() = KEY

    val buildType: (String) -> BuildType? get() = { n -> buildTypes.firstOrNull { it.name == n } }

    companion object {
        /** The single shared key — facet lookup is identity-based, so always reference this instance. */
        val KEY = FacetKey<AndroidFacet>("android")

        val DEFAULT_BUILD_TYPES: List<BuildType> = listOf(
            BuildType("debug", debuggable = true, minifyEnabled = false),
            BuildType("release", debuggable = false, minifyEnabled = false),
        )
    }
}

/** A build type (`debug`/`release`/…): how a variant is assembled regardless of flavor. */
data class BuildType(
    val name: String,
    val debuggable: Boolean = name == "debug",
    val minifyEnabled: Boolean = false,
    val applicationIdSuffix: String? = null,
    val versionNameSuffix: String? = null,
)

/** A product flavor (`free`/`paid`/`demo`/…), grouped by a [dimension]. */
data class ProductFlavor(
    val name: String,
    val dimension: String? = null,
    val applicationId: String? = null,
    val applicationIdSuffix: String? = null,
    val versionName: String? = null,
)
