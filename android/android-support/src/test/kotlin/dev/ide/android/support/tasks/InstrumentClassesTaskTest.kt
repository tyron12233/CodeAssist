package dev.ide.android.support.tasks

import dev.ide.build.ClassTransform
import dev.ide.build.ClassTransformScope
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The class-transform seam: what a contributed [ClassTransform] rewrites has to be what gets dexed, and the
 * originals — a compiler's output, a jar shared with every other project on the machine — have to be left
 * exactly as they were.
 */
class InstrumentClassesTaskTest {

    /** Records what it was offered and rewrites the marker byte, so both halves are observable. */
    private class Marker(
        override val id: String,
        override val scope: ClassTransformScope = ClassTransformScope.ALL,
        private val from: Byte = 1,
        private val to: Byte = 2,
    ) : ClassTransform {
        val seen = mutableListOf<String>()
        override fun transform(className: String, bytes: ByteArray): ByteArray? {
            seen += className
            if (!bytes.contains(from)) return null
            return bytes.map { if (it == from) to else it }.toByteArray()
        }
    }

    private fun classDir(dir: Path, vararg entries: Pair<String, ByteArray>): Path {
        val root = dir.resolve("classes")
        for ((rel, bytes) in entries) {
            val f = root.resolve(rel)
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }
        return root
    }

    private fun jar(dir: Path, name: String, vararg entries: Pair<String, ByteArray>): Path {
        val path = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { zos ->
            for ((entry, bytes) in entries) {
                zos.putNextEntry(ZipEntry(entry))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return path
    }

    private fun run(task: InstrumentClassesTask): TaskResult =
        runBlocking { task.execute(SimpleTaskContext()) }

    private fun zipEntry(jar: Path, name: String): ByteArray =
        ZipFile(jar.toFile()).use { it.getInputStream(it.getEntry(name)).readBytes() }

    @Test
    fun rewritesClassesAndJarsAndLeavesTheOriginalsAlone() {
        withTempDir("instrument") { dir ->
            val original = byteArrayOf(1, 1, 9)
            val classes = classDir(dir, "com/example/App.class" to original.copyOf())
            val library = jar(dir, "lib.jar", "org/lib/Thing.class" to original.copyOf(), "META-INF/x.txt" to byteArrayOf(1))
            val out = dir.resolve("out")
            val transform = Marker("marker")

            val result = run(
                InstrumentClassesTask(
                    TaskName(":app:instrumentClasses"), "app",
                    listOf(classes), listOf(library), listOf(transform), out,
                )
            )
            assertEquals(TaskResult.Success, result)

            val instrumentedDir = InstrumentClassesTask.instrumentedClassDir(out, classes)
            assertContentEquals(
                byteArrayOf(2, 2, 9),
                Files.readAllBytes(instrumentedDir.resolve("com/example/App.class")),
                "the project class was rewritten",
            )
            val instrumentedJar = InstrumentClassesTask.instrumentedJar(out, library, listOf(transform))
            assertContentEquals(byteArrayOf(2, 2, 9), zipEntry(instrumentedJar, "org/lib/Thing.class"))
            assertContentEquals(
                byteArrayOf(1),
                zipEntry(instrumentedJar, "META-INF/x.txt"),
                "a non-class entry is copied through untouched",
            )

            assertContentEquals(
                original,
                Files.readAllBytes(classes.resolve("com/example/App.class")),
                "the compiler's output is never modified in place",
            )
            assertContentEquals(original, zipEntry(library, "org/lib/Thing.class"), "the library jar is untouched")

            assertEquals(
                listOf("com.example.App", "org.lib.Thing"),
                transform.seen,
                "transforms see binary class names, not paths",
            )
        }
    }

    /** Narrowing the scope is what keeps a build from paying to walk every dependency. */
    @Test
    fun honoursTheScopeOfEachTransform() {
        withTempDir("instrument-scope") { dir ->
            val classes = classDir(dir, "com/example/App.class" to byteArrayOf(1))
            val library = jar(dir, "lib.jar", "org/lib/Thing.class" to byteArrayOf(1))
            val projectOnly = Marker("project", ClassTransformScope.PROJECT)
            val depsOnly = Marker("deps", ClassTransformScope.DEPENDENCIES)

            run(
                InstrumentClassesTask(
                    TaskName(":app:instrumentClasses"), "app",
                    listOf(classes), listOf(library), listOf(projectOnly, depsOnly), dir.resolve("out"),
                )
            )

            assertEquals(listOf("com.example.App"), projectOnly.seen)
            assertEquals(listOf("org.lib.Thing"), depsOnly.seen)
        }
    }

    /** A dependency's rewritten copy is keyed by content, so the second build does not redo it. */
    @Test
    fun reusesAnAlreadyInstrumentedJar() {
        withTempDir("instrument-cache") { dir ->
            val library = jar(dir, "lib.jar", "org/lib/Thing.class" to byteArrayOf(1))
            val out = dir.resolve("out")
            fun task(transform: ClassTransform) = InstrumentClassesTask(
                TaskName(":app:instrumentClasses"), "app", emptyList(), listOf(library), listOf(transform), out,
            )

            val first = Marker("marker")
            run(task(first))
            assertEquals(1, first.seen.size)

            val second = Marker("marker")
            run(task(second))
            assertTrue(second.seen.isEmpty(), "the cached copy was reused instead of re-transformed")

            // A transform whose version moved is a different rewrite, so it must not reuse the old bytes.
            val bumped = object : ClassTransform by Marker("marker") {
                override val version = 2
                val calls = mutableListOf<String>()
                override fun transform(className: String, bytes: ByteArray): ByteArray? {
                    calls += className
                    return null
                }
            }
            run(task(bumped))
            assertEquals(listOf("org.lib.Thing"), bumped.calls, "a version bump invalidates the cached copy")
        }
    }

    /** A transform that throws fails the build; shipping the un-rewritten class would crash at runtime. */
    @Test
    fun failsTheBuildWhenATransformThrows() {
        withTempDir("instrument-throw") { dir ->
            val classes = classDir(dir, "com/example/App.class" to byteArrayOf(1))
            val exploding = object : ClassTransform {
                override val id = "boom"
                override fun transform(className: String, bytes: ByteArray): ByteArray? = error("no")
            }
            val result = run(
                InstrumentClassesTask(
                    TaskName(":app:instrumentClasses"), "app",
                    listOf(classes), emptyList(), listOf(exploding), dir.resolve("out"),
                )
            )
            assertTrue(result is TaskResult.Failed, "expected a failure, got $result")
            assertTrue("boom" in result.message, "the failing transform is named: ${result.message}")
        }
    }
}
