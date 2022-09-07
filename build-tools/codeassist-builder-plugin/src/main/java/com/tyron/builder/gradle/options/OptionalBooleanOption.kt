package com.tyron.builder.gradle.options

import com.tyron.builder.gradle.errors.DeprecationReporter
import com.tyron.builder.gradle.errors.parseBoolean
import com.tyron.builder.gradle.options.Version.VERSION_BEFORE_4_0
import com.tyron.builder.model.PROPERTY_SIGNING_V1_ENABLED
import com.tyron.builder.model.PROPERTY_SIGNING_V2_ENABLED
import com.tyron.builder.model.PROPERTY_TEST_ONLY

enum class OptionalBooleanOption(
    override val propertyName: String,
    val stage: Stage,
    val recommendedValue: Boolean? = null
) : Option<Boolean> {
    SIGNING_V1_ENABLED(PROPERTY_SIGNING_V1_ENABLED, ApiStage.Stable),
    SIGNING_V2_ENABLED(PROPERTY_SIGNING_V2_ENABLED, ApiStage.Stable),
    IDE_TEST_ONLY(PROPERTY_TEST_ONLY, ApiStage.Stable),

    /**
     * This project property is read by the firebase plugin, and has no direct impact on AGP behavior.
     *
     * It is included as an OptionalBooleanOption in order that its value, if set, is recorded in the AGP analytics.
     */
    FIREBASE_PERF_PLUGIN_ENABLE_FLAG("firebasePerformanceInstrumentationEnabled", ApiStage.Stable),

    // Flags for Android Test Retention
    ENABLE_TEST_FAILURE_RETENTION_COMPRESS_SNAPSHOT("android.experimental.testOptions.emulatorSnapshots.compressSnapshots", ApiStage.Experimental),

    /* ----------------
    * SOFTLY ENFORCED FEATURES
    */
    DISABLE_AUTOMATIC_COMPONENT_CREATION("android.disableAutomaticComponentCreation",  FeatureStage.SoftlyEnforced(
        DeprecationReporter.DeprecationTarget.VERSION_8_0), true),

    /* ----------------
     * ENFORCED FEATURES
     */
    @Suppress("unused")
    ENABLE_R8(
        "android.enableR8",
        FeatureStage.Enforced(Version.VERSION_7_0, "Please remove it from `gradle.properties`.")
    ),

    /* ----------------
     * REMOVED FEATURES
     */

    @Suppress("unused")
    SERIAL_AAPT2(
        "android.injected.aapt2.serial",
        FeatureStage.Removed(
            VERSION_BEFORE_4_0,
            "Invoking AAPT2 serially is no longer supported."
        )
    ),

    ;

    override val status = stage.status

    override fun parse(value: Any): Boolean {
        return parseBoolean(propertyName, value)
    }
}