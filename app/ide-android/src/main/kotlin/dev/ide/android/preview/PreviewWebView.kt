package dev.ide.android.preview

import android.os.Build
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `:preview` process's own WebView data directory.
 *
 * From Android 9 a WebView data directory belongs to one process at a time, and the IDE's main process already
 * holds the default one (its ads are WebViews). A layout or Compose preview that inflates a `<WebView>` in
 * `:preview` then failed: "Using WebView from more than one process at once with the same data directory is not
 * supported", and the real-view render fell back to the legacy one. The suffix has to be set before anything in
 * the process loads WebView, so every `:preview` service calls [isolate] from its `onCreate`.
 */
internal object PreviewWebView {
    private val done = AtomicBoolean(false)

    fun isolate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !done.compareAndSet(false, true)) return
        // Throws once WebView is already loaded in this process, which only a second call could find.
        runCatching { WebView.setDataDirectorySuffix("preview") }
    }
}
