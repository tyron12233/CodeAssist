package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.platform.Disposable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `excludedTypePrefixes` gates ONLY index-backed type-NAME completion, hiding `androidx.*`/`android.*` in a
 * module that doesn't target Android (the shared class-names index holds every module's classpath). An Android
 * module must pass an EMPTY exclusion list so `androidx.compose.ui.Modifier` (and other Compose types) complete
 * by name — the bug where `val modifier: Modif|` offered only `java.lang.reflect.Modifier`. Driven off the
 * module's AndroidFacet by the host (`IdeServices`), replacing the brittle `android.jar`-by-filename sniff.
 */
class KotlinAndroidTypeExclusionTest {

    private val CLASS_NAMES = IndexId("java.classNames")

    private fun serviceWith(excluded: List<String>) = KotlinSymbolService(
        sourceRoots = emptyList(), classpathJars = emptyList(), index = fakeClassNamesIndex(), excludedTypePrefixes = excluded,
    )

    private fun typeFqns(svc: KotlinSymbolService, prefix: String): List<String> =
        svc.typeNameCandidates(prefix).symbols.mapNotNull { (it.type as? KotlinType)?.qualifiedName }

    @Test
    fun androidModuleKeepsAndroidxTypeNames() {
        val fqns = typeFqns(serviceWith(emptyList()), "Modif")
        assertTrue("androidx.compose.ui.Modifier" in fqns, "an Android module must offer the Compose Modifier type; got $fqns")
        assertTrue("java.lang.reflect.Modifier" in fqns, "and the java one; got $fqns")
    }

    @Test
    fun nonAndroidModuleHidesAndroidxButKeepsJava() {
        val fqns = typeFqns(serviceWith(listOf("android.", "androidx.", "com.android.")), "Modif")
        assertTrue("androidx.compose.ui.Modifier" !in fqns, "a non-Android module hides androidx type names; got $fqns")
        assertTrue("java.lang.reflect.Modifier" in fqns, "but keeps java.* ; got $fqns")
    }

    private fun fakeClassNamesIndex() = object : IndexService {
        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> {
            if (id != CLASS_NAMES) return emptySequence()
            return sequenceOf(
                Hit("Modifier", ClassNameValue("androidx.compose.ui.Modifier", IndexOrigin.LIBRARY, "INTERFACE"), 100),
                Hit("Modifier", ClassNameValue("java.lang.reflect.Modifier", IndexOrigin.LIBRARY, "CLASS"), 100),
            ) as Sequence<Hit<V>>
        }

        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = emptySequence()
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus(ready = true)
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }
}
