package dev.codeassist.csharp.editor.diagnostics

import dev.ide.analysis.DiagnosticTag
import org.junit.Assert.assertEquals
import org.junit.Test

class CsDiagnosticTagsTest {

    @Test
    fun `obsolete use is deprecated`() {
        for (code in listOf("CS0612", "CS0618", "CS0672", "CS0809")) {
            assertEquals(setOf(DiagnosticTag.DEPRECATED), CsDiagnosticTags.of(code))
        }
    }

    @Test
    fun `never used is unused`() {
        for (code in listOf("CS0168", "CS0219", "CS0169", "CS0414")) {
            assertEquals(setOf(DiagnosticTag.UNUSED), CsDiagnosticTags.of(code))
        }
    }

    @Test
    fun `other codes carry no tag`() {
        assertEquals(emptySet<DiagnosticTag>(), CsDiagnosticTags.of("CS0246"))
        assertEquals(emptySet<DiagnosticTag>(), CsDiagnosticTags.of(null))
    }
}
