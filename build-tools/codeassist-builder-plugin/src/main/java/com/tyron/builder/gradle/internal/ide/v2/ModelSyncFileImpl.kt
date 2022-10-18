package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ModelSyncFile
import java.io.File
import java.io.Serializable

data class ModelSyncFileImpl(
    override val modelSyncType: ModelSyncFile.ModelSyncType,
    override val taskName: String,
    override val syncFile: File
) : ModelSyncFile, Serializable