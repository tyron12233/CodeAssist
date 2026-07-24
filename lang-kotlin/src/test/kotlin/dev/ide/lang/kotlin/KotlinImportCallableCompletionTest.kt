package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinPackageDeclIndex
import dev.ide.lang.kotlin.index.PkgDecl
import dev.ide.lang.resolve.SymbolKind
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Import completion after a package dot (`import kotlinx.coroutines.flow.<caret>`) must offer the package's
 * top-level CALLABLES + extensions (`map`, `stateIn`), not just its types. Names come from the package-keyed
 * `kotlin.pkgDecls` index; each is shaped + visibility-filtered through the callable index, so a private/
 * internal library callable (present in `kotlin.pkgDecls`, which has no visibility filter, but NOT in the
 * completion-facing callable index) is dropped. Types still appear.
 */
class KotlinImportCallableCompletionTest {

    private fun names(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.mapNotNull { it.symbol?.name }

    @Test
    fun packageDotOffersLibraryCallables() {
        val items = names("package demo\nimport kotlinx.coroutines.flow.|\n")
        assertTrue("map" in items, "a package-level extension (map) must be offered when completing an import; got ${items.take(30)}")
        assertTrue("stateIn" in items, "a package-level extension (stateIn) must be offered; got ${items.take(30)}")
    }

    @Test
    fun packageDotStillOffersTypes() {
        assertTrue("Flow" in names("package demo\nimport kotlinx.coroutines.flow.|\n"),
            "types must still be offered alongside callables")
    }

    @Test
    fun internalLibraryCallableIsNotOffered() {
        // `internalHelper` is enumerated by `kotlin.pkgDecls` (no visibility filter) but absent from the
        // callable index (its producer drops internal/private) — so it must not leak into completion.
        assertFalse("internalHelper" in names("package demo\nimport kotlinx.coroutines.flow.|\n"),
            "an internal library callable must be filtered out of import completion")
    }

    @Test
    fun callableInsertsBareNameNotCallForm() {
        // The reported bug: an import callable must insert `map`, not `map()` — an import line takes a bare name.
        val res = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "package demo\nimport kotlinx.coroutines.flow.|\n") }
        val map = res.items.firstOrNull { it.symbol?.name == "map" }
        assertNotNull(map, "map must be offered")
        assertEquals("map", map.insertText, "an import callable must insert its bare name, not the call form")
    }

    @Test
    fun callableInWrongPackageIsNotOffered() {
        // `map` is indexed (receiver-blind), but declared in `kotlinx.coroutines.flow`; completing an import in
        // a DIFFERENT package must not surface it (package-precise, mirrors the diagnostic).
        assertFalse("map" in names("package demo\nimport kotlin.collections.|\n"),
            "a callable from another package must not be offered here")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        private const val FLOW = "kotlinx.coroutines.flow"

        private fun cbl(name: String, pkg: String, receiver: String? = null) = CallableShape(
            name = name, kind = SymbolKind.METHOD, receiverFqn = receiver, signature = "()", packageName = pkg,
            receiverTypeParam = null, typeParameters = emptyList(), returnType = null, paramTypes = emptyList(),
            receiverTypeArgs = emptyList(), declaringClassFqn = null, paramNames = emptyList(),
            isComposable = false, isInline = false, isInfix = false, isSuspend = false,
        )

        // Package-scoped declarations `kotlin.pkgDecls` would hold for kotlinx.coroutines.flow: a type, two
        // public extensions, and one INTERNAL callable (which the callable index below deliberately omits).
        private val pkgDecls = mapOf(
            FLOW to listOf(
                PkgDecl("Flow", classifier = true, facade = null),
                PkgDecl("map", classifier = false, facade = "$FLOW.FlowKt"),
                PkgDecl("stateIn", classifier = false, facade = "$FLOW.FlowKt"),
                PkgDecl("internalHelper", classifier = false, facade = "$FLOW.FlowKt"),
            ),
        )

        // The visibility-filtered callable index: the two PUBLIC extensions only (no internalHelper).
        private val callables = mapOf(
            KotlinCallableIndex.nameKey("map") to listOf(cbl("map", FLOW, "$FLOW.Flow")),
            KotlinCallableIndex.nameKey("stateIn") to listOf(cbl("stateIn", FLOW, "$FLOW.Flow")),
        )

        private val CLASS_NAMES = IndexId("java.classNames")
        private val PACKAGE_TYPES = IndexId("java.packageTypes")

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = when {
                id == KotlinPackageDeclIndex.id -> pkgDecls[key]?.asSequence()?.map { it as V } ?: emptySequence()
                id == KotlinCallableIndex.id -> callables[key]?.asSequence()?.map { it as V } ?: emptySequence()
                id == PACKAGE_TYPES && key == FLOW ->
                    sequenceOf(ClassNameValue("$FLOW.Flow", IndexOrigin.LIBRARY, "class") as V)
                id == CLASS_NAMES && key == "Flow" ->
                    sequenceOf(ClassNameValue("$FLOW.Flow", IndexOrigin.LIBRARY, "class") as V)
                else -> emptySequence()
            }
            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = fakeIndex }
    }
}
