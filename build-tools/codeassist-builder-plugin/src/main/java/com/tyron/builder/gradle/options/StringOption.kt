package com.tyron.builder.gradle.options

import com.tyron.builder.model.*

enum class StringOption(
    override val propertyName: String,
    stage: ApiStage
) : Option<String> {

    /* -----------
     * STABLE APIs
     */

    IDE_BUILD_TARGET_DENSITY(PROPERTY_BUILD_DENSITY, ApiStage.Stable),
    IDE_BUILD_TARGET_ABI(PROPERTY_BUILD_ABI, ApiStage.Stable),

    IDE_ATTRIBUTION_FILE_LOCATION(PROPERTY_ATTRIBUTION_FILE_LOCATION, ApiStage.Stable),

    /** Absolute path to a file containing the result of the `CheckJetifier` task. */
    IDE_CHECK_JETIFIER_RESULT_FILE(PROPERTY_CHECK_JETIFIER_RESULT_FILE, ApiStage.Stable),

    // Signing options
    IDE_SIGNING_STORE_TYPE(PROPERTY_SIGNING_STORE_TYPE, ApiStage.Stable),
    IDE_SIGNING_STORE_FILE(PROPERTY_SIGNING_STORE_FILE, ApiStage.Stable),
    IDE_SIGNING_STORE_PASSWORD(PROPERTY_SIGNING_STORE_PASSWORD, ApiStage.Stable),
    IDE_SIGNING_KEY_ALIAS(PROPERTY_SIGNING_KEY_ALIAS, ApiStage.Stable),
    IDE_SIGNING_KEY_PASSWORD(PROPERTY_SIGNING_KEY_PASSWORD, ApiStage.Stable),

    // device config for ApkSelect
    IDE_APK_SELECT_CONFIG(PROPERTY_APK_SELECT_CONFIG, ApiStage.Stable),

    // location where to write the APK/BUNDLE
    IDE_APK_LOCATION(PROPERTY_APK_LOCATION, ApiStage.Stable),

    IDE_TARGET_DEVICE_CODENAME(PROPERTY_BUILD_API_CODENAME, ApiStage.Stable),

    // Profiler plugin
    IDE_ANDROID_CUSTOM_CLASS_TRANSFORMS("android.advanced.profiling.transforms", ApiStage.Stable),

    // The exact version of Android Support plugin used, e.g. 2.4.0.6
    IDE_ANDROID_STUDIO_VERSION(AndroidProject.PROPERTY_ANDROID_SUPPORT_VERSION, ApiStage.Stable),

    // The version of Android Game Development Extension used to orchestrate the build
    IDE_AGDE_VERSION("agde.version", ApiStage.Stable),

    // Native
//    NATIVE_BUILD_OUTPUT_LEVEL(PROPERTY_NATIVE_BUILD_OUTPUT_LEVEL, ApiStage.Stable),

    // AGP suggests it should be upgraded if the compile sdk is newer than the version it was tested
    // with. This option allows developers to suppress that warning.
    // e.g. android.suppressUnsupportedCompileSdk=S,31,32
    SUPPRESS_UNSUPPORTED_COMPILE_SDK("android.suppressUnsupportedCompileSdk", ApiStage.Stable),

    // User-specified flag for using a profileable or debuggable build,
    // if the flag is not set, the debuggable value will fallback to using the DSL 'debuggable'.
    PROFILING_MODE("android.profilingMode", ApiStage.Stable),

    // Override for the default execution profile in the settings plugin.
    EXECUTION_PROFILE_SELECTION("android.settings.executionProfile", ApiStage.Stable),

    /* -----------------
     * EXPERIMENTAL APIs
     */

    // Installation related options
//    IDE_INSTALL_DYNAMIC_MODULES_LIST(PROPERTY_INJECTED_DYNAMIC_MODULES_LIST, ApiStage.Experimental),

    // Testing
    DEVICE_POOL_SERIAL("com.android.test.devicepool.serial", ApiStage.Experimental),
    PROFILE_OUTPUT_DIR("android.advanced.profileOutputDir", ApiStage.Experimental),

    BUILD_ARTIFACT_REPORT_FILE("android.buildartifact.reportfile", ApiStage.Experimental),

    AAPT2_FROM_MAVEN_OVERRIDE("android.aapt2FromMavenOverride", ApiStage.Experimental),

    AAPT2_FROM_MAVEN_VERSION_OVERRIDE("android.aapt2Version", ApiStage.Experimental),

    AAPT2_FROM_MAVEN_PLATFORM_OVERRIDE("android.aapt2Platform", ApiStage.Experimental),

    SUPPRESS_UNSUPPORTED_OPTION_WARNINGS(
        "android.suppressUnsupportedOptionWarnings",
        ApiStage.Experimental
    ),

    // User-specified path to Prefab jar to return from getPrefabFromMaven.
    PREFAB_CLASSPATH("android.prefabClassPath", ApiStage.Experimental),

    // User-specified Prefab version to pull from Maven in getPrefabFromMaven.
    PREFAB_VERSION("android.prefabVersion", ApiStage.Experimental),

    // Jetifier: List of regular expressions for libraries that should not be jetified
    JETIFIER_IGNORE_LIST("android.jetifier.ignorelist", ApiStage.Experimental),

    // Lint: Allow customization of the heap size of the process started to run lint
    LINT_HEAP_SIZE("android.experimental.lint.heapSize", ApiStage.Experimental),

    // Lint: Allow override of the version. Note that lint versions are generally 23 higher than
    // the version of Android Gradle Plugin. So AGP 7.0.0-beta02 defaults to using lint
    // 30.0.0-beta02
    LINT_VERSION_OVERRIDE("android.experimental.lint.version", ApiStage.Experimental),

    // User-specified flag to override the emulator gpu mode for Gradle Managed Devices,
    // If the flag is not set, the emulator gpu mode will default to auto-no-window.
    // Supported values are "auto", "auto-no-window", "host", "swiftshader_indirect",
    // "angle_indirect"
    GRADLE_MANAGED_DEVICE_EMULATOR_GPU_MODE(
        "android.testoptions.manageddevices.emulator.gpu",
        ApiStage.Experimental
    ),

    /* ---------------
     * DEPRECATED APIs
     */

    /* ------------
     * REMOVED APIs
     */

    @Suppress("unused")
    BUILD_CACHE_DIR(
        "android.buildCacheDir",
        ApiStage.Removed(
            Version.VERSION_7_0,
            "The Android-specific build caches were superseded by the Gradle build cache (https://docs.gradle.org/current/userguide/build_cache.html)."
        )
    ),

    ;

    override val status = stage.status

    override fun parse(value: Any): String {
        if (value is CharSequence || value is Number) {
            return value.toString()
        }
        throw IllegalArgumentException(
            "Cannot parse project property "
                    + this.propertyName
                    + "='"
                    + value
                    + "' of type '"
                    + value.javaClass
                    + "' as string."
        )
    }
}