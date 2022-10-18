package com.tyron.builder.api.artifact.impl

import com.tyron.builder.api.artifact.Artifact
import com.tyron.builder.api.artifact.ArtifactKind
import org.gradle.api.file.Directory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import java.lang.RuntimeException

class StorageProviderImpl {

    fun lock() {
        fileStorage.lock()
        directory.lock()
    }

    private val fileStorage = TypedStorageProvider<RegularFile> {
        objectFactory -> objectFactory.fileProperty()
    }
    private val directory= TypedStorageProvider<Directory> {
        objectFactory -> objectFactory.directoryProperty()
    }

    fun <T: FileSystemLocation> getStorage(artifactKind: ArtifactKind<out T>): TypedStorageProvider<T> {
        @Suppress("Unchecked_cast")
        return when(artifactKind) {
            ArtifactKind.FILE -> fileStorage
            ArtifactKind.DIRECTORY -> directory
            else -> throw RuntimeException("Cannot handle $this")
        } as TypedStorageProvider<T>
    }
}

class TypedStorageProvider<T :FileSystemLocation>(private val propertyAllocator: (ObjectFactory) -> Property<T>) {
    private val singleStorage= mutableMapOf<Artifact.Single<*>,  SingleArtifactContainer<T>>()
    private val multipleStorage=  mutableMapOf<Artifact.Multiple<*>,  MultipleArtifactContainer<T>>()

    @Synchronized
    internal fun getArtifact(
        objects: ObjectFactory,
        artifactType: Artifact.Single<T>
    ): SingleArtifactContainer<T> {

        return singleStorage.getOrPut(artifactType) {
            SingleArtifactContainer { SinglePropertyAdapter(propertyAllocator(objects)) }
        }
    }

    internal fun getArtifact(
        objects: ObjectFactory,
        artifactType: Artifact.Multiple<T>
    ): MultipleArtifactContainer<T> {
        return multipleStorage.getOrPut(artifactType) {
            MultipleArtifactContainer<T> {
                MultiplePropertyAdapter(
                    objects.listProperty(artifactType.kind.dataType().java))
            }
        }
    }

    internal fun copy(type: Artifact.Single<T>,
        container: SingleArtifactContainer<T>) {
        // if the target container is null, we can just override with the source container
        // however, if it is not null, which mean that is has been queried, we cannot just
        // override. In that case, we need to just link the source to the target.
        if (singleStorage[type] == null)
            singleStorage[type] = container
        else singleStorage[type]?.transferFrom(container)
    }

    internal fun copy(type: Artifact.Multiple<T>,
        container: MultipleArtifactContainer<T>) {
        // if the target container is null, we can just override with the source container
        // however, if it is not null, which mean that is has been queried, we cannot just
        // override. In that case, we need to just link the source to the target.
        if (multipleStorage[type] == null)
            multipleStorage[type] = container
        else multipleStorage[type]?.addInitialProvider(container)
    }

    fun lock() {
        singleStorage.values.forEach { it.disallowChanges() }
        multipleStorage.values.forEach { it.disallowChanges() }
    }
}