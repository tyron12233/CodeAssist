package dev.ide.lang.java

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiJavaFile
import dev.ide.lang.java.index.JavaSourceIndexer
import dev.ide.psi.IntellijPsiHost
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hammers structural parses of DISTINCT sources on the ONE shared [IntellijPsiHost] env from many threads,
 * asserting every extraction is correct and nothing corrupts or crashes.
 *
 * History: this began as the experiment for "parallelize source indexing" — it parsed through a shared read
 * lock, so `buildTree` really did run concurrently, and it passed on the desktop JVM and on an Android 8.0
 * device. A 32-bit ART tombstone from the field then caught a native SIGSEGV inside exactly that concurrent
 * `buildTree`, so the host serializes every parse again ([IntellijPsiHost.withParseLock]) and the concurrency
 * here is now on the CALLERS, not on tree building. The test is kept because it still exercises what matters:
 * many threads driving the shared env through the lock must each get a correct, complete extraction.
 */
class ConcurrentParseSpikeTest {

    @Test
    fun concurrentStructuralExtractionsAreCorrectAndCrashFree() {
        // 1. Single-threaded warm-up: force ALL Java lazy init (element types, file type, lexer, flyweight
        //    pools) BEFORE any concurrency, so the concurrent reads can't race first-touch initialization.
        IntellijPsiHost.warmUp()
        JavaSourceIndexer.parse("package w;\npublic class Warm { void m(){ int x = 1; } }")

        // 2. Distinct sources (unique package + names → each parse is independent, results are verifiable).
        val n = 400
        val sources = (0 until n).map { i ->
            i to "package p$i;\npublic class C$i extends Base$i { public int m$i(String a){ return $i; } int f$i; }"
        }

        // 3. Hammer concurrent structural parses.
        val pool = Executors.newFixedThreadPool(8)
        val correct = ConcurrentHashMap<Int, Boolean>()
        val errors = CopyOnWriteArrayList<Throwable>()
        try {
            val futures = sources.map { (i, src) ->
                pool.submit {
                    try {
                        val parsed = IntellijPsiHost.parseStructural("C$i.java", JavaLanguage.INSTANCE, src) { psi ->
                            JavaSourceIndexer.declsOf(psi as PsiJavaFile)
                        }
                        correct[i] = parsed.decls.any { it.name == "C$i" && it.kind == JavaSourceIndexer.DeclKind.CLASS } &&
                            parsed.decls.any { it.name == "m$i" && it.kind == JavaSourceIndexer.DeclKind.METHOD } &&
                            parsed.decls.any { it.name == "f$i" && it.kind == JavaSourceIndexer.DeclKind.FIELD }
                    } catch (t: Throwable) {
                        errors += t
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }

        assertTrue(errors.isEmpty(), "concurrent parse threw: ${errors.take(3).map { it.toString() }}")
        assertEquals(n, correct.size, "every source should have produced a result")
        assertTrue(correct.values.all { it }, "a concurrent parse produced wrong/incomplete declarations")
    }
}
