package com.tyron.builder.gradle.internal.dependency

import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.google.common.io.Files
import com.tyron.builder.BuildModule
import org.apache.commons.io.IOUtils
import org.gradle.api.GradleException
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.internal.UncheckedException
import java.io.FileOutputStream
import java.io.IOException
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

@CacheableTransform
abstract class AnnotationProcessorDexer: TransformAction<GenericTransformParameters> {


    @get:Classpath
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>


    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val outputFile = outputs.file("dexed-" + Files.getNameWithoutExtension(input.name) + ".jar")
        try {
            val build = D8Command.builder()
                .setOutput(outputFile.toPath(), OutputMode.DexIndexed)
                .addLibraryFiles(BuildModule.getAndroidJar().toPath())
                .addLibraryFiles(BuildModule.getLambdaStubs().toPath())
                .addProgramFiles(input.toPath())
                .setMinApiLevel(26)
                .build()
            D8.run(build)
        } catch (e: CompilationFailedException) {
            throw GradleException(e.message!!)
        }
        try {
            JarFile(outputFile).use { inputJarFile ->
                JarOutputStream(FileOutputStream(outputFile)).use { outputJarFile ->
                    // iterate through the input and copy the files that are not class files
                    val entries = inputJarFile.entries()
                    while (entries.hasMoreElements()) {
                        val inputJarEntry = entries.nextElement()
                        if (!inputJarEntry.name.endsWith(".class")) {
                            outputJarFile.putNextEntry(JarEntry(inputJarEntry))
                            IOUtils.copy(inputJarFile.getInputStream(inputJarEntry), outputJarFile)
                            outputJarFile.closeEntry()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw UncheckedException.throwAsUncheckedException(e)
        }
    }
}