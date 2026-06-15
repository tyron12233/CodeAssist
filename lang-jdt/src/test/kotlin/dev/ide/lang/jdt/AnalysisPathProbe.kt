package dev.ide.lang.jdt

import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * A/B probe (informational, prints timings) for the analysis-path question: is the binding DOM parse
 * dominated by the per-parse classpath environment scan (which the cached low-level [JdtSourceAnalyzer.diagnose]
 * avoids), or does JDT amortize it? Since android.jar can't run on the desktop, a real ~4 MB jar is put on
 * the classpath as a stand-in to compare the DOM `parse().diagnostics` against `diagnose()`.
 */
class AnalysisPathProbe {

    /** The largest jar on the test runtime classpath — a portable stand-in for a project's `android.jar`. */
    private fun jarsOnClasspath(): List<String> =
        System.getProperty("java.class.path").orEmpty().split(java.io.File.pathSeparator)
            .filter { it.endsWith(".jar") && Files.isRegularFile(Path.of(it)) }
            .maxByOrNull { runCatching { Files.size(Path.of(it)) }.getOrDefault(0L) }
            ?.let { listOf(it) } ?: emptyList()

    private fun analyzerWithJars(dir: Path, jars: List<String>): JdtSourceAnalyzer {
        val ctx = object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = listOf(StubFile(dir.toString()))
            override val classpath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries = jars.map { ClasspathEntry(StubFile(it), ClasspathEntryKind.LIBRARY) }
                override fun fingerprint() = ContentHash(jars.joinToString())
            }
            override val bootClasspath: ClasspathSnapshot = object : ClasspathSnapshot {
                override val entries = listOf(ClasspathEntry(StubFile(System.getProperty("java.home")), ClasspathEntryKind.SDK_BOOTCLASSPATH))
                override fun fingerprint() = ContentHash("boot")
            }
            override val languageLevel = LanguageLevel.JAVA_17
            override val outputDir: VirtualFile = StubFile("/out")
            override val processors: List<AnnotationProcessor> = emptyList()
        }
        return JdtSourceAnalyzer(ctx)
    }

    @Test
    fun compareDomParseVsCachedDiagnose() {
        val jars = jarsOnClasspath()
        if (jars.isEmpty()) { println("\n[AnalysisPathProbe] no stand-in jar found — skipping\n"); return }

        val dir = Files.createTempDirectory("analysis-probe")
        try {
            val analyzer = analyzerWithJars(dir, jars)
            val code = """
                package app;
                import java.util.List;
                import java.util.ArrayList;
                class Service {
                    private final List<String> names = new ArrayList<>();
                    int compute(int seed) {
                        int acc = seed;
                        for (int i = 0; i < names.size(); i++) acc += i;
                        return acc;
                    }
                }
            """.trimIndent()
            val vf = StubFile(dir.resolve("app/Service.java").toString(), code)

            val domNs = bench(warmup = 3, runs = 3, ops = 8) { analyzer.parse(vf, code).diagnostics.size.toLong() }
            val lowNs = bench(warmup = 3, runs = 3, ops = 8) { runSync { analyzer.analyze(vf) }.diagnostics.size.toLong() }

            println(
                "\n=== Analysis path: DOM parse vs cached low-level diagnose (jar on classpath) ===\n" +
                    "classpath jars: ${jars.size} (${jars.joinToString { Path.of(it).fileName.toString() }})\n" +
                    "old DOM parse().diagnostics : ${ms(domNs)}\n" +
                    "new diagnose()              : ${ms(lowNs)}\n" +
                    "speedup ${"%.2f".format(domNs / lowNs)}x\n"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private inline fun bench(warmup: Int, runs: Int, ops: Int, op: () -> Long): Double {
        var bh = 0L
        repeat(warmup) { bh += op() }
        var best = Long.MAX_VALUE
        repeat(runs) {
            val t0 = System.nanoTime()
            var i = 0
            while (i < ops) { bh += op(); i++ }
            val dt = System.nanoTime() - t0
            if (dt < best) best = dt
        }
        if (bh == Long.MIN_VALUE) println(bh)
        return best.toDouble() / ops
    }

    private fun ms(v: Double): String = "%.2f ms".format(v / 1_000_000)
}
