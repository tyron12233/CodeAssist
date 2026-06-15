package dev.ide.lang.jdt.synthetic

import dev.ide.lang.jdt.completeLabels
import dev.ide.lang.jdt.workspaceWith
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticField
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A structured [SyntheticClass] (an Android-`R`-shaped tree) must (1) emit compilable Java source and
 * (2) resolve through the real completion path when dropped into the name-environment overlay — proving
 * the "structured contract, full integration" design: member completion on `R.layout.` lists the fields.
 */
class SyntheticJavaSourceTest {

    private val r = SyntheticClass(
        fqName = "com.example.R",
        nestedClasses = listOf(
            SyntheticClass("com.example.R.layout", fields = listOf(SyntheticField("activity_main"), SyntheticField("row"))),
            SyntheticClass("com.example.R.id", fields = listOf(SyntheticField("title"), SyntheticField("subtitle"))),
            SyntheticClass("com.example.R.string", fields = listOf(SyntheticField("app_name"))),
        ),
    )

    @Test
    fun emitsCompilableSourceWithInitializedConstants() {
        val src = SyntheticJavaSource.emit(r)
        assertTrue("package com.example;" in src, src)
        assertTrue("class R" in src && "class layout" in src, src)
        // finals MUST be initialized or the unit wouldn't compile (and so wouldn't resolve).
        assertTrue("public static final int activity_main = 0;" in src, src)
    }

    @Test
    fun memberCompletionResolvesThroughTheOverlay() {
        val (analyzer, dir) = workspaceWith()
        analyzer.overlayProvider = { mapOf("com.example.R" to SyntheticJavaSource.emit(r).toCharArray()) }
        val file = dir.resolve("com/example/T.java")

        val layout = completeLabels(analyzer, file, "package com.example; class T { void m() { int v = R.layout.|CARET| } }")
        assertTrue("activity_main" in layout && "row" in layout, "R.layout.* fields expected: $layout")

        val rMembers = completeLabels(analyzer, file, "package com.example; class T { void m() { Object o = R.|CARET| } }")
        assertTrue("layout" in rMembers && "id" in rMembers && "string" in rMembers, "R nested types expected: $rMembers")
    }
}
