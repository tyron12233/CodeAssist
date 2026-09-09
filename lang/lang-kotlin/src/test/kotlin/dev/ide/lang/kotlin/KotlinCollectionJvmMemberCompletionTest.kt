package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The JDK methods Kotlin's `JvmBuiltInsCustomizer` grafts onto the mapped collection built-ins from their
 * `java.util.*` type (`MutableList.replaceAll`/`sort`, `MutableCollection.removeIf`, `Collection.stream`,
 * `Map.getOrDefault`, `MutableMap.putIfAbsent`, …). They are callable Kotlin but absent from the
 * `.kotlin_builtins` shape the backend decodes, so member completion missed them until they were pulled from
 * the mapped java bytecode (see [dev.ide.lang.kotlin.symbols.Builtins.ADDITIONAL_JVM_MEMBERS]).
 *
 * Needs a jar carrying `java.util.List`/`java.util.Map` (the kotlin-stdlib does not); self-gates on a real
 * android.jar (`assumeTrue`), like [KotlinThrowableMemberCompletionTest], so CI without an SDK skips it.
 */
class KotlinCollectionJvmMemberCompletionTest {

    private fun labels(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping collection JVM-member completion")
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }
    }

    @Test
    fun mutableListCompletesReplaceAllAndSort() {
        val ls = labels("fun main() {\n  val names = mutableListOf(\"\")\n  names.|\n}")
        assertTrue(ls.any { it.startsWith("replaceAll") }, "names.replaceAll must complete; got ${ls.take(30)}")
        assertTrue(ls.any { it.startsWith("sort") }, "names.sort must complete; got ${ls.take(30)}")
    }

    @Test
    fun replaceAllByPrefix() {
        val ls = labels("fun main() {\n  val names = mutableListOf(\"\")\n  names.replaceA|\n}")
        assertTrue(ls.any { it.startsWith("replaceAll") }, "names.replaceA| must offer replaceAll; got $ls")
    }

    @Test
    fun mutableCollectionCompletesRemoveIfAndStream() {
        val ls = labels("fun main() {\n  val names = mutableListOf(\"\")\n  names.|\n}")
        assertTrue(ls.any { it.startsWith("removeIf") }, "names.removeIf must complete; got ${ls.take(30)}")
        assertTrue(ls.any { it.startsWith("stream") }, "names.stream must complete; got ${ls.take(30)}")
    }

    @Test
    fun readOnlyListDoesNotGetMutatingReplaceAll() {
        // `replaceAll`/`sort`/`removeIf` are mutable-only — a read-only List must NOT surface them.
        val ls = labels("fun main() {\n  val names: List<String> = listOf(\"\")\n  names.|\n}")
        assertTrue(ls.none { it.startsWith("replaceAll") }, "read-only List must not offer replaceAll; got ${ls.take(30)}")
        assertTrue(ls.none { it.startsWith("removeIf") }, "read-only List must not offer removeIf; got ${ls.take(30)}")
        // but the non-mutating stream() IS on a read-only Collection.
        assertTrue(ls.any { it.startsWith("stream") }, "read-only List should offer stream; got ${ls.take(30)}")
    }

    @Test
    fun mutableMapCompletesJvmMembers() {
        val ls = labels("fun main() {\n  val m = mutableMapOf(\"\" to 1)\n  m.|\n}")
        assertTrue(ls.any { it.startsWith("getOrDefault") }, "m.getOrDefault must complete; got ${ls.take(40)}")
        assertTrue(ls.any { it.startsWith("putIfAbsent") }, "m.putIfAbsent must complete; got ${ls.take(40)}")
        assertTrue(ls.any { it.startsWith("computeIfAbsent") }, "m.computeIfAbsent must complete; got ${ls.take(40)}")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        private val androidJar: Path? = sdkRoots().map { it.resolve("platforms") }.filter { Files.isDirectory(it) }
            .flatMap { runCatching { Files.list(it).use { s -> s.toList() } }.getOrDefault(emptyList()) }
            .map { it.resolve("android.jar") }.filter { Files.isRegularFile(it) }
            .maxByOrNull { it.parent.fileName.toString() }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOfNotNull(stdlibJarPath(), androidJar)))

        private fun sdkRoots() = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("user.home") + "/Library/Android/sdk",
        ).map { Path.of(it) }.filter { Files.isDirectory(it) }
    }
}
