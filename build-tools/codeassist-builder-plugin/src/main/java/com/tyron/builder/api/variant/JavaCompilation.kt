package com.tyron.builder.api.variant

import org.gradle.api.Incubating

@Incubating
interface JavaCompilation {

    /**
     * Returns the [AnnotationProcessor] for configuring Java annotation processor.
     */
    val annotationProcessor: AnnotationProcessor
}