package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.parse.KotlinParserHost
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Regression guard for the on-device crash where opening a Kotlin project SIGSEGV'd in ART's
 * `artInstanceOfFromCode` during library-source indexing (`KotlinSourceDocIndex` → `KotlinParserHost.parse`
 * → `PsiBuilderImpl.buildTree`). The index build fans artifacts out across a bounded `Dispatchers.IO` pool,
 * and each `KtFile`'s KDoc is an `ILazyParseableElementType` whose subtree is built on first access, so the
 * `docComment.text` read in the (unlocked) index traversal fired `buildTree` on several threads at once,
 * corrupting the non-thread-safe standalone PSI. [KotlinParserHost.parse] now materializes the whole tree
 * under its parse lock, so concurrent traversal only ever reads a built tree.
 *
 * On the JVM this reproduced as intermittent exceptions / wrong doc text rather than a hard SIGSEGV, so the
 * test asserts every worker completes cleanly and reads the expected KDoc under contention.
 */
class KotlinParserConcurrencyTest {

    @Test
    fun concurrentParseAndKdocTraversalIsSafe() {
        KotlinParserHost.warmUp()

        // Distinct sources so workers don't share a KtFile: exactly the index shape (one file per artifact
        // entry), each with a KDoc chameleon that a naive traversal would build lazily off the parse lock.
        val sources = (0 until 8).map { i ->
            "com/example/Doc$i.kt" to """
                package com.example
                /** Type $i doc. */
                class Doc$i {
                    /** Greets [who] from $i. */
                    fun greet(who: String, loud: Boolean): String {
                        val target = who
                        return target
                    }
                }
            """.trimIndent()
        }

        val threads = 6
        val itersPerThread = 40
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<Throwable>()
        val docsSeen = CopyOnWriteArrayList<String>()

        try {
            val done = CountDownLatch(threads)
            repeat(threads) { t ->
                pool.submit {
                    try {
                        start.await()
                        repeat(itersPerThread) { iter ->
                            val (name, src) = sources[(t + iter) % sources.size]
                            val kt = KotlinParserHost.parse(name, src)
                            // Mirror KotlinSourceDocIndex: read each declaration's KDoc (the lazy node) and
                            // its members. Before the fix this triggered buildTree off the parse lock.
                            for (decl in kt.declarations) {
                                val cls = decl as? KtClassOrObject ?: continue
                                cls.docComment?.text?.let { docsSeen.add(it) }
                                for (m in cls.declarations) {
                                    (m as? KtNamedFunction)?.docComment?.text?.let { docsSeen.add(it) }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish (possible deadlock)")
        } finally {
            pool.shutdownNow()
        }

        assertTrue(errors.isEmpty(), "concurrent parse/traversal threw: ${errors.take(3).map { it.toString() }}")
        // Sanity: the KDoc actually materialized correctly under contention (not empty/garbled).
        assertTrue(docsSeen.any { it.contains("Greets [who]") }, "method KDoc not recovered under contention")
        assertTrue(docsSeen.any { it.contains("doc.") }, "class KDoc not recovered under contention")
    }

    @Test
    fun forcedParseYieldsFullyBuiltTree() {
        // A single-threaded correctness check that parse returns a materialized tree: the KDoc is present and
        // reads correctly right after parse, with no further parsing step needed by the caller.
        val kt = KotlinParserHost.parse(
            "com/example/Solo.kt",
            """
                package com.example
                /** Solo type. */
                class Solo {
                    /** Does [x]. */
                    fun run(x: Int) {}
                }
            """.trimIndent(),
        )
        val cls = kt.declarations.filterIsInstance<KtClassOrObject>().single()
        assertTrue(cls.docComment?.text?.contains("Solo type") == true)
        val fn = cls.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "run" }
        assertTrue(fn.docComment?.text?.contains("Does [x]") == true)
        assertEquals(listOf("x"), fn.valueParameters.map { it.name })
    }
}
