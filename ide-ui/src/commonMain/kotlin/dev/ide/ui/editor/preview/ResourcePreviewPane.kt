package dev.ide.ui.editor.preview

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
import dev.ide.ui.theme.Ca

/** Which resource preview a file gets — or null when it has none (so the Preview toggle stays hidden). */
enum class PreviewKind { DRAWABLE, COLOR, BITMAP }

private val IMAGE_EXTS = setOf("png", "webp", "jpg", "jpeg", "gif", "bmp")

/**
 * The preview a file qualifies for, by Android `res/` convention: a drawable/color/mipmap XML renders as a
 * drawable, an image file as a bitmap, and a `res/values` file named `*color*` as a color swatch list.
 */
fun previewKindOf(path: String): PreviewKind? {
    val p = path.replace('\\', '/').lowercase()
    if (!p.contains("/res/")) return null
    val file = p.substringAfterLast('/')
    val ext = file.substringAfterLast('.', "")
    val folder = p.substringBeforeLast('/').substringAfterLast('/').substringBefore('-')
    return when (ext) {
        "xml" if (folder == "drawable" || folder == "color" || folder == "mipmap") -> PreviewKind.DRAWABLE
        in IMAGE_EXTS -> PreviewKind.BITMAP
        "xml" if folder == "values" && file.contains("color") -> PreviewKind.COLOR
        else -> null
    }
}

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
    Box(modifier.background(Ca.colors.editorBg)) {
        when (previewKindOf(path)) {
            PreviewKind.DRAWABLE -> DrawablePreview(path, text, backend)
            PreviewKind.COLOR -> ColorPreview(path, text, backend)
            PreviewKind.BITMAP -> BitmapPreview(path, backend)
            null -> EmptyPreview("No preview available for this file")
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
        d == null -> EmptyPreview("Couldn't render this drawable")
        d is UiDrawable.Bitmap && d.filePath != null -> BitmapPreview(d.filePath!!, backend)
        else -> Column(
            Modifier.fillMaxSize().padding(Ca.spacing.s5),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(220.dp).clip(RoundedCornerShape(Ca.radius.md))
                    .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCheckerboard()
                    val pad = 16.dp.toPx()
                    drawUiDrawable(
                        d,
                        Offset(pad, pad),
                        Size(size.width - 2 * pad, size.height - 2 * pad)
                    )
                }
            }
            if (d is UiDrawable.Unsupported) {
                Box(Modifier.padding(top = Ca.spacing.s3)) {
                    Caption("Unsupported: <${d.rootTag}> — ${d.message}")
                }
            }
        }
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
        EmptyPreview("No colors declared in this file")
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
                        .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.sm)),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCheckerboard()
                        c.argb?.let { drawRect(argbColor(it)) }
                    }
                }
                Column {
                    androidx.compose.material3.Text(
                        c.name,
                        color = Ca.colors.textPrimary,
                        style = Ca.type.subhead,
                        fontWeight = FontWeight.Medium
                    )
                    androidx.compose.material3.Text(
                        c.rawValue.ifEmpty { "—" } + if (c.argb == null) "  (unresolved)" else "",
                        color = Ca.colors.textSecondary, style = Ca.type.caption,
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
        img == null -> EmptyPreview("Couldn't decode this image")
        else -> Column(
            Modifier.fillMaxSize().padding(Ca.spacing.s5),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio((img.width.toFloat() / img.height).coerceIn(0.2f, 5f))
                    .clip(RoundedCornerShape(Ca.radius.md))
                    .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
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
    androidx.compose.material3.Text(text, color = Ca.colors.textTertiary, style = Ca.type.footnote)
}
