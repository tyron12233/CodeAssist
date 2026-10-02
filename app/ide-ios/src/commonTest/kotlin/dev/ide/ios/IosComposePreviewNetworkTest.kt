package dev.ide.ios

import dev.ide.ios.store.IosPreferences
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The whole production path, with nothing bundled but the interpreter: a project declares androidx
 * Compose, the backend resolves it from Maven (for the editor, as Android artifacts; for the preview, as
 * the Compose Multiplatform desktop artifacts that alias them), lowers a `@Preview` and renders it in the VM.
 *
 * It downloads, so it is skipped when the network is not there. The projects root is a FIXED directory so
 * the resolver's cache survives between runs and only the first one downloads.
 */
class IosComposePreviewNetworkTest {
    private val root = IosFiles.join(NSTemporaryDirectory().trimEnd('/'), "ios-preview-network-test")
    private val suite = "ios-preview-network-test"
    private val prefs = IosPreferences(NSUserDefaults(suiteName = suite))

    @AfterTest
    fun cleanUp() {
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }

    private val source = """
        package com.example.app
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
        @Preview
        @Composable fun Card() {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        Text("Rendered from Maven")
                        Button(onClick = {}) { Text("OK") }
                    }
                }
            }
        }
    """.trimIndent()

    @Test
    fun aProjectsOwnComposeRendersItsPreview() = runBlocking {
        val backend = IosBackend(root, prefs)
        // The runtime the app bundle would carry: the trimmed interpreter jar and ASM.
        backend.previewRuntimeJars = { PreviewTestClasspath.jars.filter { "preview-runtime" in it || "/org.ow2.asm/" in it } }

        val existing = IosFiles.join(root, "PreviewApp")
        if (IosFiles.exists(existing)) assertTrue(backend.projects.openProject(existing), "reopens $existing")
        else {
            val created = backend.projects.createProject("kotlin-console", mapOf("name" to "PreviewApp", "packageName" to "com.example.app"))
            assertTrue(created.success, created.message)
        }

        val clock = TimeSource.Monotonic
        val resolveStart = clock.markNow()
        backend.deps.addDependency("app", "androidx.compose.material3:material3:1.4.0", "implementation")
        // A declaration is recorded whether or not it resolved; what tells is whether its jars arrived. A test
        // process the simulator spawns may have no working TLS (it reports every Maven host's certificate as
        // invalid), which is no evidence about the preview, so that is a skip.
        if (backend.analysisClasspath().none { "material3" in it }) {
            println("IOS-PREVIEW-NET skipped: Maven was not reachable from this process")
            return@runBlocking
        }
        val resolveMs = resolveStart.elapsedNow().inWholeMilliseconds

        val path = IosFiles.join(existing, "app/src/main/kotlin/com/example/app/Card.kt")
        IosFiles.writeText(path, source)
        val previews = backend.composePreviews(path, source)
        assertTrue(previews.any { it.functionName == "Card" }, "the @Preview is offered: $previews")

        val renderStart = clock.markNow()
        val frame = backend.renderComposePreview(path, source, "Card", 360, 220, 2f)
        val renderMs = renderStart.elapsedNow().inWholeMilliseconds
        println("IOS-PREVIEW-NET resolve ${resolveMs}ms, first render ${renderMs}ms, problems ${frame.problems}")
        val png = assertNotNull(frame.png, "a frame: ${frame.problems}")
        IosFiles.mkdirs(PreviewTestClasspath.outputDir)
        IosFiles.writeBytes(IosFiles.join(PreviewTestClasspath.outputDir, "ios-preview-network.png"), png)

        val again = clock.markNow()
        val second = backend.renderComposePreview(path, source.replace("Rendered from Maven", "Edited"), "Card", 360, 220, 2f)
        println("IOS-PREVIEW-NET edit re-render ${again.elapsedNow().inWholeMilliseconds}ms, problems ${second.problems}")
        assertNotNull(second.png, "an edited preview re-renders: ${second.problems}")
    }
}
