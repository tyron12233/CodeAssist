package dev.ide.lang.kotlin

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Go-to-declaration into library / built-in types. A Kotlin BUILT-IN (`List`, `Int`, `String` — no `.class`,
 * declared in `.kotlin_builtins`) navigates to a reconstructed read-only stub; a stdlib TYPE with a `.class`
 * (`StringBuilder`) navigates to a `library://` target. Uses the live [BuiltinsReader]/ClasspathReader (no
 * index wired), so it's CI-safe against the real kotlin-stdlib jar.
 */
class KotlinLibraryNavTest {

    private fun caretTargets(code: String, marker: String): List<NavTarget> {
        val off = code.indexOf(marker)
        require(off >= 0) { "marker '$marker' not found" }
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return analyzer.navigationTargets(doc.file, code, off, NavKind.DECLARATION)
    }

    @Test
    fun goToDeclarationOnBuiltinTypeYieldsLibraryTarget() {
        // The type reference `List` must resolve to the TYPE, not the same-named `List(size, init)` factory fn.
        val targets = caretTargets("fun f(): List<Int> = TODO()", "List")
        val lib = targets.firstOrNull { it.kind == "library" }
        assertNotNull(lib, "List should resolve to a library target; got $targets")
        assertTrue(lib.file.path == "library://kotlin.collections.List", "path was ${lib.file.path}")
    }

    @Test
    fun goToDeclarationOnTypeArgumentResolvesThatType() {
        val targets = caretTargets("fun f(): List<String> = TODO()", "String")
        val lib = targets.firstOrNull { it.kind == "library" }
        assertNotNull(lib, "String type arg should resolve to a library target; got $targets")
        assertTrue(lib.file.path == "library://kotlin.String", "path was ${lib.file.path}")
    }

    @Test
    fun builtinStubRendersTheDeclaration() {
        val list = assertNotNull(analyzer.builtinStub("kotlin.collections.List"), "no stub for List")
        assertTrue("interface List" in list, "should render the interface header:\n$list")
        assertTrue("built-in" in list && "read-only" in list, "should carry the built-in banner:\n$list")
        assertTrue("fun " in list || "val " in list, "should list members:\n$list")

        val int = assertNotNull(analyzer.builtinStub("kotlin.Int"), "no stub for Int")
        assertTrue("class Int" in int, "should render the Int class:\n$int")
    }

    @Test
    fun isBuiltinTypeRecognizesTheCommonBuiltins() {
        // Reaches the service through a public nav path: a stub is produced iff the service knows the built-in.
        assertNotNull(analyzer.builtinStub("kotlin.String"))
        assertNotNull(analyzer.builtinStub("kotlin.collections.Map"))
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        // No index wired → builtins resolve via the live BuiltinsReader over the real kotlin-stdlib jar.
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath())))
    }
}
