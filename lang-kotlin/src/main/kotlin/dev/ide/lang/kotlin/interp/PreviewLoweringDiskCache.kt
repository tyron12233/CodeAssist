package dev.ide.lang.kotlin.interp

import dev.ide.platform.log.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Disk persistence for [KotlinPreviewLowering]'s per-file cache, so a project reopen (or IDE restart) serves
 * previously-lowered declarations by DECODE instead of re-running overload resolution — the dominant cold cost
 * of the first preview on a big project (Jetsnack: seconds of `lowerFn` per entry, milliseconds to decode).
 *
 * One cache file per source path (`<sha1(path)>.plc` under [dir]), holding whatever was materialized for that
 * file when last stored: the lowered top-level callables (each with the declaration text hash + start offset
 * that gate its reuse) plus the file's lowered classes when they were materialized. Validity mirrors the
 * in-memory rules exactly:
 *  - an entry is only CONSIDERED when its stored file signature hash (`fileSignatureHash` — the text with
 *    top-level function bodies elided) matches the current parse, and
 *  - each function is only REUSED when its declaration's text hash + offset still match (checked by the
 *    caller at materialization), so spans stay exact;
 *  - classes reuse under the signature match alone (only function bodies can change without moving it, and
 *    those never affect a class's lowering) — same as the in-memory generation carry-over.
 *
 * [salt] must capture everything else lowering depends on — the classpath fingerprint at minimum (a dependency
 * change alters resolution) — and is combined here with [ResolvedTreeCodec.FORMAT], which also stands in for
 * lowering-behavior versioning (bump it on resolver changes; see its docs). A mismatched header is treated as
 * a miss and the entry overwritten. Cross-file source-signature staleness is accepted best-effort, exactly as
 * the in-memory cache already does (documented on [KotlinPreviewLowering]).
 *
 * Writes are asynchronous and coalesced per path (latest snapshot wins) on a shared daemon thread, so storing
 * never blocks the engine thread; a torn/failed write is self-healing (the next load discards it).
 */
class PreviewLoweringDiskCache(private val dir: Path, private val salt: String) {

    /** One persisted declaration: the lowered function plus the anonymous `object : Foo {}` classes its body
     *  synthesized (they must travel together — anon FQN numbering is per-lowering-generation). */
    class CachedFn(
        val textHash: Int, val startOffset: Int, val fn: ResolvedFunction,
        val anons: List<ResolvedClass> = emptyList(),
    )
    class Entry(val sigHash: Int, val fns: Map<String, CachedFn>, val classes: List<ResolvedClass>?)

    private val log = Log.logger("preview-lowering-cache")

    // Per-instance: two modules' analyzers can see the SAME absolute source path with different classpaths
    // (a shared dependency-module file); a shared pending map would let one instance drain — and mis-file —
    // the other's snapshot.
    private val pending = ConcurrentHashMap<String, Entry>()

    /** Load the stored entry for source [path], or null when absent/stale/corrupt (all self-healing misses). */
    fun load(path: String): Entry? = runCatching {
        val f = dir.resolve(fileName(path))
        if (!Files.isRegularFile(f)) return null
        val d = DataInputStream(ByteArrayInputStream(Files.readAllBytes(f)))
        if (d.readInt() != MAGIC || d.readInt() != ResolvedTreeCodec.FORMAT) return null
        val r = ResolvedTreeCodec.Reader(d)
        if (r.str() != salt || r.str() != path) return null
        val sigHash = r.int()
        val fns = LinkedHashMap<String, CachedFn>()
        repeat(r.int()) {
            val key = r.str()
            fns[key] = CachedFn(r.int(), r.int(), r.function(), r.list { r.klass() })
        }
        val classes = r.nullable { r.list { r.klass() } }
        Entry(sigHash, fns, classes)
    }.getOrElse { t ->
        log.warn("discarding unreadable preview-lowering cache entry for $path: ${t.javaClass.simpleName}: ${t.message}")
        runCatching { Files.deleteIfExists(dir.resolve(fileName(path))) }
        null
    }

    /** Persist [entry] as the snapshot for [path] — asynchronous, coalesced (the latest store wins). */
    fun store(path: String, entry: Entry) {
        pending[path] = entry
        writer.execute {
            val e = pending.remove(path) ?: return@execute
            runCatching { write(path, e) }
                .onFailure { log.warn("preview-lowering cache write failed for $path: ${it.message}") }
        }
    }

    /** Block until every store scheduled so far is on disk (the writer is serial, so an empty task is a
     *  barrier). For tests and orderly shutdown; production writes stay fire-and-forget. */
    fun flush() {
        runCatching { writer.submit { }.get() }
    }

    private fun write(path: String, entry: Entry) {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeInt(MAGIC)
        d.writeInt(ResolvedTreeCodec.FORMAT)
        ResolvedTreeCodec.Writer(d).run {
            str(salt); str(path)
            int(entry.sigHash)
            int(entry.fns.size)
            entry.fns.forEach { (key, f) ->
                str(key); int(f.textHash); int(f.startOffset); function(f.fn); list(f.anons) { klass(it) }
            }
            nullable(entry.classes) { cs -> list(cs) { klass(it) } }
        }
        d.flush()
        Files.createDirectories(dir)
        val target = dir.resolve(fileName(path))
        val tmp = dir.resolve("${fileName(path)}.tmp")
        Files.write(tmp, bos.toByteArray())
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileName(path: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".plc"
    }

    private companion object {
        const val MAGIC = 0x504C4331 // "PLC1"

        /** Shared across instances/process: writes are tiny and rare, and a daemon thread needs no disposal. */
        val writer = Executors.newSingleThreadExecutor { r ->
            Thread(r, "preview-lowering-cache").apply { isDaemon = true }
        }
    }
}
