package dev.ide.kotlin.classfile

import dev.ide.platform.openFile

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * What the whole stack costs on the platform it was built for.
 *
 * The decoder is correct on the phone, which the oracles settle. Whether it is USABLE on the phone is a
 * different question and a number, not an argument: an index that takes a minute to read `android.jar` needs
 * a different design from one that takes two seconds, and no amount of reasoning about Kotlin/Native
 * codegen substitutes for running it.
 *
 * Skipped unless `KOTLIN_CLASSFILE_BENCH_JAR` points at a jar, because the interesting corpus is tens of
 * megabytes and does not belong in the repository.
 *
 * In practice that means it runs on the JVM: a Gradle test task inherits the environment and a
 * Kotlin/Native one does not. Not worth plumbing, because a simulator number would not be a phone number
 * anyway. The simulator runs arm64 natively on the host CPU, so it reports Mac speed; the measurement that
 * decides anything needs a device.
 */
class ClasspathBenchmarkTest {

    @Test
    fun readingAWholeJarIsMeasured() {
        val path = readEnv("KOTLIN_CLASSFILE_BENCH_JAR")
        if (path == null) {
            println("KOTLIN_CLASSFILE_BENCH_JAR is unset; skipping the benchmark")
            return
        }

        val clock = TimeSource.Monotonic
        val source = assertNotNull(openFile(path), "opening $path")
        try {
            val openedAt = clock.markNow()
            val archive = assertNotNull(ZipArchive.open(source), "reading the central directory")
            val directoryMillis = openedAt.elapsedNow().inWholeMilliseconds

            val classes = archive.entries.filter { it.name.endsWith(".class") }
            assertTrue(classes.isNotEmpty(), "no classes in $path")

            var inflated = 0L
            var read = 0
            var kotlin = 0
            var declarations = 0
            var members = 0

            val startedAt = clock.markNow()
            for (entry in classes) {
                val bytes = archive.read(entry) ?: continue
                inflated += bytes.size
                val classFile = ClassFile.read(bytes) ?: continue
                read++
                members += classFile.fields.size + classFile.methods.size
                val annotation = classFile.metadata ?: continue
                val info = KotlinMetadata.read(annotation) ?: continue
                kotlin++
                declarations += info.declarations.size
            }
            val totalMillis = startedAt.elapsedNow().inWholeMilliseconds

            println(
                "benchmark ${path.substringAfterLast('/')}: " +
                    "${archive.entries.size} entries, directory in ${directoryMillis}ms; " +
                    "$read classes ($kotlin Kotlin, $members members, $declarations declarations) " +
                    "from ${inflated / 1024 / 1024} MB inflated in ${totalMillis}ms",
            )
        } finally {
            source.close()
        }
    }
}
