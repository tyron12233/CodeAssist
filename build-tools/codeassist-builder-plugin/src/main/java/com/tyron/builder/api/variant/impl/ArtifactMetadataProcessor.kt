package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType

class ArtifactMetadataProcessor {

    companion object {

        /**
         * It would be great to use [kotlin.reflect.KClass.sealedSubclasses] to get all the
         * artifacts types that have [InternalArtifactType.finalizingArtifact] set. However, the API
         * uses reflection and is slow, therefore you must give the list of annotated types here.
         *
         * There is a test that will ensure that all annotated types are present in this list.
         */
        val internalTypesFinalizingArtifacts: List<InternalArtifactType<*>> = listOf(
            InternalArtifactType.APK_IDE_REDIRECT_FILE,
            InternalArtifactType.BUNDLE_IDE_REDIRECT_FILE,
            InternalArtifactType.APK_FROM_BUNDLE_IDE_REDIRECT_FILE
        )

        fun wireAllFinalizedBy(variant: ComponentCreationConfig) {
            val allComponents = mutableListOf(variant)
            if (variant is HasAndroidTest) {
                variant.androidTest?.let { allComponents.add(it) }
            }
            if (variant is HasTestFixtures) {
                variant.testFixtures?.let { allComponents.add(it) }
            }
            internalTypesFinalizingArtifacts.forEach { kClass ->
                allComponents.forEach { component ->
                    handleFinalizedByForType(component, kClass)
                }
            }
        }

        private fun handleFinalizedByForType(
            variant: ComponentCreationConfig,
            artifact: InternalArtifactType<*>
        ) {
            artifact.finalizingArtifact.forEach { artifactFinalizedBy ->
                val artifactContainer = when (artifactFinalizedBy) {
                    is Artifact.Single -> variant.artifacts.getArtifactContainer(artifactFinalizedBy)
                    is Artifact.Multiple -> variant.artifacts.getArtifactContainer(artifactFinalizedBy)
                    else -> throw RuntimeException("Unhandled artifact type : $artifactFinalizedBy")
                }
                artifactContainer.getTaskProviders().forEach { taskProvider ->
                    taskProvider.configure {
                        it.finalizedBy(variant.artifacts.get(artifact))
                    }
                }
            }
        }
    }
}