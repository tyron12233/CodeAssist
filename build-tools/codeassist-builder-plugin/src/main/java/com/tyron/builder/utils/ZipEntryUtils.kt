@file:JvmName("ZipEntryUtils")
package com.tyron.builder.utils

import java.io.File
import java.util.zip.ZipEntry

/**
 * Validates the name of a zip entry. Zip files support .. in the file name as such an attacker
 * could use this to place a file in a directory in the users root. This function returns true
 * if the entry contains ../
 */
fun isValidZipEntryName(entry: ZipEntry): Boolean {
    return !entry.name.contains("../")
}

/**
 * Helper function to validate the path inside a zipfile does not leave the output directory.
 */
fun isValidZipEntryPath(filePath: File, outputDir: File): Boolean {
    return filePath.canonicalPath.startsWith(outputDir.canonicalPath + File.separator)
}

/** Creates a new zip entry with time set to zero. */
fun zipEntry(name: String): ZipEntry = ZipEntry(name).apply { time = -1L }