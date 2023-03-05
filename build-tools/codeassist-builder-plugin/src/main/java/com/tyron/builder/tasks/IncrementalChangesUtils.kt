package com.tyron.builder.tasks

import com.android.ide.common.resources.FileStatus
import com.google.common.collect.ImmutableList
import com.tyron.builder.files.SerializableChange
import com.tyron.builder.files.SerializableFileChanges
import com.tyron.builder.files.SerializableInputChanges
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.FileType
import org.gradle.api.provider.Provider
import org.gradle.work.ChangeType
import org.gradle.work.FileChange
import org.gradle.work.InputChanges
import java.util.*


/**
 * Convert Gradle incremental changes to a serializable form for the worker API.
 *
 * This method ignores directory changes.
 */
fun InputChanges.getChangesInSerializableForm(input: Provider<out FileSystemLocation>): SerializableInputChanges {
    return SerializableInputChanges(
        roots = ImmutableList.of(input.get().asFile),
        changes = convert(getFileChanges(input))
    )
}

/**
 * Convert Gradle incremental changes to a serializable form for the worker API.
 *
 * This method ignores directory changes.
 */
fun InputChanges.getChangesInSerializableForm(input: FileCollection): SerializableInputChanges {
    return SerializableInputChanges(
        roots = ImmutableList.copyOf(input.files),
        changes = convert(getFileChanges(input))
    )
}

private fun convert(changes: Iterable<FileChange>): Collection<SerializableChange> {
    return Collections.unmodifiableCollection(ArrayList<SerializableChange>().also { collection ->
        for (change in changes) {
            if (change.fileType == FileType.FILE) {
                collection.add(change.toSerializable())
            }
        }
    })
}

fun ChangeType.toSerializable(): FileStatus = when (this) {
    ChangeType.ADDED -> FileStatus.NEW
    ChangeType.MODIFIED -> FileStatus.CHANGED
    ChangeType.REMOVED -> FileStatus.REMOVED
}

fun FileChange.toSerializable(): SerializableChange {
    return SerializableChange(file, changeType.toSerializable(), normalizedPath)
}

fun Iterable<FileChange>.toSerializable(): SerializableFileChanges {
    return SerializableFileChanges(this.map { it.toSerializable() })
}