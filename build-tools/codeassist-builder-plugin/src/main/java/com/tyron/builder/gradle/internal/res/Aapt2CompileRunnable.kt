package com.tyron.builder.gradle.internal.res

import com.android.ide.common.resources.CompileResourceRequest
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.services.Aapt2Input
import com.tyron.builder.gradle.internal.services.getLeasingAapt2
import com.tyron.builder.internal.aapt.v2.Aapt2Exception
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

abstract class Aapt2CompileRunnable : WorkAction<Aapt2CompileRunnable.Params> {

    override fun execute() {
        runAapt2Compile(
            parameters.aapt2Input.get(),
            parameters.requests.get(),
            parameters.enableBlame.getOrElse(false)
        )
    }

    abstract class Params : WorkParameters {
        abstract val aapt2Input: Property<Aapt2Input>
        abstract val requests: ListProperty<CompileResourceRequest>
        abstract val enableBlame: Property<Boolean>
    }
}

fun runAapt2Compile(
    aapt2Input: Aapt2Input,
    requests: List<CompileResourceRequest>,
    enableBlame: Boolean
) {
    val logger = Logging.getLogger(Aapt2CompileRunnable::class.java)
    val loggerWrapper = LoggerWrapper(logger)
    val daemon = aapt2Input.getLeasingAapt2()
    val errorFormatMode = aapt2Input.buildService.get().parameters.errorFormatMode.get()
    requests.forEach { request ->
        try {
            daemon.compile(request, loggerWrapper)
        } catch (exception: Aapt2Exception) {
            throw rewriteCompileException(
                exception,
                request,
                errorFormatMode,
                enableBlame,
                logger
            )
        }
    }
}