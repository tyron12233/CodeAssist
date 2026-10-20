package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Kotlin not configured" editor notice: shown for a Kotlin source file in a module without the Kotlin
 * standard library, gone once its action configures the module, and never for a build script or a configured
 * module. Also writes the strip at a phone width to `build/snapshots` to be looked at.
 */
class KotlinNotConfiguredNoticeTest {

    private class FakeBackend(var configured: Boolean, val succeeds: Boolean = true) : StubBackend() {
        val configuredPaths = mutableListOf<String>()
        override fun moduleNameForFile(path: String): String? = "core"
        override suspend fun isKotlinConfigured(path: String): Boolean = configured
        override suspend fun configureKotlin(path: String): UiConfigResult {
            configuredPaths += path
            if (!succeeds) return UiConfigResult(false, "no stdlib")
            configured = true
            return UiConfigResult(true, "ok")
        }
    }

    private val kotlinFile = OpenFile("/p/core/src/main/java/a/Greeter.kt", "Greeter.kt", "package a\n")

    @Test
    fun aKotlinFileInAnUnconfiguredModuleOffersToConfigureIt() {
        val backend = FakeBackend(configured = false)
        val probe = Probe(backend, kotlinFile)
        val notice = assertNotNull(probe.render("kotlin-not-configured-phone.png"))
        assertTrue("core" in notice.summary, "names the module: ${notice.summary}")
        assertEquals("Configure Kotlin", notice.actionLabel)

        notice.onAction!!()
        assertNull(probe.render(null), "configured: the notice is gone")
        assertEquals(listOf(kotlinFile.path), backend.configuredPaths)
    }

    @Test
    fun aFailureSaysWhy() {
        val probe = Probe(FakeBackend(configured = false, succeeds = false), kotlinFile)
        assertNotNull(probe.render(null)).onAction!!()
        val notice = assertNotNull(probe.render(null))
        assertTrue("no stdlib" in notice.summary, notice.summary)
    }

    @Test
    fun noNoticeForAConfiguredModuleOrABuildScript() {
        assertNull(Probe(FakeBackend(configured = true), kotlinFile).render(null))
        val script = OpenFile("/p/core/build.gradle.kts", "build.gradle.kts", "")
        assertNull(Probe(FakeBackend(configured = false), script).render(null), "a .kts is a build script")
    }

    @Test
    fun aDismissedNoticeStaysDismissedForTheTab() {
        val probe = Probe(FakeBackend(configured = false), kotlinFile)
        assertNotNull(probe.render(null)).onDismiss!!()
        assertNull(probe.render(null))
    }

    /** One composition of the notice for [file], kept across [render]s so its state survives like a tab's. */
    private class Probe(backend: FakeBackend, file: OpenFile) {
        private var notice: EditorNotice? = null
        private val tick = mutableStateOf(0)

        // 860x400 @ density 2 = 430x200dp, a phone width.
        private val scene = ImageComposeScene(width = 860, height = 400, density = Density(2f)) {
            tick.value // re-read each render, so a state change made outside composition is picked up
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    notice = kotlinNotConfiguredNotice(IdeUiStateHolder.of(backend), file)
                    Box(Modifier.fillMaxWidth()) { EditorNoticeStrip(listOfNotNull(notice)) }
                }
            }
        }

        /** Let the notice's effects and coroutines run, then return what it shows. */
        fun render(snapshot: String?): EditorNotice? {
            var time = 0L
            repeat(4) { tick.value++; scene.render(time); time += 100_000_000L }
            if (snapshot != null) {
                val img = scene.render(time)
                val out = File("build/snapshots/$snapshot").apply { parentFile?.mkdirs() }
                out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("wrote snapshot: ${out.absolutePath}")
            }
            return notice
        }
    }

    /** One [IdeUiState] per backend, so every recomposition sees the same state object. */
    private object IdeUiStateHolder {
        private val states = HashMap<StubBackend, IdeUiState>()
        fun of(backend: StubBackend): IdeUiState = states.getOrPut(backend) { IdeUiState(backend) }
    }
}
