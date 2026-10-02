package dev.ide.index.impl

import dev.ide.index.IndexOrigin
import dev.ide.kotlin.classfile.ZipArchive
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.platform.ContentHash
import dev.ide.platform.openFile
import dev.ide.platform.readFile
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Where a classpath index build spends its time, phase by phase, on whichever platform runs it.
 *
 * Skipped unless [JAR_LIST] exists: one jar path per line. The interesting corpus is a real project's
 * libraries, tens of megabytes that do not belong in the repository.
 */
class ClasspathIndexBenchmarkTest {

    @Test
    fun anIndexBuildIsMeasuredByPhase() {
        val list = readFile(JAR_LIST)?.decodeToString()
        if (list == null) {
            println("$JAR_LIST is absent; skipping the benchmark")
            return
        }
        val jars = list.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val extensions = listOf(KotlinCallableIndex, KotlinTypeShapeIndex)
        val clock = TimeSource.Monotonic

        var directory = Duration.ZERO
        var inflate = Duration.ZERO
        val filter = HashMap<String, Duration>()
        val index = HashMap<String, Duration>()
        val accepted = HashMap<String, Int>()
        val collected = extensions.associate { it.id to ArrayList<IndexEntry>() }
        var entries = 0
        var inflatedBytes = 0L

        val total = clock.markNow()
        for (jar in jars) {
            val source = openFile(jar) ?: continue
            try {
                val opened = clock.markNow()
                val archive = ZipArchive.open(source) ?: continue
                directory += opened.elapsedNow()
                val hash = ContentHash(jar)
                for (entry in archive.entries) {
                    if (entry.isDirectory) continue
                    entries++
                    var bytes: ByteArray? = null
                    val input = JarEntryInput(entry.name, hash) {
                        bytes ?: run {
                            val t = clock.markNow()
                            (archive.read(entry) ?: ByteArray(0)).also {
                                inflate += t.elapsedNow(); inflatedBytes += it.size; bytes = it
                            }
                        }
                    }
                    for (ext in extensions) {
                        val key = ext.id.value
                        val f = clock.markNow()
                        val ok = ext.inputFilter.accepts(input)
                        filter[key] = (filter[key] ?: Duration.ZERO) + f.elapsedNow()
                        if (!ok) continue
                        accepted[key] = (accepted[key] ?: 0) + 1
                        // Inflation happens inside index() on first bytes(); subtract it out below. The class
                        // parse and @Metadata decode are shared, so they land on whichever extension runs first.
                        val before = inflate
                        val i = clock.markNow()
                        val produced = runCatching { ext.index(input) }.getOrNull()
                        index[key] = (index[key] ?: Duration.ZERO) + (i.elapsedNow() - (inflate - before))
                        produced ?: continue
                        for ((term, values) in produced) {
                            val k = term as? String ?: continue
                            for (v in values) collected.getValue(ext.id).add(IndexEntry(k, v, IndexOrigin.LIBRARY))
                        }
                    }
                }
            } finally {
                source.close()
            }
        }
        val read = total.elapsedNow()

        val write = HashMap<String, Duration>()
        for (ext in extensions) {
            val w = clock.markNow()
            writeSegment(scratchPath("bench-${ext.id.value}.seg"), ext, collected.getValue(ext.id))
            write[ext.id.value] = w.elapsedNow()
        }

        println("index benchmark: ${jars.size} jars, $entries entries, ${inflatedBytes / 1024 / 1024} MB inflated")
        println("  read+index ${read.inWholeMilliseconds}ms: directory ${directory.inWholeMilliseconds}ms, inflate ${inflate.inWholeMilliseconds}ms")
        for (ext in extensions) {
            val k = ext.id.value
            println(
                "  $k: filter ${filter[k]?.inWholeMilliseconds}ms, accepted ${accepted[k] ?: 0}, " +
                    "index ${index[k]?.inWholeMilliseconds}ms, ${collected.getValue(ext.id).size} entries, " +
                    "write ${write[k]?.inWholeMilliseconds}ms",
            )
        }
    }

    private companion object {
        const val JAR_LIST = "/private/tmp/codeassist-index-bench.txt"
    }
}
