package dev.ide.ios

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A Compose `@Preview` end to end on iOS: Kotlin source lowered by this host's own analysis, then
 * interpreted with its library calls running in `:jvm-vm` against Compose's desktop jars, and drawn by the
 * iOS build of skia. The lowering is native; everything from the tree interpreter down is the desktop
 * pipeline, interpreted, from the same trimmed runtime jar the app bundle carries.
 */
class IosComposePreviewTest {
    private val root = IosFiles.join(NSTemporaryDirectory().trimEnd('/'), "ios-preview-test-${IosFiles.modifiedMs(NSTemporaryDirectory())}")

    private val source = """
        package demo
        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.padding
        import androidx.compose.material3.Button
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.Surface
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp
        @Composable fun Greeting(name: String) {
            Text("Hello, " + name + "!")
        }
        @Preview
        @Composable fun P() {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Greeting("iOS")
                        Button(onClick = {}) { Text("Press me") }
                    }
                }
            }
        }
    """.trimIndent()

    /** The jars a project sees for analysis: its libraries, not the interpreter that runs its preview. */
    private fun libraryJars(): List<String> = PreviewTestClasspath.jars.filter { jar ->
        listOf("/org.jetbrains.compose", "/androidx.", "/org.jetbrains.kotlin/kotlin-stdlib", "/org.jetbrains.kotlinx/kotlinx-coroutines", "/org.jetbrains.skiko")
            .any { it in jar } && "compiler" !in jar
    }

    @Test
    fun aSourcePreviewLowersAndRendersOnIos() {
        IosFiles.mkdirs(IosFiles.join(root, "src"))
        val path = IosFiles.join(root, "src/Main.kt")
        IosFiles.writeText(path, source)

        val clock = TimeSource.Monotonic
        val analysisStart = clock.markNow()
        val analysis = IosKotlinAnalysis(root, classpathJars = libraryJars())
        assertTrue("P" in analysis.previewNames(path, source), "the @Preview is found")
        val blob = assertNotNull(analysis.lowerPreview(path, source, "P"), "P lowers")
        val lowerMs = analysisStart.elapsedNow().inWholeMilliseconds
        val decoded = dev.ide.lang.kotlin.interp.PreviewWire.decode(blob)
        val diagnostics = decoded.program.values.flatMap { it.diagnostics }.map { it.reason }
        assertTrue(diagnostics.isEmpty(), "every call resolves: $diagnostics")

        // On the preview thread, as the app renders: a coroutine worker's stack is too small for the VM.
        val renderer = IosPreviewRenderer(PreviewTestClasspath.jars)
        val coldStart = clock.markNow()
        val summary = runBlocking { IosPreviewThread.run { renderer.renderSummary(blob, 360, 220, 2f) } }
        val coldMs = coldStart.elapsedNow().inWholeMilliseconds
        val warmStart = clock.markNow()
        val png = runBlocking { IosPreviewThread.run { renderer.renderPng(blob, 360, 220, 2f) } }
        val warmMs = warmStart.elapsedNow().inWholeMilliseconds
        println(
            "IOS-PREVIEW $summary; analysis+lowering ${lowerMs}ms (blob ${blob.size} bytes), render cold ${coldMs}ms, " +
                "warm ${warmMs}ms, ${renderer.steps} steps, ${renderer.loadedClasses} classes",
        )
        IosFiles.mkdirs(PreviewTestClasspath.outputDir)
        IosFiles.writeBytes(IosFiles.join(PreviewTestClasspath.outputDir, "ios-preview.png"), png)

        assertTrue(summary.startsWith("360x220 "), summary)
        // The JVM renders this preview with 41134 pixels off the corner colour (InVmPreviewTest); iOS draws text
        // with its own fonts, so the count is held near that rather than to it.
        val inked = summary.substringAfter("inked=").toInt()
        assertTrue(inked in 39_000..43_000, "the preview drew what the JVM draws (41134 inked there): $summary")
        analysis.close()
    }

    @Test
    fun aLivePreviewTakesATapOnIos() {
        IosFiles.mkdirs(IosFiles.join(root, "src"))
        val path = IosFiles.join(root, "src/Counter.kt")
        val counter = """
            package demo
            import androidx.compose.foundation.clickable
            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Text
            import androidx.compose.runtime.*
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.tooling.preview.Preview
            import androidx.compose.ui.unit.sp
            @Preview
            @Composable fun Counter() {
                var count by remember { mutableIntStateOf(0) }
                MaterialTheme {
                    Box(Modifier.fillMaxSize().clickable { count++ }, contentAlignment = Alignment.Center) {
                        Text("Tapped " + count, fontSize = 28.sp)
                    }
                }
            }
        """.trimIndent()
        IosFiles.writeText(path, counter)
        val analysis = IosKotlinAnalysis(root, classpathJars = libraryJars())
        val blob = assertNotNull(analysis.lowerPreview(path, counter, "Counter"))
        val renderer = IosPreviewRenderer(PreviewTestClasspath.jars)
        val clock = TimeSource.Monotonic
        val (before, after, tapMs) = runBlocking {
            IosPreviewThread.run {
                fun settle(id: Int): IosPreviewFrame {
                    var f = renderer.frame(id)
                    var n = 0
                    while (f.animating && n++ < 60) f = renderer.frame(id)
                    return f
                }
                val id = renderer.open(blob, 300, 200, 2f, 1f, false, 0L)
                val first = settle(id)
                val tapStart = clock.markNow()
                renderer.pointer(id, 0, 150f, 100f)
                renderer.pointer(id, 2, 150f, 100f)
                val second = settle(id)
                val ms = tapStart.elapsedNow().inWholeMilliseconds
                renderer.close(id)
                Triple(first, second, ms)
            }
        }
        println("IOS-PREVIEW live tap: frame after tap in ${tapMs}ms, problems ${after.problems}")
        IosFiles.mkdirs(PreviewTestClasspath.outputDir)
        IosFiles.writeBytes(IosFiles.join(PreviewTestClasspath.outputDir, "ios-preview-after-tap.png"), after.png!!)
        assertTrue(after.problems.isEmpty(), after.problems.toString())
        assertTrue(!before.png!!.contentEquals(after.png!!), "the tap changed the frame")
        analysis.close()
    }
}
