package dev.ide.plugin.impl

import dev.ide.platform.ExtensionRegistry
import dev.ide.plugin.editor.EDITOR_DECORATION_EP
import dev.ide.plugin.editor.EditorDecorationContext
import dev.ide.plugin.editor.EditorDecorationProvider
import dev.ide.plugin.editor.EditorDecorations
import dev.ide.plugin.editor.EditorInlay
import dev.ide.plugin.editor.GutterMark
import dev.ide.plugin.editor.TextDecoration
import kotlin.coroutines.cancellation.CancellationException

/**
 * Collects what the providers on [EDITOR_DECORATION_EP] have to say about one file, as the editor's
 * highlighting daemon runs its decoration pass. The single consumer of that extension point, and the
 * counterpart of [ActionManager] for the editor's visual surface.
 *
 * The registry is queried live on every call, so a plugin loaded later is picked up without rebuilding this.
 *
 * One provider cannot cost the pass. A provider that throws is dropped with its contribution and the rest are
 * still collected, because a decoration is an embellishment: losing one plugin's marks is a far better outcome
 * than an editor with no coloring at all. Cancellation is the exception, and propagates: the daemon cancels
 * this run when the user types, and swallowing that would leave the run applying stale marks.
 *
 * Offsets are validated here rather than trusted. A provider computes against the text it was handed, but it
 * may have suspended across an edit, and an out-of-range or inverted range would otherwise reach the renderer
 * (which indexes lines by offset) as a crash rather than as a dropped mark.
 */
class EditorDecorationCollector(private val registry: ExtensionRegistry) {

    private fun providers(): List<EditorDecorationProvider> = registry.extensions(EDITOR_DECORATION_EP)

    /** Whether any provider claims [ctx]'s file, so the host can skip the pass entirely for most files. */
    fun anyApplies(ctx: EditorDecorationContext): Boolean =
        providers().any { runCatching { it.appliesTo(ctx) }.getOrDefault(false) }

    /**
     * Every applicable provider's marks for [ctx], merged in registration order and filtered to what the
     * given text can carry. [onError] is notified once per failing provider, with its id, so the host can log
     * the attribution rather than a bare stack trace.
     */
    suspend fun collect(
        ctx: EditorDecorationContext,
        onError: (providerId: String, error: Throwable) -> Unit = { _, _ -> },
    ): EditorDecorations {
        val providers = providers()
        if (providers.isEmpty()) return EditorDecorations.EMPTY
        val length = ctx.text.length
        val ranges = ArrayList<TextDecoration>()
        val gutter = ArrayList<GutterMark>()
        val inlays = ArrayList<EditorInlay>()
        val lineCount = countLines(ctx.text)
        for (provider in providers) {
            val applies = try {
                provider.appliesTo(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(provider.id, e)
                continue
            }
            if (!applies) continue
            val result = try {
                provider.decorate(ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(provider.id, e)
                continue
            }
            for (r in result.ranges) if (r.startOffset in 0..length && r.endOffset in (r.startOffset + 1)..length) ranges += r
            for (m in result.gutter) if (m.line in 0 until lineCount) gutter += m
            for (i in result.inlays) if (i.offset in 0..length) inlays += i
        }
        if (ranges.isEmpty() && gutter.isEmpty() && inlays.isEmpty()) return EditorDecorations.EMPTY
        // Ascending order so a later, higher-order mark draws over an earlier one; ties keep the order the
        // providers were registered in, which is the only stable tiebreak available across plugins.
        return EditorDecorations(
            ranges = if (ranges.size > 1) ranges.sortedBy { it.order } else ranges,
            gutter = gutter,
            inlays = if (inlays.size > 1) inlays.sortedBy { it.offset } else inlays,
        )
    }

    /**
     * Lines in [text], counted the way the editor's own document counts them: one line break opens one more
     * line, so a text ending in a break has a final empty line that a mark may legitimately sit on. Counting
     * it any other way here would silently drop that mark.
     */
    private fun countLines(text: String): Int {
        var lines = 1
        for (c in text) if (c == '\n') lines++
        return lines
    }
}
