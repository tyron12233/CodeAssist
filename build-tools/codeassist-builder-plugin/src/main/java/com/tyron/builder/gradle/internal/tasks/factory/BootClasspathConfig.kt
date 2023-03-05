package com.tyron.builder.gradle.internal.tasks.factory

import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider

interface BootClasspathConfig {

    /**
     * The boot classpath to be used during compilation with all available additional jars
     * including all optional libraries.
     */
    val fullBootClasspath: FileCollection
    val fullBootClasspathProvider: Provider<List<RegularFile>>

    /**
     * The boot classpath to be used during compilation with all available additional jars
     * but only the requested optional ones.
     *
     * <p>Requested libraries not found will be reported to the issue handler.
     *
     * @return a {@link FileCollection} that forms the filtered classpath.
     */
    val filteredBootClasspath: Provider<List<RegularFile>>

    /**
     * The boot classpath to be used during compilation with the core lambdas stubs.
     */
    val bootClasspath: Provider<List<RegularFile>>

    /**
     * Queries the given configuration for mockable version of the jar(s) in it.
     *
     * This is designed to mock android.jar from a configuration that contains it, via Artifact
     * Transforms.
     */
    val mockableJarArtifact: FileCollection
}