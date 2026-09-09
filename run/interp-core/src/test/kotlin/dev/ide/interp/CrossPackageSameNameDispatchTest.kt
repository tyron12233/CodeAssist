package dev.ide.interp

import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.testkit.writeSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two packages each declare a top-level `BookmarkButton` of the same arity (JetNews: a Material one in
 * `ui.utils`, a Glance one in `glance.ui`). Every call must run the function it RESOLVED to: the cross-file
 * expander keys the second one under its package-qualified `declId`, and [Interpreter.sourceFunctionFor] looks
 * that key up before the bare `name/arity`, so neither call site is silently routed to the other's body.
 */
class CrossPackageSameNameDispatchTest {

    @Test
    fun eachCallSiteRunsTheFunctionItResolvedTo() {
        val dir = Files.createTempDirectory("interp-core-test")
        dir.writeSource(
            "Glance.kt", """
                package com.example.glance
                fun BookmarkButton(id: String, isBookmarked: Boolean, onToggle: (String) -> Unit): Int = 2
                fun other(): Int = BookmarkButton("x", true) { }
            """,
        )
        dir.writeSource(
            "Utils.kt", """
                package com.example.ui.utils
                fun BookmarkButton(isBookmarked: Boolean, onClick: () -> Unit, modifier: Int = 0): Int = 1
            """,
        )
        val entry = """
            package com.example.ui.home
            import com.example.ui.utils.BookmarkButton
            import com.example.glance.other
            fun material(): Int = BookmarkButton(isBookmarked = false, onClick = {})
            fun both(): Int = BookmarkButton(isBookmarked = false, onClick = {}) * 10 + other()
        """.trimIndent()
        dir.writeSource("Use.kt", entry, trim = false)
        val service = KotlinSymbolService(listOf(DiskFile(dir)), listOf(stdlibJarPath()))
        val kt = KotlinParserHost.parse("Use.kt", entry)
        val model = KotlinPreviewLowering(service).crossFileModel(KotlinParsedFile(kt, DiskFile(dir.resolve("Use.kt")), 0))
        val interp = Interpreter(model.program, classes = model.classes)

        assertEquals(1, interp.call(model.program.getValue("material/0"), emptyList()), "the imported Material function must run, not the Glance one")
        assertEquals(12, interp.call(model.program.getValue("both/0"), emptyList()), "the Glance helper must reach ITS package's BookmarkButton (2), the entry its import (1)")
    }
}
