package dev.ide.lang.kotlin

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source go-to navigation ([KotlinSourceAnalyzer.navigationTargets]): Declaration / Type Declaration / Super
 * resolve the symbol at the caret to a project-source location. Uses the shared analyzer + `tempProject`
 * harness over the stdlib jar; no index needed for these (Implementation needs a ready SubtypeIndex, verified
 * elsewhere). The caret is the `|` marker (stripped).
 */
class KotlinNavigationTest {

    private fun nav(file: String, code: String, kind: NavKind): List<NavTarget> {
        val caret = code.indexOf('|')
        require(caret >= 0) { "no caret marker '|' in code" }
        val clean = code.removeRange(caret, caret + 1)
        return analyzer.navigationTargets(DiskFile(srcDir.resolve(file)), clean, caret, kind)
    }

    @Test
    fun declarationJumpsToTheFunctionDeclaration() {
        val code = "package demo\nfun greet() {}\nfun caller() { gr|eet() }"
        val clean = code.replace("|", "")
        val targets = nav("Use.kt", code, NavKind.DECLARATION)
        assertEquals(1, targets.size, "one declaration target; got $targets")
        assertEquals(clean.indexOf("greet"), targets[0].offset, "points at the `greet` declaration")
    }

    @Test
    fun declarationJumpsToALocalVal() {
        val code = "package demo\nfun caller() { val name = 1\nprintln(na|me) }"
        val clean = code.replace("|", "")
        val targets = nav("Use2.kt", code, NavKind.DECLARATION)
        assertTrue(targets.isNotEmpty(), "a local val resolves; got $targets")
        assertEquals(clean.indexOf("name"), targets[0].offset, "points at the `name` declaration")
    }

    @Test
    fun typeDeclarationJumpsToTheType() {
        // `item: Foo` — Type Declaration jumps to Foo's declaration (a fixture class, on disk).
        val code = "package demo\nfun caller(item: Foo) { it|em.hashCode() }"
        val targets = nav("Use3.kt", code, NavKind.TYPE_DECLARATION)
        assertTrue(targets.any { it.file.name == "Lib.kt" }, "jumps to Foo in Lib.kt; got $targets")
    }

    @Test
    fun superJumpsToTheSupertype() {
        // Caret on the subclass name → its supertype's source declaration.
        val code = "package demo\nclass Su|b : Base"
        val targets = nav("Use4.kt", code, NavKind.SUPER)
        assertTrue(targets.any { it.file.name == "Lib.kt" }, "jumps to Base in Lib.kt; got $targets")
    }

    @Test
    fun declarationJumpsToForwardTopLevelBackingProperty() {
        // Caret on a `_edit` READ inside `edit`'s getter → jump to the top-level `_edit` declared BELOW it
        // (the Compose ImageVector backing-property pattern; top-level decls are order-independent).
        val code = "package demo\n" +
            "val edit: Int\n" +
            "  get() {\n" +
            "    if (_e|dit != null) return _edit!!\n" +
            "    return _edit!!\n" +
            "  }\n" +
            "private var _edit: Int? = null\n"
        val clean = code.replace("|", "")
        val targets = nav("Edit.kt", code, NavKind.DECLARATION)
        assertTrue(targets.isNotEmpty(), "the forward top-level backing property must navigate; got $targets")
        assertEquals(clean.indexOf("_edit: Int?"), targets[0].offset, "points at the `_edit` declaration")
    }

    @Test
    fun nothingResolvesToNoTargets() {
        val code = "package demo\nfun caller() { val x = 1|2 }"
        assertTrue(nav("Use5.kt", code, NavKind.DECLARATION).isEmpty(), "a numeric literal has no declaration")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf("Lib.kt" to "package demo\nclass Foo\ninterface Base { fun f() }\n"),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
