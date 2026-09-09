package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Member completion on the concrete-class collection typealiases (`ArrayList`, `HashMap`, `HashSet`,
 * `LinkedHashMap`, `LinkedHashSet`). These resolve to `kotlin.collections.*` (via `DEFAULT_SIMPLE_TYPES`),
 * but unlike the read-only/mutable interfaces they are `typealias`es to `java.util.*` with no
 * `.kotlin_builtins` shape and no `.class`. Regression: `val l = ArrayList<String>()` completed NOTHING
 * (`.add`/`.get`/… missing) because the alias was absent from `Builtins.KOTLIN_TO_JAVA`, so member
 * enumeration never routed to `java.util.ArrayList`'s bytecode.
 *
 * Own members (`.add`, `.get`, …) come from the aliased java.util type, whose bytecode the kotlin-stdlib
 * does not carry; self-gates on a real android.jar (`assumeTrue`), like [KotlinThrowableMemberCompletionTest].
 */
class KotlinCollectionTypealiasMemberTest {

    private fun labels(code: String): List<String> {
        assumeTrue(androidJar != null, "no android.jar on this machine; skipping collection-typealias completion")
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }
    }

    @Test
    fun arrayListCompletesMembersAndExtensions() {
        val ls = labels("fun main() {\n  val l = ArrayList<String>()\n  l.|\n}")
        assertTrue(ls.any { it.startsWith("add(") }, "l.add(...) must complete; got ${ls.take(30)}")
        assertTrue(ls.any { it.startsWith("get(") }, "l.get(...) must complete; got ${ls.take(30)}")
        assertTrue("size" in ls, "l.size must complete; got ${ls.take(30)}")
        assertTrue(ls.any { it.startsWith("map(") }, "the Iterable.map extension must complete; got ${ls.take(30)}")
    }

    @Test
    fun hashMapCompletesMembers() {
        val ls = labels("fun main() {\n  val m = HashMap<String, Int>()\n  m.|\n}")
        assertTrue(ls.any { it.startsWith("put(") }, "m.put(...) must complete; got ${ls.take(30)}")
        assertTrue(ls.any { it.startsWith("get(") }, "m.get(...) must complete; got ${ls.take(30)}")
        assertTrue(ls.any { it.startsWith("containsKey(") }, "m.containsKey(...) must complete; got ${ls.take(30)}")
    }

    @Test
    fun hashSetCompletesMembers() {
        val ls = labels("fun main() {\n  val s = HashSet<String>()\n  s.|\n}")
        assertTrue(ls.any { it.startsWith("add(") }, "s.add(...) must complete; got ${ls.take(30)}")
        assertTrue(ls.any { it.startsWith("contains(") }, "s.contains(...) must complete; got ${ls.take(30)}")
    }

    @Test
    fun explicitTypeArgumentBindsElementType() {
        // `ArrayList<String>()` pins the element type, so an element read is a String — String members complete.
        val ls = labels("fun main() {\n  val l = ArrayList<String>()\n  val e = l[0]\n  e.|\n}")
        assertTrue("length" in ls, "an ArrayList<String> element must be a String (l[0].length); got ${ls.take(30)}")
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
