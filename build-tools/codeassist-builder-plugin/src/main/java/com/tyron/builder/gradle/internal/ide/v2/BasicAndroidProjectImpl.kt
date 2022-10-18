package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.BasicVariant
import com.tyron.builder.model.v2.ide.ProjectType
import com.tyron.builder.model.v2.ide.SourceSetContainer
import com.tyron.builder.model.v2.models.AndroidProject
import com.tyron.builder.model.v2.models.BasicAndroidProject
import java.io.File
import java.io.Serializable

/**
 * Implementation of [AndroidProject] for serialization via the Tooling API.
 */
data class BasicAndroidProjectImpl(
    override val path: String,
    override val buildName: String,
    override val projectType: ProjectType,
    override val mainSourceSet: SourceSetContainer?,
    override val buildTypeSourceSets: Collection<SourceSetContainer>,
    override val productFlavorSourceSets: Collection<SourceSetContainer>,
    override val variants: Collection<BasicVariant>,
    override val bootClasspath: Collection<File>,
    override val buildFolder: File,
) : BasicAndroidProject, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
