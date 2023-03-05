package com.tyron.builder.gradle.internal.services

import com.tyron.builder.gradle.options.IntegerOption
import com.tyron.builder.gradle.options.ProjectOptions
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ForkJoinPool


/** Build service used to hold shared thread pool used for aapt2. */
abstract class Aapt2ThreadPoolBuildService : BuildService<Aapt2ThreadPoolBuildService.Params>,
    AutoCloseable {
    interface Params : BuildServiceParameters {
        val aapt2ThreadPoolSize: Property<Int>
    }

    val aapt2ThreadPool: ForkJoinPool = ForkJoinPool(parameters.aapt2ThreadPoolSize.get())

    override fun close() {
        aapt2ThreadPool.shutdown()
    }

    class RegistrationAction(project: Project, projectOptions: ProjectOptions) :
        ServiceRegistrationAction<Aapt2ThreadPoolBuildService, Params>(
            project,
            Aapt2ThreadPoolBuildService::class.java
        ) {
        private val aapt2ThreadPoolSize =
            computeMaxAapt2Daemons(projectOptions)



        override fun configure(parameters: Params) {
            parameters.aapt2ThreadPoolSize.set(aapt2ThreadPoolSize)
        }
    }
}

private const val MAX_AAPT2_THREAD_POOL_SIZE = 8

fun computeMaxAapt2Daemons(projectOptions: ProjectOptions): Int {
    return projectOptions.get(IntegerOption.AAPT2_THREAD_POOL_SIZE) ?: Integer.min(
        MAX_AAPT2_THREAD_POOL_SIZE,
        ForkJoinPool.getCommonPoolParallelism()
    )
}