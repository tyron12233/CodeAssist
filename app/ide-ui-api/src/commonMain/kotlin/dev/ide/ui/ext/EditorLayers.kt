package dev.ide.ui.ext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.ide.ui.backend.IdeBackend

/**
 * The two Compose-bearing editor surfaces, above the data tier.
 *
 * `platform.editorDecoration` covers what can be said as data: a tinted range, a gutter glyph, an inlay. Two
 * things cannot be said that way, and each gets a registry here.
 *
 * An [EditorLayerContribution] places real composables at document positions: a hover card, an inline
 * button, a code-lens row above a declaration. Those need touch targets, animation and Material components,
 * none of which a canvas draw can provide, and the host positions them in the layout phase so they scroll
 * with the text without recomposing.
 *
 * An [EditorPainterContribution] draws straight into the editor's canvas, at a chosen depth. That is the
 * escape hatch for the case the decoration tier deliberately refuses: a plugin whose colors are its own (a
 * blame heatmap, a coverage gradient, a minimap band) rather than one of the theme's named roles. It is also
 * the only contribution in the IDE that runs inside a per-frame draw, so it is the only one held to a
 * per-frame budget: see the note on [EditorPainterRegistry] for what happens to a painter that throws.
 */

// ---------------------------------------------------------------------------
// Anchored composables
// ---------------------------------------------------------------------------

/** Where an [EditorWidget] sits. Positions are resolved against the live document each frame. */
sealed interface EditorAnchor {
    /** At a document offset, on that offset's row and at its column. For a marker on a specific token. */
    class AtOffset(val offset: Int) : EditorAnchor

    /** After the end of [line]'s text, on the same row. The code-lens / inline-annotation position. */
    class AfterLine(val line: Int) : EditorAnchor

    /** On its own row above [line], full width. For a header over a declaration. */
    class AboveLine(val line: Int) : EditorAnchor
}

/**
 * One placed composable. [key] identifies it across recompositions so its state survives a scroll (a widget
 * whose key changes is a new widget and loses everything it remembered), and must be unique within one
 * layer's result.
 */
class EditorWidget(
    val anchor: EditorAnchor,
    val key: Any,
    val content: @Composable () -> Unit,
)

/** The open file a layer is placing widgets over. */
interface EditorLayerContext {
    val path: String

    /** The live buffer, which may differ from disk. Offsets in an anchor are against this text. */
    val text: String

    /** Inclusive range of document lines currently on screen, so a layer can place only what is visible. */
    val visibleLines: IntRange

    /** The caret offset, or the selection's start when there is a selection. */
    val caretOffset: Int

    /** Everything the engine exposes to the UI, for a layer whose state lives behind it. */
    val backend: IdeBackend

    /** Open [path] in the editor with the caret at [offset]. See [ToolWindowContext.openFile]. */
    fun openFile(path: String, offset: Int = 0) {}

    /** Navigate to a contributed [ScreenContribution] by id. */
    fun openScreen(id: String) {}
}

/**
 * A producer of anchored composables for the open file.
 *
 * [widgets] runs inside the editor's composition on every recomposition, so it must only read state and
 * decide, exactly like [TabDecorationContribution.decorate]. Being composable is what makes it reactive: read
 * a `StateFlow` collected as state, or your own observable store, and the layer re-places itself with no
 * polling and no push channel into the editor.
 *
 * Return the empty list for a file this layer has nothing to say about, or gate it with [appliesTo], which is
 * checked before composing anything.
 */
class EditorLayerContribution(
    val id: String,
    val order: Int = 1000,
    val appliesTo: (filePath: String) -> Boolean = { true },
    val widgets: @Composable (EditorLayerContext) -> List<EditorWidget>,
)

/** The process-global registry of editor layers. Compose-observable, like the rest of `dev.ide.ui.ext`. */
object EditorLayerRegistry {
    private val ordering = compareBy<EditorLayerContribution>({ it.order }, { it.id })
    private val items = mutableStateListOf<EditorLayerContribution>()

    fun register(layer: EditorLayerContribution): Registration {
        val after = items.indexOfFirst { ordering.compare(it, layer) > 0 }
        items.add(if (after < 0) items.size else after, layer)
        return Registration { items.remove(layer) }
    }

    /** The layers claiming [filePath], in resolution order. A layer whose predicate throws is skipped. */
    fun forFile(filePath: String): List<EditorLayerContribution> =
        items.filter { runCatching { it.appliesTo(filePath) }.getOrDefault(false) }

    fun all(): List<EditorLayerContribution> = items.toList()
}

// ---------------------------------------------------------------------------
// Raw painters
// ---------------------------------------------------------------------------

/** How deep in the editor's canvas a painter draws. */
enum class EditorPaintLayer {
    /**
     * Under the text, over the editor's background. Where a fill belongs: the text stays readable over it and
     * the selection still reads as being on top.
     */
    BelowText,

    /**
     * Over the text and over every decoration. For a line, a box or a marker that must not be hidden by the
     * code. A filled shape here will bury the text under it, which is the caller's problem.
     */
    AboveText,
}

/**
 * The editor's geometry for one frame, as a painter sees it.
 *
 * Everything is in the same pixel space the [DrawScope] draws in: the code area's origin is the top-left of
 * the whole editor, with the gutter occupying `[0, gutterWidth)` and the text starting at [textLeft]. The
 * functions account for scrolling, soft wrap and collapsed folds, which is why they are functions rather than
 * a table of numbers: `line * lineHeight` is wrong the moment a fold closes or a line wraps.
 */
interface EditorPaintContext {
    val path: String

    /** Inclusive range of document lines currently on screen. Painting outside it is clipped away. */
    val visibleLines: IntRange

    /** Total lines in the document. */
    val lineCount: Int

    /** Height of one unwrapped row, in px. */
    val lineHeight: Float

    /** Advance of one character in the editor's monospace font, in px. */
    val charWidth: Float

    /** Width of the gutter, in px: the band a painter must stay out of unless it means to draw in it. */
    val gutterWidth: Float

    /** X of the first text column, in px (past the gutter, past the left padding, minus the h-scroll). */
    val textLeft: Float

    /** Viewport Y of the top of [line]'s first row, in px. Off-screen lines answer honestly. */
    fun lineTop(line: Int): Float

    /** Viewport X of a document [offset], in px, or [textLeft] when the offset is not laid out. */
    fun xOf(offset: Int): Float

    /** The document line holding [offset]. */
    fun lineOf(offset: Int): Int

    /** `[start, end)` offsets of [line], excluding its break. */
    fun lineRange(line: Int): IntRange

    /** Whether [line] is inside a collapsed fold, so nothing should be drawn for it. */
    fun isHidden(line: Int): Boolean
}

/**
 * A painter for the editor's canvas.
 *
 * [paint] runs inside the editor's draw phase, every frame, including every frame of a fling. It must not
 * allocate per frame, must not read anything it has to compute, and must not throw. Precompute in a layer or
 * a decoration provider and draw from the result.
 */
class EditorPainterContribution(
    val id: String,
    val order: Int = 1000,
    val layer: EditorPaintLayer = EditorPaintLayer.BelowText,
    val appliesTo: (filePath: String) -> Boolean = { true },
    val paint: DrawScope.(EditorPaintContext) -> Unit,
)

/**
 * The process-global registry of editor painters.
 *
 * A painter that throws is **retired for the rest of the session**, not merely skipped for the frame. That is
 * deliberate and unlike every other registry here: this is the one contribution that runs in a draw, so a
 * painter that throws once throws sixty times a second, and "skip it this frame" would turn one plugin's bug
 * into an editor that neither draws nor recovers. Retiring it costs that plugin its drawing and leaves the
 * editor working.
 *
 * The failure is kept rather than logged. This module is Compose-Multiplatform common code with no logger to
 * reach, and a retirement is worth more than a log line anyway: [retirements] is observable, so a surface that
 * wants to tell the user which plugin stopped drawing and why can read it, and re-registering the painter
 * clears it.
 */
object EditorPainterRegistry {
    private val ordering = compareBy<EditorPainterContribution>({ it.order }, { it.id })
    private val items = mutableStateListOf<EditorPainterContribution>()
    private val retired = mutableStateMapOf<String, Throwable>()

    fun register(painter: EditorPainterContribution): Registration {
        val after = items.indexOfFirst { ordering.compare(it, painter) > 0 }
        items.add(if (after < 0) items.size else after, painter)
        retired.remove(painter.id)
        return Registration {
            items.remove(painter)
            retired.remove(painter.id)
        }
    }

    /** The painters for [filePath] at [layer], in resolution order, excluding any that have been retired. */
    fun forFile(filePath: String, layer: EditorPaintLayer): List<EditorPainterContribution> =
        items.filter {
            it.layer == layer &&
                it.id !in retired &&
                runCatching { it.appliesTo(filePath) }.getOrDefault(false)
        }

    /** Retire [id] after it threw [error] from `paint`. The first failure is the one kept: it is the one that
     *  happened while the painter was still whole, and a draw failure tends to repeat in a worse state. */
    fun retire(id: String, error: Throwable) {
        if (id !in retired) retired[id] = error
    }

    /** Why each retired painter was retired, for a surface that reports it. Observable. */
    val retirements: Map<String, Throwable> get() = retired

    /** Whether [id] has been retired. */
    fun isRetired(id: String): Boolean = id in retired

    fun all(): List<EditorPainterContribution> = items.toList()
}
