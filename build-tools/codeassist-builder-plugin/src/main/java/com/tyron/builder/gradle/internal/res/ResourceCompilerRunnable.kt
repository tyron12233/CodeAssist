package com.tyron.builder.gradle.internal.res

import com.android.aaptcompiler.ResourceCompilerOptions
import com.android.ide.common.resources.CompileResourceRequest
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.services.ResourceCompilerUtils
import org.gradle.api.provider.ListProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

abstract class ResourceCompilerRunnable : WorkAction<ResourceCompilerRunnable.Params> {

  override fun execute() {
    parameters.request.get().forEach {
      compileSingleResource(it)
    }
  }

  abstract class Params: WorkParameters {
    abstract val request: ListProperty<CompileResourceRequest>
  }

  companion object {
    @JvmStatic
    fun compileSingleResource(request: CompileResourceRequest) {
      val options = ResourceCompilerOptions(
        pseudolocalize = request.isPseudoLocalize,
        partialRFile = request.partialRFile,
        legacyMode = true,
        sourcePath = request.sourcePath)

      // TODO: find a way to re-use the blame logger between requests
      val blameLogger = blameLoggerFor(request, LoggerWrapper.getLogger(this::class.java))
      ResourceCompilerUtils.test(request.inputFile, request.outputDirectory, options, blameLogger)
    }
  }
}
