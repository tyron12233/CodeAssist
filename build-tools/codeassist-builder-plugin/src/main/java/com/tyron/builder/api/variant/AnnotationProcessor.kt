package com.tyron.builder.api.variant

import org.gradle.api.Incubating
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.process.CommandLineArgumentProvider

@Incubating
interface AnnotationProcessor {

    /**
     * Annotation processors to run.
     *
     * If empty, processors will be automatically discovered.
     */
    val classNames: ListProperty<String>

    /**
     * Options for the annotation processors provided via key-value pairs.
     *
     * @see [argumentProviders]
     */
    val arguments: MapProperty<String, String>

    /**
     * Options for the annotation processors provided via [CommandLineArgumentProvider].
     *
     * @see [arguments]
     */
    val argumentProviders: MutableList<CommandLineArgumentProvider>
}