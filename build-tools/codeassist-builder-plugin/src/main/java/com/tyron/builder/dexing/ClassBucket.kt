package com.tyron.builder.dexing

import com.google.common.io.Closer
import java.io.File
import java.io.Serializable
import java.util.stream.Stream
import kotlin.math.abs

/**
 * A bucket of class files. Multiple buckets are split from a [ClassBucketGroup] using an internal
 * algorithm, and each one is identified by a bucket number.
 */
class ClassBucket(val bucketGroup: ClassBucketGroup, val bucketNumber: Int) :
    Serializable {

    /**
     * Returns a subset of the class files in this bucket, selected by the given filter.
     *
     * @param filter the filter to select a subset of the class files in this bucket
     * @param closer a [Closer] to register objects to close after the returned stream is finished
     */
    fun getClassFiles(filter: (File, String) -> Boolean, closer: Closer): Stream<ClassFileEntry> {
        var classFiles = Stream.empty<ClassFileEntry>()
        for (root in bucketGroup.getRoots()) {
            val classFileInput = ClassFileInputs.fromPath(root.toPath())
            closer.register(classFileInput)
            classFiles = Stream.concat(
                classFiles,
                classFileInput.entries { rootPath, relativePath ->
                    getBucketNumber(
                        relativePath,
                        bucketGroup.numOfBuckets,
                        bucketGroup is JarBucketGroup
                    ) == bucketNumber
                            && filter(rootPath.toFile(), relativePath)
                })
        }
        return classFiles
    }

    /** Returns the bucket number for a class file or jar entry having the given relative path. */
    private fun getBucketNumber(
        relativePath: String,
        numberOfBuckets: Int,
        isJarFile: Boolean
    ): Int {
        check(!File(relativePath).isAbsolute) {
            "Expected relative path but found absolute path: $relativePath"
        }

        val pathOfPackageOrClass = if (isJarFile) {
            // For an input jar, each bucket has a separate output jar. We group classes of the same
            // package into the same bucket, so that their corresponding dex files are put in the
            // same output jar. This is not required, but it makes the downstream DexMergingTask
            // more efficient (see `getBucketNumber` in DexMergingTask).
            File(relativePath).parent ?: ""
        } else {
            // For an input directory, all buckets share the same output directory, so grouping
            // classes by package has no effect on the output. We use relative paths instead to
            // distribute classes into buckets more evenly.
            relativePath
        }
        // Normalize the path so that it is stable across filesystems. (For jar entries, the paths
        // are already normalized.)
        val normalizedPath = File(pathOfPackageOrClass).invariantSeparatorsPath

        return abs(normalizedPath.hashCode()) % numberOfBuckets
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}