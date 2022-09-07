package com.tyron.builder.gradle.internal.aapt

import com.android.SdkConstants.DOT_9PNG
import com.android.aaptcompiler.canCompileResourceInJvm
import com.android.ide.common.resources.CompileResourceRequest
import com.android.ide.common.resources.ResourceCompilationService
import com.tyron.builder.gradle.internal.res.Aapt2CompileRunnable
import com.tyron.builder.gradle.internal.res.ResourceCompilerRunnable
import com.tyron.builder.gradle.internal.services.Aapt2Input
import com.tyron.builder.internal.aapt.v2.Aapt2RenamingConventions
import org.gradle.workers.WorkerExecutor
import java.io.File

/** Resource compilation service built on top of a Aapt2Daemon and Gradle Worker Executors. */
class WorkerExecutorResourceCompilationService(
//    private val projectPath: Provider<String>,
//    private val taskOwner: String,
    private val workerExecutor: WorkerExecutor,
//    private val analyticsService: Provider<Any?>,
    private val aapt2Input: Aapt2Input
) : ResourceCompilationService {

    /** Temporary workaround for b/73804575 / https://github.com/gradle/gradle/issues/4502
     *  Only submit a small number of worker actions */
    private val requests: MutableList<CompileResourceRequest> = ArrayList()

    override fun submitCompile(request: CompileResourceRequest) {
        // b/73804575
        requests.add(request)
    }

    override fun compileOutputFor(request: CompileResourceRequest): File {
        return File(
            request.outputDirectory,
            Aapt2RenamingConventions.compilationRename(request.inputFile)
        )
    }

    private fun getExtension(file: File): String {
        // kotlin File.extension returns png for 9.png files
        if (file.name.endsWith(DOT_9PNG)) {
            return DOT_9PNG
        }
        return file.extension
    }

    override fun close() {
        if (requests.isEmpty()) {
            return
        }
        val maxWorkersCount = aapt2Input.maxWorkerCount.get()

        // First remove all JVM res compiler compatible files to be consumed by the kotlin compiler.
        val jvmRequests = requests.filter {
            canCompileResourceInJvm(it.inputFile, it.isPngCrunching)
        }
        requests.removeAll(jvmRequests)

        // Split all requests into buckets, giving each worker the same number of files to process
        var ord = 0
        val jvmBuckets =
            jvmRequests.groupByTo(HashMap(maxWorkersCount)) { (ord++) % maxWorkersCount }

        jvmBuckets.values.forEach { bucket ->
            workerExecutor.noIsolation()
                .submit(ResourceCompilerRunnable::class.java) {
//                    it.initializeWith(projectPath = projectPath, taskOwner = taskOwner, analyticsService = analyticsService)
                    it.request.set(bucket)
                }
        }


        // Sort the resource files by extension and size for a better distribution of files
        // between workers. Files of the same type will be distributed equally between the workers.
        // Large files of the same type will also be distributed equally between the workers.
        requests.sortWith(compareBy({ getExtension(it.inputFile) }, { it.inputFile.length() }))
        val buckets = minOf(requests.size, aapt2Input.maxAapt2Daemons.get())

        for (bucket in 0 until buckets) {
            val bucketRequests = requests.filterIndexed { i, _ ->
                i.rem(buckets) == bucket
            }
            // b/73804575
            workerExecutor.noIsolation().submit(
                Aapt2CompileRunnable::class.java
            ) {
//                it.initializeWith(projectPath = projectPath, taskOwner = taskOwner, analyticsService = analyticsService)
                it.aapt2Input.set(aapt2Input)
                it.requests.set(bucketRequests)
                it.enableBlame.set(true)
            }
        }
        requests.clear()

        // No need for workerExecutor.await() here as resource compilation is the last part of the
        // merge task. This means the MergeResources task action can return, allowing other tasks
        // in the same subproject to run while resources are still being compiled.
    }
}
