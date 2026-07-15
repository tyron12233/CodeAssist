package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders [PluginsScreen] off-screen to a PNG so its layout (essential "Required" pills vs switches, the
 * dependency line, versions) can be eyeballed without launching the app; also guards that it renders without
 * a runtime layout error.
 */
class PluginsScreenSnapshot {

    private class FakeBackend : StubBackend() {
        override fun pluginCatalog(): List<UiPluginInfo> = listOf(
            UiPluginInfo("platform", "Platform", "1.0.0", "Core file-icon classifier and base file-type mappings.", essential = true, enabled = true),
            UiPluginInfo("jdt-language", "Java Language", "1.0.0", "Java editing via the Eclipse JDT backend; also the resolution fallback.", essential = true, enabled = true),
            UiPluginInfo("ide-core-services", "IDE Core Services", "1.0.0", "The engine's scoped services (analyzers, build, module, search).", essential = true, enabled = true),
            UiPluginInfo("android-support", "Android Support", "1.0.0", "Android module types, facets, variants, and the APK pipeline.", essential = false, enabled = true),
            UiPluginInfo("kotlin-language", "Kotlin Language", "1.0.0", "Kotlin editor support (parse, completion, analysis).", essential = false, enabled = true, dependsOn = listOf("jdt-language")),
            UiPluginInfo("kotlin-analysis", "Kotlin Analysis", "1.0.0", "Kotlin diagnostics and code actions.", essential = false, enabled = true, dependsOn = listOf("kotlin-language")),
            UiPluginInfo("samples", "Sample Projects", "1.0.0", "Bundled sample projects in the Create gallery.", essential = false, enabled = false),
        )
    }

    @Test
    fun renderPluginsScreen() {
        snapshot("plugins.png", 480, 920, FakeBackend())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, backend: IdeBackend) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ca.colors.bg)) { PluginsScreen(backend, onBack = {}) }
            }
        }
        try {
            scene.render()
            val img = scene.render(16_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
