package com.tyron.builder.plugin.tasks

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.gradle.internal.scope.InternalMultipleArtifactType
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.ProcessOperations
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.utils.addToStdlib.cast

@DisableCachingByDefault
abstract class RunAction : DefaultTask() {

    @TaskAction
    fun run() {
        val artifacts = project.extensions.getByName("artifacts").cast<ArtifactsImpl>()
        val dexDirs = artifacts.getAll(InternalMultipleArtifactType.DEX)
        val path = project.files(dexDirs).asFileTree.matching { it.include("**/*.dex") }.files.joinToString(":")

        val args = listOf("-cp", path, "Main")

        val processOperations = services.get(ProcessOperations::class.java)
        processOperations.exec {
            it.executable = "dalvikvm"
            it.args = args
            it.workingDir = project.rootDir
        }
    }
}