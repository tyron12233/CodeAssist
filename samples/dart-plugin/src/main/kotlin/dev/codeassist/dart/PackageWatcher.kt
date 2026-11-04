package dev.codeassist.dart

import dev.ide.platform.MessageBus
import dev.ide.vfs.FileMoved
import dev.ide.vfs.VfsEvent
import dev.ide.vfs.VfsListener
import dev.ide.vfs.VfsTopics
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * Which of a package's Dart files and pubspec changed: what a hot-reloading run or preview reloads.
 *
 * Two sources. The IDE's own file events ([VfsTopics.CHANGES], published on every editor save) are immediate and
 * cost nothing, and they [wake] whoever waits. They only reach the IDE's process; a Run in the separate build
 * process never sees them, and an edit made outside the IDE never makes one. So the package is also walked, every
 * [POLL_MS] rather than continuously, and never into `.dart_tool`, `build` or a dot directory: the projects live on
 * shared storage, where every file looked at is a round trip through Android's FUSE layer.
 */
internal class PackageWatcher(
    private val pkg: Path,
    bus: MessageBus?,
    /** Signalled when there is something to look at. Shared, so other wake-ups (a console command) use it too. */
    val wake: Channel<Unit> = Channel(Channel.CONFLATED),
    /** How often the package is walked; [POLL_MS] but in tests. */
    private val walkEveryMs: Long = POLL_MS,
) : AutoCloseable {
    private val edited = ConcurrentHashMap.newKeySet<Path>()
    private val seen = HashMap<Path, Long>()
    private var lastWalk = 0L

    private val connection = bus?.connect()?.also { c ->
        c.subscribe(VfsTopics.CHANGES, VfsListener { events -> onEvents(events) })
    }

    init {
        seen.putAll(walk())
        lastWalk = System.nanoTime()
    }

    /** Wait until something may have changed, or [timeoutMs] passed (when the walk is due). */
    suspend fun await(timeoutMs: Long = walkEveryMs) {
        withTimeoutOrNull(timeoutMs) { wake.receive() }
    }

    /** The files changed since the last call: what the events named at once, and what a due walk found. */
    fun changes(): List<Path> {
        val out = LinkedHashSet<Path>()
        if (edited.isNotEmpty()) {
            val batch = edited.toList()
            edited.removeAll(batch.toSet())
            for (p in batch) {
                val stamp = stamp(p)
                // An event for a file the walk already took in (or a save that changed nothing on disk) is not a change.
                if (stamp == seen[p]) continue
                if (stamp == null) seen.remove(p) else seen[p] = stamp
                out.add(p)
            }
        }
        if (System.nanoTime() - lastWalk >= walkEveryMs * 1_000_000) {
            val now = walk()
            for (p in now.keys + seen.keys) if (now[p] != seen[p]) out.add(p)
            seen.clear()
            seen.putAll(now)
            lastWalk = System.nanoTime()
        }
        return out.toList()
    }

    override fun close() {
        connection?.dispose()
    }

    private fun onEvents(events: List<VfsEvent>) {
        // On the publisher's thread (the editor's save): only note the paths and wake the waiter.
        var any = false
        for (e in events) {
            val paths = if (e is FileMoved) listOf(e.from, e.to) else listOf(e.file.path)
            for (raw in paths) {
                val p = runCatching { Paths.get(raw).normalize() }.getOrNull() ?: continue
                if (watched(p)) {
                    edited.add(p)
                    any = true
                }
            }
        }
        if (any) wake.trySend(Unit)
    }

    private fun watched(p: Path): Boolean {
        if (!p.startsWith(pkg) || p == pkg) return false
        val rel = pkg.relativize(p)
        if ((0 until rel.nameCount - 1).any { skipped(rel.getName(it).toString(), it == 0) }) return false
        val name = p.fileName.toString()
        return name.endsWith(".dart") || (rel.nameCount == 1 && name == DartPackages.PUBSPEC)
    }

    /** The modification time of each watched file, found without entering the directories nothing is watched in. */
    private fun walk(): Map<Path, Long> {
        val out = HashMap<Path, Long>()
        try {
            Files.walkFileTree(pkg, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != pkg && skipped(dir.fileName.toString(), dir.parent == pkg)) FileVisitResult.SKIP_SUBTREE
                    else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && watched(file)) out[file] = attrs.lastModifiedTime().toMillis()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            // The package went away mid-walk; what was found stands.
        }
        return out
    }

    private fun stamp(p: Path): Long? = runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrNull()

    private fun skipped(name: String, topLevel: Boolean) = name.startsWith(".") || (topLevel && name == "build")

    companion object {
        /** How often the package is walked for changes no event announced. */
        const val POLL_MS = 2_000L
    }
}
