package com.tyron.builder.api.component.impl

import com.tyron.builder.api.variant.AnnotationProcessor
import com.tyron.builder.api.variant.JavaCompilation
import com.tyron.builder.gradle.api.JavaCompileOptions
import com.tyron.builder.gradle.internal.services.VariantServices

class JavaCompilationImpl(
    javaCompileOptionsSetInDSL: JavaCompileOptions,
    dataBindingEnabled: Boolean,
    internalServices: VariantServices,
): JavaCompilation {

    override val annotationProcessor: AnnotationProcessor =
        AnnotationProcessorImpl(
            javaCompileOptionsSetInDSL.annotationProcessorOptions,
            dataBindingEnabled,
            internalServices
        )
}
