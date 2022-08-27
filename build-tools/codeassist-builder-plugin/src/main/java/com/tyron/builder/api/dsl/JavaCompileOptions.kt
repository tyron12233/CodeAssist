package com.tyron.builder.api.dsl

/** DSL object for javaCompileOptions. */
interface JavaCompileOptions {
    /** Options for configuration the annotation processor. */
    val annotationProcessorOptions: AnnotationProcessorOptions

    /** Configures annotation processor options. */
    fun annotationProcessorOptions(action: AnnotationProcessorOptions.() -> Unit)
}