package com.tyron.builder.compiler.viewbinding

import android.util.Log
import com.tyron.builder.compiler.BuildType
import com.tyron.builder.compiler.Task
import com.tyron.builder.exception.CompilationFailedException
import com.tyron.builder.log.ILogger
import com.tyron.builder.model.ModuleSettings
import com.tyron.builder.project.Project
import com.tyron.builder.project.api.AndroidModule
import com.tyron.viewbinding.tool.DataBindingBuilder.GradleFileWriter
import com.tyron.viewbinding.tool.processing.Scope
import com.tyron.viewbinding.tool.processing.ScopedException
import com.tyron.viewbinding.tool.store.LayoutFileParser
import com.tyron.viewbinding.tool.store.ResourceBundle
import com.tyron.viewbinding.tool.util.LoggedErrorException
import com.tyron.viewbinding.tool.util.RelativizableFile
import com.tyron.viewbinding.tool.writer.*
import java.io.File
import java.lang.IllegalStateException

class GenerateViewBindingTask(
    project: Project,
    module: AndroidModule,
    logger: ILogger
) : Task<AndroidModule>(project, module, logger) {

    private lateinit var outputDirectory: File

    override fun getName() = TAG

    override fun prepare(type: BuildType?) {
        outputDirectory = File(module.buildDirectory, VIEW_BINDING_GEN_DIR)
        doPrepare()
    }

    fun prepareWithOutputDir(outputDir: File) {
        outputDirectory = outputDir
        doPrepare()
    }

    private fun doPrepare() {
        outputDirectory.deleteRecursively() // todo: incremental?
        outputDirectory.mkdirs()
    }

    override fun run() {
        if (!module.settings.getBoolean(ModuleSettings.VIEW_BINDING_ENABLED, false)) {
            logger.info("View binding is disabled, skipping")
            return
        }

        try {
            doRun()
        } catch (e: Exception) {
            when (e) {
                is ScopedException, is LoggedErrorException, is IllegalStateException ->
                    throw CompilationFailedException(Log.getStackTraceString(e))
                else -> throw e
            }
        }
    }

    private fun doRun() {
        // generate binding classes from layouts
        val resourceBundle = generateClasses()

        // write classes to output dir
        writeClasses(resourceBundle)

        // data binding will eat some errors to be able to report them later on. This is a good
        // time to report them after the processing is done.
        Scope.assertNoError()
    }

    private fun generateClasses(): ResourceBundle {
        // it doesn't matter what we pass to the 2nd argument, we won't be using data binding anyways
        val resourceBundle = ResourceBundle(module.packageName, true)
        val resDir = module.androidResourcesDirectory

        resDir.walkTopDown().filter {
            val isXmlFile = it.isFile && it.name.endsWith(".xml")
            val isLayoutFile = it.parentFile.name == "layout" || it.parentFile.name.startsWith("layout-")
            val applicable = isXmlFile && isLayoutFile

            applicable
        }.forEach { file ->
            // 2nd argument is for stripping the file of data binding syntax, not needed for view binding
            val bundle = LayoutFileParser.parseXml(
                RelativizableFile.fromAbsoluteFile(file),
                null,
                module.packageName,
                { null },
                true,
                false
            )
            if (bundle != null) {
                resourceBundle.addLayoutBundle(bundle, true)
            }
        }
        resourceBundle.validateAndRegisterErrors()

        return resourceBundle
    }

    private fun writeClasses(resourceBundle: ResourceBundle) {
        val writer = GradleFileWriter(outputDirectory.absolutePath)

        val layoutBindings = resourceBundle.allLayoutFileBundlesInSource
            .groupBy(ResourceBundle.LayoutFileBundle::getFileName)

        layoutBindings.forEach { (_, variations) ->
            val layoutModel = BaseLayoutModel(variations)
            val viewBinder = layoutModel.toViewBinder()
            val javaFile = viewBinder.toJavaFile(
                // true  -> android.support annotations
                // false -> androidx annotations
                // the user must use the newer view binding library (androidx)
                useLegacyAnnotations = false
            )

            writer.writeToFile(javaFile)
        }
    }

    companion object {
        const val TAG = "GenerateViewBindingTask"
        const val VIEW_BINDING_GEN_DIR = "view_binding"
    }

}
