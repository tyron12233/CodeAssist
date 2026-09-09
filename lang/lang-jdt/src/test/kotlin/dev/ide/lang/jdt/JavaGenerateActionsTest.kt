package dev.ide.lang.jdt

import dev.ide.analysis.ActionProvider
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.EditorActionContext
import dev.ide.analysis.FixContext
import dev.ide.index.IndexService
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.jdt.analysis.GenerateAccessorsActionProvider
import dev.ide.lang.jdt.analysis.GenerateConstructorActionProvider
import dev.ide.lang.jdt.analysis.GenerateEqualsHashCodeActionProvider
import dev.ide.lang.jdt.analysis.GenerateToStringActionProvider
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Java member generators. Each fixture marks the caret with `|` at a member position in the class
 * body; the assertions check the generated text, and the guards check that a member which already exists
 * is not generated twice.
 */
class JavaGenerateActionsTest {

    private val person = """
        package app;
        class Person {
            private final String name;
            private int age;
            |
        }
    """.trimIndent()

    @Test
    fun generatesAConstructorAssigningEveryInstanceField() {
        val out = apply(person, GenerateConstructorActionProvider())
        assertTrue("\n    public Person(String name, int age) {" in out, "member indent:\n$out")
        assertTrue("\n        this.name = name;" in out, "body indent:\n$out")
        assertTrue("\n        this.age = age;" in out, out)
        assertTrue("\n    }\n" in out, "closing brace indent:\n$out")
    }

    @Test
    fun staticFieldsAreNotConstructorParameters() {
        val src = "package app;\nclass A {\n    static int shared;\n    int own;\n    |\n}\n"
        val out = apply(src, GenerateConstructorActionProvider())
        assertTrue("public A(int own)" in out, "a static field is not per-instance state:\n$out")
    }

    @Test
    fun aConstructorTakingTheSameFieldsIsNotGeneratedTwice() {
        val src = "package app;\nclass A {\n    int a;\n    A(int a) { this.a = a; }\n    |\n}\n"
        assertTrue(titles(src, GenerateConstructorActionProvider()).isEmpty())
    }

    @Test
    fun generatesEqualsAndHashCodeAsAPair() {
        val out = apply(person, GenerateEqualsHashCodeActionProvider())
        assertTrue("public boolean equals(Object o) {" in out, out)
        assertTrue("public int hashCode() {" in out, out)
        // A reference field is compared null-safely; a primitive with ==.
        assertTrue("name != null ? !name.equals(that.name) : that.name != null" in out, out)
        assertTrue("age != that.age" in out, out)
        assertTrue("result = 31 * result + (name != null ? name.hashCode() : 0);" in out, out)
    }

    @Test
    fun arraysAreComparedAndHashedByContent() {
        val src = "package app;\nclass A {\n    int[] values;\n    |\n}\n"
        val out = apply(src, GenerateEqualsHashCodeActionProvider())
        assertTrue("!java.util.Arrays.equals(values, that.values)" in out, out)
        assertTrue("java.util.Arrays.hashCode(values)" in out, out)
    }

    @Test
    fun aLongFieldIsHashedByBothHalves() {
        val src = "package app;\nclass A {\n    long id;\n    |\n}\n"
        val out = apply(src, GenerateEqualsHashCodeActionProvider())
        assertTrue("(int) (id ^ (id >>> 32))" in out, out)
    }

    @Test
    fun equalsIsNotOfferedWhenItAlreadyExists() {
        val src = "package app;\nclass A {\n    int a;\n    public int hashCode() { return a; }\n    |\n}\n"
        assertTrue(titles(src, GenerateEqualsHashCodeActionProvider()).isEmpty())
    }

    @Test
    fun generatesToStringOverEveryField() {
        val out = apply(person, GenerateToStringActionProvider())
        assertTrue("""return "Person{" + "name=" + name + ", age=" + age + "}";""" in out, out)
    }

    @Test
    fun generatesAGetterForEveryFieldAndASetterForTheMutableOnes() {
        val out = apply(person, GenerateAccessorsActionProvider())
        assertTrue("\n    public String getName() {" in out, "member indent:\n$out")
        assertTrue("\n        return name;" in out, "body indent:\n$out")
        assertTrue("\n    public int getAge() {" in out, out)
        assertTrue("\n    public void setAge(int age) {" in out, out)
        // `name` is final, so it gets no setter.
        assertTrue("setName" !in out, "a final field must not get a setter:\n$out")
    }

    @Test
    fun aBooleanFieldGetsAnIsAccessor() {
        val src = "package app;\nclass A {\n    boolean ready;\n    |\n}\n"
        val out = apply(src, GenerateAccessorsActionProvider())
        assertTrue("public boolean isReady() {" in out, out)
    }

    @Test
    fun onlyMissingAccessorsAreGenerated() {
        val src = "package app;\nclass A {\n    int a;\n    int b;\n    public int getA() { return a; }\n    |\n}\n"
        val out = apply(src, GenerateAccessorsActionProvider())
        assertTrue("getB" in out, out)
        assertEquals(1, Regex("""public int getA\(\)""").findAll(out).count(), "getA must not be duplicated:\n$out")
    }

    @Test
    fun nothingIsGeneratedFromInsideAMethodBody() {
        val src = "package app;\nclass A {\n    int a;\n    void m() {\n        |\n    }\n}\n"
        for (p in providers()) {
            assertTrue(titles(src, p).isEmpty(), "${p::class.simpleName} offered inside a method body")
        }
    }

    @Test
    fun nothingIsGeneratedOutsideAClass() {
        val src = "package app;\n|\nclass A {\n    int a;\n}\n"
        for (p in providers()) {
            assertTrue(titles(src, p).isEmpty(), "${p::class.simpleName} offered outside a class")
        }
    }

    @Test
    fun aClassWithNoFieldsHasNothingToGenerate() {
        val src = "package app;\nclass A {\n    |\n}\n"
        for (p in providers()) {
            assertTrue(titles(src, p).isEmpty(), "${p::class.simpleName} offered for a field-less class")
        }
    }

    @Test
    fun aNestedClassGeneratesAtItsOwnIndentation() {
        val src = "package app;\nclass Outer {\n    static class Inner {\n        int a;\n        |\n    }\n}\n"
        val out = apply(src, GenerateToStringActionProvider())
        assertTrue("\n        @Override" in out, "member indent follows the nested class:\n$out")
        assertTrue("\n            return \"Inner{\"" in out, "body indent:\n$out")
    }

    private fun providers(): List<ActionProvider> = listOf(
        GenerateConstructorActionProvider(),
        GenerateEqualsHashCodeActionProvider(),
        GenerateToStringActionProvider(),
        GenerateAccessorsActionProvider(),
    )

    // ---- harness ----

    private fun titles(codeWithCaret: String, provider: ActionProvider): List<String> =
        withTarget(codeWithCaret) { ctx -> provider.actions(ctx).map { it.title } }

    private fun apply(codeWithCaret: String, provider: ActionProvider): String =
        withTarget(codeWithCaret) { ctx ->
            val fix = provider.actions(ctx).singleOrNull()
                ?: error("expected one action, got ${provider.actions(ctx).map { it.title }}")
            val text = ctx.target.parsed.text().toString()
            val edits = runSync { fix.computeEdits(Ctx(ctx.target)) }.edits.values.flatten()
            var out = text
            for (e in edits.sortedByDescending { it.offset }) {
                out = out.substring(0, e.offset) + e.newText + out.substring(e.offset + e.oldLength)
            }
            out
        }

    private fun <T> withTarget(codeWithCaret: String, block: (EditorActionContext) -> T): T {
        val at = codeWithCaret.indexOf('|')
        require(at >= 0) { "the fixture must mark the caret with |" }
        val src = codeWithCaret.removeRange(at, at + 1)
        val (analyzer, dir) = workspaceWith()
        return try {
            val file = StubFile(dir.resolve("app/Sample.java").toString(), src)
            val parsed = analyzer.parseSyntactic(file, src)
            block(EditorActionContext.of(Target(file, parsed, analyzer), TextRange(at, at)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private class Target(
        override val file: VirtualFile,
        override val parsed: ParsedFile,
        override val resolver: SourceAnalyzer,
    ) : AnalysisTarget {
        override val documentVersion = 1L
        override val index: IndexService get() = error("the generators do not query the index")
        override val module: Module get() = error("the generators do not read the module")
        override fun checkCanceled() {}
    }

    private class Ctx(override val target: AnalysisTarget) : FixContext {
        override fun checkCanceled() {}
    }
}
