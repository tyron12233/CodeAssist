package com.tyron.builder.api.variant

import org.gradle.api.Incubating
import org.gradle.api.provider.Property

/**
 * Variant object for configuring AAR metadata.
 */
interface AarMetadata {

    /**
     * Minimum compileSdkVersion needed to consume this library. This is the minimum sdk version a
     * module must use in order to import this library.
     */
    val minCompileSdk: Property<Int>

    /**
     * Minimum compileSdkExtension needed to consume this library. This is the minimum sdk extension
     * version a module must use in order to import this library.
     *
     * The default value of [minCompileSdkExtension] is 0 if not set via the DSL.
     */
    @get:Incubating
    val minCompileSdkExtension: Property<Int>

    /**
     * Minimum Android Gradle Plugin version needed to consume this library. This is the minimum AGP
     * version a module must use in order to import this library.
     *
     * minAgpVersion must be a stable AGP version, and it must be formatted with major, minor, and
     * micro values (for example, "4.0.0").
     */
    @get:Incubating
    val minAgpVersion: Property<String>
}
