package dev.ide.model

import dev.ide.platform.ServiceKey

/**
 * What the open project's Android modules are configured as: the read half of the `android { … }` block, as
 * the build will use it.
 *
 * A plugin on an IDE whose users mostly build Android apps needs these facts to be useful at all: a lint rule
 * that depends on the API level, a manifest checker that knows the application id, a tool that behaves
 * differently in a Compose module. The configuration itself stays the IDE's (it is written from the module
 * settings screen and the Gradle importer), so this view is read-only.
 *
 * Values are the RESOLVED ones, not the raw DSL: [AndroidModuleInfo.applicationId] is the namespace when the
 * build file sets none, each [AndroidVariantInfo] carries the application id its flavors and build type add
 * up to, and a `versionCode`/`versionName` left at AGP's default reads as null ("not set here") rather than as
 * `1`/`"1.0"`, because a manifest-declared value then wins.
 *
 * Every call reads the model as it stands, so the answer follows an edit to the module's settings or a sync
 * without a subscription. A module that is not an Android module answers null rather than throwing.
 */
interface AndroidModules {
    /** The Android configuration of [module], or null when it is not an Android module. */
    fun info(module: Module): AndroidModuleInfo?

    /** [info] by module name; null for an unknown or non-Android module. */
    fun info(moduleName: String): AndroidModuleInfo?

    /** Every Android module of the open project, in model order. */
    fun all(): List<AndroidModuleInfo>
}

/** WORKSPACE-scoped [AndroidModules] for the open project. */
val ANDROID_MODULES = ServiceKey<AndroidModules>("platform.androidModules")

/**
 * One Android module's configuration. An interface rather than a data class so that a fact added later is a
 * new member the host implements, not a change to a constructor a compiled plugin links against.
 */
interface AndroidModuleInfo {
    val moduleName: String

    /** True for an app (`com.android.application`, builds an APK), false for a library (an AAR). */
    val isApplication: Boolean

    /** The `namespace`: the package of the generated `R` and `BuildConfig`. */
    val namespace: String

    /**
     * `defaultConfig.applicationId`, or the [namespace] when the build file sets none; null for a library,
     * which has no application id of its own. A variant's own id is [AndroidVariantInfo.applicationId].
     */
    val applicationId: String?

    /** The API level the module compiles against (the `android.jar` it sees). */
    val compileSdk: Int
    val minSdk: Int
    val targetSdk: Int

    /** `defaultConfig.versionCode`, or null when the build file leaves it to the manifest. */
    val versionCode: Int?

    /** `defaultConfig.versionName`, or null when the build file leaves it to the manifest. */
    val versionName: String?

    /** The main manifest, relative to the module's directory (`src/main/AndroidManifest.xml`). */
    val manifestPath: String

    /** `defaultConfig.manifestPlaceholders`, before a build type or flavor overrides any of them. */
    val manifestPlaceholders: Map<String, String>

    /** `compileOptions.coreLibraryDesugaringEnabled`: `java.time` and friends work below their API level. */
    val coreLibraryDesugaring: Boolean

    val buildFeatures: AndroidBuildFeatures

    /** The flavor dimensions, in the order a variant picks one flavor from each. */
    val flavorDimensions: List<String>
    val buildTypes: List<AndroidBuildType>
    val productFlavors: List<AndroidProductFlavor>

    /** Every variant (build type × one flavor per dimension), in the order the IDE lists them. */
    val variants: List<AndroidVariantInfo>

    /**
     * The variant the editor analyses this module as (the one picked under Build Variants, else the default),
     * by [AndroidVariantInfo.name].
     */
    val activeVariant: String
}

/** `android { buildFeatures { … } }`, plus the compiler plugins the IDE treats as build features. */
interface AndroidBuildFeatures {
    val compose: Boolean
    val viewBinding: Boolean
    val buildConfig: Boolean

    /** The kotlin-parcelize plugin (`@Parcelize`). */
    val parcelize: Boolean

    /** The kotlinx.serialization plugin (`@Serializable`). */
    val serialization: Boolean
}

/** One entry of `android { buildTypes { … } }`. */
interface AndroidBuildType {
    val name: String
    val debuggable: Boolean
    val minifyEnabled: Boolean
    val shrinkResources: Boolean
    val applicationIdSuffix: String?
    val versionNameSuffix: String?
}

/** One entry of `android { productFlavors { … } }`. */
interface AndroidProductFlavor {
    val name: String

    /** The flavor dimension it belongs to; null in a project that declares none. */
    val dimension: String?
    val applicationId: String?
    val applicationIdSuffix: String?
    val versionName: String?
}

/** One build variant, with what its build type and flavors add up to. */
interface AndroidVariantInfo {
    /** As the IDE and Gradle name it: `debug`, `freeRelease`. */
    val name: String
    val buildType: String

    /** One flavor per dimension, in dimension order; empty without flavors. */
    val flavors: List<String>

    /** The application id this variant installs as; null for a library module. */
    val applicationId: String?
    val debuggable: Boolean
    val minifyEnabled: Boolean
}
