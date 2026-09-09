package dev.ide.core

import dev.ide.testkit.withTempDir
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the Learn-tab Compose preview against the "function `Counter` has unsupported nodes" render crash: a
 * lesson snippet whose `@Preview` entry merely CALLS a broken helper (`CounterPreview { Counter() }`, where
 * `Counter` couldn't resolve `Column` / a `by remember { mutableStateOf }` delegate because the scratch's
 * `androidx.compose.*` hadn't attached yet). The entry itself lowers cleanly, so before the fix
 * [dev.ide.core.services.ComposePreviewService.lowerComposePreview] returned a non-null program with an
 * INCOMPLETE reachable function; the lesson host renders that with `tolerateGaps = false`, so the interpreter
 * threw when it invoked `Counter`, and the diagnostics said the misleading "lowered with no diagnostics".
 *
 * The gate is now the SAME for every caller (there is no lenient editor mode): a preview whose reachable
 * helper didn't lower cleanly is refused for the editor too, because the editor's `tolerateGaps = true`
 * interpreter skips such a statement MID-COMPOSITION, which can leave the live composer's groups unbalanced
 * and take the IDE down with it. `tolerateGaps` stays a backstop for a construct WE don't support inside
 * otherwise-valid code; it is not a licence to interpret the user's broken code.
 *
 * This drives the scratch WITHOUT attaching the compose AARs (so `Column`/`remember` resolve to 0 candidates,
 * exactly the pre-attach state) — no network needed — and asserts the lowering refuses it and the diagnostics
 * name the real reason.
 */
class LearnComposePreviewGateTest {

    private val counter = """
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.padding
        import androidx.compose.material3.Button
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.getValue
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember
        import androidx.compose.runtime.setValue
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp

        @Composable
        fun Counter() {
            var count by remember { mutableStateOf(0) }
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Count: " + count)
                Button(onClick = { count++ }) {
                    Text("Increment")
                }
            }
        }

        @Preview
        @Composable
        fun CounterPreview() {
            Counter()
        }
    """.trimIndent()

    @Test
    fun loweringRefusesAPreviewWhoseReachableHelperDidNotLower() = runBlocking {
        withTempDir("learn-compose-gate") { home ->
            val projects = home.resolve("projects")
            Files.createDirectories(projects)
            val manager = ProjectManager.desktop(projects)

            // The Learn Compose scratch, created exactly as LearnBackend.scratchFor("kotlin-compose") does — but
            // WITHOUT ensureCompose(): no androidx.compose.* on the classpath, so the helper's `Column`/`remember`/
            // `by` delegate resolve to 0 candidates. This is the transient first-run state the crash came from.
            val services = manager.scratch(
                "compose-android", "android-library",
                mapOf("packageName" to "dev.ide.learn.compose", "language" to "kotlin", "minSdk" to "26"),
            )
            val path = services.store.rootPath.resolve("lib/src/main/kotlin").resolve("Main.kt")
            Files.createDirectories(path.parent)
            Files.write(path, "fun main() {}\n".toByteArray())
            services.indexStatus.first { !it.building }

            val preview = services.composePreviews(path, counter).firstOrNull()
            assertNotNull(preview, "the @Preview should still be DETECTED by simple name without compose attached")

            // Not ready (compose not attached) — the host polls this and keeps retrying rather than latching.
            assertFalse(services.composePreviewReady(path), "scratch without compose attached must report not-ready")

            // The reachable helper is broken, so the preview must be refused — for the Learn host (which
            // renders with tolerateGaps = false and would throw mid-render) AND for the editor (whose
            // gap-skipping interpreter would silently drop a statement that had already opened Compose groups).
            val lowered = services.lowerComposePreview(path, counter, preview.functionName, preview.arity)
            assertNull(lowered, "lowering must refuse a preview whose reachable helper didn't lower cleanly")

            // Diagnostics must name the REAL reason (the broken helper), not the misleading "no diagnostics".
            val diags = services.composePreviewDiagnostics(path, counter, preview.functionName, preview.arity)
            assertTrue(
                diags.any { "Counter" in it && ("Column" in it || "delegate" in it) },
                "diagnostics should report the broken helper's reason; got $diags",
            )
            assertTrue(
                diags.none { "lowered with no diagnostics" in it },
                "diagnostics must not claim the preview is clean when a reachable helper is broken; got $diags",
            )

            manager.dispose()
        }
    }
}
