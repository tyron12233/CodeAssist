package com.tyron.builder.gradle.internal.dependency

import com.google.common.collect.ImmutableSet
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.HashSet
import java.util.function.Consumer

/**
 * Implementation of a [ArtifactCollection] in order to do lazy subtractions.
 *
 * The main use case for this is building an ArtifactCollection that represents the packaged
 * dependencies of a test app, minus the runtime dependencies of the tested app (to avoid duplicated
 * classes during runtime).
 */
class SubtractingArtifactCollection(
    private val mainArtifacts: ArtifactCollection,
    private val removedArtifacts: ArtifactCollection,
    private val objectFactory: ObjectFactory
) : ArtifactCollection {

    private var subtractingArtifactResult = this.SubtractingArtifactResult()

    override fun getArtifactFiles() = subtractingArtifactResult.fileCollection.value

    override fun getArtifacts() = subtractingArtifactResult.artifactResults.value

    @Suppress("UnstableApiUsage")
    override fun getResolvedArtifacts(): Provider<MutableSet<ResolvedArtifactResult>> {
        return objectFactory.setProperty(ResolvedArtifactResult::class.java).map { artifacts }
    }

    override fun getFailures() = mainArtifacts.failures + removedArtifacts.failures

    override fun iterator() = subtractingArtifactResult.artifactResults.value.iterator()

    override fun forEach(action: Consumer<in ResolvedArtifactResult>) = artifacts.forEach(action)

    override fun spliterator() = artifacts.spliterator()

    override fun toString(): String {
        return "SubtractingArtifactCollection(mainArtifacts=$mainArtifacts, " +
                "removedArtifacts=$removedArtifacts)"
    }

    /**
     * Wrapper for the set of [ResolvedArtifactResult]. Gradle configuration caching cannot directly
     * serialize them, so we need to make Gradle not serialize them and instead compute them from
     * other de-serialized properties in the following configuration-cached runs.
     */
    inner class SubtractingArtifactResult : Serializable {
        @Transient
        var artifactResults: Lazy<MutableSet<ResolvedArtifactResult>> = lazy { initArtifactResult() }

        @Transient
        private var removedFiles: Lazy<Set<File>> = lazy { initRemovedFiles() }

        @Transient
        var fileCollection = lazy { initFileCollection() }

        /**
         * Just the [ComponentIdentifier] is not enough, we need to consider the classifier information
         * (see TestWithSameDepAsAppWithClassifier).
         *
         *  TODO(b/132924287): We should be able to instead pass the configuration and call
         *   `configuration.artifactView {  }.artifacts.artifacts`, but this gives artifacts with
         *   different names so the subtraction doesn't work
         *   (The classifier is actually duplicated in the name in some cases)
         */
        private fun initArtifactResult(): MutableSet<ResolvedArtifactResult> {
            val removed = HashSet<ComponentArtifactIdentifier>(removedArtifacts.artifacts.size)
            removedArtifacts.artifacts.mapTo(removed) { it.id }
            return ImmutableSet.copyOf(mainArtifacts.artifacts.filter { !removed.contains(it!!.id) })
        }

        /**
         * Because of http://b/187353303 and https://github.com/gradle/gradle/issues/17213, removed
         * artifact set is being computed. This is computed by adding:
         * - removed files
         * - resolved artifact's file if that resolved artifact is not in the final result: this is
         * needed as with desugaring the output location will be different if the classpath differs.
         */
        private fun initRemovedFiles() = ImmutableSet.builder<File>().apply {
            addAll(removedArtifacts.artifactFiles.files)

            mainArtifacts.artifacts.forEach {
                if (it !in artifactResults.value) {
                    add(it.file)
                }
            }
        }.build()

        private fun initFileCollection() = objectFactory.fileCollection().from(
            mainArtifacts.artifactFiles.filter { it !in removedFiles.value }
        ).builtBy(mainArtifacts.artifactFiles, removedArtifacts.artifactFiles)

        private fun writeObject(objectOutputStream: ObjectOutputStream) {
            objectOutputStream.defaultWriteObject()
        }

        private fun readObject(objectInputStream: ObjectInputStream) {
            objectInputStream.defaultReadObject()
            artifactResults = lazy { initArtifactResult() }
            removedFiles = lazy { initRemovedFiles() }
            fileCollection = lazy { initFileCollection() }
        }
    }
}
