package dev.ide.lang.java

import dev.ide.lang.imports.ImportLayout
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Java auto-import placement ([JavaImportEdits.planImport]) and the "Optimize Imports" command
 * ([JavaImportOrganizer]) over the IntelliJ-PSI backend. Verifies sorted placement, IntelliJ's regular/static
 * block split, wildcard collapse at the threshold, and dropping unreferenced single imports.
 */
class JavaImportOrganizerTest {

    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private val fs = LocalFileSystem(Files.createTempDirectory("java-fs"))

    @BeforeTest fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        File(srcRoot, "com/foo").mkdirs()
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
    }

    @AfterTest fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private fun file(src: String): VirtualFile {
        val f = File(srcRoot, "com/foo/Use.java")
        f.writeText(src)
        return fs.fileFor(f.toPath())
    }

    private fun applyEdits(src: String, edits: List<DocumentEdit>): String {
        val sb = StringBuilder(src)
        for (e in edits.sortedByDescending { it.offset }) sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        return sb.toString()
    }

    private fun organize(src: String): String =
        applyEdits(src, runBlocking { JavaImportOrganizer(env::parse).organizeImports(file(src), src) })

    private fun planned(src: String, fqn: String): String {
        val psi = env.parse("com/foo/Use.java", src)
        val plan = JavaImportEdits.planImport(psi, fqn) ?: return src
        return src.substring(0, plan.offset) + plan.text + src.substring(plan.offset)
    }

    // ---- placement ----

    @Test fun insertsInSortedPosition() {
        val src = "package com.foo;\n\nimport a.A;\nimport c.C;\n\nclass X {}\n"
        assertEquals("package com.foo;\n\nimport a.A;\nimport b.B;\nimport c.C;\n\nclass X {}\n", planned(src, "b.B"))
    }

    // ---- optimize ----

    @Test fun sortsAndDropsUnused() {
        val src = "package com.foo;\n\nimport java.util.Map;\nimport java.util.List;\n\nclass X { List<String> a; }\n"
        assertEquals(
            "package com.foo;\n\nimport java.util.List;\n\nclass X { List<String> a; }\n",
            organize(src),
        )
    }

    @Test fun splitsStaticBlockAfterRegular() {
        val src = "package com.foo;\n\nimport static java.util.Collections.emptyList;\nimport java.util.List;\n\n" +
            "class X { List<String> a = emptyList(); }\n"
        assertEquals(
            "package com.foo;\n\nimport java.util.List;\n\nimport static java.util.Collections.emptyList;\n\n" +
                "class X { List<String> a = emptyList(); }\n",
            organize(src),
        )
    }

    @Test fun collapsesWildcardAtThreshold() {
        val body = "class X { void m() { C1 a; C2 b; C3 c; C4 d; C5 e; } }"
        val src = "package com.foo;\n\nimport pkg.C1;\nimport pkg.C2;\nimport pkg.C3;\nimport pkg.C4;\nimport pkg.C5;\n\n$body\n"
        assertEquals("package com.foo;\n\nimport pkg.*;\n\n$body\n", organize(src))
    }

    /** The neutral policy is shared with the Kotlin backend; this pins the Java render shape. */
    @Test fun rendersJavaSyntax() {
        assertEquals("import a.b.C;", JavaImportEdits.render(ImportLayout.ImportEntry("a.b.C")))
        assertEquals("import static a.b.C.m;", JavaImportEdits.render(ImportLayout.ImportEntry("a.b.C.m", isStatic = true)))
        assertEquals("import a.b.*;", JavaImportEdits.render(ImportLayout.ImportEntry("a.b", isWildcard = true)))
    }
}
