package com.tyron.builder.gradle.internal.scope

import com.tyron.builder.gradle.internal.dependency.AndroidAttributes
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationVariant

/**
 * Publish an artifact to a configuration.
 *
 * @param configuration The configuration to which the artifact is published.
 * @param file The artifact file published, typically a File or Provider<File>.
 * @param artifactType The ArtifactType of the artifact.
 * @param attributes Other optional Configuration Attributes.
 */
@JvmOverloads
fun publishArtifactToConfiguration(
    configuration: Configuration,
    file: Any,
    artifactType: AndroidArtifacts.ArtifactType,
    attributes: AndroidAttributes? = null
) {
    val type = artifactType.type
    configuration
        .outgoing
        .variants { variants: NamedDomainObjectContainer<ConfigurationVariant> ->
            variants.create(
                getConfigurationVariantName(artifactType, attributes)
            ) { variant ->
                variant.artifact(
                    file
                ) { artifact ->
                    artifact.type = type
                }
                variant.attributes.let { container ->
                    attributes?.addAttributesToContainer(container)
                }
            }
        }
}

@JvmOverloads
fun publishArtifactToDefaultVariant(
    configuration: Configuration,
    file: Any,
    artifactType: AndroidArtifacts.ArtifactType,
    classifier: String? = null
) {
    val type = artifactType.type

    configuration.outgoing.artifact(
        file
    ) { artifact ->
        artifact.type = type
        classifier?.let { artifact.classifier = classifier }
    }
}

/**
 * This method creates a unique ConfigurationVariant name based on the artifactType and
 * attributeMap, which is important because all items in a NamedDomainObjectContainer must have
 * unique names.
 */
private fun getConfigurationVariantName(
    artifactType: AndroidArtifacts.ArtifactType,
    attributes: AndroidAttributes?
): String {
    return artifactType.type + (attributes?.toAttributeMapString() ?: "")
}

