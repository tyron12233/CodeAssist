package dev.ide.index.impl

import dev.ide.bench.Direction
import dev.ide.bench.RegressionSuite
import dev.ide.index.ClassNameExternalizer
import dev.ide.index.ClassNameValue
import dev.ide.index.Externalizer
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexScope
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wall time of a full index pass over a WARM segment cache: what every project re-open and every resync costs
 * before anything is rebuilt. The field data put 68% of all index time in passes after the first one of a
 * session, and those are almost all warm.
 *
 * Shaped like the IDE rather than like one extension: [EXTENSIONS] extensions (the IDE registers 29), of which
 * only [CLASS_EXTENSIONS] accept `.class` inputs; the rest are source-only, so every artifact still gets an
 * empty segment for each of them, as in production. [JARS] jars of [CLASSES_PER_JAR] classes each.
 *
 * Three numbers:
 *  - `cold`: first build on an empty cache (the reference, not the target).
 *  - `relaunch`: a FRESH service over the warm cache, as on project open.
 *  - `resync`: a second pass on the SAME service, as after a model commit.
 *
 * Gated loosely (latency drifts by machine) against `baselines/index-reopen.json`. Run with
 * `./gradlew :index-impl:regressionTest --tests '*IndexReopenBenchmark*'`.
 */
@Tag("regression")
class IndexReopenBenchmark {

    private class Ext(name: String, private val acceptsClasses: Boolean) : IndexExtension<String, ClassNameValue> {
        override val id = IndexId("bench.$name")
        override val version = 1
        override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
        override val valueExternalizer: Externalizer<ClassNameValue> = ClassNameExternalizer
        override val matching = MatchingMode.PREFIX_AND_FUZZY
        override val inputFilter = InputFilter { acceptsClasses && it.unitName?.endsWith(".class") == true }
        override fun index(input: IndexInput): Map<String, Collection<ClassNameValue>> {
            val name = input.unitName!!.substringAfterLast('/').removeSuffix(".class")
            return mapOf(name to listOf(ClassNameValue(name, input.origin, "class")))
        }
    }

    private val extensions = (0 until EXTENSIONS).map { Ext("e$it", acceptsClasses = it < CLASS_EXTENSIONS) }

    private fun writeJar(path: Path, jar: Int) {
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            for (c in 0 until CLASSES_PER_JAR) {
                z.putNextEntry(ZipEntry("com/bench/j$jar/Type${jar}x$c.class"))
                z.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                z.closeEntry()
            }
        }
    }

    private fun timeMs(block: () -> Unit): Double {
        val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1e6
    }

    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]

    @Test
    fun warmPassCost() {
        val dir = Files.createTempDirectory("reopen-bench")
        try {
            val libs = Files.createDirectories(dir.resolve("libs"))
            val jars = (0 until JARS).map { j -> libs.resolve("lib$j.jar").also { writeJar(it, j) } }
            val cache = dir.resolve("cache")
            val scope = IndexScope(libraryJars = jars)

            val cold = timeMs { runBlocking { IndexServiceImpl(extensions, cache).ensureUpToDate(scope) } }

            val relaunches = (0 until RUNS).map {
                val svc = IndexServiceImpl(extensions, cache)
                timeMs { runBlocking { svc.ensureUpToDate(scope) } }.also {
                    assertEquals(0, svc.status.stats!!.artifactsBuilt, "a warm relaunch must reuse every artifact")
                    svc.close()
                }
            }

            val svc = IndexServiceImpl(extensions, cache)
            runBlocking { svc.ensureUpToDate(scope) }
            val resyncs = (0 until RUNS).map { timeMs { runBlocking { svc.ensureUpToDate(scope) } } }
            svc.close()

            val segFiles = Files.walk(cache).use { s -> s.filter { it.toString().endsWith(".seg") }.count() }
            println(
                "\n=== Warm index pass: $JARS jars x $EXTENSIONS extensions ($CLASS_EXTENSIONS accept classes), " +
                    "$segFiles segment files ===\n" +
                    "cold build      %8.1f ms\n".format(cold) +
                    "relaunch (med)  %8.1f ms   runs=%s\n".format(median(relaunches), relaunches.map { "%.0f".format(it) }) +
                    "resync (med)    %8.1f ms   runs=%s\n".format(median(resyncs), resyncs.map { "%.0f".format(it) })
            )
            val suite = RegressionSuite("index-reopen")
            suite.latencyNs("relaunch.ns", median(relaunches) * 1e6, tolerance = 1.5, ceilingNs = 30e9)
            suite.latencyNs("resync.ns", median(resyncs) * 1e6, tolerance = 1.5, ceilingNs = 30e9)
            suite.count("segment.files", segFiles.toInt(), dir = Direction.LOWER_BETTER, tolerance = 0.0)
            suite.finishAndAssert()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val EXTENSIONS = 29
        const val CLASS_EXTENSIONS = 10
        const val JARS = 60
        const val CLASSES_PER_JAR = 200
        const val RUNS = 5
    }
}
