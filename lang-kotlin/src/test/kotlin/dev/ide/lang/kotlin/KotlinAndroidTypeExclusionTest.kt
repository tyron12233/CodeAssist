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

    // --- androidNamespacesVisible: the decision that picks the exclusion list, keyed on the module's OWN
    // classpath so a Compose module never loses `androidx.compose.ui.Modifier` just because its host-side
    // Android-ness flag came out false/null (a disabled android-support plugin, a `kotlin-*` Compose module,
    // an on-device `android.jar` bundled under another name). See the bug this reproduces at the class KDoc. ---

    private fun path(s: String) = java.nio.file.Paths.get(s)

    @Test
    fun androidxOnClasspathStaysVisibleEvenWhenHostSaysNonAndroid() {
        // The reported failure mode: host injected `false` (no decoded AndroidFacet), yet the module depends on
        // Compose. The androidx jar on the classpath must keep the namespaces visible.
        val cp = listOf(path("/repo/.platform/caches/resolved-deps/androidx/compose/ui/ui-android/1.7.5/ui-android-1.7.5-exploded/classes.jar"))
        assertTrue(androidNamespacesVisible(isAndroidModule = false, classpathJars = cp), "androidx on classpath must win over a false flag")
        assertTrue(androidNamespacesVisible(isAndroidModule = null, classpathJars = cp), "…and over an unset flag")
    }

    @Test
    fun androidJarByNameOrHostFlagKeepsNamespacesVisible() {
        assertTrue(androidNamespacesVisible(isAndroidModule = true, classpathJars = emptyList()), "host flag true → visible")
        assertTrue(
            androidNamespacesVisible(isAndroidModule = null, classpathJars = listOf(path("/sdk/platforms/android-36/android.jar"))),
            "android.jar boot classpath → visible",
        )
    }

    @Test
    fun pureJvmModuleWithNoAndroidJarsHidesNamespaces() {
        val cp = listOf(
            path("/repo/.platform/caches/resolved-deps/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"),
            path("/repo/.platform/caches/resolved-deps/com/google/guava/guava/33.0.0/guava-33.0.0.jar"),
        )
        assertTrue(!androidNamespacesVisible(isAndroidModule = false, classpathJars = cp), "no android/androidx on classpath → hidden")
        assertTrue(!androidNamespacesVisible(isAndroidModule = null, classpathJars = cp), "…same for an unset flag")
    }

    @Test
    fun androidStoragePathDoesNotFalselyTriggerVisibility() {
        // On-device jars live under `…/Android/data/<pkg>/…`; that must NOT be read as an androidx dependency.
        val cp = listOf(path("/storage/emulated/0/Android/data/com.example.app/files/Projects/app/.platform/caches/resolved-deps/org/jetbrains/kotlin/kotlin-stdlib/2.0.0/kotlin-stdlib-2.0.0.jar"))
        assertTrue(!androidNamespacesVisible(isAndroidModule = false, classpathJars = cp), "the Android storage path is not an androidx coordinate; got visible")
    }

    /**
     * Two DISTINCT types that share a simple name but live in different packages (`androidx.compose.ui.Modifier`
     * vs `java.lang.reflect.Modifier`) must BOTH complete — the completion dedup used to collapse them by simple
     * name (the bytecode index records every type as kind "class"), keeping only whichever the index returned
     * first. On-device the `java.*` one sorted ahead, so the Compose `Modifier` vanished even though it was
     * indexed. The dedup now keys types by fully-qualified name, so both survive (disambiguated by package).
     */
    @Test
    fun sameSimpleNameTypesFromDifferentPackagesBothComplete() {
        kotlinx.coroutines.runBlocking {
            val dir = tempProject(mapOf("Use.kt" to "package demo\n"))
            val a = KotlinSourceAnalyzer(fakeContext(dir))
            a.indexService = fakeClassNamesIndex()
            a.isAndroidModule = true // an Android module: androidx.* is not hidden
            val items = a.completeAtCaret(dir, "Use.kt", "package demo\nfun f() { val m = Modif| }").items
            val containers = items.filter { it.insertText == "Modifier" }.mapNotNull { it.container }.toSet()
            assertTrue("androidx.compose.ui" in containers, "Compose Modifier must complete; got $containers")
            assertTrue(
                "java.lang.reflect" in containers,
                "the java Modifier must also complete — distinct types are not collapsed; got $containers",
            )
        }
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
