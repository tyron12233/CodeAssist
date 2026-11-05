package dev.ide.core.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginBlameTest {

    private fun frame(cls: String) = StackTraceElement(cls, "m", "F.kt", 1)

    private val blame = PluginBlame(
        mapOf(
            "com.example.dart" to { name: String -> name.startsWith("com.example.dart.") },
            "com.example.lsp" to { name: String -> name.startsWith("com.example.lsp.") },
        ),
    )

    @Test
    fun theTopmostPluginFrameIsBlamedPastLibraryFrames() {
        val frames = listOf(
            frame("java.util.ArrayList"),
            frame("kotlin.collections.CollectionsKt"),
            frame("com.example.lsp.Client"),
            frame("com.example.dart.Plugin"),
            frame("dev.ide.core.IdeServices"),
        )
        assertEquals("com.example.lsp", blame.blame(frames))
    }

    @Test
    fun aStackOfOnlyTheIdeAndPlatformBlamesNoPlugin() {
        assertNull(blame.blame(listOf(frame("android.os.Looper"), frame("dev.ide.ui.Editor"))))
    }

    @Test
    fun aPluginFrameInACauseIsFound() {
        val inner = RuntimeException("inner").apply { stackTrace = arrayOf(frame("com.example.dart.Worker")) }
        val outer = IllegalStateException("outer", inner).apply { stackTrace = arrayOf(frame("dev.ide.core.Host")) }
        assertEquals("com.example.dart", blame.blame(outer))
    }

    @Test
    fun aDefiningLoaderIsWhatCounts() {
        // The IDE's own classes resolve through every plugin loader (it is their parent); none is a plugin's.
        val loader = object : ClassLoader(PluginBlameTest::class.java.classLoader) {}
        val real = PluginBlame.ofLoaders(mapOf("p" to loader))
        assertNull(real.blame(listOf(frame(PluginBlameTest::class.java.name))))
    }
}
