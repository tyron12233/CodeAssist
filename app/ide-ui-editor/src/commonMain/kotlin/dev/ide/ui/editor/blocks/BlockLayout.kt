package dev.ide.ui.editor.blocks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.backend.UiBlockPart

/*
 * The block canvas's layout pass. One recursive measure-and-place over the projected [UiBlockNode] tree
 * gives every block, label, token and socket a position in canvas space, so drawing, hit-testing and the
 * drop-target search all read the same geometry instead of waiting for a composable tree to lay itself out.
 *
 * Everything here is plain arithmetic over a [TextMeasure]: no Compose UI, so the geometry and the snapping
 * rules are testable headlessly with a fake measurer.
 */

/** Which document a laid-out block belongs to: the file itself, or one of its scratch stacks. */
sealed interface DocRef {
    data object File : DocRef
    data class Scratch(val key: Long) : DocRef
}

/** How a piece of block text is styled; the measurer picks a font per role, the painter a color. */
enum class TextRole { Keyword, Name, Qualifier, Chrome, Literal, Hint, Header, HeaderDim }

/**
 * A measured run of text. [handle] is whatever the measurer needs to draw it later; [colored] means it carries
 * its own syntax colors, which drawing keeps.
 */
class MText(val text: String, val role: TextRole, val width: Float, val height: Float, val handle: Any? = null, val colored: Boolean = false)

fun interface TextMeasure {
    fun measure(text: String, role: TextRole): MText
}

/** Where the painter takes a text's color from. */
enum class TextTint { OnBlock, OnBlockDim, OnSocket, OnSocketDim, Hint, Header, HeaderDim }

/** The block geometry in px, derived from dp at [density]. Denser than the old composable blocks. */
class BlockGeometry(val density: Float) {
    private fun dp(v: Float) = v * density
    val corner = dp(4f)
    val hatCorner = dp(14f)
    val notchInset = dp(12f)
    val notchWidth = dp(16f)
    val notchDepth = dp(3.5f)
    val notchSlope = dp(3f)
    val padH = dp(8f)
    val padV = dp(5f)
    val gap = dp(4f)
    val tightGap = dp(3f)
    val rowMin = dp(20f)
    val socketH = dp(20f)
    val socketMinW = dp(36f)
    val socketPadH = dp(7f)
    val pillPadH = dp(6f)
    val pillPadV = dp(2f)
    val hexPoint = dp(7f)
    val armW = dp(14f)
    val footerH = dp(12f)
    val mouthMin = dp(16f)
    val minCWidth = dp(96f)
    val minStmtWidth = dp(44f)
    val chainIndent = dp(10f)
    val hatTop = dp(6f)
    val dividerW = dp(1f)
    val dividerH = dp(14f)
    val caret = dp(9f)
    val stackGap = dp(26f)
    val margin = dp(16f)
    val longLiteral = dp(200f)
}

// ---------------------------------------------------------------------------
// The laid-out tree. Coordinates of items are relative to the block that owns them.
// ---------------------------------------------------------------------------

sealed interface LItem {
    val x: Float
    val y: Float
    val w: Float
    val h: Float
}

class LText(override val x: Float, override val y: Float, val text: MText, val tint: TextTint, val shape: ValueShape = ValueShape.Unknown) : LItem {
    override val w get() = text.width
    override val h get() = text.height
}

/** An editable token on a block (a name, a qualifier): tap to edit it in place. */
class LToken(
    override val x: Float, override val y: Float, val text: MText, val tint: TextTint,
    val blockId: String, val field: UiBlockPart.Field,
) : LItem {
    override val w get() = text.width
    override val h get() = text.height
}

/** A value socket: empty (a hole hinting the kind it expects) or holding a reporter [child]. */
class LSocket(
    override val x: Float, override val y: Float, override val w: Float, override val h: Float,
    val ownerId: String, val slotIndex: Int, val slot: UiBlockPart.Slot, val shape: ValueShape,
    val child: LBlock?, val hint: MText?,
) : LItem

/** The thin divider between the segments of a flattened call chain. */
class LDivider(override val x: Float, override val y: Float, override val w: Float, override val h: Float) : LItem

/**
 * A parameter the call does not supply yet: a hole that adds it. Filling writes argument [insertIndex] of
 * segment [link] of [callId] (by name when [named]); [closeAt] is where the argument lands in the source and
 * [leading] what goes before it there (`, ` / `name = `), for completion while typing it.
 */
class LArgHole(
    override val x: Float, override val y: Float, override val w: Float, override val h: Float,
    val callId: String, val link: Int, val insertIndex: Int, val name: String?, val named: Boolean,
    val closeAt: Int, val leading: String, val hint: MText, val shape: ValueShape, val optional: Boolean, val key: String, val paramIndex: Int,
) : LItem

/** [Open]: show a lambda body on its own page; [Scope]: list the functions a scoped lambda body can call. */
enum class ArgChipKind { More, Fewer, Overload, Add, Open, Scope }

/** A chip on a call: show the optional parameters, switch overload, or add an argument (when nothing is known). */
class LArgChip(
    override val x: Float, override val y: Float, override val w: Float, override val h: Float,
    val kind: ArgChipKind, val key: String, val callId: String, val link: Int, val text: MText,
    val closeAt: Int, val leading: String, val insertIndex: Int,
) : LItem

/** A small mark for what a modifier or value does (see [Glyph]). */
class LGlyph(override val x: Float, override val y: Float, override val w: Float, override val h: Float, val glyph: Glyph, val onSocket: Boolean = false) : LItem

/** A color swatch for a value that spells a color. */
class LSwatch(override val x: Float, override val y: Float, override val w: Float, override val h: Float, val argb: Long) : LItem

/** The fold caret drawn on a method hat. */
class LCaret(override val x: Float, override val y: Float, override val w: Float, override val h: Float, val open: Boolean) : LItem

/** [Group] is a see-through grouping (a declaration fragment, a parenthesized value): drawn without a fill. */
enum class LKind { Statement, CBlock, Hat, Reporter, Literal, Chip, Group }

/** One horizontal band of a C-block: a header/divider row, or a mouth holding a body. */
sealed interface LSection {
    val y: Float
    val h: Float
}

class LRow(override val y: Float, override val h: Float) : LSection
class LMouth(override val y: Float, override val h: Float, val body: LBody) : LSection

class LBlock(
    val node: UiBlockNode?,
    val kind: LKind,
    val cat: BlockCat,
    val shape: ValueShape,
    val w: Float,
    /** Height of the block body; a statement's bottom bump hangs [BlockGeometry.notchDepth] below it. */
    val h: Float,
    val items: List<LItem>,
    val sections: List<LSection> = emptyList(),
    /** A `return`/`throw`/`break`/`continue`: nothing may follow it in a stack. */
    val terminal: Boolean = false,
    /** For [LKind.Hat]: the method's body, placed directly under the hat. */
    val hatBody: LBody? = null,
    /** A Compose `Modifier` chain, drawn as a card of links and edited in its own sheet. */
    val modifierChain: Boolean = false,
    /** A C-block used as a value (`buildAnnotatedString { … }` in a socket): no stack connectors. */
    val asValue: Boolean = false,
) {
    val id: String? get() = node?.id
}

/** A list slot's statements, stacked top to bottom from ([x], [y]) relative to the owner. */
class LBody(
    val ownerId: String,
    val slotIndex: Int,
    val x: Float,
    val y: Float,
    val blocks: List<LBlock>,
    /** Offsets of each block from the body origin. */
    val offsets: List<Float>,
    val w: Float,
    val h: Float,
    /** Whether drops are accepted here (a brace-less body or a free-floating preview is not). */
    val droppable: Boolean = true,
    /** Each shown block's index in the projected slot (blocks being dragged are left out of [blocks]). */
    val indices: List<Int> = blocks.indices.toList(),
    /** The projected slot's full size, for an append. */
    val total: Int = blocks.size,
    /** Where the drop preview's [Gap] opened, from the body origin; null when there is none here. */
    val gapY: Float? = null,
) {
    /** This body moved to ([x], [y]), with drops allowed or not. */
    fun at(x: Float, y: Float, droppable: Boolean = this.droppable) = LBody(ownerId, slotIndex, x, y, blocks, offsets, w, h, droppable, indices, total, gapY)
}

enum class StackKind { Method, Scratch, ScratchValue }

/** A free-standing group on the canvas at ([x], [y]). */
class LStack(
    val doc: DocRef,
    val kind: StackKind,
    val x: Float,
    val y: Float,
    /** A function's hat, or a scratch reporter, drawn at the stack origin. */
    val blocks: List<LBlock>,
    /** A scratch stack's statements. */
    val body: LBody? = null,
) {
    val w: Float get() = maxOf(body?.w ?: 0f, blocks.maxOfOrNull { it.w } ?: 0f, blocks.firstOrNull()?.hatBody?.let { it.x + it.w } ?: 0f)
    val h: Float get() = (body?.h ?: 0f) + blocks.sumOf { b -> (b.h + (b.hatBody?.h ?: 0f)).toDouble() }.toFloat()
}

class CanvasLayout(val stacks: List<LStack>, val geometry: BlockGeometry) {
    val bounds: Rect by lazy {
        if (stacks.isEmpty()) Rect.Zero
        else Rect(
            stacks.minOf { it.x }, stacks.minOf { it.y },
            stacks.maxOf { it.x + it.w }, stacks.maxOf { it.y + it.h },
        )
    }
}

// ---------------------------------------------------------------------------
// Inputs.
// ---------------------------------------------------------------------------

/** Pre-resolved strings the layout needs (the layout pass has no access to string resources). */
class BlockLabels(
    val keywordTo: String = "to",
    val keywordIn: String = "in",
)

/** What to keep out of the layout while it is being dragged: a run of statements, or one reporter. */
data class Hidden(val doc: DocRef, val ids: Set<String>)

/** Room opened in a list for the drop preview: [height] before projected entry [index]. */
data class Gap(val doc: DocRef, val ownerId: String, val slotIndex: Int, val index: Int, val height: Float)

/** A scratch stack to lay out: its projected root, and where it sits. */
class ScratchInput(val key: Long, val x: Float, val y: Float, val root: UiBlockNode, val source: String, val isValue: Boolean)

/** How deep reporters nest inline before an inner one collapses to a drill-in chip. */
const val VALUE_DEPTH_CAP = 3

private val CONTROL_LABELS = setOf("if", "for", "while", "do", "try", "switch", "synchronized", "when")
private val CLAUSE_LABELS = setOf("catch", "finally", "case", "CatchClause")
private val CHROME_KEYWORDS = setOf("else", "catch", "finally", "try", "do", "while", "return", "throw", "val", "var", "is", "as")
private val TERMINAL_LABELS = setOf("return", "throw", "break", "continue")
private val NAME_ROLE = Regex("name\\d*")
private val UNITS = setOf("dp", "sp", "em", "px")
private val NOT_COMPOSABLE = setOf("Log", "Toast", "Intent", "Thread", "Handler", "TODO", "require", "check")

// ---------------------------------------------------------------------------
// The layout pass.
// ---------------------------------------------------------------------------

class BlockLayouter(
    private val measure: TextMeasure,
    val g: BlockGeometry,
    private val labels: BlockLabels = BlockLabels(),
    private val hidden: Hidden? = null,
    private val gap: Gap? = null,
    /** The callee parameters of the page's calls, by [callKey] (file calls only). */
    private val signatures: Map<String, CallSig> = emptyMap(),
    /** Optional parameters picked to fill, by [callKey]: shown as holes like the required ones. */
    private val revealed: Map<String, Set<Int>> = emptyMap(),
    /** Calls whose optional parameters are unfolded (their `+N` chip was tapped). */
    private val expandedCalls: Set<String> = emptySet(),
    /** Names of the file's own `@Composable` functions (calls to them are colored as UI). */
    private val composables: Set<String> = emptySet(),
    /** Kotlin source: declarations read `val x = 1` rather than Java's `set x to 1`. */
    private val kotlin: Boolean = false,
) {
    private var source: String = ""
    private var doc: DocRef = DocRef.File

    /** One function's page: its hat and body (or expression body), followed by the page's [scratch] stacks. */
    fun layoutFunction(fn: UiBlockNode, source: String, scratch: List<ScratchInput>): CanvasLayout {
        this.source = source; doc = DocRef.File
        val stacks = ArrayList<LStack>()
        stacks += LStack(doc, StackKind.Method, g.margin, g.margin, listOf(hatBlock(fn)))
        for (s in scratch) scratchStack(s)?.let { stacks += it }
        return CanvasLayout(stacks, g)
    }

    /** A lambda body opened on its own page: a hat titled [title] (`clickable { }`) over the body's stack. */
    fun layoutLambda(block: UiBlockNode, title: String, source: String): CanvasLayout {
        this.source = source; doc = DocRef.File
        val items = ArrayList<LItem>()
        val row = text(title, TextRole.Header, TextTint.OnBlock)
        val top = g.hatTop
        val h = top + maxOf(row.h, g.rowMin) + g.padV * 2
        row.place(g.padH + g.gap, top + (h - top - row.h) / 2, items)
        val body = bodyOf(block)?.let { stackOf(it.ownerId, it.slotIndex, it.children) }?.at(0f, h)
        val hat = LBlock(block, LKind.Hat, BlockCat.Call, ValueShape.Unknown, maxOf(row.w + g.padH * 2 + g.gap * 2, g.minCWidth), h, items, hatBody = body)
        return CanvasLayout(listOf(LStack(doc, StackKind.Method, g.margin, g.margin, listOf(hat))), g)
    }

    /** Lay out one expression on its own (the drill-in sheet), at the origin. */
    fun layoutValue(node: UiBlockNode, source: String): CanvasLayout {
        this.source = source; doc = DocRef.File
        val v = value(node, 0)
        return CanvasLayout(listOf(LStack(DocRef.File, StackKind.ScratchValue, g.margin, g.margin, listOf(v))), g)
    }

    /** Lay out [nodes] as a free-standing stack (a dragged run, a palette template) at the origin. */
    fun layoutStatements(nodes: List<UiBlockNode>, source: String, doc: DocRef): LBody {
        this.source = source; this.doc = doc
        return stackOf("", -1, nodes, droppable = false)
    }

    /** Lay out [node] as a lone reporter (a dragged value, a palette operator). */
    fun layoutReporter(node: UiBlockNode, source: String, doc: DocRef): LBlock {
        this.source = source; this.doc = doc
        return value(node, 0)
    }

    private fun scratchStack(s: ScratchInput): LStack? {
        source = s.source; doc = DocRef.Scratch(s.key)
        val snippet = scratchContent(s.root, s.isValue) ?: return null
        if (s.isValue) {
            val init = snippet.value ?: return null
            if (hidden?.doc == doc && init.id in hidden.ids) return null
            return LStack(doc, StackKind.ScratchValue, s.x, s.y, listOf(value(init, 0)))
        }
        val body = snippet.body ?: return null
        val lb = stackOf(body.ownerId, body.slotIndex, body.children)
        if (lb.blocks.isEmpty()) return null
        return LStack(doc, StackKind.Scratch, s.x, s.y, emptyList(), lb)
    }

    // ---- the hat ----

    /**
     * A function's hat: its signature on a rounded cap, and the body stacked directly under it. An
     * expression-bodied function (`fun f() = x`) shows its value as a socket on the hat instead.
     */
    private fun hatBlock(node: UiBlockNode): LBlock {
        val items = ArrayList<LItem>()
        val body = bodyOf(node)
        val exprSlot = if (body == null) expressionBody(node) else null
        val pieces = buildList {
            add(text(hatTitle(node), TextRole.Header, TextTint.OnBlock))
            if (exprSlot != null) {
                add(text("=", TextRole.Keyword, TextTint.OnBlock))
                add(socket(node, slotIndexIn(node, exprSlot), exprSlot, 0))
            }
        }
        val row = hbox(pieces, g.gap * 1.5f)
        val top = g.hatTop
        val h = top + maxOf(row.h, g.rowMin) + g.padV * 2
        row.place(g.padH + g.gap, top + (h - top - row.h) / 2, items)
        val hatBody = body?.let { stackOf(it.ownerId, it.slotIndex, it.children) }?.at(0f, h)
        val w = maxOf(row.w + g.padH * 2 + g.gap * 2, g.minCWidth)
        val cat = if (signatureOf(node).contains("@Composable")) BlockCat.Compose else BlockCat.Method
        return LBlock(node, LKind.Hat, cat, ValueShape.Unknown, w, h, items, hatBody = hatBody)
    }

    /** The text on a hat: a function's signature, or what an `init`/accessor/constructor is. */
    private fun hatTitle(node: UiBlockNode): String = signatureOf(node).ifEmpty { node.label }

    /** The value slot of an expression-bodied function or accessor: the last value slot after its `=`. */
    private fun expressionBody(node: UiBlockNode): UiBlockPart.Slot? {
        val eq = node.parts.indexOfLast { it is UiBlockPart.Field && !it.editable && it.text.trimEnd().endsWith("=") }
        if (eq < 0) return null
        return node.parts.drop(eq + 1).filterIsInstance<UiBlockPart.Slot>()
            .firstOrNull { !it.multiple && (it.category == "EXPRESSION" || it.category == "ARGUMENT" || it.category == "OPAQUE") }
    }

    // ---- statements ----

    /** A list slot's statements as a stack; a dragged run is left out. */
    private fun stackOf(ownerId: String, slotIndex: Int, nodes: List<UiBlockNode>, droppable: Boolean = true): LBody {
        val hide = hidden?.takeIf { it.doc == doc }?.ids ?: emptySet()
        val indices = nodes.indices.filter { nodes[it].id !in hide }
        val blocks = indices.map { statement(nodes[it]) }
        val offsets = ArrayList<Float>(blocks.size)
        val open = gap?.takeIf { it.doc == doc && it.ownerId == ownerId && it.slotIndex == slotIndex }
        var gapY: Float? = null
        var y = 0f
        blocks.forEachIndexed { i, b ->
            if (open != null && gapY == null && indices[i] >= open.index) { gapY = y; y += open.height }
            offsets += y; y += b.h
        }
        if (open != null && gapY == null) { gapY = y; y += open.height }
        val w = blocks.maxOfOrNull { it.w } ?: 0f
        return LBody(ownerId, slotIndex, 0f, 0f, blocks, offsets, w, y, droppable, indices, nodes.size, gapY)
    }

    private fun statement(node: UiBlockNode): LBlock {
        val bands = bandsOf(node)
        return if (bands != null) cBlock(node, bands) else simpleStatement(node)
    }

    private fun simpleStatement(node: UiBlockNode): LBlock {
        val items = ArrayList<LItem>()
        val row = propertyCall(node) ?: inlineRow(node)
        val h = maxOf(row.h, g.rowMin) + g.padV * 2
        row.place(g.padH, (h - row.h) / 2, items)
        val w = maxOf(row.w + g.padH * 2, g.minStmtWidth)
        return LBlock(node, LKind.Statement, if (isComposableCall(node)) BlockCat.Compose else catOf(node), ValueShape.Unknown, w, h, items, terminal = isTerminal(node))
    }

    /**
     * One band of a C-block before layout: a run of header parts (keyword, chrome, sockets) or a body.
     * [owner] is the node the parts belong to (a `catch` clause's parts belong to the clause). A body is a
     * `{…}` list ([slotIndex] >= 0) or a brace-less single statement ([single]), which takes no drops.
     */
    private sealed interface Band {
        class Parts(val owner: UiBlockNode, val parts: List<UiBlockPart>, val first: Boolean) : Band
        class Body(val ownerId: String, val slotIndex: Int, val children: List<UiBlockNode>, val single: Boolean = false) : Band
    }

    /**
     * The bands of a control statement (or a call with a trailing lambda): its header row, each body, and
     * the rows between them (`else`, `catch (…)`, `finally`, a do-loop's `while (…)`, a `when` branch's
     * conditions), or null for a plain statement. A clause that holds a body (a Kotlin `catch`, a `when`
     * entry) is flattened into the bands.
     */
    private fun bandsOf(node: UiBlockNode): List<Band>? {
        if (!hasBody(node) && node.parts.none { p -> p is UiBlockPart.Slot && p.children.singleOrNull()?.let(::isClause) == true }) return null
        val bands = ArrayList<Band>()
        var pending = ArrayList<UiBlockPart>()
        var owner = node
        var first = true
        fun flush() {
            if (pending.isNotEmpty()) bands += Band.Parts(owner, pending, first)
            pending = ArrayList(); first = false
        }
        fun walk(n: UiBlockNode) {
            n.parts.forEach { part ->
                val slot = part as? UiBlockPart.Slot
                val child = slot?.children?.singleOrNull()
                when {
                    slot != null && isBodySlot(slot) && child != null -> {
                        flush(); owner = n
                        val list = if (child.kind == "block") bodyOf(child) else null
                        bands += when {
                            list != null -> Band.Body(list.ownerId, list.slotIndex, list.children)
                            child.kind == "block" -> Band.Body(child.id, -1, emptyList())
                            else -> Band.Body(n.id, slotIndexIn(n, slot), listOf(child), single = true)
                        }
                    }
                    slot != null && child != null && isClause(child) -> { flush(); owner = child; walk(child); flush(); owner = node }
                    else -> { if (owner !== n) { flush(); owner = n }; pending += part }
                }
            }
        }
        walk(node)
        flush()
        if (bands.none { it is Band.Body }) return null
        return bands
    }

    /** A statement position holding a body: a `{…}` block, or a brace-less control body. */
    private fun isBodySlot(slot: UiBlockPart.Slot): Boolean {
        if (slot.multiple || slot.category != "STATEMENT") return false
        val child = slot.children.singleOrNull() ?: return false
        return child.kind != "local_var" && child.label != "var"
    }

    private fun hasBody(n: UiBlockNode): Boolean = n.parts.any { it is UiBlockPart.Slot && isBodySlot(it) }

    /** A clause of a control statement that carries a body of its own: a Kotlin `catch`, a `when` entry. */
    private fun isClause(n: UiBlockNode): Boolean = n.label in CLAUSE_LABELS && hasBody(n)

    private fun cBlock(node: UiBlockNode, bands: List<Band>, asValue: Boolean = false): LBlock {
        val items = ArrayList<LItem>()
        val sections = ArrayList<LSection>()
        // Measure every row first so the bars share one width; a row with nothing to show is dropped.
        val rows = bands.map { b ->
            (b as? Band.Parts)?.let { if (it.first && it.owner === node) propertyCall(node) ?: bandRow(node, it) else bandRow(node, it) }
        }
        val barW = maxOf(rows.filterNotNull().maxOfOrNull { it.w + g.padH * 2 } ?: 0f, g.minCWidth)
        var y = 0f
        bands.forEachIndexed { i, band ->
            when (band) {
                is Band.Parts -> {
                    val row = rows[i]!!
                    if (row.w <= 0f) return@forEachIndexed
                    val h = maxOf(row.h, g.rowMin) + g.padV * 2
                    row.place(g.padH, y + (h - row.h) / 2, items)
                    sections += LRow(y, h)
                    y += h
                }
                is Band.Body -> {
                    // Two bodies in a row (`try {…} finally {…}` with nothing shown between) still get a bar.
                    if (sections.lastOrNull() is LMouth) { sections += LRow(y, g.footerH); y += g.footerH }
                    val body = stackOf(band.ownerId, band.slotIndex, band.children)
                    // A lambda with a receiver ends in a `+` that lists what its scope can call.
                    val scoped = band.slotIndex >= 0 && !band.single && receiverOf(node) != null && doc == DocRef.File
                    val handleH = if (scoped) g.socketH * 0.8f + 4f * g.density else 0f
                    val h = maxOf(body.h + handleH, g.mouthMin)
                    val placed = body.at(g.armW, y, droppable = band.slotIndex >= 0 && !band.single)
                    sections += LMouth(y, h, placed)
                    if (scoped) {
                        val handle = chip(ArgChipKind.Scope, "+", "${band.ownerId}:${band.slotIndex}", node.id, lambdaLink(node), -1, "", body.indices.size)
                        handle.place(g.armW + 4f * g.density, y + body.h + 2f * g.density, items)
                    }
                    y += h
                }
            }
        }
        if (sections.firstOrNull() !is LRow) { sections.add(0, LRow(0f, 0f)) }
        // A trailing body gets a closing arm.
        if (sections.lastOrNull() is LMouth) { sections += LRow(y, g.footerH); y += g.footerH }
        val cat = when {
            node.label in CONTROL_LABELS -> BlockCat.Control
            isComposableCall(node) -> BlockCat.Compose
            else -> catOf(node)
        }
        return LBlock(node, LKind.CBlock, cat, ValueShape.Unknown, barW, y, items, sections, terminal = false, asValue = asValue)
    }

    private fun bandRow(control: UiBlockNode, band: Band.Parts): Piece {
        val owner = band.owner
        val keyword = if (band.first) keywordFor(control) else null
        val strip = if (keyword != null) setOf(control.label, "for", "each") else emptySet()
        val pieces = ArrayList<Piece>()
        if (keyword != null) pieces += text(keyword, TextRole.Keyword, TextTint.OnBlock)
        val dividers = if (owner === control) chainDividerIndices(control) else emptySet()
        val done = HashSet<Int>()
        band.parts.forEach { part ->
            val i = owner.parts.indexOf(part)
            if (i in dividers) pieces += divider()
            pieces += closeExtras(owner, i, done)
            partPiece(owner, part, strip, keywordChrome = !band.first || owner !== control)?.let { pieces += it }
        }
        return hbox(pieces, g.gap)
    }

    // ---- inline content ----

    /** A block's inline content as one row: keyword, then each part (tokens, chrome, sockets). */
    private fun inlineRow(node: UiBlockNode): Piece {
        val keyword = keywordFor(node)
        val strip = if (keyword != null) setOf(node.label, "for", "each") else emptySet()
        val dividers = chainDividerIndices(node)
        val pieces = ArrayList<Piece>()
        if (keyword != null) pieces += text(keyword, TextRole.Keyword, TextTint.OnBlock)
        val done = HashSet<Int>()
        node.parts.forEachIndexed { i, part ->
            if (i in dividers) pieces += divider()
            pieces += closeExtras(node, i, done)
            partPiece(node, part, strip, keywordChrome = false)?.let { pieces += it }
        }
        return hbox(pieces, g.gap)
    }

    private fun partPiece(node: UiBlockNode, part: UiBlockPart, strip: Set<String>, keywordChrome: Boolean): Piece? = when (part) {
        is UiBlockPart.Field -> when {
            part.editable -> token(node.id, part)
            part.role == "keyword" -> text(part.text, TextRole.Keyword, TextTint.OnBlock)
            part.role == "declname" -> text(part.text, TextRole.Name, TextTint.OnBlock)
            else -> chrome(part.text, node, strip, keywordChrome)
        }
        is UiBlockPart.Slot -> if (part.multiple) null else socket(node, slotIndexIn(node, part), part, 0)
    }

    /** An editable token; [shown] displays a short form while tapping still edits the full text. */
    private fun token(blockId: String, field: UiBlockPart.Field, shown: String? = null): Piece {
        val qualifier = field.role == "qualifier"
        val m = measure.measure((shown ?: field.text).ifEmpty { "\u2022" }, if (qualifier) TextRole.Qualifier else TextRole.Name)
        val tint = if (qualifier) TextTint.OnBlockDim else TextTint.OnBlock
        return Piece(m.width, m.height) { x, y, out -> out += LToken(x, y, m, tint, blockId, field) }
    }

    /**
     * Read-only syntax: parens and braces dropped, keywords the block already shows stripped, a for-each
     * `:` read as "in" and (in Java) a declaration's `=` as "to". [keywordChrome] renders leftover words
     * (`else`, `catch`, `finally`) bold, as the row's keyword.
     */
    private fun chrome(raw: String, node: UiBlockNode, strip: Set<String>, keywordChrome: Boolean): Piece? {
        var shown = cleanChrome(raw, strip)
        if (node.kind == "EnhancedForStatement" && shown == ":") shown = labels.keywordIn
        if (!kotlin && shown == "=" && (node.label == "var" || node.kind == "local_var" || node.kind == "field_decl")) shown = labels.keywordTo
        shown = shown.removeSuffix(";").trim()
        if (shown.isEmpty()) return null
        val isWord = shown.all { it.isLetter() || it == ' ' }
        return when {
            shown == labels.keywordIn || shown == labels.keywordTo || shown == "in" -> text(shown, TextRole.Keyword, TextTint.OnBlock)
            (keywordChrome || shown in CHROME_KEYWORDS) && isWord -> text(shown, TextRole.Keyword, TextTint.OnBlock)
            else -> text(shown, TextRole.Chrome, TextTint.OnBlockDim)
        }
    }

    /** A value socket: a hole hinting the expected kind, or the reporter that fills it. */
    private fun socket(owner: UiBlockNode, slotIndex: Int, slot: UiBlockPart.Slot, depth: Int): Piece {
        val shape = valueShapeOf(slot.valueKind)
        val child = slot.children.singleOrNull()
            ?.takeUnless { hidden != null && hidden.doc == doc && it.id in hidden.ids }
        if (child == null && slot.category == "ARGUMENT" && owner.kind == "method_call") {
            // An empty `()`: nothing when the callee takes nothing, a quiet `+` when the callee is unknown.
            val link = linkIndexOf(owner, owner.parts.indexOf(slot))
            val sig = sigFor(owner, link)
            if (sig != null && (sig.params.isEmpty() || sig.params.first().optional)) return Piece(0f, 0f) { _, _, _ -> }
            if (sig == null) return chip(ArgChipKind.Add, "+", callKey(owner.start, link), owner.id, link, slot.start, "", 0)
        }
        if (child == null) {
            val param = if (slot.category == "ARGUMENT" && owner.kind == "method_call") paramForEmpty(owner, slot) else null
            val hintText = param?.let { it.name ?: it.type } ?: if (slot.valueKind != "unknown") slot.valueKind else slot.category.lowercase()
            val hint = measure.measure(hintText, TextRole.Hint)
            val pad = g.socketPadH + if (shape == ValueShape.Boolean) g.hexPoint else 0f
            val w = maxOf(hint.width + pad * 2, g.socketMinW)
            val h = g.socketH
            return Piece(w, h) { x, y, out -> out += LSocket(x, y, w, h, owner.id, slotIndex, slot, shape, null, hint) }
        }
        val v = value(child, depth)
        return Piece(v.w, v.h) { x, y, out -> out += LSocket(x, y, v.w, v.h, owner.id, slotIndex, slot, shape, v, null) }
    }

    /**
     * A reporter: a white literal, or a colored pill for names, calls and operators, shaped by the kind it
     * produces. Long chains and operator runs stack one link or operand per row; past [VALUE_DEPTH_CAP]
     * nested pills a compound value collapses to a chip.
     */
    private fun value(node: UiBlockNode, depth: Int): LBlock {
        val shape = valueShapeOf(node.valueKind)
        if (depth >= VALUE_DEPTH_CAP && node.parts.any { it is UiBlockPart.Slot }) return chip(node, shape)
        val onlyField = node.parts.singleOrNull() as? UiBlockPart.Field
        if (onlyField != null && onlyField.editable) {
            if (onlyField.role == "name") {
                val short = if (kotlin) shortValue(onlyField.text) else null
                val swatch = short?.argb ?: colorOf(onlyField.text)
                val glyph = short?.glyph ?: shapeGlyphOf(onlyField.text)
                val tok = if (short != null) token(node.id, onlyField, shown = short.text) else token(node.id, onlyField)
                val content = when {
                    swatch != null -> hbox(listOf(swatchPiece(swatch), tok), g.gap)
                    glyph != null -> hbox(listOf(glyphPiece(glyph), tok), g.gap)
                    else -> tok
                }
                return pill(node, BlockCat.Data, shape, depth, content)
            }
            return literal(node, onlyField.text, shape)
        }
        if (kotlin && isModifierChain(node)) return when {
            node.parts.any { it is UiBlockPart.Slot && it.category == "STATEMENT" } -> modifierCBlock(node, depth)
            chainLinkCount(node) == 1 -> modifierPill(node, depth)
            else -> modifierCard(node, depth)
        }
        // A value call with many or named arguments (`SpanStyle(color = …, fontWeight = …)`) uses property rows too.
        if (node.kind == "method_call" && lambdaBodySize(node) < 2) propertyCall(node)?.let { rows ->
            // As a value, a capitalized call is usually a constructor (`SpanStyle(…)`), so only the file's own
            // composables count here.
            val name = node.parts.firstNotNullOfOrNull { (it as? UiBlockPart.Field)?.takeIf { f -> f.editable && f.role == "name" }?.text }
            return pill(node, if (name in composables) BlockCat.Compose else BlockCat.Call, ValueShape.Unknown, depth, rows)
        }
        unitNumber(node)?.let { return it }
        val text = slice(node.start, node.end)
        if (node.kind == "method_call") {
            colorOf(text)?.let { argb -> return pill(node, BlockCat.Data, shape, depth, hbox(listOf(swatchPiece(argb), valueRow(node, depth + 1)), g.gap)) }
            shapeGlyphOf(text)?.let { gl -> return pill(node, BlockCat.Call, shape, depth, hbox(listOf(glyphPiece(gl), valueRow(node, depth + 1)), g.gap)) }
        }
        // A call whose trailing lambda holds real code is a C-block even as a value; a one-expression lambda
        // stays inline.
        if (node.kind == "method_call" && lambdaBodySize(node) >= 2) bandsOf(node)?.let { return cBlock(node, it, asValue = true) }
        val expr = node.label == "expr" || node.kind == "InfixExpression"
        return when {
            node.kind == "block" -> inlineLambda(node, depth) ?: chip(node, shape)
            node.kind == "type_ref" || node.kind.endsWith("Type") -> literal(node, slice(node.start, node.end), shape)
            node.kind == "method_call" && chainLinkCount(node) >= 3 -> pill(node, BlockCat.Call, shape, depth, chainStack(node, depth + 1))
            node.kind == "method_call" || node.kind == "member_access" -> pill(node, BlockCat.Call, shape, depth, valueRow(node, depth + 1))
            expr && infixOperands(node).size >= 3 -> pill(node, BlockCat.Op, shape, depth, opStack(node, depth + 1))
            expr -> pill(node, BlockCat.Op, shape, depth, valueRow(node, depth + 1))
            // Kotlin's `if` used as a value; a `when`/`try` value is too tall to inline, so it drills in.
            node.label == "if" -> pill(node, BlockCat.Control, shape, depth, valueRow(node, depth + 1))
            node.label == "when" || node.label == "try" -> chip(node, shape)
            node.parts.any { it is UiBlockPart.Slot } -> transparent(node, valueRow(node, depth))
            else -> literal(node, slice(node.start, node.end), shape)
        }
    }

    // ---- recognized values ----

    private fun swatchPiece(argb: Long): Piece {
        val d = 11f * g.density
        return Piece(d, d) { x, y, out -> out += LSwatch(x, y, d, d, argb) }
    }

    private fun glyphPiece(glyph: Glyph, onSocket: Boolean = false): Piece {
        val d = 12f * g.density
        return Piece(d, d) { x, y, out -> out += LGlyph(x, y, d, d, glyph, onSocket) }
    }

    /** `100.dp` / `14.sp`: a number with a quiet unit, edited as one value. */
    private fun unitNumber(node: UiBlockNode): LBlock? {
        if (node.kind != "method_call") return null
        val p = node.parts
        if (p.size != 3) return null
        val num = (p[0] as? UiBlockPart.Slot)?.children?.singleOrNull() ?: return null
        val unit = (p[2] as? UiBlockPart.Field)?.takeIf { it.editable && it.text in UNITS } ?: return null
        if ((p[1] as? UiBlockPart.Field)?.text?.trim() != ".") return null
        val n = slice(num.start, num.end)
        if (n.toDoubleOrNull() == null && n.removeSuffix("f").toDoubleOrNull() == null) return null
        val items = ArrayList<LItem>()
        val nm = measure.measure(n, TextRole.Literal)
        val um = measure.measure(unit.text, TextRole.Hint)
        val padH = g.pillPadH
        val h = maxOf(nm.height + g.pillPadV * 2, g.socketH)
        items += LText(padH, (h - nm.height) / 2, nm, TextTint.OnSocket, ValueShape.Number)
        items += LText(padH + nm.width + 2f * g.density, (h - um.height) / 2, um, TextTint.OnSocketDim, ValueShape.Number)
        return LBlock(node, LKind.Literal, BlockCat.Opaque, ValueShape.Number, padH * 2 + nm.width + 2f * g.density + um.width, h, items)
    }

    /**
     * A `Modifier` chain with a lambda link (`.clickable { … }`, `.pointerInput(Unit) { … }`) as a C-shaped card:
     * a row per link, and under a lambda link a mouth that takes statement blocks, with a chip that opens the
     * body on its own page.
     */
    private fun modifierCBlock(node: UiBlockNode, depth: Int): LBlock {
        val parts = node.parts
        val names = parts.indices.filter { val p = parts[it]; p is UiBlockPart.Field && p.editable && NAME_ROLE.matches(p.role) }
        val q = parts.first() as UiBlockPart.Field
        class Link(val row: Piece, val body: UiBlockNode?)
        val links = names.mapIndexed { li, nameIdx ->
            val end = names.getOrNull(li + 1) ?: parts.size
            val name = parts[nameIdx] as UiBlockPart.Field
            val seg = parts.subList(nameIdx + 1, end)
            val lambda = seg.filterIsInstance<UiBlockPart.Slot>().firstOrNull { it.category == "STATEMENT" }?.children?.singleOrNull()?.takeIf { it.kind == "block" }
            val pieces = ArrayList<Piece>()
            pieces += glyphPiece(glyphFor(name.text))
            pieces += token(node.id, name)
            seg.filterIsInstance<UiBlockPart.Slot>().filter { !it.multiple && it.category != "STATEMENT" }.forEachIndexed { i, slot ->
                if (i > 0) pieces += text(",", TextRole.Chrome, TextTint.OnBlockDim)
                pieces += socket(node, slotIndexIn(node, slot), slot, depth + 1)
            }
            pieces += segmentExtras(node, li)
            if (lambda != null && doc == DocRef.File) pieces += chip(ArgChipKind.Open, "\u2197", lambda.id, node.id, li, -1, "", 0)
            Link(hbox(pieces, g.gap), lambda)
        }
        val header = hbox(listOf(glyphPiece(Glyph.Modifier), text(q.text, TextRole.Qualifier, TextTint.OnBlockDim)), g.gap)
        val items = ArrayList<LItem>()
        val sections = ArrayList<LSection>()
        val rowW = maxOf(header.w, links.maxOf { it.row.w }) + g.padH * 2
        var y = 0f
        fun row(p: Piece) {
            val h = maxOf(p.h, g.rowMin) + g.padV * 2
            p.place(g.padH, y + (h - p.h) / 2, items)
            sections += LRow(y, h)
            y += h
        }
        row(header)
        for (l in links) {
            if (sections.lastOrNull() is LMouth) { sections += LRow(y, g.footerH * 0.6f); y += g.footerH * 0.6f }
            row(l.row)
            val list = l.body?.let(::bodyOf) ?: continue
            val body = stackOf(list.ownerId, list.slotIndex, list.children)
            sections += LMouth(y, maxOf(body.h, g.mouthMin), body.at(g.armW, y))
            y += maxOf(body.h, g.mouthMin)
        }
        if (sections.lastOrNull() is LMouth) { sections += LRow(y, g.footerH); y += g.footerH }
        return LBlock(node, LKind.CBlock, BlockCat.Call, ValueShape.Unknown, rowW, y, items, sections, modifierChain = true, asValue = true)
    }

    /** A one-link `Modifier.fillMaxSize()` as one inline pill (glyph, name, arguments); tapped, it opens the sheet. */
    private fun modifierPill(node: UiBlockNode, depth: Int): LBlock {
        val parts = node.parts
        val nameIdx = parts.indexOfFirst { it is UiBlockPart.Field && it.editable && NAME_ROLE.matches(it.role) }
        val name = parts[nameIdx] as UiBlockPart.Field
        val pieces = ArrayList<Piece>()
        pieces += glyphPiece(glyphFor(name.text))
        pieces += token(node.id, name)
        parts.subList(nameIdx + 1, parts.size).filterIsInstance<UiBlockPart.Slot>().filter { !it.multiple }.forEachIndexed { i, slot ->
            if (i > 0) pieces += text(",", TextRole.Chrome, TextTint.OnBlockDim)
            pieces += socket(node, slotIndexIn(node, slot), slot, depth + 1)
        }
        pieces += segmentExtras(node, 0)
        return pill(node, BlockCat.Call, ValueShape.Unknown, depth, hbox(pieces, g.gap), modifierChain = true)
    }

    private fun isModifierChain(node: UiBlockNode): Boolean {
        if (node.kind != "method_call" || chainLinkCount(node) < 1) return false
        val q = node.parts.firstOrNull() as? UiBlockPart.Field ?: return false
        return q.role == "qualifier" && (q.text == "Modifier" || q.text == "modifier")
    }

    /**
     * A `Modifier` chain as a card: a header naming the receiver, then one row per link with a glyph for what
     * it does, its (editable) name and its arguments as sockets, separated by hairlines.
     */
    private fun modifierCard(node: UiBlockNode, depth: Int): LBlock {
        val parts = node.parts
        val names = parts.indices.filter { val p = parts[it]; p is UiBlockPart.Field && p.editable && NAME_ROLE.matches(p.role) }
        val rows = ArrayList<Piece>()
        val q = parts.first() as UiBlockPart.Field
        rows += hbox(listOf(glyphPiece(Glyph.Modifier), text(q.text, TextRole.Qualifier, TextTint.OnBlockDim)), g.gap)
        names.forEachIndexed { li, nameIdx ->
            val end = names.getOrNull(li + 1) ?: parts.size
            val name = parts[nameIdx] as UiBlockPart.Field
            val pieces = ArrayList<Piece>()
            pieces += glyphPiece(glyphFor(name.text))
            pieces += token(node.id, name)
            val args = parts.subList(nameIdx + 1, end).filterIsInstance<UiBlockPart.Slot>().filter { !it.multiple }
            args.forEachIndexed { i, slot ->
                if (i > 0) pieces += text(",", TextRole.Chrome, TextTint.OnBlockDim)
                pieces += socket(node, slotIndexIn(node, slot), slot, depth + 1)
            }
            pieces += segmentExtras(node, li)
            rows += hbox(pieces, g.gap)
        }
        val padH = g.pillPadH + 2f * g.density
        val rowGap = 5f * g.density
        val innerW = rows.maxOf { it.w }
        val items = ArrayList<LItem>()
        var y = g.pillPadV + 2f * g.density
        rows.forEachIndexed { i, r ->
            val rh = maxOf(r.h, 16f * g.density)
            r.place(padH, y + (rh - r.h) / 2, items)
            y += rh
            if (i < rows.lastIndex) {
                items += LDivider(padH, y + rowGap / 2, innerW, 1f)
                y += rowGap
            }
        }
        y += g.pillPadV + 2f * g.density
        return LBlock(node, LKind.Reporter, BlockCat.Call, ValueShape.Unknown, innerW + padH * 2, y, items, modifierChain = true)
    }

    /** How much a call's trailing lambda holds: its statement count, or 2 when one statement has a body itself. */
    private fun lambdaBodySize(node: UiBlockNode): Int {
        val body = node.parts.filterIsInstance<UiBlockPart.Slot>().firstNotNullOfOrNull { s ->
            s.children.singleOrNull()?.takeIf { s.category == "STATEMENT" && it.kind == "block" }
        } ?: return 0
        val stmts = bodyOf(body)?.children ?: return 0
        return if (stmts.size == 1 && hasBody(stmts[0])) 2 else stmts.size
    }

    /** `{ expression }`: a one-expression lambda, drawn inline around its value. */
    private fun inlineLambda(block: UiBlockNode, depth: Int): LBlock? {
        val stmts = bodyOf(block)?.children ?: return null
        val inner = when (stmts.size) {
            0 -> null
            1 -> stmts[0].takeUnless { hasBody(it) } ?: return null
            else -> return null
        }
        val pieces = ArrayList<Piece>()
        pieces += text("{", TextRole.Chrome, TextTint.OnBlockDim)
        if (inner != null) {
            // The value sits in the lambda's statement list, which a tap rewrites as a whole.
            val list = block.parts.filterIsInstance<UiBlockPart.Slot>().firstOrNull { it.multiple } ?: return null
            val v = value(inner, depth)
            pieces += Piece(v.w, v.h) { x, y, out -> out += LSocket(x, y, v.w, v.h, block.id, slotIndexIn(block, list), list, v.shape, v, null) }
        }
        pieces += text("}", TextRole.Chrome, TextTint.OnBlockDim)
        return transparent(block, hbox(pieces, g.tightGap))
    }

    private fun pill(node: UiBlockNode, cat: BlockCat, shape: ValueShape, depth: Int, content: Piece, modifierChain: Boolean = false): LBlock {
        val items = ArrayList<LItem>()
        val padH = (if (depth == 0) g.pillPadH + 2 * g.density else g.pillPadH) + if (shape == ValueShape.Boolean) g.hexPoint else 0f
        val h = maxOf(content.h + g.pillPadV * 2, g.socketH)
        content.place(padH, (h - content.h) / 2, items)
        val w = content.w + padH * 2
        return LBlock(node, LKind.Reporter, cat, shape, w, h, items, modifierChain = modifierChain)
    }

    /** A grouping with no pill of its own (a declaration fragment, a parenthesized value). */
    private fun transparent(node: UiBlockNode, content: Piece): LBlock {
        val items = ArrayList<LItem>()
        content.place(0f, 0f, items)
        return LBlock(node, LKind.Group, BlockCat.Opaque, ValueShape.Unknown, content.w, content.h, items)
    }

    private fun literal(node: UiBlockNode, raw: String, shape: ValueShape): LBlock {
        val items = ArrayList<LItem>()
        var t = raw.replace(Regex("\\s+"), " ").ifEmpty { " " }
        var m = measure.measure(t, TextRole.Literal)
        if (shape == ValueShape.Text && m.width > g.longLiteral) {
            // A long string: keep its head and tail, tap to edit the whole thing.
            t = t.take(18) + "…" + t.takeLast(8)
            m = measure.measure(t, TextRole.Literal)
        }
        val padH = g.pillPadH + if (shape == ValueShape.Boolean) g.hexPoint else 0f
        val h = maxOf(m.height + g.pillPadV * 2, g.socketH)
        items += LText(padH, (h - m.height) / 2, m, TextTint.OnSocket, shape)
        return LBlock(node, LKind.Literal, BlockCat.Opaque, shape, m.width + padH * 2, h, items)
    }

    private fun chip(node: UiBlockNode, shape: ValueShape): LBlock {
        val items = ArrayList<LItem>()
        val t = slice(node.start, node.end).replace(Regex("\\s+"), " ").trim()
        val summary = if (t.length <= 22) t else t.take(12) + "…" + t.takeLast(7)
        val m = measure.measure("$summary  { }", TextRole.Name)
        val h = maxOf(m.height + g.pillPadV * 2, g.socketH)
        items += LText(g.pillPadH, (h - m.height) / 2, m, TextTint.OnBlock)
        return LBlock(node, LKind.Chip, catForValue(node), shape, m.width + g.pillPadH * 2, h, items)
    }

    /** A reporter's inline content on one line. */
    private fun valueRow(node: UiBlockNode, depth: Int): Piece {
        val dividers = chainDividerIndices(node)
        val pieces = ArrayList<Piece>()
        val done = HashSet<Int>()
        node.parts.forEachIndexed { i, part ->
            if (i in dividers) pieces += divider()
            pieces += closeExtras(node, i, done)
            valuePart(node, part, depth)?.let { pieces += it }
        }
        return hbox(pieces, g.tightGap)
    }

    private fun valuePart(node: UiBlockNode, part: UiBlockPart, depth: Int): Piece? = when (part) {
        is UiBlockPart.Field -> if (part.editable) token(node.id, part) else {
            val s = part.text.trim()
            when {
                part.role == "keyword" -> text(s, TextRole.Keyword, TextTint.OnBlock)
                part.role == "declname" -> text(s, TextRole.Name, TextTint.OnBlock)
                s == "else" || s == "if" -> text(s, TextRole.Keyword, TextTint.OnBlock)
                !kotlin && s == "=" && (node.label == "var" || node.kind == "local_var" || node.kind == "field_decl") ->
                    text(labels.keywordTo, TextRole.Keyword, TextTint.OnBlock)
                s.isNotEmpty() -> text(s, TextRole.Chrome, TextTint.OnBlockDim)
                else -> null
            }
        }
        is UiBlockPart.Slot -> if (part.multiple) null else socket(node, slotIndexIn(node, part), part, depth)
    }

    /** A fluent chain with one `.link(args)` per row under the receiver. */
    private fun chainStack(node: UiBlockNode, depth: Int): Piece {
        val parts = node.parts
        val names = parts.indices.filter { val p = parts[it]; p is UiBlockPart.Field && p.editable && NAME_ROLE.matches(p.role) }
        val first = names.first()
        val rows = ArrayList<Piece>()
        val head = ArrayList<Piece>()
        parts.subList(0, first).forEach { p ->
            when (p) {
                is UiBlockPart.Field -> if (p.editable) head += token(node.id, p)
                is UiBlockPart.Slot -> if (!p.multiple) head += socket(node, slotIndexIn(node, p), p, depth)
            }
        }
        head += chainLink(node, first, names.getOrNull(1) ?: parts.size, depth, leadingDot = first > 0)
        rows += hbox(head, g.tightGap)
        for (li in 1 until names.size) {
            rows += indent(chainLink(node, names[li], names.getOrNull(li + 1) ?: parts.size, depth, leadingDot = true), g.chainIndent)
        }
        return vbox(rows, 2 * g.density)
    }

    private fun chainLink(node: UiBlockNode, nameIdx: Int, end: Int, depth: Int, leadingDot: Boolean): Piece {
        val parts = node.parts
        val name = parts[nameIdx] as UiBlockPart.Field
        val args = parts.subList(nameIdx + 1, end).filterIsInstance<UiBlockPart.Slot>().filter { !it.multiple }
        val isCall = args.isNotEmpty() || (nameIdx + 1 until end).any { val p = parts[it]; p is UiBlockPart.Field && !p.editable && '(' in p.text }
        val pieces = ArrayList<Piece>()
        if (leadingDot) pieces += text(".", TextRole.Chrome, TextTint.OnBlockDim)
        pieces += token(node.id, name)
        if (isCall) {
            pieces += text("(", TextRole.Chrome, TextTint.OnBlockDim)
            args.forEachIndexed { i, slot ->
                if (i > 0) pieces += text(",", TextRole.Chrome, TextTint.OnBlockDim)
                pieces += socket(node, slotIndexIn(node, slot), slot, depth)
            }
            pieces += segmentExtras(node, linkIndexOf(node, nameIdx))
            pieces += text(")", TextRole.Chrome, TextTint.OnBlockDim)
        }
        return hbox(pieces, g.tightGap)
    }

    /** A long same-operator run, one operand per row with the operator in a gutter. */
    private fun opStack(node: UiBlockNode, depth: Int): Piece {
        val operands = infixOperands(node)
        val op = node.parts.firstNotNullOfOrNull { (it as? UiBlockPart.Field)?.takeIf { f -> !f.editable }?.text?.trim()?.ifEmpty { null } } ?: ""
        val opText = measure.measure(op, TextRole.Keyword)
        val gutter = opText.width + g.gap
        val rows = operands.mapIndexed { i, slot ->
            val s = socket(node, slotIndexIn(node, slot), slot, depth)
            val label = if (i > 0) Piece(gutter, opText.height) { x, y, out -> out += LText(x + gutter - g.gap - opText.width, y, opText, TextTint.OnBlock) }
            else Piece(gutter, 0f) { _, _, _ -> }
            hbox(listOf(label, s), 0f)
        }
        return vbox(rows, 2 * g.density)
    }

    // ---- property rows ----

    /**
     * A Kotlin call with more than two arguments, or two or more named ones, as an inspector: the call's name,
     * then one row per argument (`label  [value]`, labels in a column) and one per hole still to fill. Null
     * for a call that reads better inline.
     */
    private fun propertyCall(node: UiBlockNode): Piece? {
        if (!kotlin || node.kind != "method_call") return null
        val names = node.parts.indices.filter { val p = node.parts[it]; p is UiBlockPart.Field && p.editable && NAME_ROLE.matches(p.role) }
        if (names.size != 1) return null
        val seg = segmentParts(node, 0) ?: return null
        val s = supplied(seg)
        if (s.written.size <= 2 && s.named.size < 2) return null
        val sig = sigFor(node, 0)
        val head = ArrayList<Piece>()
        node.parts.subList(0, names[0] + 1).forEach { p -> if (p is UiBlockPart.Field && p.editable) head += token(node.id, p) }
        // Each written argument with its label: its name when named, else the parameter at its position.
        val labelled = s.written.mapIndexed { i, slot ->
            val before = (seg.getOrNull(seg.indexOf(slot) - 1) as? UiBlockPart.Field)?.text ?: ""
            val name = Regex("(\\w+)\\s*=\\s*$").find(before)?.groupValues?.get(1) ?: sig?.params?.getOrNull(i)?.name ?: ""
            name to socket(node, slotIndexIn(node, slot), slot, 0)
        }
        val holes = sig?.let { sg ->
            val closeAt = s.written.lastOrNull()?.end ?: s.emptySlot?.start ?: -1
            missingFor(node, 0, s, sg).map { m ->
                val leading = (if (s.written.isNotEmpty()) ", " else "") + if (m.named) "${m.param.name} = " else ""
                (m.param.name ?: "") to hole(node.id, 0, if (m.named) s.written.size else m.index, m, closeAt, leading, callKey(node.start, 0), hintType = true)
            }
        } ?: emptyList()
        val rows = labelled + holes
        val labelTexts = rows.map { (l, _) -> measure.measure(l, TextRole.Chrome) }
        val col = (labelTexts.maxOfOrNull { it.width } ?: 0f) + g.gap * 2
        val pieces = ArrayList<Piece>()
        pieces += hbox(head, g.gap)
        rows.forEachIndexed { i, (_, value) ->
            val m = labelTexts[i]
            // The label lines up with the value's first line, so a tall value (a builder block) hangs below it.
            val firstLine = minOf(value.h, g.socketH)
            val row = Piece(col + value.w, maxOf(value.h, m.height)) { x, y, out ->
                out += LText(x, y + (firstLine - m.height) / 2, m, TextTint.OnBlockDim)
                value.place(x + col, y, out)
            }
            pieces += indent(row, g.padH)
        }
        val closeAt = s.written.lastOrNull()?.end ?: s.emptySlot?.start ?: -1
        if (sig == null && doc == DocRef.File) {
            pieces += indent(chip(ArgChipKind.Add, "+", callKey(node.start, 0), node.id, 0, closeAt, ", ", s.written.size), g.padH + col)
        } else if (sig != null) {
            foldChip(node, 0, s, sig, closeAt)?.let { pieces += indent(it, g.padH + col) }
        }
        return vbox(pieces, 4f * g.density)
    }

    // ---- scopes ----

    /** The segment of [call] that holds its trailing lambda (the last one with a body slot). */
    private fun lambdaLink(call: UiBlockNode): Int {
        val idx = call.parts.indexOfLast { it is UiBlockPart.Slot && it.category == "STATEMENT" }
        return if (idx < 0) 0 else linkIndexOf(call, idx).coerceAtLeast(0)
    }

    /** The receiver a call's trailing lambda runs with (`AnnotatedString.Builder`), from its signature. */
    private fun receiverOf(call: UiBlockNode): String? {
        if (call.kind != "method_call") return null
        val sig = sigFor(call, lambdaLink(call)) ?: return null
        return lambdaReceiver(sig.params.lastOrNull()?.type ?: return null)
    }

    // ---- call arguments ----

    /**
     * A statement-level Kotlin call to a composable: one of the file's own `@Composable` functions, or (by
     * Compose's naming convention) a capitalized call made as a statement.
     */
    private fun isComposableCall(node: UiBlockNode): Boolean {
        if (!kotlin || node.kind != "method_call") return false
        val first = node.parts.firstOrNull()
        if (first is UiBlockPart.Field && first.role == "qualifier") return false
        val name = node.parts.firstNotNullOfOrNull { (it as? UiBlockPart.Field)?.takeIf { f -> f.editable && f.role == "name" }?.text } ?: return false
        return name in composables || (name.firstOrNull()?.isUpperCase() == true && name !in NOT_COMPOSABLE)
    }

    /** Which segment of [call] the part at [partIndex] belongs to (the name fields seen up to it, less one). */
    private fun linkIndexOf(call: UiBlockNode, partIndex: Int): Int =
        call.parts.subList(0, partIndex.coerceAtMost(call.parts.size)).count { it is UiBlockPart.Field && it.editable && NAME_ROLE.matches(it.role) } - 1

    /** Before a segment's closing `)`: the holes for what it is missing, once per segment. */
    private fun closeExtras(node: UiBlockNode, partIndex: Int, done: MutableSet<Int>): List<Piece> {
        if (node.kind != "method_call") return emptyList()
        val f = node.parts.getOrNull(partIndex) as? UiBlockPart.Field ?: return emptyList()
        if (f.editable || ')' !in f.text) return emptyList()
        val link = linkIndexOf(node, partIndex)
        if (link < 0 || !done.add(link)) return emptyList()
        return segmentExtras(node, link)
    }

    private fun sigFor(node: UiBlockNode, link: Int): CallSig? = if (doc == DocRef.File) signatures[callKey(node.start, link)] else null

    /** The parameter an empty `()` hole stands for. */
    private fun paramForEmpty(owner: UiBlockNode, slot: UiBlockPart.Slot): ParamSig? {
        val at = owner.parts.indexOf(slot)
        val link = linkIndexOf(owner, at)
        return sigFor(owner, link)?.params?.firstOrNull()
    }

    /**
     * The holes, chips and switches after segment [link]'s written arguments: one hole per missing parameter
     * (by the callee's signature), `+N` for folded optional ones, `1/3` to switch overload; with no signature,
     * a `+` that adds one more argument.
     */
    private fun segmentExtras(node: UiBlockNode, link: Int): List<Piece> {
        if (node.kind != "method_call") return emptyList()
        val seg = segmentParts(node, link) ?: return emptyList()
        val s = supplied(seg)
        if (s.written.isEmpty() && s.emptySlot == null) return emptyList() // no parentheses: nothing to add into
        val key = callKey(node.start, link)
        val closeAt = s.written.lastOrNull()?.end ?: s.emptySlot?.start ?: -1
        val sig = sigFor(node, link)
        if (sig == null) {
            return if (s.written.isNotEmpty() && doc == DocRef.File) listOf(chip(ArgChipKind.Add, "+", key, node.id, link, closeAt, ", ", s.written.size)) else emptyList()
        }
        val out: MutableList<Piece> = missingFor(node, link, s, sig).mapTo(ArrayList()) { m ->
            val sep = if (s.written.isNotEmpty()) ", " else ""
            hole(node.id, link, if (m.named) s.written.size else m.index, m, closeAt, sep + if (m.named) "${m.param.name} = " else "", key)
        }
        foldChip(node, link, s, sig, closeAt)?.let { out += it }
        return out
    }

    /**
     * The missing parameters a segment shows: required ones, optional ones picked from the sheet, and every
     * optional one while the call is unfolded.
     */
    private fun missingFor(node: UiBlockNode, link: Int, s: Supplied, sig: CallSig): List<MissingParam> {
        val key = callKey(node.start, link)
        val picked = revealed[key] ?: emptySet()
        val open = key in expandedCalls
        return missingParams(sig, s, kotlin, expanded = true).first.filter { !it.param.optional || open || it.index in picked }
    }

    /** `+N` for a segment's folded optional parameters, or `−` to fold them again; null when there are none. */
    private fun foldChip(node: UiBlockNode, link: Int, s: Supplied, sig: CallSig, closeAt: Int): Piece? {
        val key = callKey(node.start, link)
        val picked = revealed[key] ?: emptySet()
        val optional = missingParams(sig, s, kotlin, expanded = true).first.filter { it.param.optional && it.index !in picked }
        if (optional.isEmpty()) return null
        return if (key in expandedCalls) chip(ArgChipKind.Fewer, "\u2212", key, node.id, link, closeAt, "", 0)
        else chip(ArgChipKind.More, "+${optional.size}", key, node.id, link, closeAt, "", 0)
    }

    private fun hole(callId: String, link: Int, insertIndex: Int, m: MissingParam, closeAt: Int, leading: String, key: String, hintType: Boolean = false): Piece {
        val shape = shapeOfType(m.param.type)
        // In a property row the name is the row's label, so the hole hints the type instead.
        val hint = measure.measure(if (hintType) m.param.type.removePrefix("@Composable ") else m.param.name ?: m.param.type, TextRole.Hint)
        val pad = g.socketPadH + if (shape == ValueShape.Boolean) g.hexPoint else 0f
        val w = maxOf(hint.width + pad * 2, g.socketMinW)
        val h = g.socketH
        return Piece(w, h) { x, y, out ->
            out += LArgHole(x, y, w, h, callId, link, insertIndex, m.param.name, m.named, closeAt, leading, hint, shape, m.param.optional, key, m.index)
        }
    }

    private fun chip(kind: ArgChipKind, label: String, key: String, callId: String, link: Int, closeAt: Int, leading: String, insertIndex: Int): Piece {
        val m = measure.measure(label, TextRole.Chrome)
        // The add-an-argument `+` is a small round handle; the other chips are labelled pills.
        val small = kind == ArgChipKind.Add
        val h = if (small) g.socketH * 0.8f else g.socketH
        val w = if (small) h else m.width + g.pillPadH * 2
        return Piece(w, h) { x, y, out -> out += LArgChip(x, y, w, h, kind, key, callId, link, m, closeAt, leading, insertIndex) }
    }

    // ---- piece primitives ----

    private class Piece(val w: Float, val h: Float, val place: (x: Float, y: Float, out: MutableList<LItem>) -> Unit)

    private fun Piece.place(x: Float, y: Float, out: MutableList<LItem>) = place.invoke(x, y, out)

    private fun text(s: String, role: TextRole, tint: TextTint): Piece {
        val m = measure.measure(s, role)
        return Piece(m.width, m.height) { x, y, out -> out += LText(x, y, m, tint) }
    }

    private fun divider(): Piece = Piece(g.dividerW, g.dividerH) { x, y, out -> out += LDivider(x, y, g.dividerW, g.dividerH) }

    private fun indent(p: Piece, by: Float): Piece = Piece(p.w + by, p.h) { x, y, out -> p.place(x + by, y, out) }

    private fun hbox(pieces: List<Piece>, gap: Float): Piece {
        if (pieces.isEmpty()) return Piece(0f, 0f) { _, _, _ -> }
        val w = pieces.sumOf { it.w.toDouble() }.toFloat() + gap * (pieces.size - 1)
        val h = pieces.maxOf { it.h }
        return Piece(w, h) { x, y, out ->
            var cx = x
            for (p in pieces) { p.place(cx, y + (h - p.h) / 2, out); cx += p.w + gap }
        }
    }

    private fun vbox(rows: List<Piece>, gap: Float): Piece {
        if (rows.isEmpty()) return Piece(0f, 0f) { _, _, _ -> }
        val w = rows.maxOf { it.w }
        val h = rows.sumOf { it.h.toDouble() }.toFloat() + gap * (rows.size - 1)
        return Piece(w, h) { x, y, out ->
            var cy = y
            for (r in rows) { r.place(x, cy, out); cy += r.h + gap }
        }
    }

    // ---- tree helpers ----

    private fun slice(start: Int, end: Int): String =
        if (start in 0..source.length && end in start..source.length) source.substring(start, end) else ""

    private fun signatureOf(node: UiBlockNode): String = signatureText(node, source)

    private fun keywordFor(node: UiBlockNode): String? = when (node.label) {
        "if" -> "if"; "while" -> "while"; "do" -> "do"; "try" -> "try"; "switch" -> "switch"; "synchronized" -> "synchronized"
        "when" -> "when"
        "for" -> if (node.kind == "EnhancedForStatement") "for each" else "for"
        "return" -> "return"; "throw" -> "throw"
        // Kotlin carries its own `val`/`var`/`break` keyword fields; Java's are chrome the block reads instead.
        "var" -> if (kotlin) null else "set"
        "break" -> if (kotlin) null else "break"
        "continue" -> if (kotlin) null else "continue"
        "" -> if (isCallStatement(node)) "call" else null
        else -> null
    }

    private fun isCallStatement(node: UiBlockNode): Boolean =
        node.kind == "ExpressionStatement" &&
            node.parts.filterIsInstance<UiBlockPart.Slot>().any { it.children.singleOrNull()?.kind == "method_call" }

    private fun chainLinkCount(node: UiBlockNode): Int =
        node.parts.count { it is UiBlockPart.Field && it.editable && NAME_ROLE.matches(it.role) }

    private fun infixOperands(node: UiBlockNode): List<UiBlockPart.Slot> =
        node.parts.filterIsInstance<UiBlockPart.Slot>().filter { !it.multiple }

    /** Before each chrome dot that follows a previous link's parenthesis: a chain segment boundary. */
    private fun chainDividerIndices(node: UiBlockNode): Set<Int> {
        if (node.kind != "method_call") return emptySet()
        val out = HashSet<Int>()
        var sawParen = false
        node.parts.forEachIndexed { i, part ->
            val f = part as? UiBlockPart.Field ?: return@forEachIndexed
            if (f.editable) return@forEachIndexed
            val dot = f.text.indexOf('.')
            if (dot >= 0 && (sawParen || f.text.take(dot).contains(')'))) out += i
            if (f.text.contains('(')) sawParen = true
        }
        return out
    }

    private fun cleanChrome(text: String, strip: Set<String>): String {
        var t = text
        for (w in strip) t = t.replace(Regex("\\b" + Regex.escape(w) + "\\b"), "")
        t = t.filterNot { it in "(){}" }
        return t.trim().replace(Regex("\\s+"), " ")
    }
}

// ---------------------------------------------------------------------------
// Shared tree helpers (also used by the canvas and the scratch stacks).
// ---------------------------------------------------------------------------

/** A list slot's children, its owner and its index among the owner's slots. */
class BodyRef(val children: List<UiBlockNode>, val ownerId: String, val slotIndex: Int)

/**
 * The statement/member list of [node]: its own list slot, or the one inside its `{…}` block (or Kotlin
 * class body) child.
 */
fun bodyOf(node: UiBlockNode): BodyRef? {
    val slots = node.parts.filterIsInstance<UiBlockPart.Slot>()
    slots.indexOfFirst { it.multiple }.takeIf { it >= 0 }?.let { i -> return BodyRef(slots[i].children, node.id, i) }
    val blockChild = slots.firstNotNullOfOrNull { s -> s.children.singleOrNull()?.takeIf { it.kind == "block" || it.label == "body" } } ?: return null
    val inner = blockChild.parts.filterIsInstance<UiBlockPart.Slot>()
    val mi = inner.indexOfFirst { it.multiple }
    return if (mi >= 0) BodyRef(inner[mi].children, blockChild.id, mi) else null
}

fun slotIndexIn(node: UiBlockNode, part: UiBlockPart.Slot): Int =
    node.parts.filterIsInstance<UiBlockPart.Slot>().indexOf(part)

fun findFirst(node: UiBlockNode, p: (UiBlockNode) -> Boolean): UiBlockNode? {
    if (p(node)) return node
    for (part in node.parts) if (part is UiBlockPart.Slot) for (c in part.children) findFirst(c, p)?.let { return it }
    return null
}

/**
 * The initializer value of a declaration: Java's `T name = value;` (the value sits in the declarator
 * fragment) or Kotlin's `val name = value` (in the declaration itself). The value is the first value slot
 * after the `=`.
 */
fun initializerOf(decl: UiBlockNode): UiBlockNode? {
    val frag = decl.parts.filterIsInstance<UiBlockPart.Slot>().mapNotNull { it.children.singleOrNull() }
        .firstOrNull { it.label == "var" || it.kind == "local_var" } ?: decl
    val eq = frag.parts.indexOfFirst { it is UiBlockPart.Field && !it.editable && '=' in it.text }
    if (eq < 0) return null
    return frag.parts.drop(eq + 1).filterIsInstance<UiBlockPart.Slot>()
        .firstOrNull { !it.multiple && it.category != "TYPE" && it.category != "STATEMENT" }?.children?.singleOrNull()
}

/** A scratch snippet's content: its statement list, or (for a value snippet) the value. */
class ScratchContent(val body: BodyRef?, val value: UiBlockNode?)

/** Find the content of a projected scratch snippet (see [ScratchCodec] for how snippets are wrapped). */
fun scratchContent(root: UiBlockNode, isValue: Boolean): ScratchContent? {
    val method = findFirst(root) { it.label == "method" } ?: return null
    val body = bodyOf(method) ?: return null
    return if (isValue) ScratchContent(null, body.children.firstOrNull()?.let(::initializerOf)) else ScratchContent(body, null)
}

/**
 * A declaration's header: its source up to the body (`{`) or expression body (`=`), whitespace-collapsed,
 * e.g. `public int sum(int[] xs)` or `fun render(items: List<String>): Unit`.
 */
fun signatureText(node: UiBlockNode, source: String): String {
    val slots = node.parts.filterIsInstance<UiBlockPart.Slot>()
    val bodyStart = slots.firstNotNullOfOrNull { s -> s.children.singleOrNull()?.takeIf { it.kind == "block" }?.start }
    val eq = node.parts.indexOfLast { it is UiBlockPart.Field && !it.editable && it.text.trimEnd().endsWith("=") }
    val end = bodyStart ?: (node.parts.getOrNull(eq) as? UiBlockPart.Field)?.let { it.start + it.text.trimEnd().length - 1 } ?: node.end
    val s = if (node.start in 0..source.length && end in node.start..source.length) source.substring(node.start, end) else ""
    return s.trim().trimEnd('{').trim().replace(Regex("\\s+"), " ")
}

fun catOf(node: UiBlockNode): BlockCat = when {
    node.label in CONTROL_LABELS -> BlockCat.Control
    node.label in TERMINAL_LABELS -> BlockCat.Return
    node.label == "comment" -> BlockCat.Comment
    node.label == "var" || node.label == "field" || node.label == "expr" || node.kind == "local_var" || node.kind == "field_decl" -> BlockCat.Data
    node.label == "" || node.label == "call" || node.label == "access" || node.kind == "method_call" || node.kind == "ExpressionStatement" -> BlockCat.Call
    else -> BlockCat.Opaque
}

fun catForValue(node: UiBlockNode): BlockCat = when {
    node.kind == "method_call" || node.kind == "member_access" -> BlockCat.Call
    node.label == "expr" || node.kind == "InfixExpression" -> BlockCat.Op
    node.label == "if" || node.label == "when" || node.label == "try" -> BlockCat.Control
    else -> BlockCat.Data
}

fun isTerminal(node: UiBlockNode): Boolean = node.label in TERMINAL_LABELS

fun Rect.Companion.of(x: Float, y: Float, w: Float, h: Float) = Rect(x, y, x + w, y + h)

/** Distance used to pick the nearest connection: vertical offset counts double the horizontal. */
fun snapDistance(a: Offset, b: Offset): Float = kotlin.math.abs(a.y - b.y) + kotlin.math.abs(a.x - b.x) / 2f
