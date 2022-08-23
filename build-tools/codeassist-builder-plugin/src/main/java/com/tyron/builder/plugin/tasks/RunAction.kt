package com.tyron.builder.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class RunAction : DefaultTask() {

    @get:InputFiles
    @get:Classpath
    abstract val dexFiles: FileCollection

    @TaskAction
    fun run() {

    }
}