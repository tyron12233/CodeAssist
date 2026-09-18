package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Type-argument inference through coroutine builders whose functional block is NOT the first parameter —
 * `async`/`runBlocking`/`withContext` all declare their `block` LAST, after defaulted `context`/`start`
 * parameters, so a `builder { … }` call passes the block as a trailing lambda that fills the LAST parameter,
 * not the positional slot the lambda occupies. The type parameter these builders carry (`Deferred<T>` /
 * `T`) is bound from that block's result, so getting the argument→parameter mapping wrong left a raw
 * `Deferred`/`T` — and `.await()` (which returns the container's `T`) then lost all type information, so a
 * chain off it (`async { 42 }.await().<here>`) offered only the universal `Any` extensions.
 *
 * Regression guard for the "no suggestions on `.await()` after coroutines" report.
 */
class KotlinCoroutineBuilderInferenceTest {

    private fun members(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    private val IMPORTS = "package demo\nimport kotlinx.coroutines.*\n"

    @Test fun asyncBindsTFromBlockResultSoAwaitUnwrapsInt() {
        val m = members(IMPORTS + "suspend fun f(scope: CoroutineScope) { scope.async { 42 }.await().| }")
        assertTrue("toLong" in m && "inc" in m, "async { 42 }.await() should be Int; got ${m.take(20)}")
    }

    @Test fun asyncBindsTFromBlockResultSoAwaitUnwrapsString() {
        val m = members(IMPORTS + "suspend fun f(scope: CoroutineScope) { scope.async { \"s\" }.await().| }")
        assertTrue("length" in m, "async { \"s\" }.await() should be String; got ${m.take(20)}")
    }

    @Test fun asyncOnImplicitScopeReceiverStillUnwraps() {
        // The real user shape: async inside a builder that supplies the CoroutineScope receiver.
        val m = members(IMPORTS + "fun f() = runBlocking { async { 42 }.await().| }")
        assertTrue("toLong" in m, "async on the implicit runBlocking scope should unwrap to Int; got ${m.take(20)}")
    }

    @Test fun runBlockingReturnsBlockResultType() {
        val m = members(IMPORTS + "fun f() { runBlocking { 42 }.| }")
        assertTrue("toLong" in m, "runBlocking { 42 } should be Int; got ${m.take(20)}")
    }

    @Test fun withContextReturnsBlockResultType() {
        val m = members(IMPORTS + "suspend fun f() { withContext(Dispatchers.Default) { \"s\" }.| }")
        assertTrue("length" in m, "withContext(...) { \"s\" } should be String; got ${m.take(20)}")
    }

    @Test fun awaitStillSuggestedOnExplicitDeferred() {
        // Guard: the direct member path (receiver type args already known) keeps working.
        val m = members(IMPORTS + "suspend fun f(d: Deferred<Int>) { d.awa| }")
        assertTrue("await" in m, "await must be offered on a Deferred; got $m")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Marker.kt" to "package demo\nclass Marker"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), coroutinesJarPath())))

        /** The kotlinx-coroutines-core jar on the test classpath (carries `kotlinx/coroutines/Deferred.class`). */
        private fun coroutinesJarPath(): Path {
            val cp = System.getProperty("java.class.path").split(File.pathSeparator)
            val entry = cp.firstOrNull { e ->
                e.endsWith(".jar") && runCatching { ZipFile(e).use { it.getEntry("kotlinx/coroutines/Deferred.class") != null } }.getOrDefault(false)
            } ?: error("kotlinx-coroutines-core jar not found on test classpath")
            return Path.of(entry)
        }
    }
}
