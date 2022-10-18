package com.tyron.builder.common.resources

import com.android.ide.common.blame.SourceFilePosition
import com.android.ide.common.blame.SourcePosition
import java.io.File
import java.io.Serializable

/** A request for Aapt2 compile / ResourceCompiler.  */
class CompileResourceRequest @JvmOverloads constructor(
    val inputFile: File,
    val outputDirectory: File,
    val inputDirectoryName: String = inputFile.parentFile.name,
    /**
     * Whether the resource comes from a dependency or from the current subproject, or `null` if
     * this information is not available.
     */
    val inputFileIsFromDependency: Boolean? = null,
    val isPseudoLocalize: Boolean = false,
    val isPngCrunching: Boolean = true,
    /** The map of where values came from, so errors are reported correctly. */
    val blameMap: Map<SourcePosition, SourceFilePosition> = mapOf(),
    /** The original source file. For data binding, so errors are reported correctly */
    val originalInputFile: File = inputFile,
    val partialRFile: File? = null,
    /**
     * The folder containing blame logs of where values came from, so errors are reported correctly
     * This should be used in case the folder contents aren't already loaded in memory, otherwise
     * use [blameMap]
     */
    val mergeBlameFolder: File? = null,
    /** Map of source set identifier to absolute path Used for determining relative sourcePath. */
    var identifiedSourceSetMap: Map<String, String> = emptyMap()
) : Serializable {
    val sourcePath : String by lazy {
         if (identifiedSourceSetMap.any()) {
             getRelativeSourceSetPath(inputFile, identifiedSourceSetMap)
         } else {
             inputFile.absolutePath
         }
    }

    fun useRelativeSourcePath(moduleIdentifiedSourceSets: Map<String, String>) {
        identifiedSourceSetMap = moduleIdentifiedSourceSets
    }
}