package dev.ide.android

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent as AndroidMotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.ide.interp.PreviewResourceResolver
import dev.ide.interp.PreviewSandboxPolicy
import dev.ide.interp.SandboxCategory
import dev.ide.interp.SandboxFinding
import androidx.compose.ui.unit.sp
import dev.ide.android.preview.ComposePreviewRemoteClient
import dev.ide.core.IdeServicesBackend
import dev.ide.core.LoweredComposePreview
import dev.ide.core.PreviewOutcome
import dev.ide.core.resolvePreviewOutcome
import dev.ide.interp.compose.ComposePreviewRenderer
import dev.ide.interp.compose.PreviewParameterBinding
import dev.ide.interp.compose.VmLibraryExecutor
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

/** Off-screen render canvas for an isolated preview when `@Preview` declares no size (px = dp × density, clamped). */
private const val DEFAULT_PREVIEW_WIDTH_DP = 411
private const val DEFAULT_PREVIEW_HEIGHT_DP = 731
private const val MAX_PREVIEW_PX = 2400
/** Fall back to the in-process renderer if the `:preview` session streams no frame within this long of opening. */
private const val REMOTE_FIRST_FRAME_TIMEOUT_MS = 6_000L

private val inProcessLog = Log.logger("AndroidComposePreviewHost")

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
        // The last buffer that lowered cleanly, retained across edits (survives text changes; reset on a file /
        // variant switch). A mid-edit / syntactically-broken buffer must never reach the Compose runtime — the
        // debounced re-lower returns null for it, and we keep this last good render on screen instead of blanking
        // (or, worse, churning a half-formed tree that corrupts the shared composer with "Missed endGroup").
        val lastGood = remember(path, preview.variantId) { arrayOfNulls<LoweredComposePreview>(1) }
        val state by produceState<PreviewState>(PreviewState.Loading, path, preview.functionName, preview.arity, text) {
            // First-run resilience: while the workspace index is still building, library composables (`Text`,
            // `Column`, `remember`) resolve to 0 candidates and the lower fails. Rather than latch that transient
            // failure into a permanent "unresolved call" error, stay in Loading and re-lower until the classpath
            // warms (composePreviewReady flips true). Bounded so a genuinely unsupported preview still surfaces
            // its reason instead of spinning forever.
            var attempts = 0
            while (true) {
                val lowered = runCatching { backend.lowerComposePreview(path, preview.functionName, preview.arity, text) }.getOrNull()
                if (lowered != null) { lastGood[0] = lowered; value = PreviewState.Ready(lowered); break }
                val ready = runCatching { backend.composePreviewReady(path) }.getOrDefault(true)
                if (ready || attempts++ >= PREVIEW_READY_MAX_ATTEMPTS) {
                    // Broken / not-yet-lowerable buffer: keep the last good render (a broken tree must never reach
                    // Compose); only when nothing ever rendered do we surface WHY (the unsupported constructs +
                    // offending source, so a gap is investigable). Even a thrown error becomes a visible reason.
                    val outcome = resolvePreviewOutcome(null, lastGood[0]) {
                        runCatching { backend.composePreviewDiagnostics(path, preview.functionName, preview.arity, text) }
                            .getOrElse { listOf("couldn't analyze: ${it::class.simpleName}: ${it.message}") }
                            .ifEmpty { listOf("no reason reported (analysis returned nothing)") }
                    }
                    value = when (outcome) {
                        is PreviewOutcome.Render -> PreviewState.Ready(outcome.lowered)
                        is PreviewOutcome.Unavailable -> PreviewState.NotInterpretable(outcome.reasons)
                    }
                    break
                }
                value = PreviewState.Loading
                delay(PREVIEW_READY_POLL_MS)
            }
        }
        // Heavy in-process render inputs (the bytecode VM executor over the module jars + the parsed resource
        // resolver) are built lazily inside [InProcessComposePreview] — the FALLBACK path — so the isolated
        // (`:preview`) render, the common case when the toggle is on, doesn't pay to open every jar and parse all
        // res XML only to discard it (the remote process rebuilds its own from the jar/res roots).
        val night = dark || (preview.config.nightMode == true)
        val density = LocalDensity.current.density
        // The preview sandbox: block file/network/Android-system/process escapes per the project's Compose
        // Preview settings. The default-restricted policy serves the FIRST pass too (no unrestricted window
        // while the settings read is in flight); the configured one replaces it only when the project
        // actually relaxes a category (else the instance — and its findings — stays stable).
        val defaultSandbox = remember(path) { PreviewSandboxPolicy(SandboxCategory.entries.toSet()) }
        val sandbox by produceState(defaultSandbox, path) {
            val cats = runCatching { backend.composePreviewSandbox() }.getOrNull()
                ?.mapNotNullTo(HashSet()) { SandboxCategory.fromId(it) } ?: return@produceState
            if (cats != SandboxCategory.entries.toSet()) value = PreviewSandboxPolicy(cats)
        }
        var renderError by remember(path, preview.variantId, text) { mutableStateOf<Throwable?>(null) }
        var partialError by remember(path, preview.variantId, text) { mutableStateOf<Throwable?>(null) }
        var sandboxFindings by remember(path, preview.variantId, text) { mutableStateOf(listOf<SandboxFinding>()) }
        // A buffer edit resets the recorded findings so the chip reflects the current text — a still-present
        // blocked call re-records on the next render pass.
        LaunchedEffect(text, sandbox) { sandbox.clearFindings() }
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
        LaunchedEffect(state, renderError, partialError, sandboxFindings) {
            val err = renderError
            val partial = partialError
            val issues = when {
                err != null -> listOf(PreviewIssue(PreviewIssueLevel.ERROR, "Preview failed to render", err.message ?: err::class.simpleName ?: "Unknown error"))
                partial != null -> listOf(PreviewIssue(PreviewIssueLevel.WARNING, "Preview partially rendered", partial.message ?: partial::class.simpleName ?: "Unknown error"))
                state is PreviewState.NotInterpretable -> (state as PreviewState.NotInterpretable).reasons.map { PreviewIssue(PreviewIssueLevel.WARNING, "Preview not interpretable", it) }
                else -> emptyList()
            }
            // Sandbox blocks ride along whatever else is reported: the stubbed call returned null, so the
            // preview may LOOK fine — the chip is the only place the block is visible.
            report(issues + sandboxFindings.map { PreviewIssue(PreviewIssueLevel.WARNING, "Preview blocked ${it.category.label}", it.member) })
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
        // Preview process isolation (docs/compose-preview-isolation.md): when the toggle is on, render the
        // @Preview in the :preview OS process and draw the streamed frame, so a runaway recomposition or crash
        // pegs only :preview, not the IDE. Experimental — @PreviewParameter / locale previews stay in-process
        // (the remote path doesn't cover them yet), and ANY remote failure (bind/open/render error, or no frame
        // within the deadline) flips `useRemote` off so the in-process renderer takes over with no visible break.
        val context = LocalContext.current
        val remoteClient = remember { ComposePreviewRemoteClient.get(context) }
        // Warm `:preview` the moment a preview mounts (when the isolation toggle is on): forking the process +
        // binding the service is the single biggest first-open cost, so overlap it with lowering / resource
        // resolution instead of paying it serially at openSession(). Idempotent — later mounts no-op.
        LaunchedEffect(Unit) {
            if (runCatching { backend.composePreviewIsolated() }.getOrDefault(false)) {
                runCatching { withContext(Dispatchers.IO) { remoteClient.warmUp() } }
            }
        }
        val remoteJars by produceState(emptyArray<String>(), path) {
            value = runCatching { backend.composePreviewLibs(path)?.jars?.map { it.toString() }?.toTypedArray() }.getOrNull() ?: emptyArray()
        }
        // The module's res dirs + R namespace, so :preview rebuilds the resource repository itself (it can't
        // receive the in-memory one) → `stringResource(R.string.x)`/`colorResource`/… resolve remotely too. The
        // `.second` flag makes the remote render WAIT for this (else it opens with no resources and R.* fails).
        val remoteResLoad by produceState(null as Pair<Array<String>, String>? to false, path) {
            val roots = runCatching { backend.composePreviewResourceRoots(path) }.getOrNull()
            value = roots?.let { it.resDirs.map { d -> d.toString() }.toTypedArray() to it.namespace } to true
        }
        val remoteRes = remoteResLoad.first
        val remoteResReady = remoteResLoad.second
        val remoteEligible by produceState(false, path, preview.variantId) {
            value = runCatching { backend.composePreviewIsolated() }.getOrDefault(false) &&
                !preview.hasParameter && preview.config.locale == null
        }
        var useRemote by remember(path, preview.variantId, remoteEligible) { mutableStateOf(remoteEligible) }
        CompositionLocalProvider(LocalConfiguration provides cfg) {
            Box(modifier, contentAlignment = Alignment.Center) {
                when (val s = state) {
                    is PreviewState.Loading -> CircularProgressIndicator(Modifier.size(28.dp))
                    is PreviewState.Ready -> if (useRemote) {
                        // Remote render: wait only for the remote res roots (`:preview` rebuilds its own resources)
                        // — NOT the in-process resource parse, which the isolated path doesn't use.
                        if (!remoteResReady) CircularProgressIndicator(Modifier.size(28.dp)) else {
                            val widthPx = ((preview.config.widthDp ?: DEFAULT_PREVIEW_WIDTH_DP) * density).toInt().coerceIn(1, MAX_PREVIEW_PX)
                            val heightPx = ((preview.config.heightDp ?: DEFAULT_PREVIEW_HEIGHT_DP) * density).toInt().coerceIn(1, MAX_PREVIEW_PX)
                            RemoteComposePreview(
                                client = remoteClient, lowered = s.lowered, jars = remoteJars,
                                resRoots = remoteRes?.first ?: emptyArray(), namespace = remoteRes?.second ?: "",
                                widthPx = widthPx, heightPx = heightPx, density = density, night = night,
                                onUnavailable = { useRemote = false }, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        // In-process render (isolation off, or a remote failure fell back here): this composable
                        // builds the VM executor + resource resolver on demand, so that heavy work never runs on
                        // the isolated path. Its render outcomes flow up to the hoisted state → the shared chip.
                        InProcessComposePreview(
                            backend = backend, path = path, lowered = s.lowered, night = night, density = density,
                            sandbox = sandbox, partialKey = partialKey,
                            onRenderError = { renderError = it },
                            onPartialError = { partialError = it },
                            onSandboxFindings = { if (it != sandboxFindings) sandboxFindings = it },
                        )
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
        // The sandbox matches: strict mode (throw, not stub), everything restricted — an authored lesson
        // snippet has no business touching files/network/system, and a violation should fail loudly.
        val renderer = remember {
            ComposePreviewRenderer(
                null, tolerateGaps = false,
                hooks = PreviewSandboxPolicy(SandboxCategory.entries.toSet(), stubOnDeny = false),
            )
        }
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
 * The in-process Compose preview renderer — the FALLBACK path, used when the isolation toggle is off or a
 * `:preview` render failed. It builds the bytecode-VM library executor (over the module's jars) and the project
 * resource resolver ON DEMAND (so the isolated path never pays to open every jar or parse all res XML), then
 * interprets [lowered] via [ComposePreviewRenderer] into the IDE's own composition. Render outcomes are reported
 * up through [onRenderError] / [onPartialError] / [onSandboxFindings] so the host's shared problem chip reflects
 * them. Because this composable only enters composition on the fallback branch, none of its produceState work
 * runs while an isolated preview is on screen.
 */
@Composable
private fun InProcessComposePreview(
    backend: IdeServicesBackend,
    path: String,
    lowered: LoweredComposePreview,
    night: Boolean,
    density: Float,
    sandbox: PreviewSandboxPolicy,
    partialKey: Array<String?>,
    onRenderError: (Throwable) -> Unit,
    onPartialError: (Throwable?) -> Unit,
    onSandboxFindings: (List<SandboxFinding>) -> Unit,
) {
    // The project's library closure executes in the bytecode VM: dependency classes are read straight from the
    // resolved jars and interpreted (bridged to the IDE's bundled runtime), so downloaded code never reaches a
    // ClassLoader. Null while the jar list resolves / on failure → the renderer falls back to bundled Compose.
    val libraryExecutor by produceState<VmLibraryExecutor?>(null, path) {
        value = runCatching {
            backend.composePreviewLibs(path)?.let { libs ->
                // Disk-backed peer-dex cache (workspace-wide): a rebuilt executor reuses previously-dexed peers
                // instead of re-running D8 (~0.4s → ~40ms per open; see DexPeerFactory).
                val peerDexCache = runCatching { libs.cacheDir.resolveSibling("vm-peer-dex") }.getOrNull()
                withContext(Dispatchers.IO) {
                    // Guard the peer proxies: an interpreted object realized as a real interface can have its
                    // methods invoked by platform code OUTSIDE the render's error boundary; the sink degrades a
                    // failure there to a skipped call instead of crashing the app.
                    VmLibraryExecutor(
                        libs.jars,
                        peerFactory = DexPeerFactory(peerDexCache, proxyExceptionSink = { t ->
                            inProcessLog.warn("interpreted preview peer call failed (skipped): ${t.message ?: t.javaClass.simpleName}")
                        }),
                    )
                }
            }
        }.getOrNull()
    }
    // Close the executor's jar handles when it is replaced or leaves composition (produceState never disposes a
    // superseded value; the local capture closes THIS executor, not whatever the state holds later).
    DisposableEffect(libraryExecutor) {
        val exec = libraryExecutor
        onDispose { exec?.close() }
    }
    // Project resources with the previewed density + night baked in, so stringResource/colorResource/R.* resolve
    // against the project. `.first` = resolver (null for a non-Android module); `.second` = load settled — the
    // render waits for it, else the first pass fails "no resolver" and that error latches.
    val resLoad by produceState(null as PreviewResourceResolver? to false, path, night, density) {
        val resolver = runCatching {
            val res = backend.composePreviewResources(path)
            if (res == null) inProcessLog.warn("no preview resources for $path — R.string/colorResource/… won't resolve")
            res?.let { withContext(Dispatchers.IO) { AndroidPreviewResources(it.repo, it.namespace, density, night) } }
        }.onFailure { inProcessLog.warn("building preview resources for $path failed: ${it.javaClass.name}: ${it.message}", it) }.getOrNull()
        value = resolver to true
    }
    val resources = resLoad.first
    val resourcesReady = resLoad.second
    val renderer = remember(libraryExecutor, resources, sandbox) {
        ComposePreviewRenderer(resources = resources, hooks = sandbox, libraryExecutor = libraryExecutor)
    }
    // Wait for the resource load to settle before the first render, so stringResource/R.* have their resolver.
    if (!resourcesReady) {
        CircularProgressIndicator(Modifier.size(28.dp))
        return
    }
    // Key the error capture on the error's identity, not the instance: the interpreter throws a fresh Throwable
    // each pass, so keying on it would relaunch + rewrite state every recomposition → a render loop.
    val onErr: @Composable (Throwable) -> Unit = { error ->
        LaunchedEffect(error.message, error::class) { onRenderError(error) }
        PreviewRenderError(error)
    }
    val onPartial: (Throwable?) -> Unit = { e ->
        val key = e?.let { "${it::class.java.name}: ${it.message}" }
        if (key != partialKey[0]) {
            partialKey[0] = key
            if (e != null) inProcessLog.warn("Compose preview partial render", e)
            onPartialError(e)
        }
        // Drain the sandbox's blocked-call findings after each pass; the hoisted write no-ops when unchanged.
        onSandboxFindings(sandbox.findings())
    }
    PreviewVariants(renderer, lowered, onErr, onPartial)
}

/**
 * Draw a preview rendered OUT-OF-PROCESS: open a [ComposePreviewRemoteClient] session for [lowered], stream its
 * frames, and draw the latest as a bitmap scaled to the pane width. A live edit ([lowered] change) is pushed to
 * the running session ([ComposePreviewRemoteClient.Session.update], so remembered state survives). Any failure —
 * the session won't open, the render errors, or no frame arrives within [REMOTE_FIRST_FRAME_TIMEOUT_MS] — calls
 * [onUnavailable] so the host falls back to the in-process renderer with no visible break. The `:preview` bind
 * can block, so the session is opened on a background thread; Binder-callback state writes are posted to main.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RemoteComposePreview(
    client: ComposePreviewRemoteClient,
    lowered: LoweredComposePreview,
    jars: Array<String>,
    resRoots: Array<String>,
    namespace: String,
    widthPx: Int,
    heightPx: Int,
    density: Float,
    night: Boolean,
    onUnavailable: () -> Unit,
    modifier: Modifier,
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    // A new session is needed only when the classpath or resource roots change (they drive `:preview`'s executor +
    // resource rebuild). Size / night are pushed to the LIVE session via resize() instead, so they must NOT re-key
    // the session/frame state — that would tear the session down and flash a spinner. Keeping the last frame
    // across a night/size change draws it (scaled) until the re-rendered one streams in.
    val cpKey = jars.joinToString("\n")
    val resKey = resRoots.joinToString("\n")
    var frame by remember(client, cpKey, resKey, namespace) { mutableStateOf<Bitmap?>(null) }
    // Hold the Session in a plain box, NOT a mutableStateOf<Session> — a Compose-tracked field of our own type
    // makes the compiler emit a `Session.$stable` read, which crashes (NoSuchFieldError) if that class wasn't
    // instrumented. An Int epoch (bumped when the session opens/closes) drives the effects instead.
    val sessionBox = remember(client, cpKey, resKey, namespace) { arrayOfNulls<ComposePreviewRemoteClient.Session>(1) }
    var sessionEpoch by remember(client, cpKey, resKey, namespace) { mutableIntStateOf(0) }
    val fallback by rememberUpdatedState(onUnavailable)
    val openLowered by rememberUpdatedState(lowered)

    // Open the session off the main thread (the :preview bind can block) and stream frames in; re-open ONLY on a
    // classpath / resource change — NOT on a program edit (→ update()) or a size/night change (→ resize()).
    DisposableEffect(client, cpKey, resKey, namespace) {
        var disposed = false
        val sink = object : ComposePreviewRemoteClient.FrameSink {
            override fun onFrame(bitmap: Bitmap, seq: Long) { main.post { frame = bitmap } }
            override fun onError(message: String) { main.post { fallback() } }
        }
        Thread {
            val s = client.openSession(openLowered, widthPx, heightPx, density, night, sink, jars, resRoots, namespace)
            main.post {
                if (disposed) { s?.close() }
                else { sessionBox[0] = s; sessionEpoch++; if (s == null) fallback() }
            }
        }.apply { isDaemon = true; name = "compose-preview-open"; start() }
        onDispose { disposed = true; sessionBox[0]?.close(); sessionBox[0] = null }
    }
    // Live edit: push the re-lowered program to the running session (off main — update makes a Binder call).
    LaunchedEffect(sessionEpoch, lowered) {
        val s = sessionBox[0] ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { s.update(lowered) }
    }
    // Size / night change: re-target the LIVE session instead of re-opening (a night toggle re-renders on the same
    // off-screen surface; a size change recreates it in `:preview`). oneway, so hop off main. Also fires right
    // after each open with the opened values — a harmless no-op in `:preview` when nothing actually changed.
    LaunchedEffect(sessionEpoch, widthPx, heightPx, density, night) {
        val s = sessionBox[0] ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { s.resize(widthPx, heightPx, density, night) }
    }
    // First-frame watchdog: no frame within the deadline → fall back in-process.
    LaunchedEffect(sessionEpoch) {
        if (sessionBox[0] != null) { delay(REMOTE_FIRST_FRAME_TIMEOUT_MS); if (frame == null) fallback() }
    }

    val bmp = frame
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bmp != null) {
            // The frame is drawn scaled to the pane width (ContentScale.FillWidth = uniform scale). Forward each
            // pointer event to the remote session, mapping the tap from displayed pixels back to the off-screen
            // canvas' pixels (canvasPx = displayPx * bitmapWidth / displayedWidth). Single-pointer (tap/scroll/
            // drag); multi-touch is a follow-up.
            var displayWidth by remember { mutableStateOf(0) }
            val focusRequester = remember { FocusRequester() }
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { displayWidth = it.width }
                    .focusRequester(focusRequester)
                    .focusable()
                    // Forward key events (hardware keyboard, nav/shortcut keys, onKeyEvent handlers) to the focused
                    // preview. Don't swallow BACK, so the user can still navigate out. Soft-keyboard TEXT entry
                    // (commitText) is a separate IME bridge — see docs.
                    .onPreviewKeyEvent { ev ->
                        val s = sessionBox[0]
                        val ke = ev.nativeKeyEvent
                        if (s != null && ke.keyCode != AndroidKeyEvent.KEYCODE_BACK) {
                            s.dispatchKey(ke.action, ke.keyCode, ke.metaState, ke.eventTime)
                            true
                        } else false
                    }
                    .pointerInteropFilter { ev ->
                        val s = sessionBox[0]
                        if (s != null && displayWidth > 0) {
                            // Tap focuses the preview so keyboard/nav keys route to it (tap elsewhere to leave).
                            if (ev.actionMasked == AndroidMotionEvent.ACTION_DOWN) runCatching { focusRequester.requestFocus() }
                            val scale = bmp.width.toFloat() / displayWidth
                            s.dispatchInput(ev.actionMasked, ev.x * scale, ev.y * scale, ev.getPointerId(0), ev.eventTime)
                        }
                        true
                    },
            )
        } else {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
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
