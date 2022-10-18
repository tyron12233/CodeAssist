package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.SourceProvider
import com.tyron.builder.model.v2.ide.SourceSetContainer
import java.io.Serializable

data class SourceSetContainerImpl(
    override val sourceProvider: SourceProvider,
    override val androidTestSourceProvider: SourceProvider? = null,
    override val unitTestSourceProvider: SourceProvider? = null,
    override val testFixturesSourceProvider: SourceProvider? = null
) : SourceSetContainer, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
