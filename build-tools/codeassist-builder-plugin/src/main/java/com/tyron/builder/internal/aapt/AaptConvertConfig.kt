package com.tyron.builder.internal.aapt

import java.io.File

/** Configuration for an `aapt2 convert` operation. */
data class AaptConvertConfig(
    val inputFile: File,
    val outputFile: File,
    val convertToProtos: Boolean = false
)