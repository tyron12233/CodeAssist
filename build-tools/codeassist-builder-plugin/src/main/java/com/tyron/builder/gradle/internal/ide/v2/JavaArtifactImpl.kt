package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ModelSyncFile
import com.tyron.builder.model.v2.ide.JavaArtifact
import java.io.File
import java.io.Serializable

/**
 * Implementation of [JavaArtifact] for serialization via the Tooling API.
 */
data class JavaArtifactImpl(
    override val mockablePlatformJar: File?,
    override val compileTaskName: String,
    override val assembleTaskName: String,
    override val classesFolders: Set<File>,
    override val runtimeResourceFolder: File?,
    override val ideSetupTaskNames: Set<String>,
    override val generatedSourceFolders: Collection<File>,
    override val modelSyncFiles: Collection<ModelSyncFile>,
) : JavaArtifact, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
