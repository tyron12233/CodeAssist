package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.CustomSourceDirectory
import com.tyron.builder.model.v2.ide.SourceProvider
import java.io.File
import java.io.Serializable

/**
 * Implementation of [SourceProvider] for serialization via the Tooling API.
 */
data class SourceProviderImpl(
    override val name: String,
    override val manifestFile: File,
    override val javaDirectories: Collection<File>,
    override val kotlinDirectories: Collection<File>,
    override val resourcesDirectories: Collection<File>,
    override val aidlDirectories: Collection<File>?,
    override val renderscriptDirectories: Collection<File>?,
    override val resDirectories: Collection<File>?,
    override val assetsDirectories: Collection<File>?,
    override val jniLibsDirectories: Collection<File>,
    override val shadersDirectories: Collection<File>?,
    override val mlModelsDirectories: Collection<File>?,
    override val customDirectories: Collection<CustomSourceDirectory>?,
) : SourceProvider, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
