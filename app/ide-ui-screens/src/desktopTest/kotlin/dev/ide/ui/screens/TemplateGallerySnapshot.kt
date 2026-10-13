package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Renders the Create-Project [TemplateGallery] off-screen with more templates than ship today, which is the
 * case it was rebuilt for: a phone (search + category chips + one column), a window just past the rail
 * breakpoint, and a full desktop window (rail + three columns).
 */
class TemplateGallerySnapshot {

    private fun template(id: String, name: String, category: String, icon: String, description: String) =
        UiProjectTemplate(id, name, description, category, icon, emptyList())

    private val templates = listOf(
        template("java-console", "Java Console App", "Java", "java", "A runnable command-line Java application with a main() entry point."),
        template("java-library", "Java Library", "Java", "library", "A reusable Java library module with no entry point."),
        template("swing-app", "Swing Desktop App", "Java", "java", "A windowed Java application: a frame with a label and a button that responds to clicks."),
        // A plugin spelling the category in lower case: it must land in the same group.
        template("swing-paint", "Swing Custom Painting", "java", "java", "A window that draws its own graphics: a JPanel with paintComponent and a repaint on click."),
        template("kotlin-console", "Kotlin Console App", "Kotlin", "kotlin", "A Kotlin app with a top-level main(). Full editor intelligence; builds and runs in the interactive console."),
        template("kotlin-library", "Kotlin Library", "Kotlin", "kotlin", "A reusable Kotlin library module. Full editor intelligence; compiles in the native build."),
        template("compose-desktop", "Compose Desktop App", "Kotlin", "kotlin", "A Compose for Desktop window with a counter, ready to run."),
        template("kmp-app", "Multiplatform App", "Kotlin", "kotlin", "Shared Compose UI for Android and desktop, with an expect/actual pair."),
        template("android-app", "Android App", "Android", "module.android", "A native Android application that builds to an installable APK."),
        template("android-library", "Android Library", "Android", "module.android", "An Android library module that packages to an AAR."),
        template("android-compose", "Compose Android App", "Android", "module.android", "An Android app with a Material 3 Compose UI."),
        template("android-wear", "Wear OS App", "Android", "module.android", "A watch app with a round-screen layout."),
        template("ndk-lib", "Native Library (C++)", "C / C++", "file", "A JNI library built with the on-device clang toolchain."),
        template("ndk-cmake", "CMake Project", "C / C++", "file", "A CMake-driven native build producing a shared library."),
        template("libgdx", "libGDX Game", "Games", "java", "A cross-platform 2D game skeleton with a render loop."),
        template("empty", "Empty Project", "Get started", "folder", "A project with no modules yet; add what you need."),
    )

    @Test
    fun groupsCategoriesIgnoringCase() {
        assertEquals(
            listOf("Java" to 4, "Kotlin" to 4, "Android" to 4, "C / C++" to 2, "Games" to 1, "Get started" to 1),
            templateCategories(templates).map { it.label to it.templates.size },
        )
    }

    @Test
    fun renderPhone() = snapshot("gallery-phone.png", wDp = 390, hDp = 844)

    @Test
    fun renderNarrowDesktop() = snapshot("gallery-desktop-narrow.png", wDp = 900, hDp = 700)

    @Test
    fun renderDesktop() = snapshot("gallery-desktop.png", wDp = 1280, hDp = 800)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, wDp: Int, hDp: Int) {
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    TemplateGallery(templates, onBack = {}, onPick = {})
                }
            }
        }
        try {
            scene.render()
            for (frame in 1..30) scene.render(frame * 50_000_000L)
            val png = scene.render(6_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
