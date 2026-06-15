package dev.ide.vfs

import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import dev.ide.platform.Topic

/**
 * vfs-api — a single, observable view of files for the whole IDE.
 *
 * The model, indices, language backends, and the build engine all read files through this and
 * observe ONE ordered stream of [VfsEvent]s. That single stream is the backbone of
 * "handle file modifications": editor edits, external changes, and generated outputs all surface
 * the same way.
 */
interface VirtualFile {
    val path: String
    val name: String
    val isDirectory: Boolean
    val exists: Boolean
    val length: Long

    fun parent(): VirtualFile?
    fun children(): List<VirtualFile>

    /** Cheap, cached digest of the file's bytes; used as a cache key by builds and analysis. */
    fun contentHash(): ContentHash

    fun readBytes(): ByteArray
    fun readText(): CharSequence
}

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

/** Listener interface for VFS events. */
interface VfsListener {
    fun onEvents(events: List<VfsEvent>)
}

object VfsTopics {
    val CHANGES: Topic<VfsListener> = Topic("vfs.changes", VfsListener::class.java)
}
