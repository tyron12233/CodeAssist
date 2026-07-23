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
    /** AGP's `android { buildFeatures { … } }`: per-module toggles for generated-code/compiler features. */
    val buildFeatures: BuildFeatures = BuildFeatures(),
    /** AGP's `android { packaging { … } }`: how Java resources + native libs are merged into the APK. */
    val packaging: AndroidPackaging = AndroidPackaging(),
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

/**
 * AGP's `buildFeatures { }` block: per-module flags that switch on generated code or a compiler plugin.
 * Each defaults to off so a fresh module behaves like a plain Android module; the codec persists only the
 * flags that are on. Build-wide (not per-variant), matching AGP.
 */
data class BuildFeatures(
    /**
     * `viewBinding`: generate a type-safe `<Layout>Binding` class per layout (a field per `@+id`, plus
     * `inflate`/`bind`/`getRoot`). The IDE both surfaces these for completion (a synthetic class) and emits
     * the real `.java` into the build's generated sources.
     */
    val viewBinding: Boolean = false,
    /**
     * `compose`: enable Jetpack Compose for the module — the build compiles its Kotlin with the Compose
     * compiler plugin (applied once the Compose runtime is on the classpath) and the IDE adds the Compose
     * runtime/tooling dependencies. Compose previews then render from `@Preview` composables.
     */
    val compose: Boolean = false,
    /**
     * `parcelize`: enable the kotlin-parcelize compiler plugin — a `@Parcelize` class gets its `Parcelable`
     * implementation generated. Like `compose`, the plugin is applied once its runtime (the `@Parcelize`
     * annotation) is on the classpath, which enabling this adds.
     */
    val parcelize: Boolean = false,
    /**
     * `serialization`: enable the kotlinx.serialization compiler plugin — a `@Serializable` class gets its
     * generated `serializer()`/`$serializer`. Like `compose`/`parcelize`, the plugin is applied once its
     * runtime (`kotlinx.serialization.Serializable`) is on the classpath, which enabling this adds; the editor
     * then also resolves/completes the generated `serializer()` members.
     */
    val serialization: Boolean = false,
) {
    /** True when at least one build feature is enabled (drives "emit only when set" persistence). */
    val anyEnabled: Boolean get() = viewBinding || compose || parcelize || serialization
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
    /**
     * The id of the signing keystore this build type is signed with, referencing an entry in the global
     * keystore registry (app-home), e.g. `"release"`. Null ⇒ the build's default debug keystore. Only the id
     * is stored in `module.toml` — the keystore file and its passwords live in the registry, never the project.
     */
    val signingConfig: String? = null,
)

/**
 * AGP's `packaging { }` block: the merge rules the packager applies when the same archive path is
 * contributed by more than one input (the app's own resources, a sub-module, an AAR, an external jar).
 * The patterns are AGP-style globs relative to the APK root (a double-star crosses a slash, a single
 * star stays within a segment, a leading slash is optional). These are ADDED to a faithful set of AGP
 * defaults ([resources] excludes the kotlin_module + signature files + licence noise, and merges the
 * META-INF services entries), so a module with no configured packaging still behaves like AGP.
 */
data class AndroidPackaging(
    val resources: ResourcePackaging = ResourcePackaging(),
    val jniLibs: JniLibsPackaging = JniLibsPackaging(),
) {
    /** True when the user configured anything (drives "emit only when set" persistence). */
    val isDefault: Boolean get() = resources.isEmpty && jniLibs.isEmpty

    companion object {
        /**
         * AGP's default Java-resource excludes, always applied on top of the module's own: jar signatures
         * (invalid after repackaging), the redundant per-jar `MANIFEST.MF`, Maven/tooling metadata,
         * licence/notice noise, Kotlin module/metadata files, the coroutines debug probe, and hidden files.
         */
        val DEFAULT_RESOURCE_EXCLUDES: List<String> = listOf(
            "/META-INF/LICENSE",
            "/META-INF/LICENSE.txt",
            "/META-INF/LICENSE.md",
            "/META-INF/NOTICE",
            "/META-INF/NOTICE.txt",
            "/META-INF/NOTICE.md",
            "/META-INF/DEPENDENCIES",
            "/META-INF/MANIFEST.MF",
            "/META-INF/*.DSA",
            "/META-INF/*.EC",
            "/META-INF/*.SF",
            "/META-INF/*.RSA",
            "/META-INF/*.kotlin_module",
            "/META-INF/*.version",
            "/META-INF/maven/**",
            "/META-INF/proguard/**",
            "/META-INF/com.android.tools/**",
            "/NOTICE",
            "/NOTICE.txt",
            "/LICENSE",
            "/LICENSE.txt",
            "/**/*.kotlin_metadata",
            "/DebugProbesKt.bin",
            "/**/.*",
        )

        /** AGP concatenates the META-INF services entries (ServiceLoader registrations) rather than picking one. */
        val DEFAULT_RESOURCE_MERGES: List<String> = listOf("/META-INF/services/**")
    }
}

/**
 * `packaging { resources { … } }`: how Java resources (non-code files from `src/<set>/resources` and the
 * non-class entries of dependency jars/AARs) are merged into the APK root.
 */
data class ResourcePackaging(
    /** Paths to drop entirely (added to the AGP defaults). */
    val excludes: Set<String> = emptySet(),
    /** Paths where the first-seen provider wins and later duplicates are silently dropped. */
    val pickFirsts: Set<String> = emptySet(),
    /** Paths whose duplicates are concatenated rather than deduplicated (e.g. the META-INF services entries). */
    val merges: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = excludes.isEmpty() && pickFirsts.isEmpty() && merges.isEmpty()
}

/**
 * `packaging { jniLibs { … } }`: how native libraries (the `.so` files from `src/<set>/jniLibs`, an AAR's
 * `jni` dir, and any inside dependency jars) are merged into the APK's `lib` dir. There is no `merges` —
 * `.so` files are binary and can't be concatenated. Debug-symbol stripping is not modelled: it needs the
 * NDK, which is absent on device, so (like AGP without an NDK) libraries are packaged as-is.
 */
data class JniLibsPackaging(
    val excludes: Set<String> = emptySet(),
    val pickFirsts: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = excludes.isEmpty() && pickFirsts.isEmpty()
}

/** A product flavor (`free`/`paid`/`demo`/…), grouped by a [dimension]. */
data class ProductFlavor(
    val name: String,
    val dimension: String? = null,
    val applicationId: String? = null,
    val applicationIdSuffix: String? = null,
    val versionName: String? = null,
)
