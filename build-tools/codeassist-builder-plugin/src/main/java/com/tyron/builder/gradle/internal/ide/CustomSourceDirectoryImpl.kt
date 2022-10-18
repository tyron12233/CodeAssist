package com.tyron.builder.gradle.internal.ide

import com.tyron.builder.model.v2.CustomSourceDirectory
import java.io.File
import java.io.Serializable

class CustomSourceDirectoryImpl(
    override val sourceTypeName: String,
    override val directory: File,
) : CustomSourceDirectory, Serializable