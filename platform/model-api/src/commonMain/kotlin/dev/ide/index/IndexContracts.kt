package dev.ide.index

import dev.ide.platform.DataReader
import dev.ide.platform.DataWriter

/**
 * What an index IMPLEMENTS, as opposed to what runs it.
 *
 * Split out of `:index-api` so that an index extension can be written in common code: the service that
 * builds and queries indexes is JVM-bound (it memory-maps segment files and walks a project tree), but
 * nothing an extension itself does is.
 */

/** Serialize + ORDER keys (ordering is what enables prefix scans). v1 keys are strings. */
interface KeyDescriptor<K : Any> : Comparator<K> {
    /** The searchable term form of a key. */
    fun asTerm(key: K): String
    fun fromTerm(term: String): K
}

/**
 * How a value is persisted into an index segment.
 *
 * Takes [DataWriter]/[DataReader] rather than `java.io.DataOutput`/`DataInput`, which is what it took before
 * this became common code. The BYTES are unchanged: those two write exactly what `DataOutputStream` wrote,
 * modified UTF-8 included, so a segment written by an older build still reads.
 */
interface Externalizer<V : Any> {
    fun write(out: DataWriter, value: V)
    fun read(inp: DataReader): V
}

/** Which units an index consumes (a `.class` in a jar? a `.kt` source? an `.xml`?). */
fun interface InputFilter {
    fun accepts(input: IndexInput): Boolean
}

data class Hit<V>(val key: String, val value: V, val score: Int)

/** Convenience descriptor for the common string-keyed index. */
object StringKeyDescriptor : KeyDescriptor<String> {
    override fun compare(a: String, b: String): Int = a.compareTo(b)
    override fun asTerm(key: String): String = key
    override fun fromTerm(term: String): String = term
}
