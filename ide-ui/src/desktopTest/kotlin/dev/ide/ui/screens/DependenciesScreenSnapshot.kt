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
import dev.ide.ui.backend.UiDepKind
import dev.ide.ui.backend.UiDependencyNode
import dev.ide.ui.backend.UiModuleDeps
import dev.ide.ui.backend.UiVersionConflict
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders [DependenciesPane] off-screen to a PNG so the redesigned rows (scope as a color-coded letter
 * badge, conflicts shown only for real major-version clashes + a collapsible summary) can be eyeballed
 * without launching the app — and so the screen is guarded against a runtime layout error.
 */
class DependenciesScreenSnapshot {

    private fun node(
        coord: String, kind: UiDepKind = UiDepKind.Jar, declared: Boolean = true,
        scope: String? = null, children: List<String> = emptyList(),
    ): UiDependencyNode {
        val parts = coord.split(":")
        return UiDependencyNode(
            coordinate = coord, group = parts[0], name = parts.getOrElse(1) { coord },
            version = parts.getOrElse(2) { "" }, kind = kind, declared = declared, scope = scope,
            children = children,
        )
    }

    private val okhttp = node("com.squareup.okhttp3:okhttp:4.12.0", scope = "implementation", children = listOf("com.squareup.okio:okio:3.6.0"))
    private val okio = node("com.squareup.okio:okio:3.6.0", declared = false)
    private val guava = node("com.google.guava:guava:33.0.0-jre", scope = "api")
    private val junit = node("junit:junit:4.13.2", scope = "testImplementation")
    private val annotations = node("org.jetbrains:annotations:24.1.0", scope = "compileOnly")
    private val bom = node("androidx.compose:compose-bom:2024.09.00", kind = UiDepKind.Platform, scope = "platform")
    private val kotlin = node("org.jetbrains.kotlin:kotlin-stdlib:1.9.22", declared = false)

    private val moduleDeps = UiModuleDeps(
        moduleName = "app", buildSystem = "native", acceptsAar = true,
        declared = listOf(okhttp, guava, junit, annotations, bom),
        nodes = listOf(okhttp, okio, guava, junit, annotations, bom, kotlin),
        conflicts = listOf(
            // Real: spans major 31 → 33 (semver-incompatible) → flagged on the row + listed for review.
            UiVersionConflict("com.google.guava:guava", listOf("31.1-jre", "33.0.0-jre"), "33.0.0-jre"),
            // Benign: same major → only counted in the summary, no per-row glyph.
            UiVersionConflict("com.squareup.okio:okio", listOf("3.5.0", "3.6.0"), "3.6.0"),
            UiVersionConflict("org.jetbrains.kotlin:kotlin-stdlib", listOf("1.9.0", "1.9.22"), "1.9.22"),
        ),
    )

    private inner class FakeBackend : StubBackend() {
        override suspend fun moduleDependencies(moduleName: String): UiModuleDeps = moduleDeps
        override suspend fun availableVersions(moduleName: String, coordinate: String): List<String> =
            listOf("4.12.0", "4.11.0", "4.10.0", "5.0.0-alpha.14")
    }

    @Test
    fun renderDeclaredTab() {
        // 860x1480 @ density 2 ≈ 430x740dp, a realistic phone width (just under the desktop two-pane breakpoint).
        snapshot("dependencies-declared.png", 860, 1480, FakeBackend())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, backend: IdeBackend) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Ca.colors.bg)) { DependenciesPane(backend, "app") }
            }
        }
        try {
            // Pump a few frames so the moduleDependencies LaunchedEffect resolves and the content (not the
            // loading panel) is what gets captured.
            scene.render()
            scene.render(50_000_000L)
            scene.render(150_000_000L)
            val img = scene.render(300_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        // Lands under the module's build dir (gitignored). The test's real value is that scene.render()
        // throws on a bad layout; the PNG is just for eyeballing.
        const val OUT_DIR = "build/snapshots"
    }
}
