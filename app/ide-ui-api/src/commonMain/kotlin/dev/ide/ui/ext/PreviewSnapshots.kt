package dev.ide.ui.ext

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CancellationException

/**
 * What a preview pane last drew, for a caller outside the UI (the AI agent's `screenshot_preview` tool) that
 * wants to see it. [png] is the encoded frame; [path] is the source file it previews and [label] names what
 * was rendered (a `@Preview` function, a layout), both for the caller's report.
 */
class PreviewSnapshot(val path: String, val label: String, val png: ByteArray, val width: Int, val height: Int)

/** A preview pane that can hand over its current frame. */
fun interface PreviewSnapshotSource {
    /** The current frame of the preview for [path] (or of whatever is shown, when [path] is null); null when
     *  this pane is not showing that file or has nothing drawn yet. */
    suspend fun snapshot(path: String?): PreviewSnapshot?
}

/**
 * The preview panes currently on screen, registered while they are composed. Process-global because the
 * renderers live in the UI and the caller lives in the engine, and both can see this module. The most recently
 * registered pane is asked first, which is the one the user opened last.
 */
object PreviewSnapshots {
    // Snapshot state, like the other UI registries: registration happens on the UI thread, reads from the
    // engine's, and a snapshot-state list is safe across the two without a lock (which commonMain lacks).
    private val sources = mutableStateListOf<PreviewSnapshotSource>()

    fun register(source: PreviewSnapshotSource): Registration {
        sources.add(source)
        return Registration { sources.remove(source) }
    }

    /** Whether any preview pane is on screen. */
    val available: Boolean get() = sources.isNotEmpty()

    suspend fun capture(path: String?): PreviewSnapshot? {
        val current = sources.toList().asReversed()
        for (source in current) {
            val shot = try {
                source.snapshot(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (shot != null) return shot
        }
        return null
    }
}
