package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.completion.KotlinCompletionItems
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Java method's parameters as Kotlin sees them. Its signature is decoded from bytecode with Java's type names,
 * and both the completion label and an override stub must show Kotlin's: `override fun onValue(value: int)` does
 * not compile (seen in the editor's completion popup on the emulator, `addFlags(flags: int)`).
 */
class JavaSignatureRenderingTest {

    @Test
    fun implementMembersWritesKotlinTypesForAJavaInterface() {
        val code = "package demo\nclass Impl : lib.Listener"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Impl.kt")))
        val fix = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.implementMembersFix(doc.file, code.indexOf("Impl"))
        }
        assertNotNull(fix, "a Java interface's abstract methods must be offered")
        val stub = fix.edits.joinToString("") { it.newText.toString() }
        assertTrue("override fun onValue(p0: Int, p1: Long, p2: Boolean)" in stub, stub)
        assertTrue("override fun onItems(p0: Array<String>, p1: IntArray, p2: Any)" in stub, stub)
        assertTrue("override fun onMany(vararg p0: String)" in stub, stub)
        assertTrue(listOf(" int", " long", "[]", "Object").none { it in stub }, stub)
    }

    @Test
    fun theCompletionLabelShowsKotlinTypes() {
        val result = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "package demo\nfun f(l: lib.Listener) { l.|on }")
        }
        val labels = result.items.map { it.label }
        assertTrue("onValue(p0: Int, p1: Long, p2: Boolean)" in labels, "got $labels")
    }

    @Test
    fun javaTypeNamesMapToKotlin() {
        val r = KotlinCompletionItems
        assertEquals("Int", r.javaTypeTextToKotlin("int"))
        assertEquals("IntArray", r.javaTypeTextToKotlin("int[]"))
        assertEquals("Array<IntArray>", r.javaTypeTextToKotlin("int[][]"))
        assertEquals("Array<String>", r.javaTypeTextToKotlin("String[]"))
        assertEquals("Any", r.javaTypeTextToKotlin("Object"))
        assertEquals("List<String>?", r.javaTypeTextToKotlin("List<String>?"), "Kotlin text passes through")
    }

    companion object {
        /** `lib.Listener`: a Java interface whose methods take primitives, arrays, Object and a vararg. */
        private val listenerJar: Path = TestJars.buildJar {
            asmClass(
                "lib/Listener",
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            ) {
                val abstract = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT
                visitMethod(abstract, "onValue", "(IJZ)V", null, null).visitEnd()
                visitMethod(abstract, "onItems", "([Ljava/lang/String;[ILjava/lang/Object;)V", null, null).visitEnd()
                visitMethod(abstract or Opcodes.ACC_VARARGS, "onMany", "([Ljava/lang/String;)V", null, null).visitEnd()
            }
        }

        val srcDir: Path = tempProject(mapOf("Placeholder.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath(), listenerJar)))
    }
}
