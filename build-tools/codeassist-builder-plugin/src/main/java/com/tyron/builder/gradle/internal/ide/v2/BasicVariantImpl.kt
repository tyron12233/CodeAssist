package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.BasicArtifact
import com.tyron.builder.model.v2.ide.BasicVariant
import com.tyron.builder.model.v2.ide.Variant
import java.io.Serializable

/**
 * Implementation of [Variant] for serialization via the Tooling API.
 */
data class BasicVariantImpl(
    override val name: String,
    override val mainArtifact: BasicArtifact,
    override val androidTestArtifact: BasicArtifact?,
    override val unitTestArtifact: BasicArtifact?,
    override val testFixturesArtifact: BasicArtifact?,
    override val buildType: String?,
    override val productFlavors: List<String>,
) : BasicVariant, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
