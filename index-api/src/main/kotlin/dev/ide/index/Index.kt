package dev.ide.index

import dev.ide.lang.dom.ParsedFile
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionPoint
import java.io.DataInput
import java.io.DataOutput
import java.nio.file.Path

/** Serialize + ORDER keys (ordering is what enables prefix scans). v1 keys are strings. */
interface KeyDescriptor<K : Any> : Comparator<K> {
    /** The searchable term form of a key. */
    fun asTerm(key: K): String
    fun fromTerm(term: String): K
}

interface Externalizer<V : Any> {
    fun write(out: DataOutput, value: V)
    fun read(inp: DataInput): V
}

/** Which units an index consumes (a `.class` in a jar? a `.kt` source? an `.xml`?). */
fun interface InputFilter {
    fun accepts(input: IndexInput): Boolean
}

data class Hit<V>(val key: String, val value: V, val score: Int)

/** State of one unit of indexing work, for the detail view. */
enum class IndexItemState { PENDING, ACTIVE, DONE }

/** One unit of indexing work: a library/SDK artifact, or (during the source phase) the file being scanned.
 *  Surfaced in [IndexStatus.items] so the UI can show *what* is being indexed, not just an aggregate percent. */
data class IndexItem(val label: String, val state: IndexItemState = IndexItemState.PENDING)

/** Observable build state for the UI — the "availability / graceful degrade while building" signal. */
data class IndexStatus(
    val building: Boolean = false,
    val message: String = "",
    /** 0.0..1.0, or negative for indeterminate. */
    val fraction: Double = -1.0,
    /** True only after a build has SUCCESSFULLY completed and the indexes hold queryable data. Stays false
     *  before the first build, while (re)building, and after a failure. Index-backed completion uses this as
     *  the "dumb mode" gate: a classpath/library lookup returns nothing (rather than falling back to a live
     *  jar scan / `@Metadata` decode) until the index is `ready`. */
    val ready: Boolean = false,
    /** Human label of the phase currently running ("Libraries & SDK", "Project source"), for the detail view. */
    val phase: String = "",
    /** The worklist behind the progress bar: one entry per library/SDK artifact (with its state), and during
     *  the source phase the file currently being indexed. Empty when idle. Drives the index-status dialog. */
    val items: List<IndexItem> = emptyList(),
    /** Units finished / total in the current phase (0 total ⇒ unknown / indeterminate). */
    val processed: Int = 0,
    val total: Int = 0,
)

interface IndexService {
    fun <V : Any> exact(id: IndexId, key: String): Sequence<V>
    fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int = 100): Sequence<Hit<V>>
    fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int = 100): Sequence<Hit<V>>

    /** Build/refresh the indexes for [scope] in the background (reuses persisted per-artifact caches). */
    suspend fun ensureUpToDate(scope: IndexScope)

    /** Cheap incremental re-index of a single changed source file. */
    suspend fun reindexSource(path: Path, text: String)

    /** Resolve a source value's interned [IndexInput.fileId] back to its file path, or null if unknown. */
    fun filePath(id: Int): String? = null

    /** Drop all built data — in-memory and the on-disk cache — so the next [ensureUpToDate] rebuilds from scratch. */
    suspend fun invalidate() {}

    val status: IndexStatus
    fun observeStatus(listener: (IndexStatus) -> Unit): Disposable
}

/** Convenience descriptor for the common string-keyed index. */
object StringKeyDescriptor : KeyDescriptor<String> {
    override fun compare(a: String, b: String): Int = a.compareTo(b)
    override fun asTerm(key: String): String = key
    override fun fromTerm(term: String): String = term
}

/** The platform extension point every index registers on. */
val INDEX_EP = ExtensionPoint<IndexExtension<*, *>>("platform.index")
