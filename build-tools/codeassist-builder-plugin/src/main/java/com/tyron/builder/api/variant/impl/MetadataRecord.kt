package com.tyron.builder.api.variant.impl

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * Holds information of metadata file that will be eventually added to the resulting .aab file.
 */
class MetadataRecord(
    @get:Input
    val directory: String,

    @get: InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val metadataFile: Provider<RegularFile>
)
