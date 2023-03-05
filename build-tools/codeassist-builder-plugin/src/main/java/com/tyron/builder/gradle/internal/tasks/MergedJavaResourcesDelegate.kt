package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.gradle.internal.InternalScope
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.packaging.PackagingFileAction
import com.tyron.builder.gradle.internal.packaging.ParsedPackagingOptions
import com.tyron.builder.merge.DelegateIncrementalFileMergerOutput
import com.tyron.builder.merge.FilterIncrementalFileMergerInput
import com.tyron.builder.merge.IncrementalFileMerger
import com.tyron.builder.merge.IncrementalFileMergerInput
import com.tyron.builder.merge.IncrementalFileMergerOutputs
import com.tyron.builder.merge.IncrementalFileMergerState
import com.tyron.builder.merge.MergeOutputWriters
import com.tyron.builder.merge.StreamMergeAlgorithms
import com.tyron.builder.packaging.PackagingUtils
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.android.utils.FileUtils
import com.google.common.collect.ImmutableList
import org.gradle.api.logging.Logging
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.function.Predicate

@Suppress("DEPRECATION") // Legacy support
private fun isHighPriorityScope(scope: com.tyron.builder.api.transform.QualifiedContent.ScopeType?): Boolean {
    return scope == com.tyron.builder.api.transform.QualifiedContent.Scope.PROJECT || scope == InternalScope.FEATURES
}

/**
 * A delegate which actually does the merging of java resources, for example for the
 * [MergeJavaResourceTask]
 */
class MergeJavaResourcesDelegate(
    inputs: List<IncrementalFileMergerInput>,
    private val outputFile: File,
    @Suppress("DEPRECATION") // Legacy support
    private val scopeMap: MutableMap<IncrementalFileMergerInput, com.tyron.builder.api.transform.QualifiedContent.ScopeType>,
    private val packagingOptions: ParsedPackagingOptions,
    private val incrementalStateFile: File,
    private val isIncremental: Boolean,
    private val noCompress: Collection<String>
) {

    private var inputs: MutableList<IncrementalFileMergerInput>
    private val acceptedPathsPredicate: Predicate<String> = MergeJavaResourceTask.predicate

    init {
        this.inputs = inputs.toMutableList()
    }

    /**
     * Returns the incremental state.
     *
     * @throws IOException if fails to load the incremental state
     */
    private fun loadMergeState(): IncrementalFileMergerState {
        if (!incrementalStateFile.isFile || !isIncremental) {
            return IncrementalFileMergerState()
        }
        try {
            ObjectInputStream(FileInputStream(incrementalStateFile)).use {
                return it.readObject() as IncrementalFileMergerState
            }
        } catch (e: ClassNotFoundException) {
            throw IOException(e)
        }
    }

    /**
     * Save the incremental merge state.
     *
     * @param state the incremental file merger state
     * @throws IOException if fails to save the state
     */
    private fun saveMergeState(state: IncrementalFileMergerState) {
        FileUtils.mkdirs(incrementalStateFile.parentFile)
        ObjectOutputStream(FileOutputStream(incrementalStateFile)).use { it.writeObject(state) }
    }

    fun run() {

        /*
         * In an ideal world, we could just send the inputs to the file merger. However, in the
         * real world, things are more complicated :)
         *
         * We need to:
         *
         * 1. Bring inputs that refer to the project scope before the other inputs.
         * 2. Filter all inputs to remove anything not accepted by acceptedPathsPredicate or
         * by packagingOptions.
         */

        // Sort inputs to move project scopes to the start.
        @Suppress("DEPRECATION") // Legacy support
        inputs.sortBy { if (scopeMap[it] == com.tyron.builder.api.transform.QualifiedContent.Scope.PROJECT) 0 else 1 }

        // Filter inputs.
        val inputFilter =
            acceptedPathsPredicate.and { path -> packagingOptions.getAction(path) != PackagingFileAction.EXCLUDE }
        inputs =
                inputs.map {
                    val filteredInput = FilterIncrementalFileMergerInput(it, inputFilter)
                    scopeMap[filteredInput] = scopeMap[it]!!
                    filteredInput
                }.toMutableList()

        /*
         * Create the algorithm used by the merge transform. This algorithm decides on which
         * algorithm to delegate to depending on the packaging option of the path. By default it
         * requires just one file (no merging).
         */
        val mergeTransformAlgorithm = StreamMergeAlgorithms.select { path ->
            val packagingAction = packagingOptions.getAction(path)
            when (packagingAction) {
                PackagingFileAction.EXCLUDE ->
                    // Should have been excluded from the input.
                    throw AssertionError()
                PackagingFileAction.PICK_FIRST -> return@select StreamMergeAlgorithms.pickFirst()
                PackagingFileAction.MERGE -> return@select StreamMergeAlgorithms.concat()
                PackagingFileAction.NONE -> return@select StreamMergeAlgorithms.acceptOnlyOne()
                else -> throw AssertionError()
            }
        }

        /*
         * Create an output that uses the algorithm. This is not the final output because,
         * unfortunately, we still have the complexity of the project scope overriding other scopes
         * to solve.
         *
         * When resources inside a jar file are extracted to a directory, the results may not be
         * expected on Windows if the file names end with "." (bug 65337573), or if there is an
         * uppercase/lowercase conflict. To work around this issue, we copy these resources to a
         * jar file.
         */
        val baseOutput =
            IncrementalFileMergerOutputs.fromAlgorithmAndWriter(
                mergeTransformAlgorithm,
                MergeOutputWriters.toZip(
                    outputFile,
                    // Erase timestamps of zip entries for better cacheability (see bug 142890134)
                    ZFileOptions().also { it.noTimestamps = true }
                )
            )

        /*
         * We need a custom output to handle the case in which the same path appears in multiple
         * inputs and the action is NONE, but only one input is actually PROJECT or FEATURES. In
         * this specific case we will ignore all other inputs.
         */
        val highPriorityInputs =
            scopeMap.keys.filter { isHighPriorityScope(scopeMap[it]) }.toSet()

        val output = object : DelegateIncrementalFileMergerOutput(baseOutput) {
            override fun create(
                path: String,
                inputs: List<IncrementalFileMergerInput>,
                compress: Boolean
            ) {
                super.create(path, filter(path, inputs), compress)
            }

            override fun update(
                path: String,
                prevInputNames: List<String>,
                inputs: List<IncrementalFileMergerInput>,
                compress: Boolean
            ) {
                super.update(path, prevInputNames, filter(path, inputs), compress)
            }

            private fun filter(
                path: String,
                inputs: List<IncrementalFileMergerInput>
            ): ImmutableList<IncrementalFileMergerInput> {
                val packagingAction = packagingOptions.getAction(path)
                val shouldFilterInputs =
                    packagingAction == PackagingFileAction.NONE &&
                            inputs.any { highPriorityInputs.contains(it) }
                return if (shouldFilterInputs) {
                    // Warn if filtering out "low priority inputs" resolves collisions. Future
                    // AGP versions will not do this filtering and will result in an error instead.
                    // See Issue 141758241.
                    val filteredInputs =
                        ImmutableList.copyOf(inputs.filter { highPriorityInputs.contains(it) })
                    if (filteredInputs.size < inputs.size) {
                        val logger =
                            LoggerWrapper(Logging.getLogger(MergeJavaResourcesDelegate::class.java))
                        logger.warning(
                            "More than one file was found with OS independent path '$path'. "
                                    + "This version of the Android Gradle Plugin chooses the file "
                                    + "from the app or dynamic-feature module, but this can cause "
                                    + "unexpected behavior or errors at runtime. Future versions "
                                    + "of the Android Gradle Plugin will throw an error in this "
                                    + "case."
                        )
                    }
                    filteredInputs
                } else {
                    ImmutableList.copyOf(inputs)
                }
            }
        }

        saveMergeState(
            IncrementalFileMerger.merge(
                inputs.toList(),
                output,
                loadMergeState(),
                PackagingUtils.getNoCompressPredicateForJavaRes(noCompress)
            )
        )
    }
}
