package dev.ide.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** What a transfer does with an item whose name is taken in the destination (see [IdeServices.transferPaths]). */
enum class ConflictChoice { Replace, KeepBoth, Skip }

/** The outcome of [IdeServices.transferPaths]: source -> where it landed, sources left alone, failures by reason. */
data class TransferOutcome(
    val done: Map<Path, Path>,
    val skipped: List<Path>,
    val failed: Map<Path, String>,
    val undoToken: String?,
)

/** The outcome of [IdeServices.deletePaths]; [undoToken] restores the items that went to the trash. */
data class DeleteOutcome(val deleted: List<Path>, val failed: Map<Path, String>, val undoToken: String?)

/** The outcome of [IdeServices.undoFileOperation] (see `UiUndoResult` for the fields). */
data class UndoOutcome(
    val ok: Boolean,
    val message: String = "",
    val moved: Map<Path, Path> = emptyMap(),
    val removed: List<Path> = emptyList(),
    val restored: List<Path> = emptyList(),
)

/**
 * The undo record of the file tree's operations, and the trash that makes a delete or a replace reversible.
 *
 * Each operation gets a token and the list of [Step]s it took. A deleted (or replaced) item is moved under
 * [trashRoot]`/<token>/` rather than deleted, so undoing it is a move back. Only the last [MAX_ENTRIES] operations
 * are kept, and the trash of an older one is deleted when it falls off. The record lives in memory, so whatever is
 * in the trash when the IDE starts is unreachable and is cleared.
 */
internal class FileOpJournal(private val trashRoot: Path) {

    sealed interface Step {
        /** An item moved from [from] to [to]; undoing moves it back. */
        data class Moved(val from: Path, val to: Path) : Step

        /** An item was created at [path] (a copy); undoing deletes it. */
        data class Created(val path: Path) : Step

        /** An item at [original] was put aside at [trashed]; undoing moves it back. */
        data class Trashed(val original: Path, val trashed: Path) : Step
    }

    private val entries = LinkedHashMap<String, List<Step>>()
    private val counter = AtomicLong()
    private var cleared = false

    /** A new operation's token. */
    fun newToken(): String = "fop-${System.currentTimeMillis()}-${counter.incrementAndGet()}"

    /** A fresh directory under the trash for [token] to put [name] aside in (one per item, so names never clash). */
    @Synchronized
    fun trashSlot(token: String): Path {
        clearStaleTrashOnce()
        val dir = trashRoot.resolve(token).resolve(counter.incrementAndGet().toString())
        Files.createDirectories(dir)
        return dir
    }

    /** Keep [steps] as the undo of [token], dropping the oldest operation (and its trash) past [MAX_ENTRIES]. */
    @Synchronized
    fun record(token: String, steps: List<Step>) {
        if (steps.isEmpty()) {
            deleteTrashOf(token)
            return
        }
        entries[token] = steps
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.first()
            entries.remove(oldest)
            deleteTrashOf(oldest)
        }
    }

    /** The steps of [token], removed from the record (a token undoes once); null when unknown or expired. */
    @Synchronized
    fun take(token: String): List<Step>? = entries.remove(token)

    /** Delete [token]'s trash once its undo has run (whatever is still in it could not be restored). */
    @Synchronized
    fun finish(token: String) = deleteTrashOf(token)

    private fun deleteTrashOf(token: String) {
        runCatching { trashRoot.resolve(token).toFile().deleteRecursively() }
    }

    private fun clearStaleTrashOnce() {
        if (cleared) return
        cleared = true
        runCatching {
            if (Files.isDirectory(trashRoot)) Files.list(trashRoot).use { s ->
                s.forEach { p -> if (p.fileName.toString() !in entries) p.toFile().deleteRecursively() }
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 20
    }
}
