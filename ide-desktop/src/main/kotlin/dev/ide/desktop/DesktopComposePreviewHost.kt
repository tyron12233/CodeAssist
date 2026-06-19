package dev.ide.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.core.IdeServicesBackend
import dev.ide.core.LoweredComposePreview
import dev.ide.interp.compose.ComposePreviewRenderer
import dev.ide.platform.log.Log
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.editor.preview.PreviewIssue
import dev.ide.ui.editor.preview.PreviewIssueLevel
import dev.ide.ui.editor.preview.PreviewRenderError

/**
 * The desktop Compose preview host — the JVM counterpart to :ide-android's `AndroidComposePreviewHost`.
 * Lowers the open file's `@Preview` through the backend (off the UI thread, serialized with other language
 * work), then composes it via [ComposePreviewRenderer] into the IDE's own composition. The interpreter
 * dispatches library composables reflectively by their FQN, so on the JVM they resolve against Compose for
 * Desktop — a preview built from standard material/foundation composables renders as real desktop UI; one
 * that reaches an Android-only API fails to lower and reports the not-interpretable reasons to the chip.
 */
class DesktopComposePreviewHost(private val backend: IdeServicesBackend) : ComposePreviewHost {

    private val log = Log.logger("DesktopComposePreviewHost")

    // [dark] is accepted for API parity with the Android host but not honoured here: Compose for Desktop has
    // no LocalConfiguration uiMode to override, so the frame renders under the host's system theme. The
    // Light/Night split is meaningful on device, where the night-mode override drives isSystemInDarkTheme().
    @Composable
    override fun Preview(path: String, functionName: String, text: String, dark: Boolean, onProblems: (List<PreviewIssue>) -> Unit, modifier: Modifier) {
        val report by rememberUpdatedState(onProblems)
        // The project's own library jars (material-icons, third-party widgets, sibling modules) aren't on the
        // IDE process classpath, so build a parent-first loader over them — standard composables still dispatch
        // against the bundled Compose-for-Desktop, but a project-only class (`Icons.Default.Home`) now loads.
        // Keyed on path; null while resolving or when there are no extra jars (then the bundled runtime serves).
        val loader by produceState<ClassLoader?>(null, path) {
            value = runCatching { backend.composePreviewLibs(path)?.let { DesktopComposeLibraryLoader.loaderFor(it) } }.getOrNull()
        }
        val renderer = remember(loader) { ComposePreviewRenderer(loader) }
        val state by produceState<PreviewState>(PreviewState.Loading, path, functionName, text) {
            val lowered = runCatching { backend.lowerComposePreview(path, functionName, text) }.getOrNull()
            value = if (lowered != null) PreviewState.Ready(lowered) else {
                // Surface WHY it isn't interpretable (the unsupported constructs + offending source), never a
                // bare message; even a thrown error becomes a visible reason.
                val why = runCatching { backend.composePreviewDiagnostics(path, functionName, text) }
                    .getOrElse { listOf("couldn't analyze: ${it::class.simpleName}: ${it.message}") }
                    .ifEmpty { listOf("no reason reported (analysis returned nothing)") }
                PreviewState.NotInterpretable(why)
            }
        }
        var renderError by remember(path, functionName, text) { mutableStateOf<Throwable?>(null) }
        var partialError by remember(path, functionName, text) { mutableStateOf<Throwable?>(null) }
        // The interpreter re-runs on every recomposition pass, so a content lambda that fails deterministically
        // hands the renderer a FRESH Throwable each pass. Writing that to `partialError` (read during
        // composition) every pass would invalidate → re-run → invalidate … an unbounded recomposition loop.
        // Track the last error identity (type + message) and update state only when it actually changes — incl.
        // clearing to null. Keyed alongside `partialError` so both reset together on a new buffer.
        val partialKey = remember(path, functionName, text) { arrayOfNulls<String>(1) }

        // Report interpret/render problems to the pane's shared problem chip (cleared when it renders cleanly),
        // so the details live in the tappable chip rather than covering the device frame.
        // renderError = top-level failure (preview replaced by error view); partialError = content-lambda error
        // (preview still shows, but lazy content like LazyColumn items may be incomplete).
        LaunchedEffect(state, renderError, partialError) {
            val err = renderError
            val partial = partialError
            report(
                when {
                    err != null -> listOf(PreviewIssue(PreviewIssueLevel.ERROR, "Preview failed to render", err.stackTraceToString()))
                    partial != null -> listOf(PreviewIssue(PreviewIssueLevel.WARNING, "Preview partially rendered", partial.stackTraceToString()))
                    state is PreviewState.NotInterpretable -> (state as PreviewState.NotInterpretable).reasons.map { PreviewIssue(PreviewIssueLevel.WARNING, "Preview not interpretable", it) }
                    else -> emptyList()
                },
            )
        }

        Box(modifier, contentAlignment = Alignment.Center) {
            when (val s = state) {
                is PreviewState.Loading -> CircularProgressIndicator(Modifier.size(28.dp))
                is PreviewState.Ready -> renderer.Render(
                    s.lowered.entry, s.lowered.program, s.lowered.classes,
                    onError = { error ->
                        // Key the capture on the error's identity, not the instance: the interpreter throws a
                        // fresh Throwable each pass, so keying on `error` would relaunch + rewrite state every
                        // recomposition → a render loop. Same message/type ⇒ same key ⇒ captured once.
                        LaunchedEffect(error.message, error::class) { renderError = error }
                        PreviewRenderError(error)
                    },
                    onPartialError = { e ->
                        val key = e?.let { "${it::class.java.name}: ${it.message}" }
                        if (key != partialKey[0]) {
                            partialKey[0] = key
                            if (e != null) log.warn("Compose preview partial render", e)
                            partialError = e
                        }
                    },
                )
                is PreviewState.NotInterpretable -> Text(
                    "Preview not interpretable", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    private sealed interface PreviewState {
        object Loading : PreviewState
        data class Ready(val lowered: LoweredComposePreview) : PreviewState
        data class NotInterpretable(val reasons: List<String>) : PreviewState
    }
}
