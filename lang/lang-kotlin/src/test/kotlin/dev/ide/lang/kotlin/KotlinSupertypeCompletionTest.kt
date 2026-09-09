package dev.ide.lang.kotlin

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smart TYPE-candidate insertion:
 *  - in a class's supertype list a CLASS is a superclass constructor call (`: Base()`), an interface stays a
 *    bare type (`: Runner`), and a generic type gets its angle brackets (`: Holder<>`, `: Box<>()`);
 *  - in a value position an instantiable class completes as a constructor call (`val v = Base()`), while an
 *    interface / a type annotation stays bare;
 *  - a class used as a nested type ARGUMENT of a supertype (`: List<Base>`) is not itself a supertype entry;
 *  - the caret lands inside the parens when the constructor has required arguments.
 */
class KotlinSupertypeCompletionTest {

    private fun itemOf(code: String, name: String): CompletionItem {
        val items = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "$PRELUDE\n$code") }.items
        return items.firstOrNull { (it.symbol?.name ?: it.label) == name }
            ?: error("no completion item named '$name'; got ${items.map { it.symbol?.name ?: it.label }.take(30)}")
    }

    private fun insertOf(code: String, name: String): String = itemOf(code, name).insertText

    // ---- supertype list ----

    @Test fun classSupertypeInsertsConstructorCall() = assertEquals("Base()", insertOf("class Foo : Ba|", "Base"))

    @Test fun interfaceSupertypeStaysBare() = assertEquals("Runner", insertOf("class Foo : Run|", "Runner"))

    @Test fun genericInterfaceSupertypeGetsAngleBrackets() =
        assertEquals("Holder<>", insertOf("class Foo : Hol|", "Holder"))

    @Test fun genericClassSupertypeGetsAngleBracketsAndCall() =
        assertEquals("Box<>()", insertOf("class Foo : Bo|", "Box"))

    @Test fun classAsSupertypeTypeArgumentStaysBare() =
        assertEquals("Base", insertOf("class Foo : Holder<Ba|>", "Base"))

    // ---- value position ----

    @Test fun valuePositionClassInsertsConstructorCall() =
        assertEquals("Base()", insertOf("fun f() { val v = Ba| }", "Base"))

    @Test fun valuePositionInterfaceStaysBare() =
        assertEquals("Runner", insertOf("fun f() { val v = Run| }", "Runner"))

    @Test fun valuePositionRequiredArgLandsCaretInsideParens() {
        val item = itemOf("fun f() { val v = Pers| }", "Person")
        assertEquals("Person()", item.insertText)
        assertEquals(CaretAction.At("Person".length + 1), item.caret) // between the parens, ready for `name`
    }

    // ---- type-annotation position ----

    @Test fun typeAnnotationClassStaysBare() =
        assertEquals("Base", insertOf("fun f() { val v: Ba| }", "Base"))

    @Test fun genericTypeAnnotationGetsAngleBrackets() =
        assertEquals("Holder<>", insertOf("fun f() { val v: Hol| }", "Holder"))

    companion object {
        private const val PRELUDE =
            "package demo\n" +
                "open class Base\n" +
                "open class Box<T>\n" +
                "class Person(val name: String)\n" +
                "interface Runner\n" +
                "interface Holder<T>"
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
