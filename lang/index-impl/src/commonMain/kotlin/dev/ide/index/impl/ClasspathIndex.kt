package dev.ide.index.impl

import dev.ide.index.Hit
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexQueries
import dev.ide.index.IndexStatus
import dev.ide.index.MatchingMode
import dev.ide.kotlin.classfile.ZipArchive
import dev.ide.platform.createDirectories
import dev.ide.platform.fileInfo
import dev.ide.platform.openFile
import dev.ide.lang.dom.ParsedFile
import dev.ide.platform.ContentHash

/**
 * A queryable index over a fixed set of classpath jars, built and read without a project or a build.
 *
 * The engine's own [IndexService] indexes a WORKSPACE: it walks project sources, watches for edits, tracks
 * per-artifact caches and reports progress. A host that has none of that still needs the other half — given
 * these jars, what names do they offer — and that is this. It is the same segment format, the same
 * extensions and the same query path; what it leaves out is everything about a project.
 *
 * **Why it has to exist at all.** The symbol layer answers a TYPE by name and its members straight from the
 * jar, but a NAME lookup — `println` to the callables that could be it, a prefix to the types that start
 * with it — is a different shape of question: it is an inverted index or it is a scan of every class in
 * every jar. Without one, completion cannot offer library code and the checks cannot honestly call anything
 * unresolved.
 *
 * Segments are written once per (extension, classpath) into [cacheDir] and reopened after that, so the cost
 * is paid on a project's first open and never again. The key includes each jar's size, so replacing a jar
 * with a different build of the same name rebuilds rather than serving stale names.
 */
class ClasspathIndex private constructor(
    private val segments: Map<IndexId, Segment>,
    private val extensions: Map<IndexId, IndexExtension<*, *>>,
) : IndexQueries, AutoCloseable {

    /**
     * Complete by construction: this is built from a fixed jar list, in one pass, before it answers anything.
     * There is no "still building" state for a consumer to degrade around, which is the whole difference
     * between this and a workspace index.
     */
    override val status: IndexStatus = IndexStatus(
        building = false,
        message = "Indexed",
        fraction = 1.0,
        ready = true,
    )

    @Suppress("UNCHECKED_CAST")
    override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> {
        val segment = segments[id] ?: return emptySequence()
        val out = ArrayList<Any>()
        segment.exact(key, out)
        return out.asSequence() as Sequence<V>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> {
        val segment = segments[id] ?: return emptySequence()
        val out = ArrayList<Hit<Any>>()
        segment.prefix(prefix, out, limit)
        return out.asSequence() as Sequence<Hit<V>>
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> {
        val segment = segments[id] ?: return emptySequence()
        // An extension that declares itself prefix-only has no fuzzy term structure to walk; answering
        // nothing is what the engine does for one too, rather than degrading into a scan.
        if (extensions[id]?.matching != MatchingMode.PREFIX_AND_FUZZY) return emptySequence()
        val out = ArrayList<Hit<Any>>()
        segment.fuzzy(pattern, out, limit)
        return out.asSequence() as Sequence<Hit<V>>
    }

    /** Library units have no project file, so there is no id to resolve. */
    override fun filePath(id: Int): String? = null

    override fun close() {
        segments.values.forEach { runCatching { it.close() } }
    }

    companion object {

        /**
         * Index [jars] for [extensions], reusing any segment already written under [cacheDir].
         *
         * A jar that cannot be opened is SKIPPED rather than failing the build: a partial index answers for
         * what it did read, and refusing to open a project because one artifact is corrupt would be worse
         * than offering the rest.
         */
        fun build(
            jars: List<String>,
            extensions: List<IndexExtension<*, *>>,
            cacheDir: String,
            blockCacheBytes: Long = 4L * 1024 * 1024,
            /**
             * Called as each jar is read, with the number done and the number that will be read at all.
             *
             * Reported per JAR rather than per entry because that is the unit whose cost varies (one large
             * artifact holds tens of thousands of classes), and because it is the only point the build can
             * report from without threading a callback through every extension. A build that reuses every
             * cached segment reads no jar and so reports nothing, which is correct: there was no work.
             */
            onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        ): ClasspathIndex {
            createDirectories(cacheDir)
            val key = classpathKey(jars)
            val cache = BlockCache(maxBytes = blockCacheBytes)
            val opened = LinkedHashMap<IndexId, Segment>()
            val byId = extensions.associateBy { it.id }

            // Build only what is missing, and read each jar ONCE for every extension that wants it — a jar is
            // hundreds of classes and opening it per extension would multiply the cost by the extension count.
            val missing = extensions.filter { fileInfo(segmentPath(cacheDir, it, key)) == null }
            if (missing.isNotEmpty()) {
                val collected = HashMap<IndexId, MutableList<IndexEntry>>()
                for (ext in missing) collected[ext.id] = ArrayList()
                for ((done, jar) in jars.withIndex()) {
                    collect(jar, missing, collected)
                    onProgress(done + 1, jars.size)
                }
                for (ext in missing) {
                    writeSegment(segmentPath(cacheDir, ext, key), ext, collected[ext.id].orEmpty())
                }
            }

            for ((i, ext) in extensions.withIndex()) {
                val segment = runCatching {
                    Segment.open(segmentPath(cacheDir, ext, key), ext, cache, i)
                }.getOrNull() ?: continue
                opened[ext.id] = segment
            }
            return ClasspathIndex(opened, byId)
        }

        /** Hand every entry of [jar] to each extension that accepts it, collecting what they produce. */
        private fun collect(
            jar: String,
            extensions: List<IndexExtension<*, *>>,
            into: Map<IndexId, MutableList<IndexEntry>>,
        ) {
            val source = openFile(jar) ?: return
            try {
                val archive = ZipArchive.open(source) ?: return
                val jarHash = ContentHash(jar)
                for (entry in archive.entries) {
                    if (entry.isDirectory) continue
                    var bytes: ByteArray? = null
                    for (ext in extensions) {
                        val input = JarEntryInput(entry.name, jarHash) {
                            bytes ?: (archive.read(entry) ?: ByteArray(0)).also { bytes = it }
                        }
                        if (!ext.inputFilter.accepts(input)) continue
                        val produced = runCatching { ext.index(input) }.getOrNull() ?: continue
                        val out = into[ext.id] ?: continue
                        // The key type is the extension's own; every one here is keyed by String, and a
                        // non-String key would have no term to write, so it is skipped rather than coerced.
                        for ((term, values) in produced) {
                            val key = term as? String ?: continue
                            for (value in values) out.add(IndexEntry(key, value, IndexOrigin.LIBRARY))
                        }
                    }
                }
            } finally {
                source.close()
            }
        }

        /**
         * The segment's name: the extension, its VERSION, and the classpath it was built from.
         *
         * The version is in the name because an extension that changes what it produces must not read a
         * segment an older build wrote — the format would parse and the contents would be wrong.
         */
        private fun segmentPath(cacheDir: String, ext: IndexExtension<*, *>, key: String): String =
            "$cacheDir/${ext.id.value}-v${ext.version}-$key.seg"

        /**
         * A stable name for a classpath: each jar's name and size.
         *
         * Size rather than content: hashing hundreds of megabytes of jars to decide whether to reuse a cache
         * would cost more than rebuilding it, and a jar whose bytes change without its length changing is a
         * rebuild of the same version, which the extension version does not cover but a project re-resolve
         * does.
         */
        private fun classpathKey(jars: List<String>): String {
            var hash = 0L
            for (jar in jars.sorted()) {
                val info = fileInfo(jar)
                hash = hash * 1_000_003L + jar.hashCode().toLong()
                hash = hash * 1_000_003L + (info?.size ?: 0L)
            }
            return hash.toULong().toString(16)
        }
    }
}

/** One entry of a jar, as an index extension consumes it. The bytes are read on demand and once. */
private class JarEntryInput(
    override val unitName: String,
    private val jarHash: ContentHash,
    private val readBytes: () -> ByteArray,
) : IndexInput {
    override val origin = IndexOrigin.LIBRARY
    override val contentHash: ContentHash get() = jarHash
    override val sourcePath: String? = null
    override fun bytes(): ByteArray = readBytes()
    override fun text(): String? = null
    override fun dom(): ParsedFile? = null
}
