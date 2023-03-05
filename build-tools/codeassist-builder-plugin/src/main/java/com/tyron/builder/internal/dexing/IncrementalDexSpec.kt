package com.tyron.builder.internal.dexing

import com.tyron.builder.dexing.ClassBucket
import java.io.File
import java.io.Serializable

/** Information required for incremental dexing. */
class IncrementalDexSpec(

    /** The input class files to dex. A class file could be a regular file or a jar entry. */
    val inputClassFiles: ClassBucket,

    /** The path to a directory or jar file containing output dex files. */
    val outputPath: File,

    /** Parameters for dexing. */
    val dexParams: DexParametersForWorkers,

    /** Whether incremental information is available. */
    val isIncremental: Boolean,

    /**
     * The set of all changed (removed, modified, added) files, including those in input files and
     * classpath.
     */
    val changedFiles: Set<File>,

    /**
     * The file containing the desugaring graph used to compute the set of impacted files, not
     * `null` iff desugaring is enabled.
     */
    val desugarGraphFile: File?

) : Serializable {

    init {
        check(dexParams.withDesugaring xor (desugarGraphFile == null))
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}