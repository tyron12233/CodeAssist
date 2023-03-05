package com.tyron.builder.gradle.internal.core

import com.tyron.builder.gradle.api.JavaCompileOptions

/** Implementation of CoreJavaCompileOptions used to merge multiple configs together.  */
class MergedJavaCompileOptions : JavaCompileOptions,
    com.tyron.builder.api.dsl.JavaCompileOptions,
    MergedOptions<JavaCompileOptions> {

    override val annotationProcessorOptions = MergedAnnotationProcessorOptions()

    override fun annotationProcessorOptions(action: com.tyron.builder.api.dsl.AnnotationProcessorOptions.() -> Unit) {
        action.invoke(annotationProcessorOptions)
    }

    override fun reset() {
        annotationProcessorOptions.reset()
    }

    override fun append(option: JavaCompileOptions) {
        annotationProcessorOptions.append(option.annotationProcessorOptions)
    }
}
