package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.VariantServices

/** Data about a variant that produces a APK.  */
abstract class ApkVariantData protected constructor(
    componentIdentity: ComponentIdentity,
    artifacts: ArtifactsImpl,
    services: VariantServices,
    taskContainer: MutableTaskContainer
) : BaseVariantData(
    componentIdentity,
    artifacts,
    services,
    taskContainer
) {

    var compatibleScreens: Set<String> = setOf()
}