package dev.ide.vfs

import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import dev.ide.platform.Topic
import dev.ide.platform.deliverToAll

/**
 * vfs-api — a single, observable view of files for the whole IDE.
 *
 * The model, indices, language backends, and the build engine all read files through this and
 * observe ONE ordered stream of [VfsEvent]s. That single stream is the backbone of
 * "handle file modifications": editor edits, external changes, and generated outputs all surface
 * the same way.
 */
interface VirtualFileSystem {
    fun findByPath(path: String): VirtualFile?
    fun root(): VirtualFile

    /** Begin watching a subtree; dispose the returned [FileWatch] to stop. */
    fun watch(root: VirtualFile): FileWatch
}

interface FileWatch : Disposable

/**
 * File change notifications, published on the message bus AFTER being committed under the
 * appropriate lock, in a deterministic order. Consumers: build engine (mark dependent tasks
 * stale), indexer, language backends (invalidate cached ASTs/bindings).
 */
sealed interface VfsEvent {
    val file: VirtualFile
}

data class FileCreated(override val file: VirtualFile) : VfsEvent
data class FileDeleted(override val file: VirtualFile) : VfsEvent
data class FileChanged(
    override val file: VirtualFile,
    val oldHash: ContentHash,
    val newHash: ContentHash,
) : VfsEvent
data class FileMoved(override val file: VirtualFile, val from: String, val to: String) : VfsEvent

/** Listener interface for VFS events. `fun interface` so a topic's fan-out can be written as a lambda. */
fun interface VfsListener {
    fun onEvents(events: List<VfsEvent>)
}

object VfsTopics {
    val CHANGES: Topic<VfsListener> = Topic("vfs.changes") { listeners ->
        VfsListener { events -> deliverToAll(listeners) { it.onEvents(events) } }
    }
}
