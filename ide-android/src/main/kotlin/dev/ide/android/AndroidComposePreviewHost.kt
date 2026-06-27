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
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.withContext

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
            val lowered = runCatching { backend.lowerComposePreview(path, preview.functionName, preview.arity, text) }.getOrNull()
            value = if (lowered != null) PreviewState.Ready(lowered) else {
                // Surface WHY it isn't interpretable (the unsupported constructs + offending source) instead of
                // a bare message, so a gap is investigable. Even a thrown error becomes a visible reason.
                val why = runCatching { backend.composePreviewDiagnostics(path, preview.functionName, preview.arity, text) }
                    .getOrElse { listOf("couldn't analyze: ${it::class.simpleName}: ${it.message}") }
                    .ifEmpty { listOf("no reason reported (analysis returned nothing)") }
                PreviewState.NotInterpretable(why)
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
        val renderer = remember(loader) { ComposePreviewRenderer(loader) }
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
        // so a localized string/resource resolves the way that variant declares.
        val night = dark || (preview.config.nightMode == true)
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
                    is PreviewState.Ready -> {
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
