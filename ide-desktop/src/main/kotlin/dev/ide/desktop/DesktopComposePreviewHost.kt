package dev.ide.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import dev.ide.interp.compose.PreviewParameterBinding
import dev.ide.platform.log.Log
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.editor.preview.PreviewIssue
import dev.ide.ui.editor.preview.PreviewIssueLevel
import dev.ide.ui.editor.preview.PreviewRenderError
import kotlinx.coroutines.delay

/** Poll cadence + patience while the classpath warms (first-run indexing / scratch dep attach) before the
 *  preview gives up waiting and surfaces whatever it can lower. ~3 min comfortably covers a cold first-run
 *  index; the cap only bites the degenerate case where readiness never arrives (e.g. an offline scratch). */
private const val PREVIEW_READY_POLL_MS = 600L
private const val PREVIEW_READY_MAX_ATTEMPTS = 300

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

    // [dark] / the variant's uiMode / locale are accepted for API parity with the Android host but not honoured
    // here: Compose for Desktop has no LocalConfiguration to override, so the frame renders under the host's
    // system theme. The Light/Night split is meaningful on device, where the override drives isSystemInDarkTheme().
    @Composable
    override fun Preview(path: String, preview: UiComposePreview, text: String, dark: Boolean, onProblems: (List<PreviewIssue>) -> Unit, onBusy: (Boolean) -> Unit, modifier: Modifier) {
        val report by rememberUpdatedState(onProblems)
        val reportBusy by rememberUpdatedState(onBusy)
        // The project's own library jars (material-icons, third-party widgets, sibling modules) aren't on the
        // IDE process classpath, so build a parent-first loader over them — standard composables still dispatch
        // against the bundled Compose-for-Desktop, but a project-only class (`Icons.Default.Home`) now loads.
        // Keyed on path; null while resolving or when there are no extra jars (then the bundled runtime serves).
        val loader by produceState<ClassLoader?>(null, path) {
            value = runCatching { backend.composePreviewLibs(path)?.let { DesktopComposeLibraryLoader.loaderFor(it) } }.getOrNull()
        }
        val renderer = remember(loader) { ComposePreviewRenderer(loader) }
        val state by produceState<PreviewState>(PreviewState.Loading, path, preview.functionName, preview.arity, text) {
            // First-run resilience: while the workspace index is still building, library composables (`Text`,
            // `Column`, `remember`) resolve to 0 candidates and the lower fails. Rather than latch that transient
            // failure into a permanent "unresolved call" error, stay in Loading and re-lower until the classpath
            // warms (composePreviewReady flips true). Bounded so a genuinely unsupported preview still surfaces
            // its reason instead of spinning forever.
            var attempts = 0
            while (true) {
                val lowered = runCatching { backend.lowerComposePreview(path, preview.functionName, preview.arity, text) }.getOrNull()
                if (lowered != null) { value = PreviewState.Ready(lowered); break }
                val ready = runCatching { backend.composePreviewReady(path) }.getOrDefault(true)
                if (ready || attempts++ >= PREVIEW_READY_MAX_ATTEMPTS) {
                    // Surface WHY it isn't interpretable (the unsupported constructs + offending source), never a
                    // bare message; even a thrown error becomes a visible reason.
                    val why = runCatching { backend.composePreviewDiagnostics(path, preview.functionName, preview.arity, text) }
                        .getOrElse { listOf("couldn't analyze: ${it::class.simpleName}: ${it.message}") }
                        .ifEmpty { listOf("no reason reported (analysis returned nothing)") }
                    value = PreviewState.NotInterpretable(why)
                    break
                }
                value = PreviewState.Loading
                delay(PREVIEW_READY_POLL_MS)
            }
        }
        var renderError by remember(path, preview.variantId, text) { mutableStateOf<Throwable?>(null) }
        var partialError by remember(path, preview.variantId, text) { mutableStateOf<Throwable?>(null) }
        // The interpreter re-runs on every recomposition pass, so a content lambda that fails deterministically
        // hands the renderer a FRESH Throwable each pass. Writing that to `partialError` (read during
        // composition) every pass would invalidate → re-run → invalidate … an unbounded recomposition loop.
        // Track the last error identity (type + message) and update state only when it actually changes — incl.
        // clearing to null. Keyed alongside `partialError` so both reset together on a new buffer.
        val partialKey = remember(path, preview.variantId, text) { arrayOfNulls<String>(1) }

        // Tell the pane when the engine is busy lowering/interpreting the buffer (the Loading phase) vs. settled,
        // so its badge can show a loading state while a fresh edit is being caught up to.
        LaunchedEffect(state) { reportBusy(state is PreviewState.Loading) }

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
                is PreviewState.Ready -> {
                    // Key the capture on the error's identity, not the instance: the interpreter throws a fresh
                    // Throwable each pass, so keying on it would relaunch + rewrite state every recomposition →
                    // a render loop. Same message/type ⇒ same key ⇒ captured once.
                    val onErr: @Composable (Throwable) -> Unit = { error ->
                        LaunchedEffect(error.message, error::class) { renderError = error }
                        PreviewRenderError(error)
                    }
                    val onPartial: (Throwable?) -> Unit = { e ->
                        val key = e?.let { "${it::class.java.name}: ${it.message}" }
                        if (key != partialKey[0]) {
                            partialKey[0] = key
                            if (e != null) log.warn("Compose preview partial render", e)
                            partialError = e
                        }
                    }
                    PreviewVariants(renderer, s.lowered, onErr, onPartial)
                }
                is PreviewState.NotInterpretable -> {
                    SelectionContainer {
                        Text(
                            "Preview not interpretable",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    // A Learn-lesson snippet: lower it through the backend's Compose scratch (no open project) and render
    // against the bundled Compose-for-Desktop (loader = null). Shares the interpret/render + error-reporting
    // shape with [Preview]; there's no @Preview variant config to honour, so [dark] is the only surface knob.
    @Composable
    override fun LessonPreview(code: String, dark: Boolean, onProblems: (List<PreviewIssue>) -> Unit, onBusy: (Boolean) -> Unit, modifier: Modifier) {
        val report by rememberUpdatedState(onProblems)
        val reportBusy by rememberUpdatedState(onBusy)
        // tolerateGaps=false so a snippet that fails to dispatch surfaces the reason instead of a blank preview.
        val renderer = remember { ComposePreviewRenderer(null, tolerateGaps = false) }
        val state by produceState<PreviewState>(PreviewState.Loading, code) {
            // Same first-run resilience as [Preview]: the hidden Compose scratch's androidx.compose.* download +
            // attach may still be in flight, so `Text`/`Column`/`remember` don't resolve yet. Stay in Loading
            // and re-lower until the scratch is ready (lessonComposePreviewReady flips true), bounded so a real
            // gap still surfaces its reason.
            var attempts = 0
            while (true) {
                val lowered = runCatching { backend.lowerLessonComposePreview(code) }.getOrNull()
                if (lowered != null) { value = PreviewState.Ready(lowered); break }
                val ready = runCatching { backend.lessonComposePreviewReady() }.getOrDefault(true)
                if (ready || attempts++ >= PREVIEW_READY_MAX_ATTEMPTS) {
                    val why = runCatching { backend.lessonComposePreviewDiagnostics(code) }
                        .getOrElse { listOf("couldn't analyze: ${it::class.simpleName}: ${it.message}") }
                        .ifEmpty { listOf("no reason reported (analysis returned nothing)") }
                    value = PreviewState.NotInterpretable(why)
                    break
                }
                value = PreviewState.Loading
                delay(PREVIEW_READY_POLL_MS)
            }
        }
        var renderError by remember(code) { mutableStateOf<Throwable?>(null) }
        var partialError by remember(code) { mutableStateOf<Throwable?>(null) }
        val partialKey = remember(code) { arrayOfNulls<String>(1) }

        LaunchedEffect(state) { reportBusy(state is PreviewState.Loading) }
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
                is PreviewState.Ready -> {
                    val onErr: @Composable (Throwable) -> Unit = { error ->
                        LaunchedEffect(error.message, error::class) { renderError = error }
                        PreviewRenderError(error)
                    }
                    val onPartial: (Throwable?) -> Unit = { e ->
                        val key = e?.let { "${it::class.java.name}: ${it.message}" }
                        if (key != partialKey[0]) {
                            partialKey[0] = key
                            if (e != null) log.warn("Compose lesson preview partial render", e)
                            partialError = e
                        }
                    }
                    PreviewVariants(renderer, s.lowered, onErr, onPartial)
                }
                is PreviewState.NotInterpretable -> SelectionContainer {
                    Text(
                        "Preview not interpretable",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    private sealed interface PreviewState {
        object Loading : PreviewState
        data class Ready(val lowered: LoweredComposePreview) : PreviewState
        data class NotInterpretable(val reasons: List<String>) : PreviewState
    }
}

/**
 * Render the lowered preview, expanding a `@PreviewParameter` into one stacked render per sample value (each in
 * its own `key` so their composition slots don't collide). A plain preview renders once with no args; a
 * provider that yields nothing falls back to a single arg-less render so the preview still shows.
 */
@Composable
private fun PreviewVariants(
    renderer: ComposePreviewRenderer,
    lowered: LoweredComposePreview,
    onError: @Composable (Throwable) -> Unit,
    onPartialError: (Throwable?) -> Unit,
) {
    val binding = lowered.parameter?.let { PreviewParameterBinding(it.providerClass, it.providerFqn, it.limit) }
    if (binding == null) {
        renderer.Render(lowered.entry, lowered.program, lowered.classes, emptyList(), onError, onPartialError)
        return
    }
    val values = remember(lowered.entry, lowered.program, binding) {
        renderer.parameterValues(lowered.program, lowered.classes, binding)
    }
    if (values.isEmpty()) {
        renderer.Render(lowered.entry, lowered.program, lowered.classes, emptyList(), onError, onPartialError)
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        values.forEachIndexed { i, value ->
            key(i) {
                if (values.size > 1) Text(
                    "[$i] ${value?.toString()?.take(48) ?: "null"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                renderer.Render(lowered.entry, lowered.program, lowered.classes, listOf(value), onError, onPartialError)
            }
        }
    }
}
