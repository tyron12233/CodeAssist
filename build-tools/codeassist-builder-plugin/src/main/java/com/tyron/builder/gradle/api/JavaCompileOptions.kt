package com.tyron.builder.gradle.api

/** Options for configuring Java compilation. */
@Deprecated("This is deprecated. Use the new variant API in the gradle-api module.")
interface JavaCompileOptions {
    /** Returns the [AnnotationProcessorOptions] for configuring Java annotation processor. */
    val annotationProcessorOptions: AnnotationProcessorOptions
}