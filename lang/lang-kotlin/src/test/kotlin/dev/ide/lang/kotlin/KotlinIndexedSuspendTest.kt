package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.CallableShapeExternalizer
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `suspend` callable resolved through the persistent `kotlin.callables` index (a LIBRARY/binary suspend
 * function, the wired-IDE path) must keep its `isSuspend` flag — otherwise the suspend calling-convention
 * check ([KotlinSourceAnalyzer]'s `suspendInvocation`) silently no-ops on it. Regression: [CallableShape]
 * used to carry `isComposable`/`isInline` but drop `isSuspend`, so a library suspend call from a plain
 * function was never flagged in the real IDE (only the standalone scan path preserved it).
 */
class KotlinIndexedSuspendTest {

    private fun suspendShape(name: String) = CallableShape(
        name = name, kind = SymbolKind.METHOD, receiverFqn = null, signature = "(): Unit",
        packageName = "lib", receiverTypeParam = null, typeParameters = emptyList(),
        returnType = KotlinType("kotlin.Unit"), paramTypes = emptyList(), receiverTypeArgs = emptyList(),
        declaringClassFqn = "lib.LibKt", paramNames = emptyList(), isComposable = false, isInline = false,
        isInfix = false, isSuspend = true,
    )

    @Test fun callableShapeRoundTripsIsSuspend() {
        val shape = suspendShape("doWork")
        assertTrue(shape.toSymbol(null).isSuspend, "from/toSymbol must preserve isSuspend")
        // Codec round-trip (the persisted form).
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { CallableShapeExternalizer.write(it, shape) }
        val back = DataInputStream(ByteArrayInputStream(bos.toByteArray())).use { CallableShapeExternalizer.read(it) }
        assertTrue(back.isSuspend, "the codec must persist isSuspend")
        assertTrue(back.toSymbol(null).isSuspend, "the decoded shape's symbol must be suspend")
    }

    @Test fun callableShapeRoundTripsIsInfix() {
        // `infix fun Int.downTo(to: Int)` → the shape must carry isInfix through from/toSymbol AND the codec,
        // or infix-function completion in the operator slot breaks for indexed (library) callables.
        val shape = CallableShape(
            name = "downTo", kind = SymbolKind.METHOD, receiverFqn = "kotlin.Int", signature = "(to: Int): IntProgression",
            packageName = "kotlin.ranges", receiverTypeParam = null, typeParameters = emptyList(),
            returnType = KotlinType("kotlin.ranges.IntProgression"), paramTypes = listOf(KotlinType("kotlin.Int")),
            receiverTypeArgs = emptyList(), declaringClassFqn = "kotlin.ranges.RangesKt", paramNames = listOf("to"),
            isComposable = false, isInline = false, isInfix = true, isSuspend = false,
        )
        assertTrue(shape.toSymbol(null).isInfix, "from/toSymbol must preserve isInfix")
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { CallableShapeExternalizer.write(it, shape) }
        val back = DataInputStream(ByteArrayInputStream(bos.toByteArray())).use { CallableShapeExternalizer.read(it) }
        assertTrue(back.isInfix, "the codec must persist isInfix")
        assertTrue(back.toSymbol(null).isInfix, "the decoded shape's symbol must be infix")
    }

    @Test fun indexedLibrarySuspendCallIsFlagged() {
        val text = "package demo\nfun f() { doWork() }"
        val doc = SnippetDoc(text, DiskFile(srcDir.resolve("Use.kt")))
        val diags: List<Diagnostic> = runBlocking {
            analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics
        }
        assertTrue(
            diags.any { it.code == "kt.suspendContext" },
            "a library suspend call resolved via the index must be flagged; got ${diags.map { it.code to it.message }}",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        // An index serving exactly one entry: the top-level suspend callable `doWork` (so it resolves ONLY via
        // the index path — it is not in the source model). Mirrors the wired IDE for a library suspend function.
        private val doWork = CallableShape(
            name = "doWork", kind = SymbolKind.METHOD, receiverFqn = null, signature = "(): Unit",
            packageName = "lib", receiverTypeParam = null, typeParameters = emptyList(),
            returnType = KotlinType("kotlin.Unit"), paramTypes = emptyList(), receiverTypeArgs = emptyList(),
            declaringClassFqn = "lib.LibKt", paramNames = emptyList(), isComposable = false, isInline = false,
            isInfix = false, isSuspend = true,
        )

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                if (id == KotlinCallableIndex.id && key == KotlinCallableIndex.topKey("doWork"))
                    sequenceOf(doWork as V) else emptySequence()

            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
                if (id == KotlinCallableIndex.id && KotlinCallableIndex.topKey("doWork").startsWith(prefix))
                    sequenceOf(Hit(KotlinCallableIndex.topKey("doWork"), doWork as V, 0)) else emptySequence()

            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = fakeIndex }
    }
}
