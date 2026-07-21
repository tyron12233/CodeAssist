package dev.ide.lang.imports

import dev.ide.lang.imports.ImportLayout.ImportEntry
import dev.ide.lang.imports.ImportLayout.ImportLayoutConfig
import dev.ide.lang.imports.ImportLayout.PositionedImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The language-neutral import ordering: [ImportLayout.organize]/[ImportLayout.renderBlocks] (Optimize Imports)
 * and [ImportLayout.planInsert] (auto-import placement). Rendered with a Java-shaped renderer here; the Kotlin
 * backend renders without the `;`/`static` but orders through the same policy.
 */
class ImportLayoutTest {

    private fun java(e: ImportEntry): String {
        val stat = if (e.isStatic) "static " else ""
        val path = if (e.isWildcard) "${e.fqn}.*" else e.fqn
        return "import $stat$path;"
    }

    private fun reg(fqn: String) = ImportEntry(fqn)
    private fun stat(fqn: String) = ImportEntry(fqn, isStatic = true)

    // ---- organize ----

    @Test fun sortsRegularImportsAlphabetically() {
        val blocks = ImportLayout.organize(listOf(reg("java.util.Map"), reg("android.os.Bundle"), reg("java.util.List")), ImportLayoutConfig.JAVA)
        assertEquals(listOf(listOf("android.os.Bundle", "java.util.List", "java.util.Map")), blocks.map { b -> b.map { it.fqn } })
    }

    @Test fun splitsStaticIntoOwnBlockAfterRegular() {
        val out = ImportLayout.organize(
            listOf(stat("java.util.Collections.emptyList"), reg("java.util.List"), reg("android.os.Bundle")),
            ImportLayoutConfig.JAVA,
        )
        val text = ImportLayout.renderBlocks(out, ::java)
        assertEquals(
            """
            import android.os.Bundle;
            import java.util.List;

            import static java.util.Collections.emptyList;
            """.trimIndent(),
            text,
        )
    }

    @Test fun kotlinKeepsEverythingInOneBlock() {
        val out = ImportLayout.organize(listOf(reg("b.B"), reg("a.A")), ImportLayoutConfig.KOTLIN)
        assertEquals(1, out.size)
        assertEquals(listOf("a.A", "b.B"), out[0].map { it.fqn })
    }

    @Test fun deduplicatesIdenticalImports() {
        val out = ImportLayout.organize(listOf(reg("a.A"), reg("a.A"), reg("b.B")), ImportLayoutConfig.JAVA)
        assertEquals(listOf("a.A", "b.B"), out.flatten().map { it.fqn })
    }

    @Test fun collapsesToWildcardAtThreshold() {
        val out = ImportLayout.organize(
            (1..5).map { reg("com.pkg.C$it") } + reg("other.Z"),
            ImportLayoutConfig.JAVA,
        )
        val text = ImportLayout.renderBlocks(out, ::java)
        assertEquals(
            """
            import com.pkg.*;
            import other.Z;
            """.trimIndent(),
            text,
        )
    }

    @Test fun doesNotCollapseBelowThreshold() {
        val out = ImportLayout.organize((1..4).map { reg("com.pkg.C$it") }, ImportLayoutConfig.JAVA)
        assertEquals(4, out.flatten().size)
        assertEquals(false, out.flatten().any { it.isWildcard })
    }

    @Test fun staticWildcardUsesLowerThreshold() {
        val out = ImportLayout.organize((1..3).map { stat("com.T.m$it") }, ImportLayoutConfig.JAVA)
        assertEquals(listOf(ImportEntry("com.T", isStatic = true, isWildcard = true)), out.flatten())
    }

    @Test fun aliasedImportsAreNeverCollapsed() {
        val entries = (1..5).map { reg("com.pkg.C$it") } + ImportEntry("com.pkg.C6", alias = "Renamed")
        val out = ImportLayout.organize(entries, ImportLayoutConfig.KOTLIN).flatten()
        // The five plain ones collapse to a wildcard; the aliased one survives as its own line.
        assertEquals(true, out.any { it.isWildcard && it.fqn == "com.pkg" })
        assertEquals(true, out.any { it.alias == "Renamed" })
    }

    // ---- planInsert ----

    private fun positioned(text: String): Pair<CharSequence, List<PositionedImport>> {
        // Parse a tiny Java-ish buffer: package line, then import lines. Returns the buffer + positioned imports.
        val imports = ArrayList<PositionedImport>()
        var offset = 0
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("import ")) {
                val body = trimmed.removePrefix("import ").removeSuffix(";").trim()
                val isStatic = body.startsWith("static ")
                val path = body.removePrefix("static ").trim()
                val wildcard = path.endsWith(".*")
                imports.add(PositionedImport(ImportEntry(path.removeSuffix(".*"), isStatic, wildcard), offset, offset + line.length + 1))
            }
            offset += line.length + 1
        }
        return text to imports
    }

    private fun apply(text: CharSequence, plan: ImportLayout.InsertPlan?): String {
        if (plan == null) return text.toString()
        return text.substring(0, plan.offset) + plan.text + text.substring(plan.offset)
    }

    @Test fun insertsInSortedPositionWithinBlock() {
        val src = "package p;\nimport a.A;\nimport c.C;\n"
        val (text, existing) = positioned(src)
        val plan = ImportLayout.planInsert(reg("b.B"), existing, ImportLayoutConfig.JAVA, text, packageLineEnd = 11) { java(it) }
        assertEquals("package p;\nimport a.A;\nimport b.B;\nimport c.C;\n", apply(text, plan))
    }

    @Test fun insertsBeforeAllWhenSortsFirst() {
        val src = "package p;\nimport m.M;\nimport z.Z;\n"
        val (text, existing) = positioned(src)
        val plan = ImportLayout.planInsert(reg("a.A"), existing, ImportLayoutConfig.JAVA, text, packageLineEnd = 11) { java(it) }
        assertEquals("package p;\nimport a.A;\nimport m.M;\nimport z.Z;\n", apply(text, plan))
    }

    @Test fun appendsWhenSortsLast() {
        val src = "package p;\nimport a.A;\nimport b.B;\n"
        val (text, existing) = positioned(src)
        val plan = ImportLayout.planInsert(reg("z.Z"), existing, ImportLayoutConfig.JAVA, text, packageLineEnd = 11) { java(it) }
        assertEquals("package p;\nimport a.A;\nimport b.B;\nimport z.Z;\n", apply(text, plan))
    }

    @Test fun dedupeReturnsNull() {
        val src = "package p;\nimport a.A;\n"
        val (text, existing) = positioned(src)
        assertNull(ImportLayout.planInsert(reg("a.A"), existing, ImportLayoutConfig.JAVA, text, packageLineEnd = 11) { java(it) })
    }

    @Test fun firstImportGetsBlankLineAfterPackage() {
        val src = "package p;\nclass C {}\n"
        val text: CharSequence = src
        val plan = ImportLayout.planInsert(reg("a.A"), emptyList(), ImportLayoutConfig.JAVA, text, packageLineEnd = 11) { java(it) }
        assertEquals("package p;\n\nimport a.A;\nclass C {}\n", apply(text, plan))
    }

    @Test fun firstImportDoesNotDoubleExistingBlankLine() {
        val src = "package p;\n\nclass C {}\n"
        val text: CharSequence = src
        val plan = ImportLayout.planInsert(reg("a.A"), emptyList(), ImportLayoutConfig.JAVA, text, packageLineEnd = 11) { java(it) }
        assertEquals("package p;\n\nimport a.A;\nclass C {}\n", apply(text, plan))
    }

    @Test fun staticImportStartsItsOwnBlockAfterRegular() {
        val src = "package p;\nimport a.A;\nimport b.B;\n"
        val (text, existing) = positioned(src)
        val plan = ImportLayout.planInsert(stat("c.C.m"), existing, ImportLayoutConfig.JAVA, text, packageLineEnd = 11) { java(it) }
        assertEquals("package p;\nimport a.A;\nimport b.B;\n\nimport static c.C.m;\n", apply(text, plan))
    }

    @Test fun regularImportInsertsBeforeExistingStaticBlock() {
        val src = "package p;\nimport static x.Y.z;\n"
        val (text, existing) = positioned(src)
        val plan = ImportLayout.planInsert(reg("a.A"), existing, ImportLayoutConfig.JAVA, text, packageLineEnd = 11) { java(it) }
        assertEquals("package p;\nimport a.A;\n\nimport static x.Y.z;\n", apply(text, plan))
    }
}
