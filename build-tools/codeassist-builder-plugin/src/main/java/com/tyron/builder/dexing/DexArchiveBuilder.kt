package com.tyron.builder.dexing

import java.io.File
import java.nio.file.Path
import java.util.stream.Stream

/**
 * An abstract dex archive builder that converts input class files to dex files that are written to
 * dex archive. This class contains the logic for reading the class files from the input,
 * [ClassFileInput], and writing the output to a [DexArchive]. Implementation of conversion from the
 * class files to dex files is left to the sub-classes. To trigger the conversion, create an
 * instance of this class, and invoke [convert].
 */
abstract class DexArchiveBuilder {

    /**
     * Converts the specified input, and writes it to the output dex archive. If dex archive does
     * not exist, it will be created. If it exists, entries will be added or replaced.
     *
     * @param input a [Stream] of input class files
     * @param output the path to the directory or jar containing output dex files
     * @param desugarGraphUpdater the dependency graph for desugaring to be updated. It could be
     *     `null` if the dependency graph is not required or is computed by the Android Gradle
     *     plugin.
     */
    @Throws(DexArchiveBuilderException::class)
    abstract fun convert(
        input: Stream<ClassFileEntry>,
        output: Path,
        desugarGraphUpdater: DependencyGraphUpdater<File>? = null
    )

    companion object {

        /** Creates an instance that is using d8 to convert class files to dex files.  */
        @JvmStatic
        fun createD8DexBuilder(dexParams: DexParameters): DexArchiveBuilder {
            return D8DexArchiveBuilder(dexParams)
        }
    }
}