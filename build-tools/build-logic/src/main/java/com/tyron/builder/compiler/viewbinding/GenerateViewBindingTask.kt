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
import com.tyron.viewbinding.tool.writer.BaseLayoutModel
import com.tyron.viewbinding.tool.writer.toJavaFile
import com.tyron.viewbinding.tool.writer.toViewBinder
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * @param addToClasspath true if the generated binding classes
 * should be added to the module classpath for compilation
 */
class GenerateViewBindingTask(
    project: Project?,
    module: AndroidModule,
    logger: ILogger,
    private val addToClasspath: Boolean,
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
        val resourceBundle = generateClassesToBundle()

        // write classes to output dir
        writeClassesToDisk(resourceBundle)

        // data binding will eat some errors to be able to report them later on. This is a good
        // time to report them after the processing is done.
        Scope.assertNoError()

        // add classes to module classpath
        addToClasspath()
    }

    private fun generateClassesToBundle(): ResourceBundle {
        // it doesn't matter what we pass to the 2nd argument, we won't be using data binding anyways
        val resourceBundle = ResourceBundle(module.packageName, true)
        val resDir = module.androidResourcesDirectory

        resDir.walkTopDown().filter {
            val isXmlFile = it.isFile && it.name.endsWith(".xml")
            val isLayoutFile = it.parentFile.name == "layout" || it.parentFile.name.startsWith("layout-")
            val applicable = isXmlFile && isLayoutFile

            applicable
        }.forEach { file ->
            val bundle = LayoutFileParser.parseXml(
                RelativizableFile.fromAbsoluteFile(file),
                module.packageName,
                getUpToDateFileContent(module, file),
                true
            )
            if (bundle != null) {
                resourceBundle.addLayoutBundle(bundle, true)
            }
        }
        resourceBundle.validateAndRegisterErrors()
        return resourceBundle
    }

    private fun writeClassesToDisk(resourceBundle: ResourceBundle) {
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

    private fun addToClasspath() {
        if (addToClasspath) {
            outputDirectory.walkTopDown().filter {
                it.isFile && it.name.endsWith(".java")
            }.forEach(module::addResourceClass)
        }
    }

    companion object {
        const val TAG = "GenerateViewBindingTask"
        const val VIEW_BINDING_GEN_DIR = "view_binding"

        private fun getUpToDateFileContent(module: AndroidModule, file: File): String? {
            try {
                val fileManager = module.fileManager
                if (fileManager.isOpened(file)) {
                    val fileContent = fileManager.getFileContent(file)
                    if (fileContent.isPresent) {
                        return fileContent.get().toString()
                    }
                }
            } catch (ignored: IOException) {}

            try {
                return FileUtils.readFileToString(file, StandardCharsets.UTF_8)
            } catch (ignored: IOException) {}

            return null
        }
    }

}
