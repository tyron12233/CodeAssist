package com.tyron.builder.internal.dexing

import com.google.common.io.Closer
import com.android.ide.common.blame.MessageReceiver
import com.tyron.builder.dexing.*
import com.tyron.builder.gradle.errors.MessageReceiverImpl
import com.tyron.builder.gradle.internal.tasks.DexArchiveBuilderTaskDelegate
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.util.internal.GFileUtils
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** Work action to process a bucket of class files. */
abstract class DexWorkAction : WorkAction<DexWorkActionParams> {

    override fun execute() {
        try {
            launchProcessing(
                parameters,
                MessageReceiverImpl(
                    parameters.dexSpec.get().dexParams.errorFormatMode,
                    Logging.getLogger(DexArchiveBuilderTaskDelegate::class.java)
                )
            )
        } catch (e: Exception) {
            throw GradleException(e.message!!, e)
        }
    }
}

/** Parameters for running [DexWorkAction]. */
abstract class DexWorkActionParams: WorkParameters {
    abstract val dexSpec: Property<IncrementalDexSpec>
}

fun launchProcessing(
    dexWorkActionParams: DexWorkActionParams,
    receiver: MessageReceiver
) {
    val dexArchiveBuilder = getDexArchiveBuilder(
        dexWorkActionParams, receiver
    )
    if (dexWorkActionParams.dexSpec.get().isIncremental) {
        processIncrementally(dexArchiveBuilder, dexWorkActionParams)
    } else {
        processNonIncrementally(dexArchiveBuilder, dexWorkActionParams)
    }
}

private fun processIncrementally(
    dexArchiveBuilder: DexArchiveBuilder,
    dexWorkActionParams: DexWorkActionParams
) {
    with(dexWorkActionParams.dexSpec.get()) {
        val desugarGraph = desugarGraphFile?.let {
            try {
                readDesugarGraph(desugarGraphFile)
            } catch (e: Exception) {
                loggerWrapper.warn(
                    "Failed to read desugaring graph." +
                            " Cause: ${e.javaClass.simpleName}, message: ${e.message}.\n" +
                            "Fall back to non-incremental mode."
                )
                processNonIncrementally(dexArchiveBuilder, dexWorkActionParams)
                return@processIncrementally
            }
        }

        // Compute impacted files based on the changed files and the desugaring graph (if
        // desugaring is enabled)
        val unchangedButImpactedFiles = desugarGraph?.getAllDependents(changedFiles) ?: emptySet()
        val changedOrImpactedFiles = changedFiles + unchangedButImpactedFiles

        // Remove stale nodes in the desugaring graph (stale dex outputs have been removed earlier
        // before the workers are launched)
        desugarGraph?.let { graph ->
            // Note that the `changedOrImpactedFiles` set may contain added files, which should not
            // exist in the graph and will be ignored.
            changedOrImpactedFiles.forEach { graph.removeNode(it) }
        }

        // Process only input files that are modified, added, or unchanged-but-impacted
        val filter: (File, String) -> Boolean = { rootPath: File, relativePath: String ->
            // Note that the `changedOrImpactedFiles` set may contain removed files, but those files
            // will not not be selected as candidates in the process() method and therefore will not
            // make it to this filter.
            rootPath in changedOrImpactedFiles /* for jars (we don't track class files in jars) */ ||
                    rootPath.resolve(relativePath) in changedOrImpactedFiles /* for class files in dirs */
        }
        process(
            dexArchiveBuilder = dexArchiveBuilder,
            inputClassFiles = inputClassFiles,
            inputFilter = filter,
            outputPath = outputPath,
            desugarGraphUpdater = desugarGraph
        )

        // Store the desugaring graph for use in the next build. If dexing failed earlier, it is
        // intended that we will not store the graph as the graph is only meant to contain info
        // about a previous successful build.
        desugarGraphFile?.let {
            writeDesugarGraph(it, desugarGraph!!)
        }
    }
}

private fun processNonIncrementally(
    dexArchiveBuilder: DexArchiveBuilder,
    dexWorkActionParams: DexWorkActionParams
) {
    // Dex outputs have been removed earlier before the workers are launched)

    with(dexWorkActionParams.dexSpec.get()) {
        val desugarGraph = desugarGraphFile?.let {
            MutableDependencyGraph<File>()
        }

        process(
            dexArchiveBuilder = dexArchiveBuilder,
            inputClassFiles = inputClassFiles,
            inputFilter = { _, _ -> true },
            outputPath = outputPath,
            desugarGraphUpdater = desugarGraph
        )

        // Store the desugaring graph for use in the next build. If dexing failed earlier, it is
        // intended that we will not store the graph as the graph is only meant to contain info
        // about a previous successful build.
        desugarGraphFile?.let {
            GFileUtils.mkdirs(it.parentFile)
            writeDesugarGraph(it, desugarGraph!!)
        }
    }
}

private fun process(
    dexArchiveBuilder: DexArchiveBuilder,
    inputClassFiles: ClassBucket,
    inputFilter: (File, String) -> Boolean,
    outputPath: File,
    desugarGraphUpdater: DependencyGraphUpdater<File>?
) {
    val inputRoots = inputClassFiles.bucketGroup.getRoots()
    inputRoots.forEach { loggerWrapper.debug("Dexing '${it.path}' to '${outputPath.path}'") }
    try {
        Closer.create().use { closer ->
            inputClassFiles.getClassFiles(filter = inputFilter, closer = closer).use {
                dexArchiveBuilder.convert(it, outputPath.toPath(), desugarGraphUpdater)
            }
        }
    } catch (ex: DexArchiveBuilderException) {
        throw DexArchiveBuilderException(
            "Failed to process: ${inputRoots.joinToString(", ") { it.path }}",
            ex
        )
    }
}

private fun getDexArchiveBuilder(
    dexWorkActionParams: DexWorkActionParams,
    messageReceiver: MessageReceiver
): DexArchiveBuilder {
    val dexArchiveBuilder: DexArchiveBuilder
    with(dexWorkActionParams) {
        val dexSpec = dexSpec.get()
        dexArchiveBuilder = DexArchiveBuilder.createD8DexBuilder(
            com.tyron.builder.dexing.DexParameters(
                minSdkVersion = dexSpec.dexParams.minSdkVersion,
                debuggable = dexSpec.dexParams.debuggable,
                dexPerClass = dexSpec.dexParams.dexPerClass,
                withDesugaring = dexSpec.dexParams.withDesugaring,
                desugarBootclasspath =
                DexArchiveBuilderTaskDelegate.sharedState.getService(dexSpec.dexParams.desugarBootclasspath).service,
                desugarClasspath =
                DexArchiveBuilderTaskDelegate.sharedState.getService(dexSpec.dexParams.desugarClasspath).service,
                coreLibDesugarConfig = dexSpec.dexParams.coreLibDesugarConfig,
                coreLibDesugarOutputKeepRuleFile =
                dexSpec.dexParams.coreLibDesugarOutputKeepRuleFile,
                messageReceiver = messageReceiver
            )
        )
    }
    return dexArchiveBuilder
}

fun readDesugarGraph(desugarGraphFile: File): MutableDependencyGraph<File> {
    return ObjectInputStream(FileInputStream(desugarGraphFile).buffered()).use {
        @Suppress("UNCHECKED_CAST")
        it.readObject() as MutableDependencyGraph<File>
    }
}

fun writeDesugarGraph(desugarGraphFile: File, desugarGraph: MutableDependencyGraph<File>) {
    ObjectOutputStream(FileOutputStream(desugarGraphFile).buffered()).use {
        it.writeObject(desugarGraph)
    }
}

private val loggerWrapper = Logging.getLogger(DexWorkAction::class.java)