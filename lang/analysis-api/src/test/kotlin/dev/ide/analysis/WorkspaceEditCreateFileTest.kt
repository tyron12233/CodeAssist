package dev.ide.analysis

import dev.ide.lang.incremental.DocumentEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkspaceEditCreateFileTest {

    @Test
    fun createFileNamesAMissingFileHoldingTheText() {
        val edit = WorkspaceEdit.createFile("/p/app/src/main/cpp/native-lib.cpp", "#include <jni.h>\n")
        val (file, edits) = edit.edits.entries.single()
        assertEquals("/p/app/src/main/cpp/native-lib.cpp", file.path)
        assertFalse(file.exists, "the host tells a creation from an edit by the file not existing")
        assertEquals(listOf(DocumentEdit(0, 0, "#include <jni.h>\n")), edits)
    }

    @Test
    fun plusMergesTheEditsOfBothPerFile() {
        val a = WorkspaceEdit.createFile("/p/a.cpp", "a")
        val b = WorkspaceEdit.createFile("/p/b.cpp", "b")
        val merged = a + b + WorkspaceEdit.createFile("/p/a.cpp", "more")
        assertEquals(setOf("/p/a.cpp", "/p/b.cpp"), merged.files.map { it.path }.toSet())
        assertEquals(2, merged.edits.entries.single { it.key.path == "/p/a.cpp" }.value.size)
    }
}
