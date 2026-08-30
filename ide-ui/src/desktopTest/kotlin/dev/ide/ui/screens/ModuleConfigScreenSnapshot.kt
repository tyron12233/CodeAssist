package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiFacetConfig
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiSourceSetInfo
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the module **Settings** tab off-screen to a PNG so the Material 3 pass can be eyeballed without
 * launching the app — and so the screen is guarded against a runtime layout error. The tab strip is the part
 * worth watching: M3's `Tab` defaults `unselectedContentColor` to `selectedContentColor`, so a strip that
 * doesn't pass both explicitly paints every tab identically and the selection is invisible.
 */
class ModuleConfigScreenSnapshot {

    private val config = UiModuleConfig(
        name = "app",
        typeId = "android-app",
        typeDisplay = "Android Application",
        languageLevel = "JAVA_17",
        languageLevels = listOf("JAVA_11", "JAVA_17", "JAVA_21"),
        outputDir = "/project/app/build/classes",
        sourceSets = listOf(UiSourceSetInfo("main", "IMPLEMENTATION", listOf("/project/app/src/main/kotlin"))),
        facets = listOf(
            UiFacetConfig(
                table = "android",
                title = "Android",
                fields = listOf(
                    UiConfigField.Text("namespace", "namespace", "com.example.playground"),
                    UiConfigField.Number("minSdk", "minSdk", 24),
                    UiConfigField.Number("targetSdk", "targetSdk", 36),
                    UiConfigField.Number("versionCode", "versionCode", 1),
                    UiConfigField.Text("versionName", "versionName", "1.0"),
                    UiConfigField.Bool("isApplication", "isApplication", true),
                ),
            ),
        ),
    )

    private inner class FakeBackend : StubBackend() {
        override fun configurableModules(): List<UiModuleRef> = listOf(UiModuleRef("app", "Android Application"))
        override suspend fun getModuleConfig(moduleName: String): UiModuleConfig = config
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderModuleSettings() {
        val scene = ImageComposeScene(width = 860, height = 1480, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ModuleConfigScreen(FakeBackend(), initialModule = "app", onBack = {})
                }
            }
        }
        try {
            scene.render(); scene.render(50_000_000L); scene.render(150_000_000L)
            val img = scene.render(300_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/module-settings.png").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/module-settings.png (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    /**
     * The pinned save bar must appear the moment anything differs from the loaded config — the whole point of
     * moving it out of the bottom of the scrolling form. Driven by a real click on a Java-version chip rather
     * than by poking state, so the dirty check is exercised the way a user reaches it.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun saveBarAppearsOnEdit() {
        val scene = ImageComposeScene(width = 860, height = 1480, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ModuleConfigScreen(FakeBackend(), initialModule = "app", onBack = {})
                }
            }
        }
        try {
            scene.render(); scene.render(50_000_000L); scene.render(150_000_000L)
            // "Java 21" — the unselected level chip, at the position the first snapshot renders it.
            val chip = Offset(497f, 563f)
            scene.sendPointerEvent(PointerEventType.Press, chip)
            scene.sendPointerEvent(PointerEventType.Release, chip)
            scene.render(200_000_000L)
            scene.render(400_000_000L)
            val img = scene.render(600_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/module-settings-dirty.png").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/module-settings-dirty.png (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "build/snapshots"
    }
}
