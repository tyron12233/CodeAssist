package dev.ide.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ide.interp.PreviewResourceResolver
import androidx.compose.ui.unit.sp
import dev.ide.core.IdeServicesBackend
import dev.ide.core.LoweredComposePreview
import dev.ide.interp.compose.ComposePreviewRenderer
import dev.ide.interp.compose.PreviewParameterBinding
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.editor.preview.PreviewIssue
import dev.ide.ui.editor.preview.PreviewIssueLevel
import dev.ide.ui.editor.preview.PreviewRenderError
import dev.ide.platform.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Poll cadence + patience while the classpath warms (first-run indexing / scratch dep attach) before the
 *  preview gives up waiting and surfaces whatever it can lower. ~3 min comfortably covers a cold first-run
 *  index; the cap only bites the degenerate case where readiness never arrives (e.g. an offline scratch). */
private const val PREVIEW_READY_POLL_MS = 600L
private const val PREVIEW_READY_MAX_ATTEMPTS = 300

/**
 * The on-device Compose preview host (the editor's live-pixel renderer). Lowers the open file's `@Preview`
 * through the backend (off the UI thread, serialized with other language work), then composes it via
 * [ComposePreviewRenderer] into the IDE's own composition. Held by [MainActivity] over a stable
 * [IdeServicesBackend] — which swaps its inner services on project switch — so one host serves all projects.
 */
class AndroidComposePreviewHost(private val backend: IdeServicesBackend) : ComposePreviewHost {

    private val log = Log.logger("AndroidComposePreviewHost")

    @Composable
    override fun Preview(path: String, preview: UiComposePreview, text: String, dark: Boolean, onProblems: (List<PreviewIssue>) -> Unit, onBusy: (Boolean) -> Unit, modifier: Modifier) {
        val report by rememberUpdatedState(onProblems)
        val reportBusy by rememberUpdatedState(onBusy)
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
                    // Surface WHY it isn't interpretable (the unsupported constructs + offending source) instead
                    // of a bare message, so a gap is investigable. Even a thrown error becomes a visible reason.
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
        // Dex the project's library closure once (off-thread, cached by dependency fingerprint) and dispatch
        // library composables through it. Null while it builds / on failure → the renderer falls back to the
        // IDE's bundled Compose, which still serves standard composables.
        val loader by produceState<ClassLoader?>(null, path) {
            value = runCatching {
                backend.composePreviewLibs(path)?.let { withContext(Dispatchers.IO) { ComposeLibraryLoader.loaderFor(it) } }
            }.getOrNull()
        }
        // Interpreter-mediated project resources: fetch the module's res off-thread, then build a resolver with
        // the previewed density + night baked in (see [AndroidPreviewResources]) so `stringResource(R.string.x)`
        // / `colorResource`/`painterResource`/… resolve against the project (not the IDE app's own Resources).
        // Null while it builds / for a non-Android module → the renderer falls back to no resource resolution.
        val night = dark || (preview.config.nightMode == true)
        val density = LocalDensity.current.density
        // The resolver loads async (off-thread res parse). Track "settled" so the render WAITS for it — else the
        // first render fires with a null resolver (before this completes), `stringResource(R.string.x)` throws
        // "no resource resolver", and that error latches (`renderError` never clears on a later good render).
        // `.first` = the resolver (or genuine null for a non-Android module); `.second` = load finished.
        val resLoad by produceState(null as PreviewResourceResolver? to false, path, night, density) {
            val resolver = runCatching {
                val res = backend.composePreviewResources(path)
                if (res == null) log.warn("no preview resources for $path — R.string/colorResource/… won't resolve (module has no Android namespace or an empty resource repo)")
                res?.let { withContext(Dispatchers.IO) { AndroidPreviewResources(it.repo, it.namespace, density, night) } }
            }.onFailure { log.warn("building preview resources for $path failed: ${it.javaClass.name}: ${it.message}", it) }.getOrNull()
            value = resolver to true
        }
        val resources = resLoad.first
        val resourcesReady = resLoad.second
        val renderer = remember(loader, resources) { ComposePreviewRenderer(loader, resources = resources) }
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
                    err != null -> listOf(PreviewIssue(PreviewIssueLevel.ERROR, "Preview failed to render", err.message ?: err::class.simpleName ?: "Unknown error"))
                    partial != null -> listOf(PreviewIssue(PreviewIssueLevel.WARNING, "Preview partially rendered", partial.message ?: partial::class.simpleName ?: "Unknown error"))
                    state is PreviewState.NotInterpretable -> (state as PreviewState.NotInterpretable).reasons.map { PreviewIssue(PreviewIssueLevel.WARNING, "Preview not interpretable", it) }
                    else -> emptyList()
                },
            )
        }

        // Force the requested night mode so a theme reading isSystemInDarkTheme() (i.e. LocalConfiguration's
        // uiMode) renders the same preview Light or Dark. The effective night is the surface's Night toggle OR
        // the variant's own @Preview(uiMode = UI_MODE_NIGHT_YES). A @Preview(locale=...) overrides the locale
        // so a localized string/resource resolves the way that variant declares. (`night` is computed above,
        // where the resource resolver is built.)
        val locale = preview.config.locale?.takeIf { it.isNotBlank() }
        val base = LocalConfiguration.current
        val cfg = remember(base, night, locale) {
            Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                // @Preview locale qualifiers use the resource form ("fr-rFR"); normalize to a BCP-47 tag.
                locale?.let { setLocale(java.util.Locale.forLanguageTag(it.replace("-r", "-"))) }
            }
        }
        CompositionLocalProvider(LocalConfiguration provides cfg) {
            Box(modifier, contentAlignment = Alignment.Center) {
                when (val s = state) {
                    is PreviewState.Loading -> CircularProgressIndicator(Modifier.size(28.dp))
                    // Wait for the resource load to settle before the first render, so `stringResource`/`R.*`
                    // have their resolver (else the first pass fails "no resolver" and that error latches).
                    is PreviewState.Ready -> if (!resourcesReady) CircularProgressIndicator(Modifier.size(28.dp)) else {
                        // Key the capture on the error's identity, not the instance: the interpreter throws a
                        // fresh Throwable each pass, so keying on it would relaunch + rewrite state every
                        // recomposition → a render loop. Same message/type ⇒ same key ⇒ captured once.
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
                    is PreviewState.NotInterpretable -> Text(
                        "Preview not interpretable", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    // A Learn-lesson snippet: lower it through the backend's Compose scratch (no open project) and render
    // against the bundled Compose-for-Android (loader = null — standard material3/foundation composables serve
    // from the app runtime). Shares the interpret/render + error-reporting shape with [Preview]; there's no
    // @Preview variant config, so [dark] alone drives the forced night scheme.
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
                    err != null -> listOf(PreviewIssue(PreviewIssueLevel.ERROR, "Preview failed to render", err.message ?: err::class.simpleName ?: "Unknown error"))
                    partial != null -> listOf(PreviewIssue(PreviewIssueLevel.WARNING, "Preview partially rendered", partial.message ?: partial::class.simpleName ?: "Unknown error"))
                    state is PreviewState.NotInterpretable -> (state as PreviewState.NotInterpretable).reasons.map { PreviewIssue(PreviewIssueLevel.WARNING, "Preview not interpretable", it) }
                    else -> emptyList()
                },
            )
        }

        // Force the requested night scheme so a theme reading isSystemInDarkTheme() renders the same either way.
        val base = LocalConfiguration.current
        val cfg = remember(base, dark) {
            Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
        }
        CompositionLocalProvider(LocalConfiguration provides cfg) {
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
                    is PreviewState.NotInterpretable -> Text(
                        "Preview not interpretable", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, modifier = Modifier.padding(16.dp),
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
