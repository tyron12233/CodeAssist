package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ModelSyncFile
import com.tyron.builder.model.v2.ide.AndroidArtifact
import com.tyron.builder.model.v2.ide.ApiVersion
import com.tyron.builder.model.v2.ide.BundleInfo
import com.tyron.builder.model.v2.ide.CodeShrinker
import com.tyron.builder.model.v2.ide.PrivacySandboxSdkInfo
import com.tyron.builder.model.v2.ide.TestInfo
import java.io.File
import java.io.Serializable

/**
 * Implementation of [AndroidArtifact] for serialization via the Tooling API.
 */
data class AndroidArtifactImpl(
    override val minSdkVersion: ApiVersion,
    override val targetSdkVersionOverride: ApiVersion?,
    override val maxSdkVersion: Int?,

    override val signingConfigName: String?,
    override val isSigned: Boolean,

    override val applicationId: String?,

    override val abiFilters: Set<String>?,
    override val testInfo: TestInfo?,
    override val bundleInfo: BundleInfo?,
    override val codeShrinker: CodeShrinker?,

    override val compileTaskName: String,
    override val assembleTaskName: String,
    override val sourceGenTaskName: String,
    override val resGenTaskName: String?,
    override val ideSetupTaskNames: Set<String>,

    override val generatedSourceFolders: Collection<File>,
    override val generatedResourceFolders: Collection<File>,
    override val classesFolders: Set<File>,
    override val assembleTaskOutputListingFile: File?,
    override val modelSyncFiles: Collection<ModelSyncFile>,
    override val privacySandboxSdkInfo: PrivacySandboxSdkInfo?,
    override val desugaredMethodsFiles: Collection<File>
) : AndroidArtifact, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 2L
    }
}
