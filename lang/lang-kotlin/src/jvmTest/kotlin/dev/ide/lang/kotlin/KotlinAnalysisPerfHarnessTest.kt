package dev.ide.lang.kotlin

import dev.ide.lang.CacheInvalidation
import dev.ide.platform.log.Log
import dev.ide.platform.log.LogSink
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Whole-corpus cost of the editor's Kotlin diagnostics, opt-in: `-Dkt.perf=<report file>`.
 *
 * The regression benchmarks each time one synthetic file. This times every source file of this module
 * (`commonMain` + `jvmMain`, a few hundred real files, some of them thousands of lines) against the module's
 * real classpath, the same setup as the real-classpath analysis sweep, and records per file:
 * wall time, thread CPU time and bytes allocated. Four passes:
 *
 * - `open`: each file analyzed for the first time, while the session memos warm up across the corpus.
 * - `reopen`: the same files again. The analyzer keeps only the last eight parses, so every file is parsed
 *   and analyzed in full again, now against warm session memos. This is the steady-state full-file cost.
 * - `keystroke`: per file, one character typed inside its last block-bodied function, then analyze. This
 *   is what the editor pays per debounced edit.
 * - `stages`: the reopen pass once more with [KotlinPerf] on, summing its per-stage buckets over the corpus.
 *
 * Retained heap is read (after forced GCs) after setup, after each pass, and after a memory-pressure
 * invalidation, which is what is left that the analyzer cannot drop.
 *
 * `-Dkt.perfOnly=<path substring>` narrows the corpus; `-Dkt.perfLimit=<n>` caps it.
 */
class KotlinAnalysisPerfHarnessTest {

    @Test
    fun corpusAnalysisCost() {
        val out = System.getProperty("kt.perf")?.let(::File) ?: return
        val root = File(System.getProperty("kt.repoRoot") ?: error("kt.repoRoot is required"))
        // `-Dkt.perfCorpus=<dir holding commonMain/ and jvmMain/>` pins the corpus, so two commits are timed
        // over the very same files; by default it is this module's own sources.
        val module = System.getProperty("kt.perfCorpus")?.let(::File) ?: File(root, "lang/lang-kotlin/src")
        val only = System.getProperty("kt.perfOnly")
        val limit = System.getProperty("kt.perfLimit")?.toIntOrNull() ?: Int.MAX_VALUE
        val files = listOf("commonMain", "jvmMain").flatMap { dir ->
            File(module, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.sortedBy { it.path }.filter { only == null || it.path.contains(only) }.take(limit)

        val report = StringBuilder()
        fun line(s: String) { println(s); report.append(s).append('\n') }

        val heapStart = retainedHeap()
        val tSetup = System.nanoTime()
        val tmp = File(root, "lang/lang-kotlin/build/tmp").toPath()
        // The module's real classpath, as the real-classpath analysis sweep builds it: this test JVM's jars plus
        // the JDK packed back into a readable jar.
        val classpath = System.getProperty("java.class.path").orEmpty().split(File.pathSeparatorChar)
            .filter { it.endsWith(".jar") }.map { java.nio.file.Paths.get(it) }
            .filter { java.nio.file.Files.isRegularFile(it) } +
            listOfNotNull(dev.ide.testkit.TestJars.jdkBaseJar(tmp.resolve("sweep-jdk")))
        // The persistent type-shape and callable indexes over the classpath, as the app wires them
        // (`IdeServices`): without them the symbol service falls back to in-memory jar scans the app never
        // uses. `-Dkt.perfIndex=false` times that fallback instead. The index persists under build/tmp, so
        // only the first run on a machine pays its build.
        val useIndex = System.getProperty("kt.perfIndex") != "false"
        val tIndex = System.nanoTime()
        val index = if (!useIndex) null else dev.ide.index.impl.IndexServiceImpl(
            listOf(dev.ide.lang.kotlin.index.KotlinTypeShapeIndex, dev.ide.lang.kotlin.index.KotlinCallableIndex),
            cacheRoot = tmp.resolve("perf-index").also { java.nio.file.Files.createDirectories(it) },
        ).also { runBlocking { it.ensureUpToDate(dev.ide.index.IndexScope(libraryJars = classpath)) } }
        val indexMs = (System.nanoTime() - tIndex) / 1e6
        val analyzer = KotlinSourceAnalyzer(fakeContext(module.toPath(), classpath)).apply {
            extensionCacheDir = tmp.resolve("sweep-extension-cache").also { java.nio.file.Files.createDirectories(it) }
            if (index != null) indexService = index
        }
        // First touch stands the symbol service up over the classpath; time it apart from the passes.
        analyzeText(analyzer, module, "commonMain/kotlin/dev/ide/lang/kotlin/PerfSeed.kt", "package dev.ide.lang.kotlin\nfun perfSeed() = 1\n")
        val setupMs = (System.nanoTime() - tSetup) / 1e6
        val heapSetup = retainedHeap()

        val texts = files.associateWith { it.readText().replace("\r\n", "\n") }
        val lines = texts.values.sumOf { t -> t.count { it == '\n' } }
        line("corpus: ${files.size} files, $lines lines under $module, real classpath (${classpath.size} jars)")
        line("setup: ${"%.0f".format(setupMs)} ms (index ${if (index == null) "off" else "%.0f ms".format(indexMs)}), retained heap ${mb(heapSetup - heapStart)}")

        fun pass(name: String, edit: Boolean = false): List<Sample> = files.map { f ->
            val rel = f.relativeTo(module).path.replace(File.separatorChar, '/')
            val text = texts.getValue(f)
            if (edit) {
                analyzeText(analyzer, module, rel, text)
                val at = editOffset(text)
                val edited = text.substring(0, at) + " " + text.substring(at)
                measure(rel) { analyzeText(analyzer, module, rel, edited) }
            } else {
                measure(rel) { analyzeText(analyzer, module, rel, text) }
            }
        }

        val open = pass("open")
        val heapOpen = retainedHeap()
        val reopen = pass("reopen")
        val heapReopen = retainedHeap()
        val keystroke = pass("keystroke", edit = true)
        val heapKey = retainedHeap()

        line("")
        line("pass        files   total ms   p50 ms   p90 ms   p99 ms   max ms   cpu ms    alloc MB   MB/file p50")
        for ((name, s) in listOf("open" to open, "reopen" to reopen, "keystroke" to keystroke)) line(summary(name, s))

        line("")
        line("retained heap: setup ${mb(heapSetup - heapStart)}, after open ${mb(heapOpen - heapStart)}, " +
            "after reopen ${mb(heapReopen - heapStart)}, after keystroke ${mb(heapKey - heapStart)}")

        // Per-stage buckets over one more warm full pass.
        val buckets = LinkedHashMap<String, Double>()
        val sink = LogSink { r ->
            if (r.tag != "kotlin-perf" || !r.message.startsWith("kt.analyze total=")) return@LogSink
            for (m in Regex("""(\S+)=(\d+)\.(\d)ms""").findAll(r.message)) {
                val ms = m.groupValues[2].toDouble() + m.groupValues[3].toDouble() / 10
                synchronized(buckets) { buckets[m.groupValues[1]] = (buckets[m.groupValues[1]] ?: 0.0) + ms }
            }
        }
        Log.addSink(sink); KotlinPerf.enabled = true
        try { pass("stages") } finally { KotlinPerf.enabled = false; Log.removeSink(sink) }
        line("")
        line("stage buckets over a warm full pass (KotlinPerf, summed ms; nested spans overlap their parents):")
        for ((k, v) in buckets.entries.sortedByDescending { it.value }.take(30)) line("  %-28s %9.1f".format(k, v))

        line("")
        line("heaviest files (reopen, warm full analysis):")
        for (s in reopen.sortedByDescending { it.wallNs }.take(15)) {
            line("  %8.1f ms  %7.1f MB  %s".format(s.wallNs / 1e6, s.allocBytes / 1e6, s.file))
        }
        line("heaviest keystrokes:")
        for (s in keystroke.sortedByDescending { it.wallNs }.take(10)) {
            line("  %8.1f ms  %7.1f MB  %s".format(s.wallNs / 1e6, s.allocBytes / 1e6, s.file))
        }

        analyzer.invalidateCaches(CacheInvalidation.MEMORY_PRESSURE)
        val heapDropped = retainedHeap()
        line("")
        line("retained heap after a memory-pressure invalidation: ${mb(heapDropped - heapStart)}")

        // `-Dkt.perfHisto=true`: the live-object class histogram at this point (after the invalidation), for
        // finding what the analyzer keeps that it cannot drop.
        if (System.getProperty("kt.perfHisto") == "true") {
            val histo = java.lang.management.ManagementFactory.getPlatformMBeanServer().invoke(
                javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram", arrayOf<Any>(arrayOf<String>()), arrayOf("[Ljava.lang.String;"),
            ) as String
            File(out.path + ".histo").writeText(histo)
            // Keep the analyzer reachable until the histogram is taken, so it measures what a live analyzer holds.
            println("histogram taken with ${analyzer.hashCode()} live")
        }

        out.parentFile?.mkdirs()
        out.writeText(report.toString())
        // Machine-readable per-file rows, for diffing two commits file by file.
        File(out.path + ".tsv").printWriter().use { w ->
            w.println("pass\tfile\twall_ns\tcpu_ns\talloc_bytes")
            for ((name, s) in listOf("open" to open, "reopen" to reopen, "keystroke" to keystroke)) {
                for (x in s) w.println("$name\t${x.file}\t${x.wallNs}\t${x.cpuNs}\t${x.allocBytes}")
            }
        }
    }

    private class Sample(val file: String, val wallNs: Long, val cpuNs: Long, val allocBytes: Long)

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private inline fun measure(file: String, block: () -> Unit): Sample {
        val id = Thread.currentThread().id
        val a0 = threads.getThreadAllocatedBytes(id)
        val c0 = threads.getThreadCpuTime(id)
        val t0 = System.nanoTime()
        block()
        val wall = System.nanoTime() - t0
        return Sample(file, wall, threads.getThreadCpuTime(id) - c0, threads.getThreadAllocatedBytes(id) - a0)
    }

    private fun analyzeText(analyzer: KotlinSourceAnalyzer, module: File, rel: String, text: String) {
        val doc = SnippetDoc(text, DiskFile(module.toPath().resolve(rel)))
        runCatching { runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics } }
    }

    /** Just after the `{` of the last block-bodied function, so the edit lands inside one declaration. */
    private fun editOffset(text: String): Int {
        val m = Regex("""\bfun\b[^\n{=]*\)[^\n{=]*\{""").findAll(text).lastOrNull() ?: return text.length
        return m.range.last + 1
    }

    private fun summary(name: String, s: List<Sample>): String {
        val wall = s.map { it.wallNs }.sorted()
        fun pct(p: Double) = wall[((wall.size - 1) * p).toInt()] / 1e6
        val allocs = s.map { it.allocBytes }.sorted()
        return "%-10s %6d %10.0f %8.2f %8.2f %8.1f %8.1f %8.0f %11.0f %12.2f".format(
            name, s.size, s.sumOf { it.wallNs } / 1e6, pct(0.5), pct(0.9), pct(0.99), wall.last() / 1e6,
            s.sumOf { it.cpuNs } / 1e6, s.sumOf { it.allocBytes } / 1e6, allocs[allocs.size / 2] / 1e6,
        )
    }

    private fun retainedHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(4) { System.gc(); Thread.sleep(60) }
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1e6)
}
