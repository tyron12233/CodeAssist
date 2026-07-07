package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SUPERTYPE_NOT_INITIALIZED: a class-type supertype written without its constructor call (`class C : Base` →
 * `Base()`). Fires only for a class supertype that must be initialized in the supertype list; an interface,
 * a `Base()` call, a secondary-constructor `super(...)`, an unknown, or a type-parameter supertype is clean.
 */
class KotlinSupertypeInitTest {
    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }
    private fun b(fn: String) = "package demo\n$fn\n"
    private val C = "kt.supertypeNotInitialized"

    @Test fun bareClassSupertypeIsFlagged() {
        assertTrue(diagnose("S1.kt", b("open class Base\nclass C : Base")).any { it.code == C }, "class : Base must be Base()")
        assertTrue(diagnose("S2.kt", b("abstract class A\nclass C : A")).any { it.code == C }, "abstract class supertype still needs ()")
        assertTrue(diagnose("S3.kt", b("open class Base\nobject O : Base")).any { it.code == C }, "object : Base must be Base()")
    }

    @Test fun properlyInitializedOrInterfaceIsClean() {
        val ok = listOf(
            "open class Base\nclass C : Base()",                          // initialized
            "interface I\nclass C : I",                                    // an interface is bare-legal
            "interface I\nopen class Base\nclass C : Base(), I",           // class initialized + bare interface
            "open class Base\nclass C : Base {\n  constructor() : super()\n}", // secondary ctor initializes it
            "interface A\ninterface B : A",                                // an interface's supertypes are never initialized
        )
        for (o in ok) assertTrue(diagnose("Sok.kt", b(o)).none { it.code == C }, "`$o` must be clean; got ${diagnose("Sok.kt", b(o))}")
    }

    @Test fun unknownOrTypeParameterSupertypeBacksOff() {
        assertTrue(diagnose("S4.kt", b("class C : SomeUnknownExternal")).none { it.code == C }, "an unknown supertype backs off")
        assertTrue(diagnose("S5.kt", b("class C<T> : T")).none { it.code == C }, "a type-parameter supertype backs off")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
