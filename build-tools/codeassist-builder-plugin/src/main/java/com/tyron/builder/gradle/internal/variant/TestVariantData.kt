package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.VariantServices

/**
 * Data about a test component in a normal plugin
 *
 *
 * For the test plugin, ApplicationVariantData is used.
 */
class TestVariantData(
    componentIdentity: ComponentIdentity,
    artifacts: ArtifactsImpl,
    services: VariantServices,
    taskContainer: MutableTaskContainer
) : ApkVariantData(
    componentIdentity,
    artifacts,
    services,
    taskContainer
)