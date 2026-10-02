package dev.ide.agent.impl

import dev.ide.agent.AgentWorkspace
import dev.ide.agent.FileChange
import dev.ide.agent.TextEdit

/**
 * An [AgentWorkspace] that remembers what every write replaced, so the files an agent turn changed can be shown
 * as diffs and reverted.
 *
 * Each write snapshots its target before delegating, into the bucket of the current [turn] (one user message and
 * everything the agent did answering it) and the current tool call ([beginCall]). Only the FIRST snapshot of a
 * path in a turn is kept: that is the state to go back to, however many times the turn then edits the file.
 *
 * What it can and cannot revert: content writes, creates, deletes (a deleted directory's files are snapshotted
 * up to [MAX_SNAPSHOT_FILES]), renames and moves are all undone exactly. Engine-driven multi-file edits whose
 * targets are not known up front (a project-wide rename_symbol, a dependency added to a build file the tool
 * picks itself) are not recorded, and [revert] says so rather than pretending.
 */
class CheckpointWorkspace(private val delegate: AgentWorkspace) : AgentWorkspace by delegate {

    private sealed interface Entry {
        /** [path] held [content] before the turn touched it; null content means it did not exist. */
        data class Content(val path: String, val content: String?) : Entry

        /** The turn moved [from] to [to]; reverting moves it back. */
        data class Moved(val from: String, val to: String) : Entry

        /** A change the workspace could not snapshot, reported on revert. */
        data class Untracked(val what: String) : Entry
    }

    private class Turn(val id: Long) {
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
    }

    private val turns = ArrayList<Turn>()
    private var current: Turn? = null
    private var callId: String? = null

    /** What each path the current call touched held before the call, in touch order. */
    private val callBefore = LinkedHashMap<String, String?>()

    /** Starts recording into the turn for user message [id]. */
    @Synchronized
    fun startTurn(id: Long) {
        current = Turn(id).also { turns += it }
    }

    /** Whether turn [id] recorded any change. */
    @Synchronized
    fun hasChanges(id: Long): Boolean = turns.any { it.id == id && it.entries.isNotEmpty() }

    /** Attributes writes from now on to tool call [id] (see [endCall]). */
    @Synchronized
    fun beginCall(id: String) {
        callId = id
        callBefore.clear()
    }

    /** The files the current call changed, as they were before the call and as they are now. */
    suspend fun endCall(): List<FileChange> {
        val touched = synchronized(this) {
            callId = null
            callBefore.toList().also { callBefore.clear() }
        }
        return touched.mapNotNull { (path, before) ->
            val after = readOrNull(path)
            if (before == after) null else FileChange(path, before?.let(::bounded), after?.let(::bounded))
        }
    }

    /**
     * Restores everything turns [fromId] and later changed, newest first, and forgets them. Returns a short
     * report of what was restored and what could not be.
     */
    suspend fun revert(fromId: Long): String {
        val undo = synchronized(this) {
            val index = turns.indexOfFirst { it.id >= fromId }
            if (index < 0) return "Nothing to revert."
            turns.subList(index, turns.size).toList().also {
                turns.subList(index, turns.size).clear()
                if (current in it) current = null
            }
        }
        var restored = 0
        val problems = ArrayList<String>()
        for (turn in undo.asReversed()) {
            for (entry in turn.entries.asReversed()) {
                try {
                    when (entry) {
                        is Entry.Content -> {
                            if (entry.content == null) delegate.deletePath(entry.path)
                            else delegate.writeFile(entry.path, entry.content)
                            restored++
                        }
                        is Entry.Moved -> {
                            val parent = entry.from.substringBeforeLast('/', "")
                            val name = entry.from.substringAfterLast('/')
                            val toParent = entry.to.substringBeforeLast('/', "")
                            if (parent == toParent) delegate.renamePath(entry.to, name)
                            else delegate.movePath(entry.to, parent.ifEmpty { "." })
                            restored++
                        }
                        is Entry.Untracked -> problems += entry.what
                    }
                } catch (e: Exception) {
                    problems += "${describe(entry)}: ${e.message ?: e::class.simpleName}"
                }
            }
        }
        return buildString {
            append("Reverted $restored change").append(if (restored == 1) "" else "s").append('.')
            if (problems.isNotEmpty()) {
                append(" Not reverted: ").append(problems.distinct().joinToString("; ")).append('.')
            }
        }
    }

    // --- recording ---

    private suspend fun snapshot(path: String) {
        val (turn, forCall) = synchronized(this) {
            val turn = current?.takeIf { path !in it.seen }?.also { it.seen += path }
            val forCall = callId != null && path !in callBefore
            turn to forCall
        }
        if (turn == null && !forCall) return
        val content = readOrNull(path)
        synchronized(this) {
            turn?.entries?.add(Entry.Content(path, content))
            if (forCall) callBefore[path] = content
        }
    }

    /** Snapshots every file under directory [path] (bounded), for a delete. */
    private suspend fun snapshotTree(path: String) {
        val files = ArrayList<String>()
        val pending = ArrayDeque(listOf(path))
        while (pending.isNotEmpty() && files.size < MAX_SNAPSHOT_FILES) {
            val dir = pending.removeFirst()
            val entries = runCatching { delegate.listDir(dir) }.getOrNull() ?: continue
            for (e in entries) if (e.isDirectory) pending += e.path else files += e.path
        }
        if (pending.isNotEmpty() || files.size >= MAX_SNAPSHOT_FILES) {
            record(Entry.Untracked("part of the deleted directory $path (too large to snapshot)"))
        }
        files.take(MAX_SNAPSHOT_FILES).forEach { snapshot(it) }
    }

    private fun record(entry: Entry) {
        synchronized(this) { current?.entries?.add(entry) }
    }

    private suspend fun readOrNull(path: String): String? = runCatching { delegate.readFile(path) }.getOrNull()

    private fun bounded(text: String): String =
        if (text.length <= MAX_DIFF_CHARS) text else text.take(MAX_DIFF_CHARS) + "\n… (truncated)"

    private fun describe(entry: Entry): String = when (entry) {
        is Entry.Content -> entry.path
        is Entry.Moved -> "${entry.to} -> ${entry.from}"
        is Entry.Untracked -> entry.what
    }

    // --- the writes ---

    override suspend fun createFile(path: String, content: String): String {
        snapshot(path)
        return delegate.createFile(path, content)
    }

    override suspend fun writeFile(path: String, content: String) {
        snapshot(path)
        delegate.writeFile(path, content)
    }

    override suspend fun applyEdits(path: String, edits: List<TextEdit>) {
        snapshot(path)
        delegate.applyEdits(path, edits)
    }

    override suspend fun deletePath(path: String): Boolean {
        val isDir = runCatching { delegate.listDir(path) }.isSuccess && readOrNull(path) == null
        if (isDir) snapshotTree(path) else snapshot(path)
        return delegate.deletePath(path)
    }

    override suspend fun renamePath(path: String, newName: String): String {
        val result = delegate.renamePath(path, newName)
        record(Entry.Moved(path, result))
        return result
    }

    override suspend fun movePath(path: String, destDir: String): String {
        val result = delegate.movePath(path, destDir)
        record(Entry.Moved(path, result))
        return result
    }

    override suspend fun applyQuickFix(path: String, line: Int, index: Int): String {
        snapshot(path)
        return delegate.applyQuickFix(path, line, index)
    }

    override suspend fun formatFile(path: String): String {
        snapshot(path)
        return delegate.formatFile(path)
    }

    override suspend fun organizeImports(path: String): String {
        snapshot(path)
        return delegate.organizeImports(path)
    }

    override suspend fun renameSymbol(path: String, offset: Int, newName: String): dev.ide.agent.RenameResult {
        val result = delegate.renameSymbol(path, offset, newName)
        if (result.success) record(Entry.Untracked("the project-wide rename to $newName (undo it with another rename)"))
        return result
    }

    override suspend fun addDependency(module: String, coordinate: String): String {
        val result = delegate.addDependency(module, coordinate)
        record(Entry.Untracked("the dependency $coordinate added to $module (remove it by hand)"))
        return result
    }

    private companion object {
        const val MAX_SNAPSHOT_FILES = 200
        const val MAX_DIFF_CHARS = 200_000
    }
}
