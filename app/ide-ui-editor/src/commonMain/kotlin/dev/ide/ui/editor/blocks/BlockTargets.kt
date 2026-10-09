package dev.ide.ui.editor.blocks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/*
 * Absolute geometry for a laid-out canvas: every block, token and socket with its canvas-space rect, for
 * hit-testing, and every place a dragged block could connect, for the nearest-target search. Rebuilt with the
 * layout; pure, so the snapping rules test without a UI.
 */

/** Where a block sits: as entry [index] of a list, as a socket's value, or as a page's own top. */
sealed interface BlockSite {
    val doc: DocRef

    /** Entry [index] of the list slot [slotIndex] of [ownerId]; [tail] is how many entries follow from it. */
    data class InList(override val doc: DocRef, val ownerId: String, val slotIndex: Int, val index: Int, val tail: Int, val droppable: Boolean) : BlockSite

    /** The value in socket [slotIndex] of [ownerId]. */
    data class InSocket(override val doc: DocRef, val ownerId: String, val slotIndex: Int) : BlockSite

    /** A function hat, or the value of a scratch value stack. */
    data class Top(override val doc: DocRef) : BlockSite
}

class PlacedBlock(val block: LBlock, val rect: Rect, val site: BlockSite, val depth: Int)

class PlacedToken(val token: LToken, val rect: Rect, val doc: DocRef, val depth: Int)

class PlacedSocket(val socket: LSocket, val rect: Rect, val doc: DocRef, val depth: Int)

/** A call's argument hole or chip, placed. */
class PlacedAction(val item: LItem, val rect: Rect, val doc: DocRef, val depth: Int)

/** A list slot's absolute origin, with the blocks shown in it. */
class PlacedBody(val body: LBody, val origin: Offset, val doc: DocRef)

/** Something a drag can connect to. [anchor] is where the dragged block's top-left lands for it. */
sealed interface DropTarget {
    val doc: DocRef
    val anchor: Offset

    /**
     * Insert at [index] (in the projected slot) of list [slotIndex] of [ownerId]. [afterTerminal] = the shown
     * block above is a `return`/`break`; [atEnd] = an append. [above] = attach the dragged stack's bottom to
     * the top of a scratch stack (the stack stays put and the new blocks grow upward), so its anchor is
     * offset by the dragged height at match time.
     */
    data class Stack(
        override val doc: DocRef, val ownerId: String, val slotIndex: Int, val index: Int,
        override val anchor: Offset, val width: Float, val afterTerminal: Boolean, val atEnd: Boolean, val above: Boolean = false,
    ) : DropTarget

    /** Put a value in socket [slotIndex] of [ownerId] (replacing what is there). */
    data class Socket(
        override val doc: DocRef, val ownerId: String, val slotIndex: Int, override val anchor: Offset,
        val rect: Rect, val shape: ValueShape,
    ) : DropTarget

    /** Wrap a C-block around a whole list: [count] entries from [firstId]. [anchor] is the first entry's top-left. */
    data class Wrap(override val doc: DocRef, val firstId: String, val count: Int, override val anchor: Offset) : DropTarget
}

class CanvasIndex(val layout: CanvasLayout) {
    val blocks = ArrayList<PlacedBlock>()
    val tokens = ArrayList<PlacedToken>()
    val sockets = ArrayList<PlacedSocket>()
    val bodies = ArrayList<PlacedBody>()
    val actions = ArrayList<PlacedAction>()
    private val g = layout.geometry

    init {
        for (stack in layout.stacks) {
            var y = stack.y
            for (b in stack.blocks) {
                place(b, stack.x, y, BlockSite.Top(stack.doc), stack.doc, 0)
                b.hatBody?.let { placeBody(it, stack.x + it.x, y + it.y, stack.doc, 1) }
                y += b.h + (b.hatBody?.h ?: 0f)
            }
            stack.body?.let { placeBody(it, stack.x, stack.y, stack.doc, 0) }
        }
    }

    private fun placeBody(body: LBody, x: Float, y: Float, doc: DocRef, depth: Int) {
        bodies += PlacedBody(body, Offset(x, y), doc)
        body.blocks.forEachIndexed { i, b ->
            val site = BlockSite.InList(doc, body.ownerId, body.slotIndex, body.indices[i], body.blocks.size - i, body.droppable)
            place(b, x, y + body.offsets[i], site, doc, depth + 1)
        }
    }

    private fun place(b: LBlock, x: Float, y: Float, site: BlockSite, doc: DocRef, depth: Int) {
        blocks += PlacedBlock(b, Rect.of(x, y, b.w, b.h), site, depth)
        for (item in b.items) when (item) {
            is LToken -> tokens += PlacedToken(item, Rect.of(x + item.x, y + item.y, item.w, item.h), doc, depth + 1)
            is LArgHole, is LArgChip -> actions += PlacedAction(item, Rect.of(x + item.x, y + item.y, item.w, item.h), doc, depth + 1)
            is LSocket -> {
                sockets += PlacedSocket(item, Rect.of(x + item.x, y + item.y, item.w, item.h), doc, depth + 1)
                item.child?.let { place(it, x + item.x, y + item.y, BlockSite.InSocket(doc, item.ownerId, item.slotIndex), doc, depth + 2) }
            }
            else -> {}
        }
        for (s in b.sections) if (s is LMouth) placeBody(s.body, x + s.body.x, y + s.body.y, doc, depth + 1)
    }

    // ---- hit-testing ----

    sealed interface Hit {
        data class Token(val placed: PlacedToken) : Hit
        data class Socket(val placed: PlacedSocket) : Hit
        data class Block(val placed: PlacedBlock) : Hit
        data class Action(val placed: PlacedAction) : Hit
    }

    /** The deepest thing under [p]: a token or empty socket wins over the block it sits on. */
    fun hitAt(p: Offset): Hit? {
        actions.filter { it.rect.inflate(2f * g.density).contains(p) }.maxByOrNull { it.depth }?.let { return Hit.Action(it) }
        val block = blocks.filter { it.block.kind != LKind.Group && it.rect.contains(p) && insideShape(it, p) }.maxByOrNull { it.depth }
        val token = tokens.filter { it.rect.inflate(2f * g.density).contains(p) }.maxByOrNull { it.depth }
        val socket = sockets.filter { it.socket.child == null && it.rect.contains(p) }.maxByOrNull { it.depth }
        val leaf = listOfNotNull(token?.let { Hit.Token(it) to it.depth }, socket?.let { Hit.Socket(it) to it.depth })
            .filter { (_, d) -> block == null || d >= block.depth }
            .maxByOrNull { it.second }?.first
        // A literal value is edited by tapping it, like a socket.
        if (leaf == null && block != null && block.block.kind == LKind.Literal) {
            val site = block.site as? BlockSite.InSocket
            val socketOf = sockets.firstOrNull { site != null && it.socket.ownerId == site.ownerId && it.socket.slotIndex == site.slotIndex && it.doc == site.doc }
            if (socketOf != null) return Hit.Socket(socketOf)
        }
        return leaf ?: block?.let { Hit.Block(it) }
    }

    /** A C-block's rect includes its mouths; a point in a mouth's empty space is not the block. */
    private fun insideShape(p: PlacedBlock, pt: Offset): Boolean {
        if (p.block.kind != LKind.CBlock) return true
        val local = pt - p.rect.topLeft
        return p.block.sections.none { it is LMouth && local.y >= it.y && local.y < it.y + it.h && local.x > g.armW }
    }

    /** The placed block for [id] in [doc], if shown. */
    fun block(doc: DocRef, id: String): PlacedBlock? = blocks.firstOrNull { it.site.doc == doc && it.block.id == id }

    // ---- drop targets ----

    /** Every connection a dragged statement run could make. */
    fun stackTargets(): List<DropTarget> {
        val out = ArrayList<DropTarget>()
        for (pb in bodies) {
            val body = pb.body
            if (!body.droppable) continue
            val n = body.blocks.size
            for (i in 0..n) {
                val y = pb.origin.y + if (i < n) body.offsets[i] else body.h
                val index = if (i < n) body.indices[i] else body.total
                val afterTerminal = i > 0 && body.blocks[i - 1].terminal
                out += DropTarget.Stack(pb.doc, body.ownerId, body.slotIndex, index, Offset(pb.origin.x, y), maxOf(body.w, g.minStmtWidth), afterTerminal, atEnd = i == n)
            }
            // Wrapping takes the whole list, so not one the dragged run is being lifted out of.
            if (n > 0 && n == body.total) {
                body.blocks.first().id?.let { first ->
                    out += DropTarget.Wrap(pb.doc, first, body.total, pb.origin)
                }
            }
            // A scratch stack's top also takes a stack attached above it.
            if (pb.doc is DocRef.Scratch && n > 0 && body.indices.first() == 0) {
                out += DropTarget.Stack(pb.doc, body.ownerId, body.slotIndex, 0, pb.origin, body.w, afterTerminal = false, atEnd = false, above = true)
            }
        }
        return out
    }

    /** Every socket a dragged value could go into. */
    fun socketTargets(): List<DropTarget> = sockets.mapNotNull { ps ->
        val cat = ps.socket.slot.category
        if (cat != "EXPRESSION" && cat != "ARGUMENT" && cat != "OPAQUE") return@mapNotNull null
        DropTarget.Socket(ps.doc, ps.socket.ownerId, ps.socket.slotIndex, ps.rect.topLeft, ps.rect, ps.socket.shape)
    }
}

/** What is being dragged, as far as connecting it is concerned. */
class DragShape(
    /** A value (reporter) rather than a run of statements. */
    val isValue: Boolean,
    /** The run's height (for attaching above a scratch stack). */
    val height: Float,
    /** The run ends in a `return`/`throw`/`break`: nothing may follow it. */
    val terminal: Boolean,
    /** For a C-block with an empty first body: where that body starts, relative to the block's top-left. */
    val mouth: Offset?,
    /** A value's shape, for socket compatibility. */
    val shape: ValueShape = ValueShape.Unknown,
)

/**
 * The target the dragged block (top-left at [pos]) should connect to: the nearest within [threshold] by
 * [snapDistance], honouring the terminal-block rules and socket kinds. [current] is kept unless another
 * target is closer by [hysteresis], so the preview does not flicker between two near-equal targets.
 */
fun nearestTarget(
    targets: List<DropTarget>,
    shape: DragShape,
    pos: Offset,
    threshold: Float,
    current: DropTarget? = null,
    hysteresis: Float = 0f,
): DropTarget? {
    fun landing(t: DropTarget): Offset? = when (t) {
        is DropTarget.Stack -> when {
            shape.isValue -> null
            t.above -> if (shape.terminal) null else t.anchor - Offset(0f, shape.height)
            shape.terminal && !t.atEnd -> null
            t.afterTerminal -> null
            else -> t.anchor
        }
        is DropTarget.Wrap -> shape.mouth?.let { t.anchor - it }?.takeUnless { shape.isValue }
        is DropTarget.Socket -> if (shape.isValue && accepts(t.shape, shape.shape)) t.anchor else null
    }
    var best: DropTarget? = null
    var bestD = Float.MAX_VALUE
    var currentD = Float.MAX_VALUE
    for (t in targets) {
        val at = landing(t) ?: continue
        val d = snapDistance(pos, at)
        if (t == current) currentD = d
        if (d < threshold && d < bestD) { best = t; bestD = d }
    }
    if (current != null && currentD < threshold && currentD <= bestD + hysteresis) return current
    return best
}

/** Where the dragged block's top-left goes for [t] (the silhouette's position). */
fun landingOf(t: DropTarget, shape: DragShape): Offset = when (t) {
    is DropTarget.Stack -> if (t.above) t.anchor - Offset(0f, shape.height) else t.anchor
    is DropTarget.Wrap -> t.anchor - (shape.mouth ?: Offset.Zero)
    is DropTarget.Socket -> t.anchor
}

/** Whether a socket of [socket] kind takes a value of [value] kind. Unknown on either side is permissive. */
fun accepts(socket: ValueShape, value: ValueShape): Boolean = when (socket) {
    ValueShape.Unknown, ValueShape.Object -> value != ValueShape.Type
    ValueShape.Type -> false
    else -> value == socket || value == ValueShape.Unknown || value == ValueShape.Object
}

/** The first droppable mouth of a C-block, if it is empty: what lets the block be wrapped around a stack. */
fun emptyMouthOf(block: LBlock): Offset? {
    val mouth = block.sections.firstOrNull { it is LMouth } as? LMouth ?: return null
    if (!mouth.body.droppable || mouth.body.blocks.isNotEmpty()) return null
    return Offset(mouth.body.x, mouth.body.y)
}
