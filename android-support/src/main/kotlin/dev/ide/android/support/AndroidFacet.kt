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
    /** `defaultConfig.versionCode` — injected into the manifest at link (AGP defaults to 1). */
    val versionCode: Int = 1,
    /** `defaultConfig.versionName` — injected into the manifest at link (AGP defaults to "1.0"). */
    val versionName: String = "1.0",
    /** true == `android-app` (produces an APK); false == `android-lib` (produces an AAR). */
    val isApplication: Boolean = true,
    /** Flavor dimension order; a variant picks one flavor per dimension in this order. */
    val flavorDimensions: List<String> = emptyList(),
    /** Build types; defaults to the conventional `debug` + `release`. */
    val buildTypes: List<BuildType> = DEFAULT_BUILD_TYPES,
    /** Product flavors; empty for the common single-variant-per-build-type project. */
    val productFlavors: List<ProductFlavor> = emptyList(),
    /**
     * R8 full mode (AGP's `android.enableR8.fullMode`, default on since AGP 8): R8 optimizes more
     * aggressively and is NOT bound by ProGuard semantics. When false, R8 runs in ProGuard-compatibility
     * mode (safer for rule sets written for ProGuard, less effective). Build-wide, not per-build-type.
     */
    val r8FullMode: Boolean = true,
    /**
     * Enable core-library desugaring (AGP's `compileOptions.coreLibraryDesugaringEnabled`): D8/R8 rewrite
     * uses of `java.time`, `java.util.stream`, `java.nio.file`, etc. to backports so they run below the
     * native API level, and the desugared runtime is L8-compiled into the APK. Build-wide.
     */
    val coreLibraryDesugaringEnabled: Boolean = false,
) : Facet {
    override val key: FacetKey<AndroidFacet> get() = KEY

    val buildType: (String) -> BuildType? get() = { n -> buildTypes.firstOrNull { it.name == n } }

    companion object {
        /** The single shared key — facet lookup is identity-based, so always reference this instance. */
        val KEY = FacetKey<AndroidFacet>("android")

        val DEFAULT_BUILD_TYPES: List<BuildType> = listOf(
            BuildType("debug", debuggable = true, minifyEnabled = false),
            // Mirrors AGP's default release block: the proguardFiles are declared even though minify is off,
            // so flipping minifyEnabled = true picks up the optimizing defaults + a conventional rules file.
            BuildType(
                "release", debuggable = false, minifyEnabled = false,
                proguardFiles = listOf(DefaultProguardFiles.OPTIMIZE, "proguard-rules.pro"),
            ),
        )
    }
}

/** A build type (`debug`/`release`/…): how a variant is assembled regardless of flavor. */
data class BuildType(
    val name: String,
    val debuggable: Boolean = name == "debug",
    /** Run R8 (shrink + optimize + obfuscate + dex) instead of the plain D8 dex pipeline. */
    val minifyEnabled: Boolean = false,
    /**
     * Strip unused resources from the APK (AGP's `shrinkResources`). Requires [minifyEnabled]; ignored
     * with a warning when minify is off (resource shrinking is driven by R8's reachable-code analysis).
     */
    val shrinkResources: Boolean = false,
    /**
     * ProGuard/R8 configuration files. An entry naming a bundled default ([DefaultProguardFiles]) is
     * resolved from android-support's assets, like AGP's `getDefaultProguardFile(...)`; any other entry
     * is a module-relative path (e.g. `proguard-rules.pro`). Missing module-relative files are skipped.
     */
    val proguardFiles: List<String> = emptyList(),
    /**
     * For an `android-lib`: the keep rules this library exports to its consumers (AGP's
     * `consumerProguardFiles`), packaged into the AAR's `proguard.txt` and applied by the app's R8 run.
     */
    val consumerProguardFiles: List<String> = emptyList(),
    /** Inline ProGuard/R8 directives appended verbatim (the escape hatch for rules not in a file). */
    val proguardRules: List<String> = emptyList(),
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
