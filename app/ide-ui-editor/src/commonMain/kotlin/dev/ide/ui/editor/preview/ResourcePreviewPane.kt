package dev.ide.ui.editor.preview

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Path
import dev.ide.ui.icons.CaIcons
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiColorEntry
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.respreview_anim_forever
import dev.ide.ui.generated.resources.respreview_anim_frames
import dev.ide.ui.generated.resources.respreview_anim_length
import dev.ide.ui.generated.resources.respreview_anim_pause
import dev.ide.ui.generated.resources.respreview_anim_play
import dev.ide.ui.generated.resources.respreview_anim_restart
import dev.ide.ui.generated.resources.respreview_decode_image_failed
import dev.ide.ui.generated.resources.respreview_no_colors
import dev.ide.ui.generated.resources.respreview_no_preview
import dev.ide.ui.generated.resources.respreview_render_drawable_failed
import dev.ide.ui.generated.resources.respreview_unresolved
import dev.ide.ui.generated.resources.respreview_unsupported
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource


fun isPreviewable(path: String): Boolean = previewKindOf(path) != null

/**
 * The resource Preview view — renders the drawable/color/bitmap resource at [path] (live buffer [text]),
 * resolving references through [backend]. Static (no editing); recomputes when the buffer changes.
 */
@Composable
fun ResourcePreviewPane(
    path: String,
    text: String,
    backend: IdeBackend,
    modifier: Modifier = Modifier
) {
    Box(modifier.background(Ide.colors.editorBg)) {
        when (previewKindOf(path)) {
            PreviewKind.DRAWABLE -> DrawablePreview(path, text, backend)
            PreviewKind.COLOR -> ColorPreview(path, text, backend)
            PreviewKind.BITMAP -> BitmapPreview(path, backend)
            null -> EmptyPreview(stringResource(Res.string.respreview_no_preview))
        }
    }
}

@Composable
private fun DrawablePreview(path: String, text: String, backend: IdeBackend) {
    var drawable by remember(path) { mutableStateOf<UiDrawable?>(null) }
    var loaded by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path, text) {
        drawable = runCatching { backend.preview.drawablePreview(path, text) }.getOrNull()
        loaded = true
    }
    val d = drawable
    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {}
        d == null -> EmptyPreview(stringResource(Res.string.respreview_render_drawable_failed))
        d is UiDrawable.Bitmap && d.filePath != null -> BitmapPreview(d.filePath!!, backend)
        else -> DrawableStage(d, backend)
    }
}

/**
 * The drawable on its checkerboard. An animated one plays (looping, with a short hold on the last frame so a
 * one-shot animation's end state is seen) under play/pause and restart controls. Image frames and layers are
 * loaded up front so an animation-list of PNGs shows its pictures rather than placeholders.
 */
@Composable
private fun DrawableStage(d: UiDrawable, backend: IdeBackend) {
    val images = rememberNestedImages(d, backend)
    val animated = remember(d) { DrawableAnimation.isAnimated(d) }
    val duration = remember(d) { DrawableAnimation.durationMs(d) }
    // Kept across edits of the file, so typing in the drawable does not restart or un-pause it.
    var playing by remember { mutableStateOf(true) }
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(playing, animated) {
        if (!playing || !animated) return@LaunchedEffect
        val from = elapsed
        val start = withFrameMillis { it }
        while (true) withFrameMillis { now -> elapsed = from + (now - start) }
    }
    val shown = if (animated) DrawableAnimation.atTime(d, playhead(elapsed, duration)) else d
    val paths = remember { TransientPathCache() }
    Column(
        Modifier.fillMaxSize().padding(Ca.spacing.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(220.dp).clip(RoundedCornerShape(Ca.radius.md))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.md)),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCheckerboard()
                val pad = 16.dp.toPx()
                drawUiDrawable(
                    shown,
                    Offset(pad, pad),
                    Size(size.width - 2 * pad, size.height - 2 * pad),
                    images = { bitmap -> bitmap.filePath?.let(images::get) },
                    parsePath = if (animated) paths::get else AndroidPathParser::cached,
                )
            }
        }
        if (animated) {
            Row(
                Modifier.padding(top = Ca.spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s1),
            ) {
                IconButton(onClick = { playing = !playing }) {
                    Icon(
                        if (playing) CaIcons.pause else CaIcons.play,
                        stringResource(if (playing) Res.string.respreview_anim_pause else Res.string.respreview_anim_play),
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { elapsed = 0L }) {
                    Icon(
                        CaIcons.refresh, stringResource(Res.string.respreview_anim_restart), Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Caption(animationSummary(d, duration))
            }
        }
        if (d is UiDrawable.Unsupported) {
            Box(Modifier.padding(top = Ca.spacing.s3)) {
                Caption(stringResource(Res.string.respreview_unsupported, d.rootTag, d.message))
            }
        }
    }
}

/** How long the preview rests on the last frame of an animation that ends before playing it again. */
private const val END_HOLD_MS = 800L

/** Where in the animation to draw after [elapsed] ms of playing: a finite one repeats with [END_HOLD_MS] rests. */
private fun playhead(elapsed: Long, duration: Long?): Long = when {
    duration == null -> elapsed
    duration <= 0L -> 0L
    else -> (elapsed % (duration + END_HOLD_MS)).coerceAtMost(duration)
}

@Composable
private fun animationSummary(d: UiDrawable, duration: Long?): String {
    val length = if (duration == null) stringResource(Res.string.respreview_anim_forever)
    else stringResource(Res.string.respreview_anim_length, duration.toInt())
    return if (d is UiDrawable.Frames) stringResource(Res.string.respreview_anim_frames, d.frames.size) + " · " + length
    else length
}

/**
 * Parsed paths for a drawable that is animating. A morphing path is new path data on every frame, so these stay
 * out of [AndroidPathParser.cached]'s shared cache, where they would push out the paths of every icon on screen.
 */
private class TransientPathCache {
    private val paths = LinkedHashMap<String, Path>()

    fun get(pathData: String, evenOdd: Boolean): Path {
        val key = if (evenOdd) "e:$pathData" else pathData
        paths[key]?.let { return it }
        if (paths.size >= MAX) paths.remove(paths.keys.first())
        return AndroidPathParser.parse(pathData, evenOdd).also { paths[key] = it }
    }

    private companion object {
        const val MAX = 128
    }
}

/** The decoded images of every bitmap a drawable draws (its frames, layers, states), keyed by file path. */
@Composable
private fun rememberNestedImages(d: UiDrawable, backend: IdeBackend): Map<String, ImageBitmap> {
    val files = remember(d) { LinkedHashSet<String>().also { collectImageFiles(d, it) }.toList() }
    var images by remember(files) { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(files) {
        if (files.isEmpty()) return@LaunchedEffect
        images = files.mapNotNull { path ->
            runCatching { backend.preview.resourceImageBytes(path)?.let { decodeImageBytes(it) } }.getOrNull()?.let { path to it }
        }.toMap()
    }
    return images
}

private fun collectImageFiles(d: UiDrawable, out: MutableSet<String>) {
    when (d) {
        is UiDrawable.Bitmap -> d.filePath?.let(out::add)
        is UiDrawable.Layers -> d.layers.forEach { collectImageFiles(it.drawable, out) }
        is UiDrawable.States -> d.defaultLayer?.let { collectImageFiles(it, out) }
        is UiDrawable.Frames -> d.frames.forEach { collectImageFiles(it.drawable, out) }
        else -> Unit
    }
}

@Composable
private fun ColorPreview(path: String, text: String, backend: IdeBackend) {
    var colors by remember(path) { mutableStateOf<List<UiColorEntry>>(emptyList()) }
    LaunchedEffect(path, text) {
        colors =
            runCatching { backend.preview.colorResources(path, text) }.getOrDefault(emptyList())
    }
    if (colors.isEmpty()) {
        EmptyPreview(stringResource(Res.string.respreview_no_colors))
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Ca.spacing.s5),
        verticalArrangement = Arrangement.spacedBy(Ca.spacing.s3)
    ) {
        for (c in colors) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s3)
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(Ca.radius.sm))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.sm)),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCheckerboard()
                        c.argb?.let { drawRect(argbColor(it)) }
                    }
                }
                Column {
                    androidx.compose.material3.Text(
                        c.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    androidx.compose.material3.Text(
                        c.rawValue.ifEmpty { "—" } + if (c.argb == null) "  " + stringResource(Res.string.respreview_unresolved) else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun BitmapPreview(path: String, backend: IdeBackend) {
    var image by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var loaded by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        image = runCatching {
            backend.preview.resourceImageBytes(path)?.let { decodeImageBytes(it) }
        }.getOrNull()
        loaded = true
    }
    val img = image
    when {
        !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {}
        img == null -> EmptyPreview(stringResource(Res.string.respreview_decode_image_failed))
        else -> Column(
            Modifier.fillMaxSize().padding(Ca.spacing.s5),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio((img.width.toFloat() / img.height).coerceIn(0.2f, 5f))
                    .clip(RoundedCornerShape(Ca.radius.md))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.md)),
            ) {
                Canvas(Modifier.fillMaxSize()) { drawCheckerboard() }
                Image(
                    img,
                    contentDescription = path.substringAfterLast('/'),
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Box(Modifier.padding(top = Ca.spacing.s3)) { Caption("${img.width} × ${img.height} px") }
        }
    }
}

@Composable
private fun EmptyPreview(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Caption(message) }
}

@Composable
private fun Caption(text: String) {
    androidx.compose.material3.Text(text, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
}
