package com.tyron.builder.gradle.internal.services

import com.android.aaptcompiler.ResourceCompilerOptions
import com.android.aaptcompiler.canCompileResourceInJvm
import com.android.aaptcompiler.compileResource
import com.android.utils.ILogger
import com.tyron.builder.common.resources.CompileResourceRequest
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.res.blameLoggerFor
import com.tyron.builder.internal.aapt.AaptConvertConfig
import com.tyron.builder.internal.aapt.AaptPackageConfig
import com.tyron.builder.internal.aapt.v2.Aapt2
import javax.annotation.concurrent.ThreadSafe

/** Wraps an [Aapt2] to push some compile requests to the in-process resource compiler */
@ThreadSafe
class PartialInProcessResourceProcessor (val delegate: Aapt2):
    Aapt2 {
    override fun compile(request: CompileResourceRequest, logger: ILogger) {
        if (canCompileResourceInJvm(request.inputFile, request.isPngCrunching)) {
            val options = ResourceCompilerOptions(
                    pseudolocalize = request.isPseudoLocalize,
                    legacyMode = true,
                    sourcePath = request.sourcePath,
                    partialRFile = request.partialRFile,
            )

            val blameLogger = blameLoggerFor(request, LoggerWrapper.getLogger(this::class.java))
            compileResource(request.inputFile, request.outputDirectory, options, blameLogger)
        } else {
            delegate.compile(request, logger)
        }
    }

    override fun link(request: AaptPackageConfig, logger: ILogger) = delegate.link(request, logger)

    override fun convert(request: AaptConvertConfig, logger: ILogger) = delegate.convert(request,logger)
}