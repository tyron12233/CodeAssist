package dev.ide.lang.kotlin

import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.kotlin.parse.KotlinParserHost
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin auto-import placement ([KotlinImportEdits.planImport]) and the "Optimize Imports" command
 * ([KotlinImportOrganizer]). Placement splices a new import in sorted position; Optimize sorts, de-duplicates,
 * collapses to a wildcard at the threshold, and drops imports whose bound name is never referenced.
 */
class KotlinImportOrganizerTest {

    private fun applyEdits(src: String, edits: List<DocumentEdit>): String {
        val sb = StringBuilder(src)
        for (e in edits.sortedByDescending { it.offset }) sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        return sb.toString()
    }

    // ---- placement ----

    private fun planned(src: String, fqn: String): String {
        val kt = KotlinParserHost.parse("Test.kt", src)
        val plan = KotlinImportEdits.planImport(kt, fqn) ?: return src
        return src.substring(0, plan.offset) + plan.text + src.substring(plan.offset)
    }

    @Test fun insertsInSortedPosition() {
        val src = "package p\n\nimport a.A\nimport c.C\n\nclass X\n"
        assertEquals("package p\n\nimport a.A\nimport b.B\nimport c.C\n\nclass X\n", planned(src, "b.B"))
    }

    @Test fun firstImportLandsBlankLineAfterPackage() {
        val src = "package p\n\nclass X\n"
        assertEquals("package p\n\nimport a.A\nclass X\n", planned(src, "a.A"))
    }

    @Test fun alreadyImportedIsNoOp() {
        val src = "package p\n\nimport a.A\n"
        assertEquals(src, planned(src, "a.A"))
    }

    // ---- optimize ----

    private fun organize(src: String): String {
        val edits = runBlocking { KotlinImportOrganizer().organizeImports(FakeFile("Test.kt"), src) }
        return applyEdits(src, edits)
    }

    @Test fun sortsAndDropsUnused() {
        val src = "package p\n\nimport z.Zebra\nimport a.Apple\n\nfun f(): Apple = Apple()\n"
        // Zebra is never referenced → dropped; Apple kept; sorted.
        assertEquals("package p\n\nimport a.Apple\n\nfun f(): Apple = Apple()\n", organize(src))
    }

    @Test fun collapsesWildcardAtThreshold() {
        val body = "fun f() { C1(); C2(); C3(); C4(); C5() }"
        val src = "package p\n\nimport pkg.C1\nimport pkg.C2\nimport pkg.C3\nimport pkg.C4\nimport pkg.C5\n\n$body\n"
        assertEquals("package p\n\nimport pkg.*\n\n$body\n", organize(src))
    }

    @Test fun keepsOperatorImportEvenIfNotNamedDirectly() {
        // `getValue` is a delegation-convention name — kept even though it isn't referenced by simple name.
        val src = "package p\n\nimport pkg.getValue\n\nval x by lazyThing\n"
        assertEquals(src, organize(src))
    }
}
