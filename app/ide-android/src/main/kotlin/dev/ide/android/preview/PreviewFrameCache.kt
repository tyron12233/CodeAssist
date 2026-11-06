package dev.ide.android.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The last frame each Compose preview showed, kept on disk so opening it again (a file reopened, a switch back to
 * a variant, the app restarted) shows a picture at once while the live render catches up, instead of a spinner.
 *
 * Keyed by everything that changes what the frame looks like before any code runs: the file, the variant, night
 * mode and the surface size. A frame from code edited since is replaced by the live one moments later, which is
 * the trade this cache makes. Lives in the app's cache dir, so the system may clear it, and it is pruned to the
 * most recent [MAX_FILES].
 */
internal object PreviewFrameCache {
    private const val DIR = "compose-preview-frames"
    private const val MAX_FILES = 64

    fun key(path: String, variantId: String, night: Boolean, widthPx: Int, heightPx: Int): String {
        val raw = "$path\u0000$variantId\u0000$night\u0000${widthPx}x$heightPx"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun file(context: Context, key: String) = File(File(context.cacheDir, DIR), "$key.png")

    suspend fun load(context: Context, key: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = file(context, key)
        if (!f.isFile) null else runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
    }

    /**
     * Save [frame] (cropped to [cropW] x [cropH] when smaller) as [key]'s last frame. A hardware bitmap (the
     * zero-copy frames from the preview process) is copied to a software one first, since it can't be compressed.
     */
    suspend fun save(context: Context, key: String, frame: Bitmap, cropW: Int?, cropH: Int?) = withContext(Dispatchers.IO) {
        runCatching {
            val soft = if (frame.config == Bitmap.Config.HARDWARE) frame.copy(Bitmap.Config.ARGB_8888, false) else frame
            val w = cropW?.coerceIn(1, soft.width) ?: soft.width
            val h = cropH?.coerceIn(1, soft.height) ?: soft.height
            val out = if (w < soft.width || h < soft.height) Bitmap.createBitmap(soft, 0, 0, w, h) else soft
            val dest = file(context, key).apply { parentFile?.mkdirs() }
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            tmp.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
            if (out !== soft) out.recycle()
            if (soft !== frame) soft.recycle()
            prune(dest.parentFile!!)
        }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".png") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }
}

/**
 * What to show while a preview has no live frame yet: its last frame from [PreviewFrameCache] with a small progress
 * mark in the corner, or just the progress indicator when there is none. Drawn like the live frame (scaled to the
 * pane's width) so the switch to it doesn't move anything.
 */
@Composable
internal fun LastFrameOrSpinner(frameKey: String?, modifier: Modifier = Modifier, onShown: () -> Unit = {}) {
    val context = LocalContext.current
    val last by produceState<ImageBitmap?>(null, frameKey) {
        value = frameKey?.let { PreviewFrameCache.load(context, it) }
        if (value != null) onShown()
    }
    val image = last
    if (image == null) {
        CircularProgressIndicator(Modifier.size(28.dp))
        return
    }
    Box(modifier) {
        Image(image, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp), strokeWidth = 2.dp)
    }
}
