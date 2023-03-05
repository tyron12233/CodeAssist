package com.tyron.builder.gradle.internal.tasks

import com.google.gson.GsonBuilder
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.IOException

/** Module information like its application ID, version code and version name  */
class ModuleMetadata(
    val applicationId: String,
    val versionCode: String?,
    val versionName: String?,
    val debuggable: Boolean,
    val abiFilters: List<String>,
    val ignoredLibraryKeepRules: Set<String>,
    val ignoreAllLibraryKeepRules: Boolean
) {

    @Throws(IOException::class)
    fun save(outputFile: File) {
        val gsonBuilder = GsonBuilder()
        val gson = gsonBuilder.create()
        FileUtils.write(outputFile, gson.toJson(this))
    }

    companion object {

        internal const val PERSISTED_FILE_NAME = "application-metadata.json"

        @Throws(IOException::class)
        @JvmStatic
        fun load(input: File): ModuleMetadata {
            if (input.name != PERSISTED_FILE_NAME) {
                throw FileNotFoundException("No application declaration present.")
            }
            val gsonBuilder = GsonBuilder()
            val gson = gsonBuilder.create()
            FileReader(input).use { fileReader ->
                return gson.fromJson(
                    fileReader,
                    ModuleMetadata::class.java
                )
            }
        }
    }
}
