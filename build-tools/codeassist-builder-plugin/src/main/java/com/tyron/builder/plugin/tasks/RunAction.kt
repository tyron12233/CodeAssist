package com.tyron.builder.plugin.tasks

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.gradle.internal.scope.InternalMultipleArtifactType
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.utils.addToStdlib.cast

@DisableCachingByDefault
abstract class RunAction : DefaultTask() {

    @get:InputFiles
    @get:Classpath
    abstract val dexFiles: FileCollection

    @TaskAction
    fun run() {
        val artifacts = project.extensions.getByName("").cast<ArtifactsImpl>()
        val dexDirs = artifacts.getAll(InternalMultipleArtifactType.DEX)
        project.files(dexDirs).asFileTree
    }
}