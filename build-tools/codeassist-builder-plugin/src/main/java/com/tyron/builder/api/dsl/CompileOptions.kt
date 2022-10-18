package com.tyron.builder.api.dsl

import org.gradle.api.JavaVersion

/**
 * Java compilation options.
 */
interface CompileOptions {
    /**
     * Language level of the java source code.
     *
     * Similar to what [Gradle Java plugin](http://www.gradle.org/docs/current/userguide/java_plugin.html)
     * uses. Formats supported are:
     *
     * - `"1.6"`
     * - `1.6`
     * - `JavaVersion.Version_1_6`
     * - `"Version_1_6"`
     */
    var sourceCompatibility: JavaVersion

    /**
     * Language level of the java source code.
     *
     * Similar to what [Gradle Java plugin](http://www.gradle.org/docs/current/userguide/java_plugin.html)
     * uses. Formats supported are:
     *
     * - `"1.6"`
     * - `1.6`
     * - `JavaVersion.Version_1_6`
     * - `"Version_1_6"`
     */
    fun sourceCompatibility(sourceCompatibility: Any)

    /**
     * Version of the generated Java bytecode.
     *
     * Similar to what [Gradle Java plugin](http://www.gradle.org/docs/current/userguide/java_plugin.html)
     * uses. Formats supported are:
     *
     * - `"1.6"`
     * - `1.6`
     * - `JavaVersion.Version_1_6`
     * - `"Version_1_6"`
     */
    var targetCompatibility: JavaVersion

    /**
     * Version of the generated Java bytecode.
     *
     * Similar to what [Gradle Java plugin](http://www.gradle.org/docs/current/userguide/java_plugin.html)
     * uses. Formats supported are:
     *
     * - `"1.6"`
     * - `1.6`
     * - `JavaVersion.Version_1_6`
     * - `"Version_1_6"`
     */
    fun targetCompatibility(targetCompatibility: Any)

    /** Java source files encoding. */
    var encoding: String

    /** Whether core library desugaring is enabled. */
    var isCoreLibraryDesugaringEnabled: Boolean
}