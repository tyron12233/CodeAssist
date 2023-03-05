package com.tyron.builder.compiling

import java.io.File
import java.io.IOException


interface GeneratedCodeFileCreator {
    // The directory containing the generatedFilePath.
    val folderPath : File
    // The file which will be written to by the code generator.
    val generatedFilePath : File

    /** Writes a file at the generatedFilePath based on the properties of the implementing class */
    @Throws(IOException::class)
    fun generate()
}