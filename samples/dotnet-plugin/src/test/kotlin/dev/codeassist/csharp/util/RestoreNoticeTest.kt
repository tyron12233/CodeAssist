package dev.codeassist.csharp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreNoticeTest {

    @Test
    fun `the notification names the module, the first problem and the net hint`() {
        val text = RestoreNotice.failure("App", listOf("offline"))
        assertTrue(text.startsWith("NuGet restore failed for App: offline."))
        assertTrue(text.endsWith(RestoreNotice.NET_HINT))
    }

    @Test
    fun `more problems are counted, not listed`() {
        assertTrue("(+2 more, see the log)" in RestoreNotice.failure("App", listOf("a", "b", "c")))
        assertEquals(
            "NuGet restore failed: a (+1 more). Save the .csproj to retry, or run Restore from the Run picker.",
            RestoreNotice.fileWarning(listOf("a", "b")),
        )
    }
}
