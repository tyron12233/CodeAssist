package dev.ide.lang.jdt

import dev.ide.lang.jdt.rename.JdtRename
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JdtRenameTest {

    private fun parse(analyzer: JdtSourceAnalyzer, file: Path): Pair<dev.ide.lang.jdt.dom.JdtParsedFile, String> {
        val text = Files.readString(file)
        return analyzer.parse(StubFile(file.toString(), text), text) to text
    }

    @Test
    fun findsFieldDeclarationAndAllUses() {
        val (analyzer, dir) = workspaceWith(
            "Foo.java" to "class Foo {\n  int count = 0;\n  int next() { count = count + 1; return count; }\n}",
        )
        val (parsed, text) = parse(analyzer, dir.resolve("Foo.java"))
        val target = JdtRename.targetAt(parsed, text.indexOf("count"))
        assertNotNull(target)
        assertEquals("field", target.kind)
        assertEquals("count", target.oldName)
        assertTrue(!target.fileLocal && !target.isType)
        val refs = JdtRename.referencesIn(parsed, target)
        assertEquals(4, refs.size) // declaration + 3 uses
        refs.forEach { assertEquals("count", text.substring(it.start, it.end)) }
    }

    @Test
    fun findsClassUsesAcrossFilesIncludingConstructor() {
        val (analyzer, dir) = workspaceWith(
            "Foo.java" to "public class Foo {\n  Foo() {}\n  void hi() {}\n}",
            "Bar.java" to "class Bar {\n  void use() { Foo f = new Foo(); f.hi(); }\n}",
        )
        val (parsedFoo, fooText) = parse(analyzer, dir.resolve("Foo.java"))
        val target = JdtRename.targetAt(parsedFoo, fooText.indexOf("Foo"))
        assertNotNull(target)
        assertTrue(target.isType)
        assertEquals("class", target.kind)
        // the class-declaration name + the constructor-declaration name (both spelled "Foo")
        assertEquals(2, JdtRename.referencesIn(parsedFoo, target).size)

        val (parsedBar, barText) = parse(analyzer, dir.resolve("Bar.java"))
        val barRefs = JdtRename.referencesIn(parsedBar, target)
        assertEquals(2, barRefs.size) // the `Foo` type + the `new Foo()` type
        barRefs.forEach { assertEquals("Foo", barText.substring(it.start, it.end)) }
    }

    @Test
    fun localVariableRenameIsFileLocalAndDoesNotTouchAShadowedField() {
        val (analyzer, dir) = workspaceWith(
            "A.java" to "class A {\n  int x;\n  void m() { int x = 1; int y = x + x; }\n}",
        )
        val (parsed, text) = parse(analyzer, dir.resolve("A.java"))
        // caret on the local `x` declaration (inside m), not the field
        val target = JdtRename.targetAt(parsed, text.indexOf("int x = 1") + 4)
        assertNotNull(target)
        assertTrue(target.fileLocal)
        assertEquals("local variable", target.kind)
        assertEquals(3, JdtRename.referencesIn(parsed, target).size) // local decl + 2 uses; field untouched
    }

    @Test
    fun findsMethodDeclarationAndInvocations() {
        val (analyzer, dir) = workspaceWith(
            "S.java" to "class S {\n  int sum(int a) { return a; }\n  int run() { return sum(1) + sum(2); }\n}",
        )
        val (parsed, text) = parse(analyzer, dir.resolve("S.java"))
        val target = JdtRename.targetAt(parsed, text.indexOf("sum"))
        assertNotNull(target)
        assertEquals("method", target.kind)
        assertEquals(3, JdtRename.referencesIn(parsed, target).size) // declaration + 2 invocations
    }
}
