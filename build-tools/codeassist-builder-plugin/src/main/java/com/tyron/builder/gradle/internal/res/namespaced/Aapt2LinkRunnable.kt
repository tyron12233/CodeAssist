package com.tyron.builder.gradle.internal.res.namespaced

import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.res.rewriteLinkException
import com.tyron.builder.gradle.internal.services.Aapt2DaemonServiceKey
import com.tyron.builder.gradle.internal.services.useAaptDaemon
import com.tyron.builder.internal.aapt.AaptPackageConfig
import com.tyron.builder.internal.aapt.v2.Aapt2Exception
import com.tyron.builder.plugin.options.SyncOptions
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

abstract class Aapt2LinkRunnable : WorkAction<Aapt2LinkRunnable.Params> {

    override fun execute() {
        runAapt2Link(
                parameters.aapt2ServiceKey.get(),
                parameters.request.get(),
                parameters.errorFormatMode.get()
        )
    }

    abstract class Params : WorkParameters {
        abstract val aapt2ServiceKey: Property<Aapt2DaemonServiceKey>
        abstract val request: Property<AaptPackageConfig>
        abstract val errorFormatMode: Property<SyncOptions.ErrorFormatMode>
    }
}

fun runAapt2Link(
        aapt2ServiceKey: Aapt2DaemonServiceKey,
        request: AaptPackageConfig,
        errorFormatMode: SyncOptions.ErrorFormatMode
) {
    val logger = Logging.getLogger(Aapt2LinkRunnable::class.java)
    useAaptDaemon(aapt2ServiceKey) { daemon ->
        try {
            daemon.link(request, LoggerWrapper(logger))
        } catch (exception: Aapt2Exception) {
            throw rewriteLinkException(
                    exception,
                    errorFormatMode,
                    null,
                    null,
                    emptyMap(),
                    logger
            )
        }
    }
}
