package com.tyron.viewbinding.task

import com.tyron.builder.compiler.viewbinding.GenerateViewBindingTask
import com.tyron.builder.compiler.viewbinding.GenerateViewBindingTask.Companion.VIEW_BINDING_GEN_DIR
import com.tyron.builder.log.ILogger
import com.tyron.builder.model.ModuleSettings
import com.tyron.builder.model.SourceFileObject
import com.tyron.builder.project.Project
import com.tyron.builder.project.api.AndroidModule
import com.tyron.completion.java.JavaCompilerProvider
import java.io.File
import java.time.Instant

/**
 * Used to create fake View Binding classes from the project resources for it to
 * show up on code completion. Files generated from this task should not
 * be included in the compilation process as the values of the fields are
 * not accurate from what [viewbinding-lib] generates.
 */
class InjectViewBindingTask private constructor(
    val project: Project,
    val module: AndroidModule,
) {

    private fun doInject(consumer: (List<File>) -> Unit) {
        val genTask = GenerateViewBindingTask(project, module, ILogger.EMPTY, false)
        val outputDir = File(module.buildDirectory, "injected/${VIEW_BINDING_GEN_DIR}")

        try {
            genTask.prepareWithOutputDir(outputDir)
            genTask.run()

            val sources = outputDir.walkTopDown().filter {
                it.isFile && it.name.endsWith(".java")
            }.toList()

            // inject classes
            sources.forEach(module::addInjectedClass)

            consumer.invoke(sources)
        } catch (ignored: Throwable) {}
    }

    companion object {
        @JvmStatic
        fun inject(project: Project, module: AndroidModule) {
            if (!module.settings.getBoolean(ModuleSettings.VIEW_BINDING_ENABLED, false)) {
                return
            }

            val service = JavaCompilerProvider.get(project, module) ?: return

            val task = InjectViewBindingTask(project, module)
            task.doInject { files ->
                if (project.isCompiling || project.isIndexing) {
                    return@doInject
                }

                val sources = files.map { file ->
                    SourceFileObject(file.toPath(), module, Instant.now())
                }
                service.compile(sources)
            }
        }
    }

}
