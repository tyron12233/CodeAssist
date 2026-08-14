package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.index.PackagesIndex
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A bare name used as a qualified expression's RECEIVER (`viewModelScope.launch { }`) must be flagged
 * unresolved when it names a classpath callable no import brings into scope — the reported "the IDE never marks
 * a missing `viewModelScope` import" bug. Such a receiver used to be skipped unconditionally unless it was a
 * known library TYPE, because a receiver may also be a package segment (`kotlinx.coroutines.delay(1)`) or a
 * generated same-package class (`R`, `BuildConfig`) — so the check now fires only on POSITIVE, package-precise
 * evidence from the callable index, and never on a known root package.
 */
class KotlinUnimportedCallableReceiverTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val srcDir = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = readyIndex }
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun unresolved(diags: List<Diagnostic>) = diags.filter { it.code == "kt.unresolved" }.map { it.message }

    @Test
    fun unimportedExtensionPropertyReceiverIsFlagged() {
        val u = unresolved(diagnose("package demo\nclass Vm {\n  fun load() { viewModelScope.launch() }\n}"))
        assertTrue(
            u.any { it.contains("viewModelScope") },
            "an unimported extension property used as a receiver must be flagged; got $u",
        )
    }

    @Test
    fun flaggedReceiverOffersItsImportQuickFix() {
        // The payoff: the diagnostic is what the lightbulb keys off, so `viewModelScope` now offers its import.
        val code = "package demo\nclass Vm {\n  fun load() { viewModelScope.launch() }\n}"
        val srcDir = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = readyIndex }
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        val titles = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.importFixesAt(doc.file, code.indexOf("viewModelScope") + 1).map { it.title }
        }
        assertTrue(
            titles.any { it == "Import androidx.lifecycle.viewModelScope" },
            "the flagged receiver must offer its import; got $titles",
        )
    }

    @Test
    fun unimportedTopLevelPropertyReceiverIsFlagged() {
        val u = unresolved(diagnose("package demo\nfun f() { appScope.launch() }"))
        assertTrue(u.any { it.contains("appScope") }, "an unimported top-level property receiver must be flagged; got $u")
    }

    @Test
    fun importedTopLevelPropertyReceiverResolves() {
        val u = unresolved(diagnose("package demo\nimport lib.appScope\nfun f() { appScope.launch() }"))
        assertTrue(u.none { it.contains("appScope") }, "once imported the receiver must resolve; got $u")
    }

    @Test
    fun packageQualifierReceiverIsNotFlagged() {
        // `scope` is BOTH a root package and a top-level callable name: the leftmost segment of a fully-qualified
        // reference parses as a receiver, so a known root package must never be flagged.
        val u = unresolved(diagnose("package demo\nfun f() { scope.impl.doThing() }"))
        assertTrue(u.none { it.contains("scope") }, "a package qualifier must not be flagged; got $u")
    }

    @Test
    fun unknownReceiverIsStillNotFlagged() {
        // No evidence at all (not a type, not a callable, not a package) → still left alone, as before: it may be
        // a generated / not-yet-built class the index doesn't hold.
        val u = unresolved(diagnose("package demo\nfun f() { binding.root() }"))
        assertTrue(u.none { it.contains("binding") }, "an unknown receiver must not be flagged; got $u")
    }

    @Test
    fun sameFilePropertyReceiverIsNotFlagged() {
        // A same-file top-level property named like an indexed callable resolves without an import.
        val u = unresolved(diagnose("package demo\nval appScope = Scope()\nclass Scope { fun launch() {} }\nfun f() { appScope.launch() }"))
        assertTrue(u.none { it.contains("appScope") }, "a same-file property receiver must not be flagged; got $u")
    }

    private companion object {
        /** An indexed extension PROPERTY (`val ViewModel.viewModelScope`), keyed receiver-blind like the real one. */
        private val viewModelScope = CallableShape(
            name = "viewModelScope", kind = SymbolKind.FIELD, receiverFqn = "androidx.lifecycle.ViewModel",
            signature = ": CoroutineScope", packageName = "androidx.lifecycle", receiverTypeParam = null,
            typeParameters = emptyList(), returnType = KotlinType("kotlinx.coroutines.CoroutineScope"),
            paramTypes = emptyList(), receiverTypeArgs = emptyList(),
            declaringClassFqn = "androidx.lifecycle.ViewModelKt", paramNames = emptyList(),
            isComposable = false, isInline = false, isInfix = false, isSuspend = false,
        )

        /** A top-level property `lib.appScope` — resolvable bare only once imported. */
        private val appScope = CallableShape(
            name = "appScope", kind = SymbolKind.FIELD, receiverFqn = null, signature = ": CoroutineScope",
            packageName = "lib", receiverTypeParam = null, typeParameters = emptyList(),
            returnType = KotlinType("kotlinx.coroutines.CoroutineScope"), paramTypes = emptyList(),
            receiverTypeArgs = emptyList(), declaringClassFqn = "lib.LibKt", paramNames = emptyList(),
            isComposable = false, isInline = false, isInfix = false, isSuspend = false,
        )

        /** A top-level function whose name collides with a root PACKAGE (`scope.impl.…`). */
        private val scope = CallableShape(
            name = "scope", kind = SymbolKind.METHOD, receiverFqn = null, signature = "(): Unit",
            packageName = "lib", receiverTypeParam = null, typeParameters = emptyList(),
            returnType = KotlinType("kotlin.Unit"), paramTypes = emptyList(), receiverTypeArgs = emptyList(),
            declaringClassFqn = "lib.LibKt", paramNames = emptyList(),
            isComposable = false, isInline = false, isInfix = false, isSuspend = false,
        )

        @Suppress("UNCHECKED_CAST")
        val readyIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = when {
                id == KotlinCallableIndex.id && key == KotlinCallableIndex.nameKey("viewModelScope") ->
                    sequenceOf(viewModelScope)
                id == KotlinCallableIndex.id && key == KotlinCallableIndex.topKey("appScope") -> sequenceOf(appScope)
                id == KotlinCallableIndex.id && key == KotlinCallableIndex.topKey("scope") -> sequenceOf(scope)
                // The package indexes key every package PREFIX: `scope` is a real root package here.
                id in PackagesIndex.ALL && (key == "scope" || key == "scope.impl") -> sequenceOf(key)
                else -> emptySequence()
            } as Sequence<V>

            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }
    }
}
