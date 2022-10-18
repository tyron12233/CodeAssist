package com.tyron.builder.api.artifact.impl

import com.google.common.annotations.VisibleForTesting
import com.tyron.builder.api.artifact.Artifact
import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Common behaviors for a container of an artifact that gets published within the
 * project scope.
 *
 * The container will provide methods to set, get the artifact. The artifact type is
 * always either a [org.gradle.api.file.RegularFile] or a [org.gradle.api.file.Directory].
 * An artifact cardinality is either single or multiple elements of the above type.
 *
 * @param T the artifact type as [FileSystemLocation] subtype for single element artifact
 * or a [List] of the same [FileSystemLocation] subtype for multiple elements artifact.
 */
internal abstract class ArtifactContainer<T, U>(private val allocator: () -> U) where U: PropertyAdapter<T> {

    // this represents the current provider(s) for the artifact.
    internal var current = allocator()

    // property that can be used to inject into consumers of the artifact at any time.
    // it will always represent the final value.
    val final = allocator()

    private val needInitialProducer = AtomicBoolean(true)
    private val hasCustomTransformers = AtomicBoolean(false)

    // although it is not technically necessary to keep a reference on the [TaskProvider] that
    // is currently registered as the task owning the current provider, it is useful to
    // automatically set dependencies based on the [InternalArtifactType.finalizingArtifact]
    // annotation attribute.
    protected val currentTaskProviders = mutableListOf<TaskProvider<*>>()

    // task provider to detect final one in chain of transformers
    private var finalTaskProvider:TaskProvider<*>? = null

    fun getFinalProvider() = finalTaskProvider

    /**
     * If another [org.gradle.api.Task] is replacing the initial providers through the
     * Variant API, it is interesting to determine that initial providers are useless since replaced
     * and therefore should probably not even be configured.
     *
     * @return true if the AGP providers will be used when the artifact becomes resolved.
     */
    fun needInitialProducer() = needInitialProducer

    /**
     * @return the final version of the artifact with associated providers to build it.
     */
    fun get(): Provider<T> = final.get()

    fun getTaskProviders(): List<TaskProvider<*>> =
        currentTaskProviders

    /**
     * Returns a protected (no changes allowed) version of the current artifact providers.
     *
     * @return the current version of the artifact providers at the time this call is made.
     * This is very useful for [org.gradle.api.Task] that want to transform an artifact and need the
     * current version as its input while producing the final version.
     */
    @VisibleForTesting
    internal fun getCurrent(): Provider<T>  {
        val property = allocator()
        property.from(current)
        property.disallowChanges()
        return property.get()
    }

    /**
     * Replace the current producer(s). Previous producers may still be used to produce [with]
     *
     * @param taskProvider the task provider for the task producing the [with]. Mainly provided for
     * bookkeeping reasons, not strictly required for wiring.
     * @param with the provider that will be the transformed artifact.
     * @return the current provider for the artifact (which will be transformed)
     */
    @Synchronized
    open fun transform(taskProvider: TaskProvider<*>, with: Provider<T>): Provider<T> {
        hasCustomTransformers.set(true)
        val oldCurrent = current
        current = allocator()
        current.set(with)
        currentTaskProviders.clear()
        currentTaskProviders.add(taskProvider)
        finalTaskProvider = taskProvider
        final.from(current)
        return oldCurrent.get()
    }

    /**
     * Replace the current producer(s) for this artifact with a new one.
     *
     * @param taskProvider the task provider for the task producing the [with]. Mainly provided for
     * bookkeeping reasons, not strictly required for wiring.
     * @param with the provider that will be the transformed artifact.
     */
    @Synchronized
    open fun replace(taskProvider: TaskProvider<*>, with: Provider<T>) {
        needInitialProducer.set(false)
        transform(taskProvider, with)
    }

    /**
     * Locks this artifact for any further changes.
     */
    open fun disallowChanges() {
        current.disallowChanges()
        final.disallowChanges()
    }

    /**
     * Returns true if at least one custom provider is registered for this artifact.
     */
    fun hasCustomProviders(): Boolean {
        return hasCustomTransformers.get()
    }
}

/**
 * Specialization of [ArtifactContainer] for single elements of [FileSystemLocation]
 *
 * @param T the single element type, either [org.gradle.api.file.RegularFile] or
 * [org.gradle.api.file.Directory]
 */
internal class SingleArtifactContainer<T: FileSystemLocation>(
    val allocator: () -> SinglePropertyAdapter<T>
) : ArtifactContainer<T, SinglePropertyAdapter<T>>(allocator) {

    private val agpProducer= allocator()

    init {
        current.from(agpProducer)
        final.from(current)
    }

    var finalFilename: Property<String>? = null
    var buildOutputLocation: Property<Directory>? = null

    fun initOutputs(finalFilenameProperty: Property<String>,
        buildOutputDirectory: Property<Directory>){
        finalFilename = finalFilenameProperty
        buildOutputLocation = buildOutputDirectory
    }

    /**
     * Specific hook for AGP providers to register the initial producer of the artifact.
     *
     * @param taskProvider the task provider for the task producing the [with]. Mainly provided for
     * bookkeeping reasons, not strictly required for wiring.
     * @param with the provider that will be the transformed artifact.
     */
    fun setInitialProvider(taskProvider: TaskProvider<*>, with: Provider<T>) {
        // TODO: should we make this an assertion.
        if (needInitialProducer().compareAndSet(true, false)) {
            agpProducer.set(with)
            currentTaskProviders.add(taskProvider)
        }
    }

    /**
     * Copies the provider from another [ArtifactContainer]
     */
    fun transferFrom(from: SingleArtifactContainer<T>) {
        if (needInitialProducer().compareAndSet(true, false)) {
            agpProducer.set(from.final.get())
            currentTaskProviders.addAll(from.getTaskProviders())
        }
    }

    override fun disallowChanges() {
        super.disallowChanges()
        agpProducer.disallowChanges()
    }
}
/**
 * Specialization of [ArtifactContainer] for multiple elements of [FileSystemLocation]
 *
 * @param T the multiple elements type, either [org.gradle.api.file.RegularFile] or
 * [org.gradle.api.file.Directory]
 */
internal class MultipleArtifactContainer<T: FileSystemLocation>(
    val allocator: () -> MultiplePropertyAdapter<T>
):
    ArtifactContainer<List<T>, MultiplePropertyAdapter<T>>(allocator) {

    // this represents the providers from the AGP.
    private val agpProducers = allocator()

    init {
        current.from(agpProducers)
        final.from(current)
    }

    fun addInitialProvider(taskProvider: List<TaskProvider<*>>, with: Provider<List<T>>) {
        needInitialProducer().set(false)
        // in theory, we should add those first ?
        agpProducers.addAll(with)
        currentTaskProviders.addAll(taskProvider)
    }

    fun addInitialProvider(from: MultipleArtifactContainer<T>) {
        needInitialProducer().set(false)
        agpProducers.addAll(from.final.get())
        currentTaskProviders.addAll(from.getTaskProviders())
    }

    fun addInitialProvider(taskProvider: TaskProvider<*>?, item: Provider<T>) {
        needInitialProducer().set(false)
        agpProducers.add(item)
        taskProvider?.let {
            currentTaskProviders.add(it)
        }
    }

    fun transferFrom(source: ArtifactsImpl, from: Artifact.Single<T>) {
        needInitialProducer().set(false)
        source.getArtifactContainer(from).let { artifactContainer ->
            agpProducers.add(artifactContainer.get())
            currentTaskProviders.addAll(artifactContainer.getTaskProviders())
        }
    }

    override fun disallowChanges() {
        super.disallowChanges()
        agpProducers.disallowChanges()
    }
}
