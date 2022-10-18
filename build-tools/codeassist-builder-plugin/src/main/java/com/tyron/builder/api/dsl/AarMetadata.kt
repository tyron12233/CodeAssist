package com.tyron.builder.api.dsl
import org.gradle.api.Incubating

/**
 * DSL object for configuring metadata that is embedded in the AAR.
 *
 * This metadata is used by consumers of the AAR to control their behavior.
 */
interface AarMetadata {
    /**
     * The minimum compileSdkVersion required by any consuming module.
     *
     * For example, setting this when the AAR uses an Android resource from a new version of the
     * Android platform will alert consuming projects that they need to update their compileSdk
     * version to match, rather than getting a 'resource not found' error during resource
     * processing.
     */
    var minCompileSdk: Int?

    /**
     * The minimum compileSdkExtension required by any consuming module.
     */
    @get:Incubating
    @set:Incubating
    var minCompileSdkExtension: Int?

    /**
     * The minimum Android Gradle Plugin version required by any consuming module.
     *
     * For example, setting this when the AAR relies on a feature from a new version of AGP will
     * alert consuming projects that they need to update their AGP version to match, rather than
     * getting an ambiguous error from the older version of AGP.
     *
     * minAgpVersion must be a stable AGP version, and it must be formatted with major, minor, and
     * micro values (for example, "4.0.0").
     */
    @get:Incubating
    @set:Incubating
    var minAgpVersion: String?
}