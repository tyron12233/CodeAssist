package dev.ide.ui.editor.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.ComposePreviewHost
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiComposePreview
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
    var previews by remember(path) { mutableStateOf<List<UiComposePreview>>(emptyList()) }
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

    // Detect the @Preview variants from the rendered buffer (so the list/selection track what's on screen,
    // and detection stops churning while paused).
    LaunchedEffect(path, renderText) {
        previews = runCatching { backend.composePreviews(path, renderText) }.getOrDefault(emptyList())
    }
    // Variant selection: the editor gutter picks one (via [selected]); the in-pane selector can override it
    // (cleared whenever the gutter selection changes so a fresh gutter tap wins).
    var picked by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(selected) { picked = null }
    val current = previews.firstOrNull { it.variantId == (picked ?: selected) } ?: previews.firstOrNull()
    val cfg = current?.config

    // A @Preview(device=...)/widthDp/heightDp sizes the card to that profile instead of the user's selection.
    val deviceOverride = remember(cfg?.device, cfg?.widthDp, cfg?.heightDp) {
        cfg?.let { PreviewDevices.resolve(it.device, it.widthDp, it.heightDp) }
    }
    // With no device/size and no system UI, a Compose @Preview wraps the composable's own size (not a phone).
    val wrap = current != null && deviceOverride == null && !(cfg?.showSystemUi ?: false)
    // A @Preview(uiMode = UI_MODE_NIGHT_*) defaults the surface to that scheme on variant change; the user can
    // still toggle Night afterward (until they switch variants).
    LaunchedEffect(current?.variantId) { cfg?.nightMode?.let { state.night = it } }
    // @Preview(showBackground=true, backgroundColor=...) paints the card; otherwise the surface's light/dark card.
    val cardBg = cfg?.takeIf { it.showBackground && it.backgroundColor != null }?.let { Color(it.backgroundColor!!) }
        ?: if (state.night) Color(0xFF161719) else Color.White

    PreviewSurface(
        modifier = modifier,
        state = state,
        deviceOverride = deviceOverride,
        wrapContent = wrap,
        cardColor = cardBg,
        cardBorderColor = Ca.colors.separatorStrong,
        overlays = {
            PreviewProblemChip(problems, Modifier.align(Alignment.TopStart).padding(Ca.spacing.s3))
        },
        topBarExtras = { compact ->
            // The variant being previewed. With several variants it's a dropdown (grouped by @Preview group) so
            // any can be picked; with one it's a plain label. Weighted + ellipsized so it shrinks (instead of
            // squishing the status badge) as the bar narrows, and capped tighter when compact.
            if (current != null) {
                Divider()
                if (previews.size > 1) {
                    VariantSelector(
                        previews = previews, current = current, compact = compact,
                        onSelect = { picked = it.variantId },
                    )
                } else {
                    Text(
                        current.label, color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .widthIn(max = if (compact) 96.dp else 168.dp)
                            .padding(horizontal = Ca.spacing.s1),
                    )
                }
            }
            if (host != null) {
                Divider()
                Row(
                    Modifier.padding(horizontal = Ca.spacing.s1),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // When compact the status is icon-only (a colored dot, or a spinner while catching up) — the
                    // Live/Loading/Paused label is dropped so the bar fits a small surface.
                    when {
                        // Engine catching up to a fresh buffer (compiling/interpreting): a spinner, not a dot.
                        loading -> {
                            CircularProgressIndicator(Modifier.size(9.dp), color = Ca.colors.warning, strokeWidth = 1.5.dp)
                            if (!compact) Text("Loading", color = Ca.colors.warning, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
                        }
                        live -> {
                            Box(Modifier.size(if (compact) 7.dp else 6.dp).background(Ca.colors.run, RoundedCornerShape(Ca.radius.pill)))
                            if (!compact) Text("Live", color = Ca.colors.run, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
                        }
                        else -> {
                            Box(Modifier.size(if (compact) 7.dp else 6.dp).background(Ca.colors.textTertiary, RoundedCornerShape(Ca.radius.pill)))
                            if (!compact) Text("Paused", color = Ca.colors.textTertiary, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
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
        // Wrap mode: the card sizes to the composable, so the content (and this box) must wrap too rather than
        // fill a fixed viewport. Fixed mode fills the device card.
        val contentMod = if (wrap) Modifier.wrapContentSize() else Modifier.fillMaxSize()
        Box(contentMod, contentAlignment = Alignment.Center) {
            when {
                host != null && current != null -> key(nonce, current.variantId) {
                    // Render at DEVICE scale by lowering the DENSITY the content sees: the card is already
                    // sized to the device viewport in px, so telling the content it has the device's density
                    // makes it lay out at the device's dp (a phone UI uses a phone's worth of width), and the
                    // surface's pan/zoom graphicsLayer scales the result. A @Preview(fontScale=...) multiplies
                    // the font scale. Night drives the host's uiMode; the host reports problems back to the chip.
                    val fontScale = base.fontScale * (current.config.fontScale ?: 1f)
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        val preview = @Composable {
                            host.Preview(path, current, renderText, state.night, { problems = it }, { busy = it }, contentMod)
                        }
                        // @Preview(showSystemUi=true) frames the preview in a mock status + navigation bar (always
                        // fixed-size, never wrap), so the device chrome has a viewport to fill.
                        if (current.config.showSystemUi) SystemUiChrome(state.night, Modifier.fillMaxSize(), preview)
                        else preview()
                    }
                }
                else -> {
                    LaunchedEffect(Unit) { problems = emptyList(); busy = false }
                    Text(
                        if (current == null) "No @Preview found" else "Compose preview renders on device",
                        color = if (state.night) Color(0xFFA0A1AA) else Ca.colors.textTertiary,
                        style = Ca.type.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}

/** The top-bar preview picker shown when a file has several `@Preview` variants: a pill of the current label
 *  that drops down the full list (each prefixed with its `@Preview(group=...)` when set). */
@Composable
private fun RowScope.VariantSelector(
    previews: List<UiComposePreview>,
    current: UiComposePreview,
    compact: Boolean,
    onSelect: (UiComposePreview) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f, fill = false)) {
        PillButton({ open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current.label, color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (compact) 76.dp else 144.dp).padding(start = Ca.spacing.s1),
                )
                Icon(CaIcons.chevronDown, "Choose preview", Modifier.size(13.dp).padding(start = 2.dp), tint = Ca.colors.textTertiary)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            previews.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (p.group != null) "${p.group} · ${p.label}" else p.label,
                            style = Ca.type.caption,
                            color = if (p.variantId == current.variantId) Ca.colors.accent else Ca.colors.textPrimary,
                            fontWeight = if (p.variantId == current.variantId) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { onSelect(p); open = false },
                )
            }
        }
    }
}

/** Frames a `@Preview(showSystemUi = true)` preview in a mock status + navigation bar so the composable is
 *  seen inside a phone shell (matching Android Studio's system-UI chrome), not edge-to-edge. */
@Composable
private fun SystemUiChrome(dark: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val bar = if (dark) Color(0xFF0B0B0D) else Color(0xFF35363A)
    val fg = Color.White.copy(alpha = 0.85f)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(24.dp).background(bar).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("12:00", color = fg, style = Ca.type.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { Box(Modifier.size(8.dp).background(fg, RoundedCornerShape(2.dp))) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        Row(
            Modifier.fillMaxWidth().height(38.dp).background(bar),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Box(Modifier.size(11.dp).background(fg.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
            Box(Modifier.size(13.dp).background(fg.copy(alpha = 0.6f), CircleShape))
            Box(Modifier.size(11.dp).background(fg.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
        }
    }
}
