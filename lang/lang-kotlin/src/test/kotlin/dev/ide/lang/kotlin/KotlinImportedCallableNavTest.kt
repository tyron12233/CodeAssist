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
import dev.ide.lang.resolve.SymbolKind
import dev.ide.platform.Disposable
import dev.ide.testkit.TestJars
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reference whose simple name is ALSO the simple name of some unrelated class on the classpath must not
 * resolve to that class. A file importing `androidx.compose.material3.Text` (a top-level function) had every
 * go-to navigation, hover and quick doc land on `android.jar`'s `org.w3c.dom.Text`, because when the scope
 * lookup missed, resolution fell back to searching the classpath for ANY type with that simple name.
 *
 * The fixture is that exact shape: a jar holding `org.w3c.dom.Text` plus the Kotlin file facade
 * `androidx.compose.material3.TextKt` whose static `Text(String)` is the imported function, and a class-names
 * index that knows the w3c type by its simple name (as android.jar's entry does).
 */
class KotlinImportedCallableNavTest {

    private val CLASS_NAMES = IndexId("java.classNames")

    private fun index(withCallable: Boolean) = object : IndexService {
        private val classHit =
            Hit("Text", ClassNameValue("org.w3c.dom.Text", IndexOrigin.LIBRARY, "INTERFACE"), 100)
        private val callable = CallableShape(
            name = "Text", kind = SymbolKind.METHOD, receiverFqn = null, signature = "(text: String)",
            packageName = "androidx.compose.material3", receiverTypeParam = null, typeParameters = emptyList(),
            returnType = null, paramTypes = emptyList(), receiverTypeArgs = emptyList(),
            declaringClassFqn = "androidx.compose.material3.TextKt", paramNames = listOf("text"),
            isComposable = true, isInline = false, isInfix = false, isSuspend = false,
        )

        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> =
            if (id == CLASS_NAMES && "Text".startsWith(pattern)) sequenceOf(classHit) as Sequence<Hit<V>>
            else emptySequence()

        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = when {
            id == CLASS_NAMES && key == "Text" -> sequenceOf(classHit.value) as Sequence<V>
            withCallable && id == KotlinCallableIndex.id && key == KotlinCallableIndex.topKey("Text") ->
                sequenceOf(callable) as Sequence<V>
            else -> emptySequence()
        }

        @Suppress("UNCHECKED_CAST")
        override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
            if (id == CLASS_NAMES && "Text".startsWith(prefix)) sequenceOf(classHit) as Sequence<Hit<V>>
            else emptySequence()

        override suspend fun ensureUpToDate(scope: IndexScope) {}
        override suspend fun reindexSource(path: Path, text: String) {}
        override val status = IndexStatus(ready = true)
        override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
    }

    private fun libJar(): Path = TestJars.buildJar {
        asmClass("org/w3c/dom/Text", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT)
        asmClass("androidx/compose/material3/TextKt") {
            visitAnnotation("Lkotlin/Metadata;", true).apply {
                visit("mv", intArrayOf(2, 0, 0))
                visit("k", 2)
                visitEnd()
            }
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "Text", "(Ljava/lang/String;)V", null, null).apply {
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 1)
                visitEnd()
            }
        }
    }

    /** Navigation targets for every [NavKind] at the `Text("hello")` call. */
    private fun targets(withCallable: Boolean): Map<NavKind, List<String>> {
        val src = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, libJars = listOf(stdlibJarPath(), libJar())))
        analyzer.indexService = index(withCallable)
        val code = "package demo\n" +
            "import androidx.compose.material3.Text\n" +
            "fun screen() {\n" +
            "    Text(\"hello\")\n" +
            "}\n"
        val doc = SnippetDoc(code, DiskFile(src.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        val off = code.indexOf("Text(\"hello\")")
        return NavKind.entries.associateWith { kind ->
            analyzer.navigationTargets(doc.file, code, off, kind).map { it.file.path }
        }
    }

    @Test
    fun anImportedTopLevelFunctionNeverNavigatesToASameNamedUnrelatedClass() {
        val byKind = targets(withCallable = false)
        val all = byKind.values.flatten()
        assertTrue(
            all.none { "org.w3c.dom" in it },
            "no navigation may land on an unimported same-named class; got $byKind",
        )
    }

    @Test
    fun anImportedTopLevelFunctionNavigatesToItsOwnFacade() {
        val byKind = targets(withCallable = true)
        assertEquals(
            listOf("library://androidx.compose.material3.TextKt#Text"),
            byKind[NavKind.DECLARATION],
            "declaration lands on the facade declaring the imported function; got $byKind",
        )
        assertTrue(
            byKind.values.flatten().none { "org.w3c.dom" in it },
            "and never on the same-named class; got $byKind",
        )
    }

    @Test
    fun aGenuineTypeReferenceStillResolves() {
        // The fallback still has to work for a name that IS a type in scope: `StringBuilder` via `java.lang`.
        val src = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, libJars = listOf(stdlibJarPath())))
        val code = "package demo\nfun f(): StringBuilder = TODO()\n"
        val doc = SnippetDoc(code, DiskFile(src.resolve("Use2.kt")))
        analyzer.incrementalParser.parseFull(doc)
        val targets = analyzer.navigationTargets(doc.file, code, code.indexOf("StringBuilder"), NavKind.DECLARATION)
        assertTrue(
            targets.any { "StringBuilder" in it.file.path },
            "a type in scope by a default star import must still navigate; got $targets",
        )
    }
}
