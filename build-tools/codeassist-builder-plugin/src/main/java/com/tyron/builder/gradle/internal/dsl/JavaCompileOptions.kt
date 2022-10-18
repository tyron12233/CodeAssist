package com.tyron.builder.gradle.internal.dsl

import com.google.common.base.MoreObjects

/** DSL object for javaCompileOptions. */
abstract class JavaCompileOptions: com.tyron.builder.gradle.api.JavaCompileOptions,
    com.tyron.builder.api.dsl.JavaCompileOptions {
    abstract override val annotationProcessorOptions: AnnotationProcessorOptions

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
            .add("annotationProcessorOptions", annotationProcessorOptions)
            .toString()
    }
}