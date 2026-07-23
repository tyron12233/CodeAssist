package dev.ide.lang.jdt

import dev.ide.lang.jdt.analysis.JdtImportLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Sorted placement of the JDT add-import quick-fix over the neutral DOM ([JdtImportLayout.planImport]): a new
 * `import` lands in the correct alphabetical position within the regular block, a static import splits into its
 * own block after it, and an already-present import is a no-op.
 */
class JdtImportLayoutTest {

    private fun planned(src: String, fqn: String): String {
        val (analyzer, dir) = workspaceWith()
        return try {
            val file = StubFile(dir.resolve("app/A.java").toString(), src)
            val parsed = analyzer.parseSyntactic(file, src)
            val plan = JdtImportLayout.planImport(parsed, fqn) ?: return src
            src.substring(0, plan.offset) + plan.text + src.substring(plan.offset)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun insertsInSortedPosition() {
        val src = "package app;\n\nimport a.A;\nimport c.C;\n\nclass X {}\n"
        assertEquals("package app;\n\nimport a.A;\nimport b.B;\nimport c.C;\n\nclass X {}\n", planned(src, "b.B"))
    }

    @Test fun sortsBeforeAll() {
        val src = "package app;\n\nimport m.M;\nimport z.Z;\n\nclass X {}\n"
        assertEquals("package app;\n\nimport a.A;\nimport m.M;\nimport z.Z;\n\nclass X {}\n", planned(src, "a.A"))
    }

    @Test fun firstImportBlankLineAfterPackage() {
        val src = "package app;\n\nclass X {}\n"
        assertEquals("package app;\n\nimport a.A;\nclass X {}\n", planned(src, "a.A"))
    }

    @Test fun alreadyImportedIsNoOp() {
        val src = "package app;\n\nimport a.A;\n\nclass X {}\n"
        assertEquals(src, planned(src, "a.A"))
    }

    @Test fun coveredByWildcardIsNoOp() {
        val src = "package app;\n\nimport a.*;\n\nclass X {}\n"
        assertNull(run {
            val (analyzer, dir) = workspaceWith()
            try {
                val file = StubFile(dir.resolve("app/A.java").toString(), src)
                JdtImportLayout.planImport(analyzer.parseSyntactic(file, src), "a.A")
            } finally { dir.toFile().deleteRecursively() }
        })
    }
}
