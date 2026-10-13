package dev.ide.interp.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.interp.Interpreter
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Compose Multiplatform template's preview, rendered as the IDE renders it: `AppPreview` in commonMain calls
 * `App`, whose text comes from `greeting()`, which calls `expect fun platformName()`. The module's source roots are
 * the ones the Gradle import gives it, commonMain plus ONE platform set, so the preview must run that set's
 * `actual`. It used to merge the bodiless `expect` and fail on "empty body", on Android and the desktop alike.
 */
class KmpPreviewRenderTest {

    private val platformKt = """
        package demo

        expect fun platformName(): String

        fun greeting(): String = "Hello from " + platformName() + "!"
    """.trimIndent() + "\n"

    // The template's App.kt, minus the `@Preview` annotation (its artifact is not on this classpath; the IDE finds
    // previews by the annotation's short name, and the render below calls the function directly).
    private val appKt = """
        package demo

        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.Spacer
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.layout.height
        import androidx.compose.foundation.layout.padding
        import androidx.compose.material3.Button
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.Surface
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.getValue
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember
        import androidx.compose.runtime.setValue
        import androidx.compose.ui.Alignment
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.unit.dp

        @Composable
        fun App() {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var clicks by remember { mutableStateOf(0) }
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(greeting(), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { clicks++ }) {
                            Text(if (clicks == 0) "Click me" else "Clicked ${'$'}clicks times")
                        }
                    }
                }
            }
        }

        @Composable
        fun AppPreview() {
            App()
        }
    """.trimIndent() + "\n"

    @Test fun rendersOnTheAndroidImport() = rendersWith("androidMain", "Android")

    @Test fun rendersOnTheDesktopImport() = rendersWith("desktopMain", "Desktop")

    @OptIn(ExperimentalComposeUiApi::class)
    private fun rendersWith(platformSet: String, platform: String) {
        val dir = Files.createTempDirectory("kmp-preview")
        fun write(rel: String, text: String): Path =
            dir.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
        write("src/commonMain/kotlin/demo/Platform.kt", platformKt)
        val app = write("src/commonMain/kotlin/demo/App.kt", appKt)
        write("src/$platformSet/kotlin/demo/Platform.$platformSet.kt", "package demo\n\nactual fun platformName(): String = \"$platform\"\n")

        val service = previewSymbolService(
            listOf(Disk(dir.resolve("src/commonMain/kotlin")), Disk(dir.resolve("src/$platformSet/kotlin"))),
        )
        val parsed = KotlinIncrementalParser().parseFull(DiskDoc(app)) as KotlinParsedFile
        val model = KotlinPreviewLowering(service).crossFileModel(parsed)
        val diags = model.program.values.flatMap { f -> f.diagnostics.map { "${f.name}: ${it.reason}" } }
        assertTrue(diags.isEmpty(), "every function must lower cleanly; diags=$diags")
        assertEquals(
            "Hello from $platform!",
            Interpreter(model.program, ComposeDispatcher(), classes = model.classes).call(model.program.getValue("greeting/0"), emptyList()),
            "the preview must run $platformSet's actual",
        )

        var hard: String? = null
        val partials = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val renderer = ComposePreviewRenderer(loader = null)
        val entry = model.program.getValue("AppPreview/0")
        val content: @Composable () -> Unit = {
            renderer.Render(entry, model.program, model.classes, emptyList(), onError = { hard = it.message }, onPartialError = { partials.add(it?.message) })
        }
        val threw = try {
            val scene = ImageComposeScene(360, 640, Density(1f), content = content)
            try { scene.render(0L) } finally { scene.close() }
            null
        } catch (t: Throwable) {
            if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t.javaClass.simpleName.contains("LibraryLoad")) return
            "${t.javaClass.simpleName}: ${t.message}"
        }
        assertTrue(threw == null, "the preview must render; threw $threw; hard=$hard partials=${partials.filterNotNull()}")
        assertTrue(hard == null && partials.filterNotNull().isEmpty(), "no render errors; hard=$hard partials=${partials.filterNotNull()}")
    }

    private class Disk(val p: Path) : VirtualFile {
        override val path get() = p.toString(); override val name get() = p.fileName?.toString() ?: p.toString()
        override val isDirectory get() = Files.isDirectory(p); override val exists get() = Files.exists(p)
        override val length get() = if (exists && !isDirectory) Files.size(p) else 0L
        override fun parent(): VirtualFile? = p.parent?.let { Disk(it) }
        override fun children(): List<VirtualFile> = if (isDirectory) Files.list(p).use { s -> s.map { Disk(it) as VirtualFile }.toList() } else emptyList()
        override fun contentHash() = ContentHash(if (exists && !isDirectory) Files.readString(p) else path)
        override fun readBytes() = if (exists && !isDirectory) Files.readAllBytes(p) else ByteArray(0)
        override fun readText(): CharSequence = if (exists && !isDirectory) Files.readString(p) else ""
    }

    private class DiskDoc(val f: Path) : DocumentSnapshot {
        override val text: CharSequence = Files.readString(f); override val file: VirtualFile = Disk(f); override val version = 1L
        override fun length() = text.length
    }
}
