package com.tyron.builder.gradle.internal.dependency

import com.tyron.builder.dexing.ClassFileInput
import com.android.SdkConstants
import org.gradle.util.internal.GFileUtils
import java.io.File
import java.util.zip.ZipFile
import kotlin.streams.toList

class EnumerateClassesDelegate {
    fun run(classJar: File,
        outputFile: File
    ) {
        GFileUtils.deleteIfExists(outputFile)

        val outputString = extractClasses(classJar).joinToString(separator = "\n")

        outputFile.writeText(outputString)
    }

    private fun extractClasses(jarFile: File): List<String> = ZipFile(jarFile).use { zipFile ->
        return zipFile.stream()
            .filter { ClassFileInput.CLASS_MATCHER.test(it.name) }
            .map { it.name.replace('/', '.').dropLast(SdkConstants.DOT_CLASS.length) }
            .toList()
            .sorted()
    }
}