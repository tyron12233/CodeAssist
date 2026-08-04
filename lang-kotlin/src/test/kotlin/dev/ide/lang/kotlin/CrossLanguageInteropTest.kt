package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.index.JavaSourceMemberCodec
import dev.ide.index.MemberValue
import dev.ide.lang.dom.Diagnostic
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cross-language SOURCE interop: a Kotlin file resolving a same-project Java SOURCE class, whose members have
 * no `.class` and no `@Metadata` and arrive through the `java.membersByOwner` index (keyed by owner FQN,
 * public-only). A fake [IndexService] stands in for the real one (built in ide-core), serving type names +
 * members exactly as the real index would — including each member's SHAPE encoded via [JavaSourceMemberCodec],
 * so this verifies the whole lang-kotlin consumer path: a `static` method surfaces on `Type.` and a call
 * type-checks with real arity (the reported `Test.main(arrayOf(""))` bug), while instance members stay on an
 * instance receiver.
 */
class CrossLanguageInteropTest {

    private val greeterFqn = "com.example.Greeter"

    /** A Java class: `static void main(String[])`, instance `String greet(String)`, `static final int VERSION`. */
    private val greeterMembers = listOf(
        MemberValue("main", greeterFqn, "method",
            JavaSourceMemberCodec.encodeMethod(static = true, listOf("args"), listOf("String[]"), "void", -1)),
        MemberValue("greet", greeterFqn, "method",
            JavaSourceMemberCodec.encodeMethod(static = false, listOf("name"), listOf("String"), "String", -1)),
        MemberValue("VERSION", greeterFqn, "field",
            JavaSourceMemberCodec.encodeField(static = true, "int")),
    )

    @Suppress("UNCHECKED_CAST")
    private val fakeIndex = object : IndexService {
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = when {
            id.value == "java.membersByOwner" && key == greeterFqn -> greeterMembers.asSequence() as Sequence<V>
            id.value == "java.classNames" && key == "Greeter" ->
                sequenceOf(ClassNameValue(greeterFqn, IndexOrigin.SOURCE, "class")) as Sequence<V>
            else -> emptySequence()
        }

        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus(ready = true)
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    private val srcDir = tempProject(emptyMap())
    private val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).apply { indexService = fakeIndex }

    private fun completions(fileName: String, code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, fileName, code) }.items.mapNotNull { it.symbol?.name }

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun staticMembersSurfaceOnTheTypeReceiver() {
        val items = completions(
            "TypeRecv.kt",
            "import com.example.Greeter\nfun f() { Greeter.| }",
        )
        assertTrue("main" in items, "a Java `static` method must surface on `Greeter.`; got $items")
        assertTrue("VERSION" in items, "a Java `static` field must surface on `Greeter.`; got $items")
        assertTrue("greet" !in items, "a Java INSTANCE method must NOT surface on the type receiver; got $items")
    }

    @Test
    fun instanceMembersSurfaceOnAnInstanceReceiver() {
        val items = completions(
            "InstRecv.kt",
            "import com.example.Greeter\nfun f(g: Greeter) { g.| }",
        )
        assertTrue("greet" in items, "a Java instance method must surface on an instance receiver; got $items")
        assertTrue("main" !in items, "a Java `static` method must NOT surface on an instance receiver; got $items")
    }

    @Test
    fun staticCallWithCorrectArityDoesNotFalselyError() {
        // The reported bug: `main(String[])` came back shapeless (0 params) so a 1-arg call was "too many".
        val d = diagnose(
            "GoodCall.kt",
            "import com.example.Greeter\nfun f() { Greeter.main(arrayOf(\"\")) }",
        )
        assertTrue(
            d.none { it.code == "kt.argumentCount" },
            "`Greeter.main(arrayOf(\"\"))` matches `main(String[])` — no argument-count error; got $d",
        )
    }

    @Test
    fun staticCallWithTooManyArgumentsIsFlagged() {
        val d = diagnose(
            "BadCall.kt",
            "import com.example.Greeter\nfun f() { Greeter.main(arrayOf(\"\"), arrayOf(\"\")) }",
        )
        assertTrue(
            d.any { it.code == "kt.argumentCount" },
            "two arguments to a 1-parameter `main` must be flagged too-many; got $d",
        )
    }

    @Test
    fun instanceCallResolvesWithoutFalseError() {
        val d = diagnose(
            "InstCall.kt",
            "import com.example.Greeter\nfun f(g: Greeter) { val s: String = g.greet(\"x\") }",
        )
        assertTrue(
            d.none { it.code == "kt.argumentCount" || it.code == "kt.unresolved" },
            "`g.greet(\"x\")` returning String must resolve cleanly; got $d",
        )
    }
}
