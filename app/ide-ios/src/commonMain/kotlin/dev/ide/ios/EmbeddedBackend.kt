package dev.ide.ios

import dev.ide.android.layout.ANDROID_LAYOUT_MODEL
import dev.ide.android.layout.LAYOUT_RENDERER
import dev.ide.android.layout.remote.RemoteLayoutServices
import dev.ide.android.layouteditor.LayoutEditorPlugin
import dev.ide.android.layouteditor.LayoutEditorUiPlugin
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceLookup
import dev.ide.ui.ext.UiPlugin
import dev.ide.ui.ext.asUiPlugin
import platform.Foundation.NSData

/**
 * The JVM the app embeds, as the app hands it to this framework.
 *
 * Most of the IDE's backend is JVM code. On iOS it runs in a JVM linked into the app, started by the app's own
 * native code (which is where JNI lives); this is the whole of what that code has to offer. `NSData` both ways,
 * because that is what it has on its side of the call and a picture is megabytes.
 */
interface EmbeddedJvm {
    /** Open the project at [projectRoot] in the embedded backend. Empty when it opened, else why it did not. */
    fun openProject(projectRoot: String): String

    /** One request of the layout services' byte protocol in, its answer out. Blocks until answered. */
    fun call(request: NSData): NSData
}

/**
 * What an embedded backend adds to this host: the layout editor, mounted as the plugin it is, with its two
 * services answered across the channel by the same code that answers them on a phone.
 */
internal class EmbeddedBackend(
    private val jvm: EmbeddedJvm,
    /** The project the app has open, which the embedded backend follows. */
    private val activeRoot: () -> String?,
) {
    private var openedRoot: String? = null
    private var openFailure: String? = null
    private val lock = dev.ide.ui.concurrent.UiLock()

    /**
     * The embedded backend opens a project the first time something is asked of it, not when the app does:
     * most projects opened on this host never show a layout, and starting an engine for them costs for nothing.
     */
    private fun ensureOpen() = lock.withLock {
        val root = activeRoot() ?: error("No project is open.")
        if (root != openedRoot) {
            openFailure = runCatching { jvm.openProject(root) }.getOrElse { it.message ?: it.toString() }.ifEmpty { null }
            openedRoot = root
        }
        openFailure?.let { error(it) }
    }

    private val remote = RemoteLayoutServices { request ->
        ensureOpen()
        jvm.call(request.toNSData()).toByteArray()
    }

    private val services = object : ServiceLookup {
        override fun <T : Any> getService(key: ServiceKey<T>): T =
            getServiceOrNull(key) ?: error("no service ${key.id} on this host")

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> getServiceOrNull(key: ServiceKey<T>): T? = when (key.id) {
            LAYOUT_RENDERER.id -> remote.renderer as T
            ANDROID_LAYOUT_MODEL.id -> remote.model as T
            else -> null
        }
    }

    val uiPlugins: List<UiPlugin> = listOf(LayoutEditorUiPlugin.asUiPlugin(LayoutEditorPlugin.ID, services))
}
