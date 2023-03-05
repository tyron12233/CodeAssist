package com.tyron.builder.gradle.internal.dependency

import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal

/**
 * Generic [TransformParameters] for all of our Artifact Transforms.
 */
interface GenericTransformParameters: TransformParameters {
    @get:Internal
    val projectName: Property<String>
}