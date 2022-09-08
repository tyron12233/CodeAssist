package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.AndroidArtifact
import com.tyron.builder.model.v2.ide.JavaArtifact
import com.tyron.builder.model.v2.ide.TestedTargetVariant
import com.tyron.builder.model.v2.ide.Variant
import java.io.File
import java.io.Serializable

/**
 * Implementation of [Variant] for serialization via the Tooling API.
 */
data class VariantImpl(
    override val name: String,
    override val displayName: String,
    override val mainArtifact: AndroidArtifact,
    override val androidTestArtifact: AndroidArtifact?,
    override val unitTestArtifact: JavaArtifact?,
    override val testFixturesArtifact: AndroidArtifact?,
    override val testedTargetVariant: TestedTargetVariant?,
    override val isInstantAppCompatible: Boolean,
    override val desugaredMethods: List<File>
) : Variant, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
