package com.tyron.builder.gradle.internal.dependency

import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.AttributesSchema
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.internal.artifacts.ArtifactAttributes.ARTIFACT_FORMAT

class ModelArtifactCompatibilityRule : AttributeCompatibilityRule<String> {

    override fun execute(details: CompatibilityCheckDetails<String>) {
        val producerValue = details.producerValue
        when (details.consumerValue) {
            producerValue -> details.compatible()
            AndroidArtifacts.ArtifactType.AAR_OR_JAR.type -> {
                when (producerValue) {
                    AndroidArtifacts.ArtifactType.AAR.type -> details.compatible()
                    AndroidArtifacts.ArtifactType.JAR.type -> details.compatible()
                }
            }
            AndroidArtifacts.ArtifactType.LOCAL_EXPLODED_AAR_FOR_LINT.type -> {
                when (producerValue) {
                    AndroidArtifacts.ArtifactType.EXPLODED_AAR.type -> details.compatible()
                }
            }
        }
    }

    companion object {
        fun setUp(attributesSchema: AttributesSchema) {
            val strategy = attributesSchema.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE)
            strategy.compatibilityRules.add(ModelArtifactCompatibilityRule::class.java) { }
        }
    }
}