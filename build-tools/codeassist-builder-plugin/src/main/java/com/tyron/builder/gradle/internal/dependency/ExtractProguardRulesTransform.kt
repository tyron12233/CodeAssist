package com.tyron.builder.gradle.internal.dependency

import com.google.common.io.ByteStreams
import com.tyron.builder.dexing.isProguardRule
import com.tyron.builder.dexing.isToolsConfigurationFile
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.util.internal.GFileUtils
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject

fun isProguardRule(entry: ZipEntry): Boolean {
    return !entry.isDirectory && isProguardRule(entry.name)
}

fun isToolsConfigurationFile(entry: ZipEntry): Boolean {
    return !entry.isDirectory && isToolsConfigurationFile(entry.name)
}

@CacheableTransform
abstract class ExtractProGuardRulesTransform @Inject constructor() :
    TransformAction<GenericTransformParameters> {

    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(transformOutputs: TransformOutputs) {
        performTransform(inputArtifact.get().asFile, transformOutputs)
    }

    companion object {
        /** Returns true if some rules were found in the jar. */
        @JvmStatic
        fun performTransform(
            jarFile: File,
            transformOutputs: TransformOutputs,
            extractLegacyProguardRules: Boolean = true
        ): Boolean {
            ZipFile(jarFile, StandardCharsets.UTF_8).use { zipFile ->
                val entries = zipFile
                    .stream()
                    .filter { zipEntry ->
                        isToolsConfigurationFile(zipEntry)
                                || (extractLegacyProguardRules && isProguardRule(zipEntry))
                    }.iterator()

                if (!entries.hasNext()) {
                    return false;
                }
                val outputDirectory = transformOutputs.dir("rules")
                while (entries.hasNext()) {
                    val zipEntry = entries.next()
                    val outPath = zipEntry.name.replace('/', File.separatorChar)
                    val outFile = GFileUtils.join(outputDirectory.resolve("lib"), outPath)
                    GFileUtils.mkdirs(outFile.parentFile)
                    BufferedInputStream(zipFile.getInputStream(zipEntry)).use { inFileStream ->
                        BufferedOutputStream(outFile.outputStream()).use {
                            ByteStreams.copy(inFileStream, it)
                        }
                    }
                }
                return true
            }
        }
    }
}