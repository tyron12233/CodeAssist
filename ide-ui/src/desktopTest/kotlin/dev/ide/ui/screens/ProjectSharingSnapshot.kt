package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.UiExportModule
import dev.ide.ui.backend.UiExportPlan
import dev.ide.ui.backend.UiImportPreview
import dev.ide.ui.backend.UiPackagedEntry
import dev.ide.ui.backend.UiPackagedModule
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the redesigned sharing screens off-screen to PNGs so the Material 3 pass (module summary instead
 * of a wall of paths, the editable import name, the export's module picker) can be eyeballed without
 * launching the app — and so a layout error in either screen fails a test rather than shipping.
 */
class ProjectSharingSnapshot {

    private val preview = UiImportPreview(
        name = "Jetsnack",
        description = "A sample snack-ordering app built with Jetpack Compose, Material 3 and a shared " +
            "element transition between the feed and the snack detail screen.",
        author = "Ada Lovelace",
        createdBy = "CodeAssist",
        isAndroid = true,
        packageName = "com.example.jetsnack",
        moduleCount = 3,
        modules = listOf(
            UiPackagedModule("app", "android-app", 84, 3_182_000),
            UiPackagedModule("core", "android-lib", 31, 812_000),
            UiPackagedModule("data", "java-lib", 12, 96_000),
        ),
        fileCount = 127,
        uncompressedSizeBytes = 6_500_000,
        hasBundledDeps = true,
        icon = null,
        files = listOf(
            UiPackagedEntry("app/src/main/AndroidManifest.xml", 1_204),
            UiPackagedEntry("app/src/main/kotlin/com/example/jetsnack/MainActivity.kt", 4_820),
            UiPackagedEntry("core/module.toml", 640),
        ),
        compatible = true,
    )

    private val plan = UiExportPlan(
        modules = listOf(
            UiExportModule("app", "android-app", "app", 84, 3_182_000, listOf("core", "data")),
            UiExportModule("core", "android-lib", "core", 31, 812_000, listOf("data")),
            UiExportModule("data", "java-lib", "data", 12, 96_000, emptyList()),
            UiExportModule("benchmark", "java-lib", "benchmark", 6, 41_000, emptyList()),
        ),
        bundledDepsBytes = 18_400_000,
    )

    private inner class SharingBackend : StubBackend() {
        override suspend fun exportPlan(rootPath: String): UiExportPlan = plan
        override suspend fun importDestination(projectName: String): String =
            "/storage/CodeAssist/projects/${projectName.lowercase().replace(' ', '-')}"
    }

    /** A host that can do everything, so every affordance shows up in the capture. */
    private object AllActions : FileActions {
        override val canImport = true
        override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) = Unit
        override val canPickFile = true
        override fun pickFile(extensions: List<String>, onPicked: (String?) -> Unit) = onPicked(null)
        override val canShare = true
        override fun share(path: String) = Unit
        override val canExport = true
        override val canReveal = true
    }

    @Test
    fun renderImportPreview() {
        snapshot("import-preview.png") {
            ImportPreviewScreen(SharingBackend(), "/downloads/jetsnack.caproj", preview, onCancel = {}, onImported = {})
        }
    }

    @Test
    fun renderExportConfigure() {
        snapshot("export-configure.png", height = 2800) {
            ExportProjectScreen(
                backend = SharingBackend(),
                project = ProjectInfo("Jetsnack", "/ws/jetsnack", 4, isAndroid = true),
                fileActions = AllActions,
                initialAuthor = "Ada Lovelace",
                onAuthorRemembered = {},
                onReveal = {}, onSaveCopy = {}, onShare = {},
                onDone = {},
            )
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, height: Int = 1760, content: @Composable () -> Unit) {
        // 880 wide @ density 2 = 440dp, a phone in portrait; a taller canvas captures a whole long form.
        val scene = ImageComposeScene(width = 880, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
        try {
            // Pump frames so the export plan's LaunchedEffect resolves before the capture.
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
        const val OUT_DIR = "build/snapshots"
    }
}
