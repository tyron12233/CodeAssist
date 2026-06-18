package dev.ide.ui.editor.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * The Compose `@Preview` pane. Shares the exact [PreviewSurface] chrome with the layout-XML preview —
 * device selection, rotation, the Light/Night toggle, free pan + pinch/zoom — so both Preview views look
 * and behave the same. The open file's first `@Preview` is composed live inside the device card by the
 * platform render [host] (the on-device interpreter); the Night toggle drives the `uiMode`, mirroring
 * `@Preview(uiMode = UI_MODE_NIGHT_YES)`. A green Live badge shows when a host is present, and a Rebuild
 * button forces a fresh composition. With no host (desktop) the card shows the "renders on device" note.
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

    // Detect the @Preview functions in the live buffer (debounce is handled by the caller's edit cadence).
    LaunchedEffect(path, text) {
        previews = runCatching { backend.composePreviews(path, text).map { it.functionName } }.getOrDefault(emptyList())
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
                    Box(Modifier.size(6.dp).background(Ca.colors.run, RoundedCornerShape(Ca.radius.pill)))
                    Text("Live", color = Ca.colors.run, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        bottomBarExtras = {
            Divider()
            PillButton({ nonce++ }) { Icon(CaIcons.refresh, "Rebuild preview", Modifier.size(15.dp), tint = Ca.colors.textSecondary) }
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
                        host.Preview(path, fn, text, state.night, { problems = it }, Modifier.fillMaxSize())
                    }
                }
                else -> {
                    LaunchedEffect(Unit) { problems = emptyList() }
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
