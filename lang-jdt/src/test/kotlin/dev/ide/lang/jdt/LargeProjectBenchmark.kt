package dev.ide.lang.jdt

import dev.ide.bench.Bench
import dev.ide.bench.MemoryProbe
import dev.ide.bench.RegressionSuite
import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.complete
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scale regression suite: completion on a **large project with complex dependencies** (opt-in:
 * `./gradlew :lang-jdt:regressionTest`). The other lang-jdt suites use a single small focal unit; this one
 * proves the engine stays fast, cheap, and correct at realistic scale:
 *
 *  - **source scale** — a generated multi-package project (~300 classes across 30 packages, each
 *    cross-referencing the previous package), so the sourcepath the focal unit resolves against is large.
 *  - **dependency scale** — the *real* jars on the test runtime classpath as `LIBRARY` entries. The Eclipse
 *    JDT core jar alone carries thousands of classes; this is the "complex dependency" a real project drags
 *    in, and exactly the kind of classpath whose per-resolve re-enumeration the [JdtEnvironmentCache] had to
 *    eliminate (on device that jar is `android.jar`). If the cache regresses, latency/alloc here explode.
 *
 * It records member-access + name-reference latency and allocation, the retained heap of an analyzer over
 * the whole project, and a **quality-at-scale** recall check (the expected symbol must still surface and
 * resolve correctly even with thousands of classpath symbols in play), all against
 * `baselines/completion-largeproject.json`.
 */
@Tag("regression")
class LargeProjectBenchmark {

    private val packages = 30
    private val perPackage = 10
    private val deepPkg get() = packages - 1   // the package the focal unit reaches into

    private data class Scenario(val label: String, val category: String, val body: String, val expected: String)

    /** The focal unit: reaches into the deepest generated package, with a member-access and a name caret. */
    private fun focal(body: String): String = """
        package gen.app;
        import gen.pkg$deepPkg.Class${deepPkg}_0;
        public class App {
            int run() {
                Class${deepPkg}_0 c = new Class${deepPkg}_0();
                int result = 0;
                $body
                return result;
            }
        }
    """.trimIndent()

    private fun scenarios() = listOf(
        Scenario("member-access", "member", "result = c.comp|CARET| ;", "compute0"),
        Scenario("member-value", "member", "result = c.val|CARET| ;", "value"),
        Scenario("name-ref", "name", "result = res|CARET| ;", "result"),
    )

    @Test
    fun largeProjectCompletionHoldsAgainstBaseline() {
        val heapBefore = MemoryProbe.settledUsedHeap()
        val dir = Files.createTempDirectory("large-project")
        try {
            val fileCount = generateProject(dir)
            val jars = classpathJars()
            val analyzer = JdtSourceAnalyzer(context(dir, jars))
            val focalFile = dir.resolve("gen/app/App.java")

            // Warm: the first resolve cold-builds the environment over the big sourcepath + jars.
            runSync { analyzer.complete(request(focalFile, scenarios()[0])) }
            val retained = (MemoryProbe.settledUsedHeap() - heapBefore).coerceAtLeast(0)

            val suite = RegressionSuite("completion-largeproject")
            val report = StringBuilder(
                "\n=== large project: ${fileCount} source files + ${jars.size} dependency jars ===\n")
            report.append("scenario        | category |        ns/op     alloc/op   rank/items\n")

            var present = 0
            for (s in scenarios()) {
                val req = request(focalFile, s)
                val result = runSync { analyzer.complete(req) }
                val labels = result.items.map { it.insertText.substringBefore('(') }
                val rank = labels.indexOf(s.expected)
                if (rank >= 0) present++

                val nsPerOp = Bench.nsPerOp(warmup = 3, runs = 5, ops = 5) {
                    runSync { analyzer.complete(req) }.items.size.toLong()
                }
                val bytesPerOp = Bench.allocPerOp(warmup = 3, ops = 5) {
                    runSync { analyzer.complete(req) }.items.size.toLong()
                }
                report.append("%-15s | %-8s | %12s %12s   %s/%d\n".format(
                    s.label, s.category, Bench.ns(nsPerOp), Bench.bytes(bytesPerOp.toDouble()),
                    if (rank < 0) "MISS" else rank.toString(), labels.size))

                // Even with a huge classpath, a warm completion is amortized by the env cache. Loose drift +
                // a 300 ms backstop (more headroom than the small-unit suite, for the cold-built big env).
                suite.latencyNs("${s.label}.ns", nsPerOp, tolerance = 1.5, ceilingNs = 300_000_000.0)
                suite.allocBytes("${s.label}.bytes", bytesPerOp, tolerance = 0.50, ceilingBytes = 64.0 * 1024 * 1024)
            }
            println(report)
            println("retained heap over the whole project: ${Bench.bytes(retained.toDouble())}\n")

            val recall = present.toDouble() / scenarios().size
            // Quality at scale: the right symbol must still be offered despite thousands of classpath symbols.
            suite.quality("recall", recall, tolerance = 0.0, floor = 1.0)
            suite.heapBytes("retainedHeap.bytes", retained, tolerance = 1.0, ceilingBytes = 256.0 * 1024 * 1024)
            suite.count("files", fileCount, tolerance = 0.0)
            suite.finishAndAssert()

            assertTrue(present == scenarios().size, "every expected symbol must resolve at scale (got $present/${scenarios().size})")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ---- project generation ----

    /** Writes ~[packages]×[perPackage] classes, each referencing the previous package's Class*_0. */
    private fun generateProject(dir: Path): Int {
        var count = 0
        for (p in 0 until packages) {
            val pkgDir = dir.resolve("gen/pkg$p")
            Files.createDirectories(pkgDir)
            for (k in 0 until perPackage) {
                val name = "Class${p}_$k"
                val depImport = if (p > 0) "import gen.pkg${p - 1}.Class${p - 1}_0;" else ""
                val depUse = if (p > 0) "acc += new Class${p - 1}_0().value();" else ""
                val src = """
                    package gen.pkg$p;
                    import java.util.List;
                    import java.util.ArrayList;
                    $depImport
                    public class $name {
                        private int total = $k;
                        private final List<String> items = new ArrayList<>();
                        public int value() { return total; }
                        public int compute$k(int seed) {
                            int acc = seed;
                            $depUse
                            for (int i = 0; i < items.size(); i++) acc += i;
                            return acc;
                        }
                        public String label() { return "$name"; }
                    }
                """.trimIndent()
                Files.writeString(pkgDir.resolve("$name.java"), src)
                count++
            }
        }
        return count
    }

    /** Real `.jar` entries on the test runtime classpath (JDT core, kotlin-stdlib, …) — complex deps. */
    private fun classpathJars(): List<Path> =
        (System.getProperty("java.class.path") ?: "").split(File.pathSeparator)
            .filter { it.endsWith(".jar") && !it.endsWith("-sources.jar") && !it.endsWith("-javadoc.jar") }
            .map { Path.of(it) }
            .filter { Files.exists(it) }

    private fun context(srcDir: Path, jars: List<Path>): CompilationContext = object : CompilationContext {
        override val sourceRoots: List<VirtualFile> = listOf(StubFile(srcDir.toString()))
        override val classpath: ClasspathSnapshot = object : ClasspathSnapshot {
            override val entries = jars.map { ClasspathEntry(StubFile(it.toString()), ClasspathEntryKind.LIBRARY) }
            override fun fingerprint() = ContentHash(jars.joinToString { it.toString() })
        }
        override val bootClasspath: ClasspathSnapshot = object : ClasspathSnapshot {
            override val entries = listOf(ClasspathEntry(StubFile(System.getProperty("java.home")), ClasspathEntryKind.SDK_BOOTCLASSPATH))
            override fun fingerprint() = ContentHash("boot")
        }
        override val languageLevel = LanguageLevel.JAVA_17
        override val outputDir: VirtualFile = StubFile("/out")
        override val processors: List<AnnotationProcessor> = emptyList()
    }

    private fun request(file: Path, s: Scenario): CompletionRequest {
        val text = focal(s.body)
        val offset = text.indexOf("|CARET|")
        require(offset >= 0) { "scenario '${s.label}' has no |CARET|" }
        val clean = text.replace("|CARET|", "")
        return CompletionRequest(LargeSnapshot(StubFile(file.toString(), clean), 1, clean), offset, CompletionTrigger.Explicit)
    }
}

private class LargeSnapshot(
    override val file: VirtualFile,
    override val version: Long,
    override val text: CharSequence,
) : DocumentSnapshot {
    override fun length() = text.length
}
