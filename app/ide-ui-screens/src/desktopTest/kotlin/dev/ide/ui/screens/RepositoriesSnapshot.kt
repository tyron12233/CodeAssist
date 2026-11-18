package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiRepository
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Repositories manager: the built-in repositories locked, a custom one with its initial, and the
 * well-known ones offered as one-tap adds until they are in the list.
 */
class RepositoriesSnapshot {
    private class Backend : StubBackend() {
        val repos = mutableListOf(
            UiRepository("Maven Central", "https://repo1.maven.org/maven2", builtin = true),
            UiRepository("Google", "https://dl.google.com/dl/android/maven2", builtin = true),
            UiRepository("Company", "https://maven.example.com/releases", builtin = false),
        )
        override fun repositories(): List<UiRepository> = repos.toList()
        override fun addRepository(name: String, url: String): Boolean = repos.add(UiRepository(name, url, builtin = false))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renders() {
        val scene = ImageComposeScene(width = 860, height = 900, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    RepositoriesContent(Backend(), FontFamily.Monospace, Modifier.padding(20.dp).fillMaxWidth())
                }
            }
        }
        try {
            scene.render()
            val png = scene.render(100_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/repositories.png").apply { parentFile?.mkdirs() }.writeBytes(png)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a suggestion leaves the offer once it is added`() {
        val backend = Backend()
        val state = RepositoriesState(backend, kotlinx.coroutines.MainScope())
        val jitpack = state.suggestions.first { it.name == "JitPack" }
        state.addSuggestion(jitpack)
        assertTrue(state.repositories.any { it.url == "https://jitpack.io" && !it.builtin })
        assertTrue(state.suggestions.none { it.name == "JitPack" })
    }

    @Test
    fun `adding says which problem it hit`() {
        val state = RepositoriesState(Backend(), kotlinx.coroutines.MainScope())
        state.updateUrl("not an address")
        state.add()
        assertEquals(RepositoryError.NotAnAddress, state.error)
        state.updateUrl("repo1.maven.org/maven2")
        state.add()
        assertEquals(RepositoryError.Duplicate("Maven Central"), state.error)
        state.updateUrl("jitpack.io")
        state.add()
        assertEquals(null, state.error)
        assertEquals("JitPack", state.repositories.last().name)
        assertEquals("", state.url)
    }
}
