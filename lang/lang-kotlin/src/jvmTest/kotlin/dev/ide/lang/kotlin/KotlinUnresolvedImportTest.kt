package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.resolve.SymbolKind
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The unresolved-IMPORT check: an explicit import whose target doesn't exist is flagged (Kotlin's "Unresolved
 * reference" on the import). It runs ONLY when a WIRED classpath index is ready, and checks BOTH:
 *  - CLASSIFIER-style (Capitalized) imports by NAME against the class-name index, and
 *  - lowercase CALLABLE imports (top-level / extension functions & properties) by PACKAGE + name against the
 *    callable index (`kotlin.callables`; its completeness + package-precision proven by
 *    [KotlinCallableIndexCompletenessTest]).
 * It backs off on member imports into a real type (an ancestor segment is a known type) so it never
 * false-positives on a genuine import.
 */
class KotlinUnresolvedImportTest {

    /** A minimal indexed callable — enough for the check's package+name query. */
    private fun cbl(name: String, pkg: String, receiver: String? = null) = CallableShape(
        name = name, kind = SymbolKind.METHOD, receiverFqn = receiver, signature = "()", packageName = pkg,
        receiverTypeParam = null, typeParameters = emptyList(), returnType = null, paramTypes = emptyList(),
        receiverTypeArgs = emptyList(), declaringClassFqn = null, paramNames = emptyList(),
        isComposable = false, isInline = false, isInfix = false, isSuspend = false,
    )

    // A ready index that knows a few real library type NAMES (name-only — no type shape), like the real
    // `java.classNames` index, PLUS a few real flow-operator extensions in the callable index (keyed by the
    // receiver-blind `name:` key with their true package). `Food`/`calculatePrice` are deliberately absent.
    @Suppress("UNCHECKED_CAST")
    private val readyIndex = object : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = when {
            id.value == "java.classNames" && key == "ComponentActivity" ->
                sequenceOf(ClassNameValue("androidx.activity.ComponentActivity", IndexOrigin.LIBRARY, "class"))
            id.value == "java.classNames" && key == "StateFlow" ->
                sequenceOf(ClassNameValue("kotlinx.coroutines.flow.StateFlow", IndexOrigin.LIBRARY, "class"))
            // A Java class whose static method is member-imported (`java.lang.Math.max`) — the back-off anchor.
            id.value == "java.classNames" && key == "Math" ->
                sequenceOf(ClassNameValue("java.lang.Math", IndexOrigin.LIBRARY, "class"))
            // Flow extension operators, receiver-blind `name:` keys, in their real package.
            id == KotlinCallableIndex.id && key == KotlinCallableIndex.nameKey("map") ->
                sequenceOf(cbl("map", "kotlinx.coroutines.flow", "kotlinx.coroutines.flow.Flow"))
            id == KotlinCallableIndex.id && key == KotlinCallableIndex.nameKey("stateIn") ->
                sequenceOf(cbl("stateIn", "kotlinx.coroutines.flow", "kotlinx.coroutines.flow.Flow"))
            id == KotlinCallableIndex.id && key == KotlinCallableIndex.nameKey("asStateFlow") ->
                sequenceOf(cbl("asStateFlow", "kotlinx.coroutines.flow", "kotlinx.coroutines.flow.MutableStateFlow"))
            else -> emptySequence()
        } as Sequence<V>
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus(ready = true)
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val srcDir = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = readyIndex }
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun unresolved(diags: List<Diagnostic>) = diags.filter { it.code == "kt.unresolved" }.map { it.message }

    @Test
    fun nonExistentTypeImportIsFlagged() {
        val u = unresolved(diagnose("Bad.kt", "package demo\nimport com.example.ordersystem.models.Food\nfun f() {}"))
        assertTrue(u.any { it.contains("Food") }, "a fabricated type import must be flagged; got $u")
    }

    @Test
    fun realNameIndexedTypeImportIsNotFlagged() {
        // Known by NAME only (no type shape) — must still be trusted, like the real class-name index.
        val u = unresolved(diagnose("A.kt", "package demo\nimport androidx.activity.ComponentActivity\nfun f() {}"))
        assertTrue(u.none { it.contains("ComponentActivity") }, "a name-indexed library type import must not be flagged; got $u")
    }

    @Test
    fun realCallableImportIsNotFlagged() {
        // The previously-reported false positive: valid top-level/extension flow operators. Now they resolve
        // package-precisely through the callable index instead of being blanket-skipped.
        val u = unresolved(
            diagnose(
                "C.kt",
                "package demo\n" +
                    "import kotlinx.coroutines.flow.map\n" +
                    "import kotlinx.coroutines.flow.stateIn\n" +
                    "import kotlinx.coroutines.flow.asStateFlow\n" +
                    "fun f() {}\n"
            )
        )
        assertTrue(u.none { it.contains("map") || it.contains("stateIn") || it.contains("asStateFlow") },
            "real callable imports in their true package must not be flagged; got $u")
    }

    @Test
    fun nonExistentCallableImportIsFlagged() {
        // A fabricated lowercase callable — the whole point of extending the check to callables.
        val u = unresolved(diagnose("D.kt", "package demo\nimport com.example.ordersystem.util.calculatePrice\nfun f() {}"))
        assertTrue(u.any { it.contains("calculatePrice") }, "a fabricated callable import must be flagged; got $u")
    }

    @Test
    fun callableImportInWrongPackageIsFlagged() {
        // `map` is real, but only in `kotlinx.coroutines.flow` — this import names a dead path. Proves the
        // check is package-precise (name-alone would wrongly accept this).
        val u = unresolved(diagnose("E.kt", "package demo\nimport com.wrong.pkg.map\nfun f() {}"))
        assertTrue(u.any { it.contains("map") }, "a callable import in the wrong package must be flagged; got $u")
    }

    @Test
    fun memberImportIntoRealTypeIsNotFlagged() {
        // `import <RealType>.Something` — parent is a known type, so a member/nested/enum-entry import backs off.
        val u = unresolved(diagnose("M.kt", "package demo\nimport androidx.activity.ComponentActivity.Companion\nfun f() {}"))
        assertTrue(u.none { it.contains("Companion") }, "a member import into a real type must not be flagged; got $u")
    }

    @Test
    fun staticMemberImportIntoRealTypeIsNotFlagged() {
        // A lowercase member imported THROUGH a known enclosing type (`java.lang.Math.max`) — the ancestor-type
        // back-off, so a Java static import (never in the Kotlin callable index) is trusted, not flagged.
        val u = unresolved(diagnose("S.kt", "package demo\nimport java.lang.Math.max\nfun f() {}"))
        assertTrue(u.none { it.contains("max") }, "a static member import into a real type must not be flagged; got $u")
    }

    @Test
    fun dumbModeDoesNotFlag() {
        // With no wired index (dumb / index-less), the check is skipped entirely — a fabricated import is trusted.
        val srcDir = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)) // no indexService wired
        val doc = SnippetDoc("package demo\nimport com.example.nope.Ghost\nfun f() {}", DiskFile(srcDir.resolve("D.kt")))
        val diags = runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        assertTrue(diags.none { it.code == "kt.unresolved" && it.message.contains("Ghost") },
            "without a ready index the import check must not run; got $diags")
    }
}
