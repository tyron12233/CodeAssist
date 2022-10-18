package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.ArtifactDependencies
import com.tyron.builder.model.v2.ide.Library
import com.tyron.builder.model.v2.models.VariantDependencies
import java.io.Serializable

/**
 * Implementation of [VariantDependencies] for serialization via the Tooling API.
 */
data class VariantDependenciesImpl(
    override val name: String,
    override val mainArtifact: ArtifactDependencies,
    override val androidTestArtifact: ArtifactDependencies?,
    override val unitTestArtifact: ArtifactDependencies?,
    override val testFixturesArtifact: ArtifactDependencies?,
    override val libraries: Map<String, Library>
) : VariantDependencies, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
