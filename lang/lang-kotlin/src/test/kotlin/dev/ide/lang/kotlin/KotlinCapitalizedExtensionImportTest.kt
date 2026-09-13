package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.resolve.SymbolKind
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An `import` whose final segment is Capitalized but names a top-level **extension** — every Compose Material
 * icon (`import androidx.compose.material.icons.filled.Add` is a `val Icons.Filled.Add: ImageVector`).
 *
 * The import validator picked its lookup by the leaf's first letter: Capitalized went to the classifier
 * branch, which asks for a type, a member of a known parent type, or a `top:`-keyed top-level callable. An
 * extension is indexed under `ext:`/`name:` and never `top:`, so the classifier branch could not see it and
 * the import line was flagged `Unresolved reference: Add`. Capitalization is a convention, not a rule, so the
 * first letter cannot decide what an import names.
 *
 * Reported against the Nimbus Weather store template: `Add` and `Search` on two files, and since 3.14 a file
 * with errors refuses ALL of its previews, so the whole home screen went blank.
 *
 * The validator only runs against a READY classpath index (it must not guess while one is building), so this
 * serves a real [CallableShape] through a fake index the way [KotlinCallableIndexTest] does.
 */
class KotlinCapitalizedExtensionImportTest {

    private fun messages(code: String): List<String> = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics.map { it.message }
    }

    @Test
    fun aCapitalizedExtensionImportIsNotFlagged() {
        val msgs = messages("package demo\nimport $ICON_PKG.$ICON_NAME\n")
        assertFalse(
            msgs.any { "Unresolved reference: $ICON_NAME" in it },
            "a Capitalized top-level extension import must resolve; got $msgs",
        )
    }

    @Test
    fun theLowercaseSpellingStillResolvesToo() {
        val msgs = messages("package demo\nimport $ICON_PKG.$LOWER_NAME\n")
        assertFalse(
            msgs.any { "Unresolved reference: $LOWER_NAME" in it },
            "the lowercase branch was already correct and must stay so; got $msgs",
        )
    }

    /** The control: a Capitalized leaf the index knows nothing about is still a genuine error, so the test
     *  above proves the lookup found something rather than that the check stopped reporting. */
    @Test
    fun anUnknownCapitalizedImportIsStillFlagged() {
        val msgs = messages("package demo\nimport $ICON_PKG.NoSuchIcon\n")
        assertTrue(
            msgs.any { "Unresolved reference: NoSuchIcon" in it },
            "an import of a name nothing declares must still be reported; got $msgs",
        )
    }

    /** And a Capitalized extension in a DIFFERENT package than the one imported is not a match either. */
    @Test
    fun theExtensionMustLiveInTheImportedPackage() {
        val msgs = messages("package demo\nimport some.other.pkg.$ICON_NAME\n")
        assertTrue(
            msgs.any { "Unresolved reference: $ICON_NAME" in it },
            "the package has to match, not just the name; got $msgs",
        )
    }

    companion object {
        private const val ICON_PKG = "androidx.compose.material.icons.filled"
        private const val ICON_NAME = "Add"
        private const val LOWER_NAME = "addIcon"
        private const val RECEIVER = "androidx.compose.material.icons.Icons.Filled"

        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        private fun shape(name: String) = CallableShape(
            name = name,
            kind = SymbolKind.FIELD,
            receiverFqn = RECEIVER,
            signature = null,
            packageName = ICON_PKG,
            receiverTypeParam = null,
            typeParameters = emptyList(),
            returnType = null,
            paramTypes = emptyList(),
            receiverTypeArgs = emptyList(),
            declaringClassFqn = "$ICON_PKG.${name}Kt",
            paramNames = emptyList(),
            isComposable = false,
            isInline = false,
            isInfix = false,
            isSuspend = false,
        )

        /** Exactly what the real producer emits for an extension: a receiver-keyed entry plus the
         *  receiver-blind name key. Deliberately NO `top:` entry — that absence is the bug's cause. */
        private val served: Map<String, List<CallableShape>> = listOf(ICON_NAME, LOWER_NAME).flatMap { n ->
            listOf(
                KotlinCallableIndex.extKey(RECEIVER, n) to listOf(shape(n)),
                KotlinCallableIndex.nameKey(n) to listOf(shape(n)),
            )
        }.toMap()

        @Suppress("UNCHECKED_CAST")
        private val fakeIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                if (id == KotlinCallableIndex.id) served[key]?.asSequence()?.map { it as V } ?: emptySequence()
                else emptySequence()

            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
                if (id == KotlinCallableIndex.id)
                    served.asSequence()
                        .filter { it.key.startsWith(prefix) }
                        .flatMap { (k, vs) -> vs.asSequence().map { Hit(k, it as V, 0) } }
                        .take(limit)
                else emptySequence()

            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true) // the validator only runs against a ready index
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = fakeIndex }
    }
}
