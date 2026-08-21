package dev.ide.android.support.tools

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [DexInputDedup], the fix for `FAILED :<module>:generateSources: failed to dex compiler-plugin classpath:
 * Duplicate class '…'`. A module that activates two bundled KSP processors gets the UNION of two processor
 * closures, and each closure ships its own copy of the shared transitive libraries (all four bundles carry
 * `annotations-13.0.jar`; Room/Moshi/Hilt/Glide each carry their own `guava`, `kotlin-reflect`, `kotlinpoet`).
 * A `URLClassLoader` shadows the later copies; D8 rejects the whole input.
 */
class DexInputDedupTest {

    @Test
    fun aFullyShadowedJarIsDroppedFromTheDexInputs() = withTempDir("dedup-exact") { tmp ->
        // The real shape: the same `annotations-13.0.jar` reached through two processor bundles.
        val room = jarOf(tmp, "room/annotations-13.0.jar", mapOf("org/intellij/lang/annotations/Identifier.class" to bytes("a")))
        val hilt = jarOf(tmp, "hilt/annotations-13.0.jar", mapOf("org/intellij/lang/annotations/Identifier.class" to bytes("a")))

        val program = DexInputDedup.firstWins(listOf(room, hilt), tmp.resolve("dedup"))

        assertContentEquals(listOf(room), program, "the second copy contributes nothing and is dropped whole")
        assertNoDuplicateClasses(program)
    }

    @Test
    fun aPartiallyOverlappingJarIsRewrittenKeepingOnlyItsOwnClasses() = withTempDir("dedup-partial") { tmp ->
        // Two versions of one library: the newer adds a class, so neither jar is redundant.
        val old = jarOf(tmp, "guava-30.jar", mapOf("com/google/common/Base.class" to bytes("old"), "com/google/common/Old.class" to bytes("old")))
        val new = jarOf(tmp, "guava-33.jar", mapOf("com/google/common/Base.class" to bytes("new"), "com/google/common/New.class" to bytes("new")))

        val program = DexInputDedup.firstWins(listOf(old, new), tmp.resolve("dedup"))

        assertEquals(2, program.size)
        assertSame(old, program[0], "the first jar on the classpath is never touched")
        assertNoDuplicateClasses(program)
        // First-wins, the URLClassLoader rule: the shared class stays the FIRST jar's copy.
        assertContentEquals(bytes("old"), classEntry(program[0], "com/google/common/Base.class"))
        assertEquals(
            setOf("com/google/common/New.class"),
            classEntriesOf(program[1]),
            "the rewritten later jar keeps only what it alone defines",
        )
    }

    @Test
    fun aClasspathWithNoOverlapIsPassedThroughUntouched() = withTempDir("dedup-clean") { tmp ->
        val a = jarOf(tmp, "a.jar", mapOf("A.class" to bytes("a")))
        val b = jarOf(tmp, "b.jar", mapOf("B.class" to bytes("b")))
        val dedupDir = tmp.resolve("dedup")

        val program = DexInputDedup.firstWins(listOf(a, b), dedupDir)

        assertContentEquals(listOf(a, b), program, "original paths, no copies")
        assertTrue(!Files.exists(dedupDir), "nothing is written when there is nothing to resolve")
    }

    /** Resources are not classes: a jar carrying only `META-INF/services` must survive, never be `all seen`. */
    @Test
    fun aResourceOnlyJarIsKept() = withTempDir("dedup-res") { tmp ->
        val res = jarOf(tmp, "svc.jar", mapOf("META-INF/services/x.Y" to bytes("impl")))
        val other = jarOf(tmp, "svc2.jar", mapOf("META-INF/services/x.Y" to bytes("impl")))

        assertContentEquals(listOf(res, other), DexInputDedup.firstWins(listOf(res, other), tmp.resolve("dedup")))
    }

    /** A rewritten jar keeps its non-class entries, so nothing downstream loses a resource it still needs. */
    @Test
    fun aRewrittenJarKeepsItsResources() = withTempDir("dedup-keep-res") { tmp ->
        val a = jarOf(tmp, "a.jar", mapOf("Shared.class" to bytes("a")))
        val b = jarOf(
            tmp, "b.jar",
            mapOf("Shared.class" to bytes("b"), "Own.class" to bytes("b"), "META-INF/services/x.Y" to bytes("impl")),
        )

        val program = DexInputDedup.firstWins(listOf(a, b), tmp.resolve("dedup"))

        assertContentEquals(bytes("impl"), classEntry(program[1], "META-INF/services/x.Y"))
    }

    private fun assertNoDuplicateClasses(jars: List<Path>) {
        val seen = HashSet<String>()
        for (jar in jars) {
            for (entry in classEntriesOf(jar)) {
                assertTrue(seen.add(entry), "$entry is defined twice on the dex inputs (D8 would reject this)")
            }
        }
    }

    private fun classEntriesOf(jar: Path): Set<String> = ZipFile(jar.toFile()).use { zf ->
        zf.entries().asSequence().filter { it.name.endsWith(".class") }.mapTo(HashSet()) { it.name }
    }

    private fun classEntry(jar: Path, name: String): ByteArray =
        ZipFile(jar.toFile()).use { zf -> zf.getInputStream(zf.getEntry(name)).use { it.readBytes() } }

    /** Distinct, valid-enough bytes per copy: this exercises entry bookkeeping, never class parsing. */
    private fun bytes(tag: String): ByteArray = "class-$tag".toByteArray()

    private fun jarOf(dir: Path, relative: String, entries: Map<String, ByteArray>): Path {
        val jar = dir.resolve(relative)
        Files.createDirectories(jar.parent)
        JarOutputStream(Files.newOutputStream(jar)).use { jos ->
            for ((entry, content) in entries) {
                jos.putNextEntry(JarEntry(entry)); jos.write(content); jos.closeEntry()
            }
        }
        return jar
    }
}
