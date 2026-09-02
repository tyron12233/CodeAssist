package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders [PluginsScreen] off-screen to a PNG so its layout (the Built-in / Installed tab strip with its
 * counts, essential "Required" pills vs switches, the dependency line, versions, an installed plugin's origin
 * load failure, and a plugin whose manifest could not be read at all, which gets a reason and no switch) can
 * be eyeballed without launching the app; also guards that it renders without a runtime
 * layout error. The Installed tab is reached by a real click on the tab, so the switch is exercised the way a
 * user reaches it rather than by poking state.
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
            UiPluginInfo(
                "com.example.hello", "Hello", "1.2.0", "Adds a Hello tool window.",
                essential = false, enabled = true, builtIn = false, origin = "com.example.hello",
            ),
            UiPluginInfo(
                "com.example.stale", "Stale Plugin", "0.9.0", "Built against an older plugin API.",
                essential = false, enabled = true, builtIn = false, origin = "com.example.stale",
                error = "built for plugin API 0, this version of the IDE loads API 1",
            ),
            UiPluginInfo(
                "com.example.pending", "Pending Plugin", "1.0.0", "Found on the device, not yet allowed to run.",
                essential = false, enabled = false, builtIn = false, origin = "com.example.pending",
                needsConsent = true, capabilities = listOf("ui.action", "fs.read"),
                signature = "a".repeat(64),
            ),
            UiPluginInfo(
                "com.example.broken", "Broken Plugin", "", "",
                essential = false, enabled = false, builtIn = false, origin = "com.example.broken",
                error = "plugin id 'com.Example.Broken' must be lowercase letters, digits, '.', '-' or '_'",
                togglable = false,
            ),
        )
    }

    /** With nothing installed, the Installed tab still exists and shows its empty state. */
    private class NoInstalledBackend : StubBackend() {
        override fun pluginCatalog(): List<UiPluginInfo> = listOf(
            UiPluginInfo("platform", "Platform", "1.0.0", "Core file-icon classifier and base file-type mappings.", essential = true, enabled = true),
            UiPluginInfo("samples", "Sample Projects", "1.0.0", "Bundled sample projects in the Create gallery.", essential = false, enabled = false),
        )
    }

    @Test
    fun renderBuiltInTab() {
        snapshot("plugins.png", FakeBackend())
    }

    @Test
    fun renderInstalledTab() {
        snapshot("plugins-installed.png", FakeBackend(), clickInstalledTab = true)
    }

    @Test
    fun renderInstalledTabWithNothingInstalled() {
        snapshot("plugins-installed-empty.png", NoInstalledBackend(), clickInstalledTab = true)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, backend: IdeBackend, clickInstalledTab: Boolean = false) {
        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { PluginsScreen(backend, onBack = {}) }
            }
        }
        try {
            scene.render()
            scene.render(16_000_000L)
            if (clickInstalledTab) {
                scene.sendPointerEvent(PointerEventType.Press, INSTALLED_TAB)
                scene.sendPointerEvent(PointerEventType.Release, INSTALLED_TAB)
                // The tab indicator animates across; step the clock until it has settled.
                for (frame in 2..40) scene.render(frame * 50_000_000L)
            }
            val img = scene.render(2_400_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 840
        const val HEIGHT = 1600

        /** The Installed tab's centre, at the position the Built-in snapshot renders the tab strip. */
        val INSTALLED_TAB = Offset(630f, 352f)

        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
