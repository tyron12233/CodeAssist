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
import dev.ide.ui.backend.UiBuildFeature
import dev.ide.ui.backend.UiBuildFeatures
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiFacetConfig
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiRunConfig
import dev.ide.ui.backend.UiSdkOption
import dev.ide.ui.backend.UiSourceSetInfo
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the Modules screen off-screen at a phone viewport and a desktop window, so the adaptive layout (list then
 * detail on a phone, list beside detail on a wide window) can be eyeballed without launching the app, and so the
 * screen is guarded against a runtime layout error. The tab strip is worth watching: M3's `Tab` defaults
 * `unselectedContentColor` to `selectedContentColor`, so a strip that doesn't pass both explicitly paints every
 * tab identically and the selection is invisible.
 */
class ModuleConfigScreenSnapshot {

    private fun roots(set: String) = listOf("assets", "java", "kotlin", "res").map { "/project/app/src/$set/$it" }

    private val androidConfig = UiModuleConfig(
        name = "app",
        typeId = "android-app",
        typeDisplay = "Android Application",
        languageLevel = "JAVA_17",
        languageLevels = listOf("JAVA_8", "JAVA_11", "JAVA_17", "JAVA_21"),
        outputDir = "/project/app/build/classes",
        sourceSets = listOf(
            UiSourceSetInfo("debug", "IMPLEMENTATION", roots("debug")),
            UiSourceSetInfo("main", "IMPLEMENTATION", roots("main")),
            UiSourceSetInfo("release", "IMPLEMENTATION", roots("release")),
        ),
        facets = listOf(
            UiFacetConfig(
                table = "android",
                title = "Android",
                fields = listOf(
                    UiConfigField.Text("namespace", "Namespace", "com.example.playground"),
                    UiConfigField.Text("applicationId", "Application ID", "com.example.playground"),
                    UiConfigField.Number("minSdk", "Min SDK", 24),
                    UiConfigField.Number("targetSdk", "Target SDK", 36),
                    UiConfigField.Number("versionCode", "Version code", 1),
                    UiConfigField.Text("versionName", "Version name", "1.0"),
                    UiConfigField.Bool("multiDex", "Multidex", true),
                    UiConfigField.Bool("coreLibraryDesugaring", "Core library desugaring", false),
                ),
            ),
        ),
        platformSdk = "",
        resolvedSdk = "android-34",
        availableSdks = listOf("android", "android-29", "android-34", "android-36", "android-37").map { UiSdkOption(it, "$it · Android") } +
            UiSdkOption("core-java", "core-java · Java"),
    )

    private val javaConfig = UiModuleConfig(
        name = "cli",
        typeId = "java",
        typeDisplay = "Java Application",
        languageLevel = "JAVA_21",
        languageLevels = listOf("JAVA_8", "JAVA_11", "JAVA_17", "JAVA_21"),
        outputDir = "/project/cli/build/classes",
        sourceSets = listOf(UiSourceSetInfo("main", "IMPLEMENTATION", listOf("/project/cli/src/main/java", "/project/cli/src/main/resources"))),
        facets = emptyList(),
        runConfig = UiRunConfig("", listOf("com.example.cli.Main", "com.example.cli.Tool"), "com.example.cli.Main"),
    )

    private inner class FakeBackend(private val moduleRefs: List<UiModuleRef> = MODULES) : StubBackend() {
        override fun configurableModules(): List<UiModuleRef> = moduleRefs
        override suspend fun getModuleConfig(moduleName: String): UiModuleConfig = if (moduleName == "cli") javaConfig else androidConfig
        override suspend fun getBuildFeatures(moduleName: String): UiBuildFeatures? =
            if (moduleName == "cli") null else UiBuildFeatures(moduleName, listOf(UiBuildFeature("viewBinding", "View binding", "Generate binding classes", false)))
    }

    @Test
    fun phoneList() {
        render("modules-phone-list.png", PHONE_W, PHONE_H, FakeBackend(), initialModule = null)
    }

    @Test
    fun phoneEmptyList() {
        render("modules-phone-empty.png", PHONE_W, PHONE_H, FakeBackend(emptyList()), initialModule = null)
    }

    @Test
    fun phoneSettings() {
        render("modules-phone-settings.png", PHONE_W, PHONE_H, FakeBackend(), initialModule = "app")
    }

    /** A console module has no Android facet: only Settings and Dependencies, plus the Run card. */
    @Test
    fun phoneJavaModule() {
        render("modules-phone-java.png", PHONE_W, PHONE_H, FakeBackend(), initialModule = "cli")
    }

    /** Tall enough to show the whole Settings page, facet grid included. */
    @Test
    fun desktopFullSettings() {
        render("modules-desktop-full.png", DESKTOP_W, 3000, FakeBackend(), initialModule = "app")
    }

    @Test
    fun desktopTwoPane() {
        render("modules-desktop.png", DESKTOP_W, DESKTOP_H, FakeBackend(), initialModule = null)
    }

    /**
     * The pinned save bar must appear the moment anything differs from the loaded config: the whole point of
     * moving it out of the bottom of the scrolling form. Driven by a real click on the "Java 21" segment rather
     * than by poking state, so the dirty check is exercised the way a user reaches it.
     */
    @Test
    fun saveBarAppearsOnEdit() {
        render("modules-desktop-dirty.png", DESKTOP_W, DESKTOP_H, FakeBackend(), initialModule = "app", click = JAVA_21_SEGMENT_DESKTOP)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, width: Int, height: Int, backend: FakeBackend, initialModule: String?, click: Offset? = null) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ModuleConfigScreen(backend, initialModule = initialModule, onBack = {})
                }
            }
        }
        try {
            scene.render(); scene.render(50_000_000L); scene.render(150_000_000L)
            var t = 300_000_000L
            if (click != null) {
                scene.sendPointerEvent(PointerEventType.Press, click)
                scene.sendPointerEvent(PointerEventType.Release, click)
                // Step the clock so the save bar's slide-in actually runs rather than jumping past it.
                for (frame in 1..30) scene.render(300_000_000L + frame * 16_000_000L)
                t = 800_000_000L
            }
            val img = scene.render(t)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "build/snapshots"
        val MODULES = listOf(
            UiModuleRef("app", "Android Application"),
            UiModuleRef("core", "Android Library"),
            UiModuleRef("cli", "Java Application"),
        )

        /** 411 x 890 dp at 2x: a typical phone. */
        const val PHONE_W = 822
        const val PHONE_H = 1780

        /** 1280 x 800 dp at 2x: a laptop window. */
        const val DESKTOP_W = 2560
        const val DESKTOP_H = 1600

        /** The "Java 21" segment in the desktop render, in scene pixels. */
        val JAVA_21_SEGMENT_DESKTOP = Offset(1460f, 541f)
    }
}
