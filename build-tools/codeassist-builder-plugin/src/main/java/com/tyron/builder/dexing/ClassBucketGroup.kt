package com.tyron.builder.dexing

import java.io.File
import java.io.Serializable

/** A group of class files, which is split into a number of [ClassBucket]'s. */
sealed class ClassBucketGroup(val numOfBuckets: Int) : Serializable {

    /** Returns the roots of the class files, which could be directories or jars. */
    abstract fun getRoots(): List<File>

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** A group of all class files in some root directories. */
class DirectoryBucketGroup(

    /** The root directories of the class files. */
    private val rootDirs: List<File>,

    /** The number of buckets that this group is split into. */
    numOfBuckets: Int

) : ClassBucketGroup(numOfBuckets), Serializable {

    override fun getRoots() = rootDirs

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** A group of all class files in a jar. */
class JarBucketGroup(

    /** The jar file. It may have been removed. */
    val jarFile: File,

    /** The number of buckets that this group is split into. */
    numOfBuckets: Int

) : ClassBucketGroup(numOfBuckets), Serializable {

    init {
        check(isJarFile(jarFile))
    }

    override fun getRoots() = listOf(jarFile)

    companion object {
        private const val serialVersionUID = 1L
    }
}