package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.index.MemberValue
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cross-language SOURCE interop: a Kotlin file enumerating a same-project Java SOURCE class's members,
 * which have no `.class` and no `@Metadata`; they come from the `java.membersByOwner` index (keyed by owner
 * FQN, public-only). Here a fake [IndexService] stands in for the real one (built in ide-core), serving the
 * `JavaSrc` type name + its members, to verify the lang-kotlin consumer path end to end.
 */
class CrossLanguageInteropTest {

    private val javaSrcFqn = "com.example.JavaSrc"

    @Suppress("UNCHECKED_CAST")
    private val fakeIndex = object : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = when {
            id.value == "java.membersByOwner" && key == javaSrcFqn -> sequenceOf(
                MemberValue("greet", javaSrcFqn, "method", ""),
                MemberValue("count", javaSrcFqn, "field", ""),
                // a private one must NOT be here — the index only emits public members (visibility-safe)
            ) as Sequence<V>
            id.value == "java.classNames" && key == "JavaSrc" ->
                sequenceOf(ClassNameValue(javaSrcFqn, IndexOrigin.SOURCE, "class")) as Sequence<V>
            else -> emptySequence()
        }

        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus(ready = true)
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    @Test
    fun kotlinSeesJavaSourceClassMembers() {
        val srcDir = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = fakeIndex }
        val items = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "import com.example.JavaSrc\nfun f(j: JavaSrc) { j.| }")
        }.items.mapNotNull { it.symbol?.name }
        assertTrue("greet" in items, "Kotlin should see the Java SOURCE method via java.membersByOwner; got $items")
        assertTrue("count" in items, "Kotlin should see the Java SOURCE field; got $items")
    }
}
