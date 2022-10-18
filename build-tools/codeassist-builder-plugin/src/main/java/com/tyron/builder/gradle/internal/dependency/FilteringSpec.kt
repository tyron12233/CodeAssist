package com.tyron.builder.gradle.internal.dependency

import com.tyron.builder.gradle.internal.tasks.featuresplit.toIdString
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * Implementation of [Spec] to filter out directories from a [FileCollection]
 *
 * @param artifacts the [ArtifactCollection] containing the elements to be filtered
 * @param excludedDirectoryFiles [FileCollection] containing text files which content are a list
 * of filtered artifacts.
 */
class FilteringSpec(
    internal val artifacts: ArtifactCollection,
    private val excludedDirectoryFiles: FileCollection,
    private val objectFactory: ObjectFactory
) : Spec<File>, Serializable {

    @Transient
    private var excluded: Lazy<Set<String>> = lazy { initExcluded() }

    @Transient
    private var filteredFileCollection: Lazy<FileCollection> = lazy { initFilteredFileCollection() }

    /** Keep lazy, as invoking getArtifacts() is quite costly with configuration caching. */
    @Transient
    private var filteredArtifacts = lazy { initFilteredArtifacts() }

    override fun isSatisfiedBy(file: File): Boolean {
        if (excluded.value.isEmpty()) return true
        return filteredArtifacts.value.any { it.file.path == file.path }
    }

    private fun initExcluded(): Set<String> = excludedDirectoryFiles.files.asSequence()
        .filter { it.isFile }
        .flatMapTo(HashSet()) { it.readLines(Charsets.UTF_8).asSequence() }

    private fun initFilteredFileCollection() =
        objectFactory.fileCollection().from(artifacts.artifactFiles.filter(this))
            .builtBy(artifacts.artifactFiles.buildDependencies)
            .builtBy(excludedDirectoryFiles.buildDependencies)

    private fun initFilteredArtifacts() = artifacts.artifacts.asSequence()
        .filter { !excluded.value.contains(it.toIdString()) }
        .toMutableSet()

    // Returns a MutableSet as FilteredArtifactCollection#getIterator expects this to be mutable to
    // returns a mutable iterator.
    fun getArtifactFiles(): MutableSet<ResolvedArtifactResult> = filteredArtifacts.value

    fun getResolvedArtifacts(): Provider<Set<ResolvedArtifactResult>> =
        objectFactory.setProperty(ResolvedArtifactResult::class.java).map { getArtifactFiles() }

    fun getFilteredFileCollection(): FileCollection = filteredFileCollection.value

    private fun writeObject(objectOutputStream: ObjectOutputStream) {
        objectOutputStream.defaultWriteObject()
    }

    private fun readObject(objectInputStream: ObjectInputStream) {
        objectInputStream.defaultReadObject()
        excluded = lazy { initExcluded() }
        filteredFileCollection = lazy { initFilteredFileCollection() }
        filteredArtifacts = lazy { initFilteredArtifacts() }
    }
}