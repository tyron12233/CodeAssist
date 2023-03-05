package com.tyron.builder.api.dsl

/**
 * Optional settings for the Compose feature.
 */
interface ComposeOptions {
    /**
     * Sets the version of the Kotlin Compiler used to compile the project or null if using
     * the default one.
     */
    @Deprecated("Android Gradle Plugin will ignore this option and use the kotlin compiler version that is set in the build script.")
    var kotlinCompilerVersion: String?

    /**
     * Sets the version of the Kotlin Compiler extension for the project or null if using
     * the default one.
     */
    var kotlinCompilerExtensionVersion: String?

    /**
     * Enables live literals in Compose
     */
    var useLiveLiterals: Boolean
}