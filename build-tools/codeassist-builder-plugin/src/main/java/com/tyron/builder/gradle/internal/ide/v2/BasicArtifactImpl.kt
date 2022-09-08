package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.AndroidArtifact
import com.tyron.builder.model.v2.ide.BasicArtifact
import com.tyron.builder.model.v2.ide.SourceProvider
import java.io.Serializable

/**
 * Implementation of [AndroidArtifact] for serialization via the Tooling API.
 */
data class BasicArtifactImpl(
    override val variantSourceProvider: SourceProvider?,
    override val multiFlavorSourceProvider: SourceProvider?,
) : BasicArtifact, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
