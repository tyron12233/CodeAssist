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
import dev.ide.ui.backend.UiPluginChange
import dev.ide.ui.backend.UiPluginChangeKind
import dev.ide.ui.backend.UiPluginApp
import dev.ide.ui.backend.UiPluginDownload
import dev.ide.ui.backend.UiPluginDownloads
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders [PluginsScreen] off-screen to a PNG so its layout (the Get plugins / Installed / Built-in tab strip
 * with its counts, the download cards, a plugin's page, essential "Required" pills vs switches, the dependency line, versions, an installed plugin's origin
 * load failure, and a plugin whose manifest could not be read at all, which gets a reason and no switch) can
 * be eyeballed without launching the app; also guards that it renders without a runtime
 * layout error. The Installed tab is reached by a real click on the tab, so the switch is exercised the way a
 * user reaches it rather than by poking state. A separate case renders the restart hint: the plugin changes
 * waiting to be applied, named, with the Restart button a host that can restart itself provides.
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
                "dev.codeassist.ndk", "NDK (C/C++)", "1.3.0",
                "Compiles C and C++ on the device with a bundled clang and lld for Android arm64.",
                essential = false, enabled = true, builtIn = false, origin = "dev.codeassist.ndk",
                capabilities = listOf("ui.action", "ui.editorLanguage", "lang.backend", "build.task", "fs.read", "fs.write"),
                signature = "b".repeat(64), minHostVersion = "3.21.0", apiVersion = 4,
                app = UiPluginApp(
                    packageName = "dev.codeassist.ndk", appVersion = "1.3.0",
                    firstInstalledAt = System.currentTimeMillis() - 9 * 86_400_000L,
                    lastUpdatedAt = System.currentTimeMillis() - 2 * 86_400_000L,
                    sizeBytes = 47_354_399, signedLikeIde = true, installer = null,
                ),
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

    /** The download tab: one plugin installed with an update waiting, one too new for this IDE, one not installed. */
    private class DownloadsBackend : StubBackend() {
        override fun pluginCatalog(): List<UiPluginInfo> = FakeBackend().pluginCatalog()
        override suspend fun pluginDownloads(): UiPluginDownloads = UiPluginDownloads(
            listOf(
                UiPluginDownload(
                    "dev.codeassist.ndk", "NDK (C/C++)", "C and C++ on your phone, with clang and lld built in.",
                    "Compiles C and C++ on the device with a bundled clang and lld for Android arm64: fast completion and errors, semantic highlighting, Run for console programs, JNI checks and templates.",
                    "CodeAssist", "1.4.0", 47_354_399, "https://example.com/ndk.apk", "https://example.com/release",
                    listOf("ui.action", "ui.editorLanguage", "lang.backend", "build.task", "fs.read", "fs.write"), "C/C++",
                    installedVersion = "1.3.0", updateAvailable = true,
                ),
                UiPluginDownload(
                    "dev.codeassist.dart", "Dart & Flutter", "Dart and Flutter apps, with hot reload and live previews.",
                    "Dart on the device, with a bundled Dart SDK and Flutter for Android arm64.",
                    "CodeAssist", "0.1.0", 82_333_586, "https://example.com/dart.apk", "https://example.com/release",
                    listOf("lang.server", "model.moduleType", "build.runTask", "ui.editorPreview", "fs.read", "net"), "Dart · Flutter",
                    needsHost = "3.40.0",
                ),
            ),
        )
    }

    /** A plugin app updated on the device and a plugin turned off, both waiting for a restart. */
    private class WaitingBackend : StubBackend() {
        private val delegate = FakeBackend()

        override fun pluginCatalog(): List<UiPluginInfo> = delegate.pluginCatalog()

        override fun pendingPluginChanges(): List<UiPluginChange> = listOf(
            UiPluginChange("Hello", UiPluginChangeKind.UPDATED),
            UiPluginChange("Sample Projects", UiPluginChangeKind.DISABLED),
        )

        override fun canRestartApplication(): Boolean = true
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
        snapshot("plugins.png", FakeBackend(), startTab = PluginsTab.BuiltIn)
    }

    @Test
    fun renderInstalledTab() {
        // Reached by a real click on the tab, from the download tab.
        snapshot("plugins-installed.png", FakeBackend(), startTab = PluginsTab.Get, click = INSTALLED_TAB)
    }

    @Test
    fun renderInstalledTabWithNothingInstalled() {
        snapshot("plugins-installed-empty.png", NoInstalledBackend(), startTab = PluginsTab.Installed)
    }

    @Test
    fun renderDownloadTab() {
        snapshot("plugins-get.png", DownloadsBackend(), startTab = PluginsTab.Get)
    }

    @Test
    fun renderPluginPage() {
        // A tap on the first installed plugin's card opens its page.
        snapshot("plugins-detail.png", FakeBackend(), startTab = PluginsTab.Installed, click = FIRST_CARD, height = 2400)
    }

    @Test
    fun renderWaitingChangesWithRestart() {
        snapshot("plugins-restart-pending.png", WaitingBackend(), onRestart = {})
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(
        name: String,
        backend: IdeBackend,
        startTab: PluginsTab? = null,
        click: Offset? = null,
        onRestart: (() -> Unit)? = null,
        height: Int = HEIGHT,
    ) {
        val scene = ImageComposeScene(width = WIDTH, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    PluginsScreen(backend, onBack = {}, onRestart = onRestart, onOpenUrl = {}, startTab = startTab)
                }
            }
        }
        try {
            scene.render()
            scene.render(16_000_000L)
            if (click != null) {
                scene.sendPointerEvent(PointerEventType.Press, click)
                scene.sendPointerEvent(PointerEventType.Release, click)
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

        /** The Installed tab's centre (the middle of three), where the tab strip renders. */
        val INSTALLED_TAB = Offset(420f, 352f)

        /** On the first card of a list with no search field above it. */
        val FIRST_CARD = Offset(380f, 520f)

        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
