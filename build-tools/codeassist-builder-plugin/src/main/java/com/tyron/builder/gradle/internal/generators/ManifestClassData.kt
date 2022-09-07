package com.tyron.builder.gradle.internal.generators

import java.io.File

data class ManifestClassData(
    val manifestFile: File,
    val namespace : String,
    val outputFilePath: File
)
