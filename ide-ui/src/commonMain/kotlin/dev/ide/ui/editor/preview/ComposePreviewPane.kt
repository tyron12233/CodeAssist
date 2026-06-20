package dev.ide.ui.editor.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay

/** Idle delay before the live buffer is re-interpreted, so a burst of typing settles into one re-composition. */
private const val PREVIEW_DEBOUNCE_MS = 400L

/**
 * The Compose `@Preview` pane. Shares the exact [PreviewSurface] chrome with the layout-XML preview —
 * device selection, rotation, the Light/Night toggle, free pan + pinch/zoom — so both Preview views look
 * and behave the same. The open file's first `@Preview` is composed live inside the device card by the
 * platform render [host] (the on-device interpreter); the Night toggle drives the `uiMode`, mirroring
 * `@Preview(uiMode = UI_MODE_NIGHT_YES)`. The interpreter is NOT re-run on every keystroke: the rendered
 * buffer trails the editor by an idle debounce, and a Stop/Resume button freezes it entirely so editing
 * does no interpretation work. The Live/Paused badge reflects that state; Rebuild renders the current
 * buffer on demand. With no host (desktop) the card shows the "renders on device" note.
 */
@Composable
fun ComposePreviewPane(
    path: String,
    text: String,
    backend: IdeBackend,
    host: ComposePreviewHost?,
    modifier: Modifier,
    /** Which `@Preview` function to render (chosen from the editor gutter); null/unknown → the first one. */
    selected: String? = null,
) {
    val state = rememberPreviewSurfaceState(path)
    var previews by remember(path) { mutableStateOf<List<String>>(emptyList()) }
    var nonce by remember(path) { mutableStateOf(0) }
    var problems by remember(path) { mutableStateOf<List<PreviewIssue>>(emptyList()) }
    // Live preview is debounced + pausable so the interpreter doesn't re-run on every keystroke. `renderText`
    // is the buffer actually lowered + interpreted: while live it trails the editor by an idle delay (so a
    // burst of typing settles into ONE re-composition), and while paused it's frozen at its last value so
    // editing does no interpretation work at all. Rebuild renders the current buffer on demand regardless.
    var live by remember(path) { mutableStateOf(true) }
    var renderText by remember(path) { mutableStateOf(text) }
    LaunchedEffect(path, text, live) {
        if (!live) return@LaunchedEffect
        delay(PREVIEW_DEBOUNCE_MS)
        renderText = text
    }
    // The engine is "working" either while the host is actively lowering/interpreting the rendered buffer
    // (it reports this via onBusy) or while a live edit is still settling through the debounce before the
    // host even sees it. Either way the on-screen render is stale, so the badge shows a loading state.
    var busy by remember(path) { mutableStateOf(false) }
    val loading = host != null && (busy || (live && renderText != text))

    // Detect the @Preview functions from the rendered buffer (so the list/selection track what's on screen,
    // and detection stops churning while paused).
    LaunchedEffect(path, renderText) {
        previews = runCatching { backend.composePreviews(path, renderText).map { it.functionName } }.getOrDefault(emptyList())
    }
    // Render the user's selected preview when it still exists in the file; otherwise the first one.
    val fn = selected?.takeIf { it in previews } ?: previews.firstOrNull()


    PreviewSurface(
        modifier = modifier,
        state = state,
        cardColor = if (state.night) Color(0xFF161719) else Color.White,
        cardBorderColor = Ca.colors.separatorStrong,
        overlays = {
            PreviewProblemChip(problems, Modifier.align(Alignment.TopStart).padding(Ca.spacing.s3))
        },
        topBarExtras = {
            // The function currently being previewed (the editor gutter picks it; defaults to the first).
            if (fn != null) {
                Divider()
                Text(
                    fn, color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = Ca.spacing.s1),
                )
            }
            if (host != null) {
                Divider()
                Row(
                    Modifier.padding(horizontal = Ca.spacing.s1),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when {
                        // Engine catching up to a fresh buffer (compiling/interpreting): a spinner, not a dot.
                        loading -> {
                            CircularProgressIndicator(Modifier.size(9.dp), color = Ca.colors.warning, strokeWidth = 1.5.dp)
                            Text("Loading", color = Ca.colors.warning, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
                        }
                        live -> {
                            Box(Modifier.size(6.dp).background(Ca.colors.run, RoundedCornerShape(Ca.radius.pill)))
                            Text("Live", color = Ca.colors.run, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
                        }
                        else -> {
                            Box(Modifier.size(6.dp).background(Ca.colors.textTertiary, RoundedCornerShape(Ca.radius.pill)))
                            Text("Paused", color = Ca.colors.textTertiary, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        bottomBarExtras = {
            // Stop/resume the live preview so editing doesn't trigger interpretation (host-only — desktop has
            // no live interpreter). Resuming catches up to the current buffer after the usual debounce.
            if (host != null) {
                Divider()
                PillButton({ live = !live }) {
                    Icon(
                        if (live) CaIcons.stop else CaIcons.play,
                        if (live) "Stop live preview" else "Resume live preview",
                        Modifier.size(15.dp),
                        tint = if (live) Ca.colors.textSecondary else Ca.colors.run,
                    )
                }
            }
            Divider()
            // Render the current buffer now (also the manual trigger while paused).
            PillButton({ renderText = text; nonce++ }) { Icon(CaIcons.refresh, "Rebuild preview", Modifier.size(15.dp), tint = Ca.colors.textSecondary) }
        },
    ) { _, _, density ->
        val base = LocalDensity.current
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                host != null && fn != null -> key(nonce, fn) {
                    // Render at DEVICE scale by lowering the DENSITY the content sees: the card is already
                    // sized to the device viewport in px, so telling the content it has the device's density
                    // makes it lay out at the device's dp (a phone UI uses a phone's worth of width), and the
                    // surface's pan/zoom graphicsLayer scales the result. Night drives the host's uiMode; the
                    // host reports interpret/render problems back up to the shared chip.
                    CompositionLocalProvider(LocalDensity provides Density(density, base.fontScale)) {
                        host.Preview(path, fn, renderText, state.night, { problems = it }, { busy = it }, Modifier.fillMaxSize())
                    }
                }
                else -> {
                    LaunchedEffect(Unit) { problems = emptyList(); busy = false }
                    Text(
                        if (fn == null) "No @Preview found" else "Compose preview renders on device",
                        color = if (state.night) Color(0xFFA0A1AA) else Ca.colors.textTertiary,
                        style = Ca.type.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}
