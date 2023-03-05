package com.tyron.builder.gradle.internal.dependency

import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.provider.Provider
import java.util.function.Consumer

/**
 * Implementation of a [ArtifactCollection] on top of a main collection, and a component
 * filter, coming from a list of files published by sub-modules as [InternalArtifactType.PACKAGED_DEPENDENCIES]

 *
 * The main use case for this is building an ArtifactCollection that represents the runtime
 * dependencies of a test app, minus the runtime dependencies of the tested app (to avoid duplicated
 * classes during runtime).
 */
class FilteredArtifactCollection(private val filteringSpec: FilteringSpec) : ArtifactCollection {
    override fun getArtifactFiles() = filteringSpec.getFilteredFileCollection()
    override fun getArtifacts() = filteringSpec.getArtifactFiles()
    override fun getFailures(): Collection<Throwable> = filteringSpec.artifacts.failures
    override fun iterator() = artifacts.iterator()
    override fun spliterator() = artifacts.spliterator()
    override fun forEach(action: Consumer<in ResolvedArtifactResult>) = artifacts.forEach(action)
    @Suppress("UnstableApiUsage")
    override fun getResolvedArtifacts(): Provider<Set<ResolvedArtifactResult>>
    = filteringSpec.getResolvedArtifacts()
}
