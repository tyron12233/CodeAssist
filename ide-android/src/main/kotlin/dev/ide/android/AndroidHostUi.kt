package dev.ide.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.platform.log.Log
import dev.ide.ui.backend.TreeNode

/**
 * Small, self-contained host helpers split out of [MainActivity]: the AdMob initializer, the "Save As"
 * document contract, the source-root search, and the boot splash. None of them belong to the activity's
 * lifecycle, so they live here to keep [MainActivity] to bootstrap + the Compose host.
 */

/**
 * Initialize the AdMob SDK once (idempotent, async). Ads only render if the user hasn't turned them off /
 * isn't a supporter; the app-id is declared in the manifest. Guarded: `play-services-ads` reads the WebView
 * user-agent during `initialize()`, which throws on an image with no WebView — ads are optional, so a failed
 * init must never take down the IDE.
 */
internal fun initAds(context: Context) {
    runCatching {
        com.google.android.gms.ads.MobileAds.initialize(context) { status ->
            val adapters = status.adapterStatusMap.entries.joinToString { (name, s) ->
                "$name=${s.initializationState}(${s.description})"
            }
            Log.logger("ide.ads").info("MobileAds initialized: $adapters")
        }
    }.onFailure { e -> Log.logger("ide.ads").warn("MobileAds init skipped: ${e.message}", e) }
}

/** Depth-first search for the first source root (a node carrying a source-root path) in the tree. */
internal fun firstSourceRoot(root: TreeNode): String? {
    root.sourceRootPath?.let { return it }
    for (child in root.children) firstSourceRoot(child)?.let { return it }
    return null
}

/**
 * "Save As" contract that derives the document MIME type from the file's own extension instead of a fixed
 * generic one. This is what keeps a collision-renamed export as `app-debug (1).apk` rather than
 * `app-debug.apk (1)`: DocumentsUI only splits off the extension before appending ` (n)` when the requested
 * MIME type maps back to the title's extension. With a mismatched type (e.g. `application/octet-stream` for an
 * `.apk`, which maps to `application/vnd.android.package-archive`) it treats the whole `app-debug.apk` as the
 * base name. Requesting `getMimeTypeFromExtension(ext) ?: octet-stream` mirrors exactly how the system
 * re-derives the type from the extension, so the two always agree and the ` (n)` lands before the extension.
 * Input is the suggested file name; result is the chosen document URI (or null if cancelled).
 */
internal class ExportDocumentContract : ActivityResultContract<String, android.net.Uri?>() {
    override fun createIntent(context: Context, input: String): Intent {
        val ext = input.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): android.net.Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

/** Minimal dark splash shown while the engine boots (before the themed UI is available). */
@Composable
internal fun Splash(message: String) {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0E0E12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(color = Color(0xFF8B7BF0))
            Text(
                message, color = Color(0xFFB9B9C6), fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}
