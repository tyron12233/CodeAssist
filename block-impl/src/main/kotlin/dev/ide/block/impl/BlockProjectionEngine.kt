package dev.ide.block.impl

import dev.ide.block.BlockEdit
import dev.ide.block.BlockField
import dev.ide.block.BlockId
import dev.ide.block.BlockMapping
import dev.ide.block.BlockNode
import dev.ide.block.BlockPart
import dev.ide.block.BlockProjectionService
import dev.ide.block.BlockSlot
import dev.ide.block.BlockTemplate
import dev.ide.block.BlockTree
import dev.ide.block.Delete
import dev.ide.block.InsertTemplate
import dev.ide.block.Move
import dev.ide.block.ProjectionContext
import dev.ide.block.ReplaceWithText
import dev.ide.block.SetField
import dev.ide.block.SlotCategory
import dev.ide.block.ValueKind
import dev.ide.block.ValueKindOracle
import dev.ide.block.Wrap
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit

/**
 * The projection engine. It projects a [ParsedFile] into a [BlockTree] by gap carving — interleaving
 * the literal source between child ranges as read-only chrome with each child projected into a slot —
 * and compiles a [BlockEdit] into the minimal [DocumentEdit]s that realize it, leaving everything
 * outside the touched ranges byte-for-byte intact.
 *
 * A [BlockMapping] decides which node kinds decompose (its `handles` set): a handled child becomes its
 * own sub-block; an unhandled child collapses to a single editable text slot (opaque/expression-collapse)
 * — the same slot typed into to "explode" code back into blocks. Containers (a method body, a class
 * body) override to a single list slot so statements/members can be inserted.
 *
 * Projection is a full pass; the structural-sharing fast path is a later optimization.
 */
class BlockProjectionEngine(mappings: List<BlockMapping>, private val oracle: ValueKindOracle? = null) : BlockProjectionService {

    private val mappingByKind: Map<NodeKind, BlockMapping> =
        mappings.flatMap { m -> m.handles.map { it to m } }.toMap()

    companion object {
        /** An engine wired with the built-in Java mapping (statements + key expressions). */
        fun withJava(oracle: ValueKindOracle? = null): BlockProjectionEngine =
            BlockProjectionEngine(listOf(JavaBlockMapping), oracle)
    }

    override fun project(file: ParsedFile): BlockTree {
        val pass = ProjectionPass(file.text(), mappingByKind, oracle)
        val root = pass.projectRoot(file)
        return BlockTreeImpl(root, file.documentVersion, pass.byId, pass.all)
    }

    override fun computeEdit(tree: BlockTree, text: CharSequence, edit: BlockEdit): List<DocumentEdit> = when (edit) {
        is SetField -> {
            val field = tree.block(edit.block.id)?.fields?.firstOrNull { it.role == edit.role && it.editable }
            val r = field?.range
            if (r == null) emptyList() else listOf(DocumentEdit(r.start, r.length, edit.text))
        }

        is ReplaceWithText -> {
            val slot = tree.block(edit.slot.owner)?.slots?.getOrNull(edit.slot.slotIndex)
            if (slot == null) emptyList() else listOf(DocumentEdit(slot.range.start, slot.range.length, edit.text))
        }

        is Delete -> {
            val block = tree.block(edit.block.id)
            if (block == null) emptyList() else listOf(deleteEdit(text, block.range))
        }

        is InsertTemplate -> {
            val slot = tree.block(edit.at.owner)?.slots?.getOrNull(edit.at.slotIndex)
            if (slot == null) emptyList() else listOf(insertEdit(text, slot, edit.at.index, edit.template))
        }

        is Wrap -> {
            val block = tree.block(edit.block.id)
            if (block == null) emptyList() else {
                val inner = text.subSequence(block.range.start, block.range.end).toString()
                val wrapped = edit.with.defaultText.replace(BlockTemplate.PLACEHOLDER.toString(), inner)
                listOf(DocumentEdit(block.range.start, block.range.length, wrapped))
            }
        }

        is Move -> {
            val block = tree.block(edit.block.id)
            val slot = tree.block(edit.to.owner)?.slots?.getOrNull(edit.to.slotIndex)
            if (block == null || slot == null) emptyList() else {
                val moved = text.subSequence(block.range.start, block.range.end).toString()
                val del = deleteEdit(text, block.range)
                val insOffset = insertOffset(slot, edit.to.index)
                // Two disjoint replaces; the caller applies them in descending-offset order.
                listOf(DocumentEdit(insOffset, 0, moved + "\n"), del).sortedByDescending { it.offset }
            }
        }
    }

    // ---- surgical-edit helpers ----

    /** Delete a block's range, also swallowing a trailing `;`, the line break, and a now-blank indent. */
    private fun deleteEdit(text: CharSequence, range: TextRange): DocumentEdit {
        var end = range.end
        if (end < text.length && text[end] == ';') end++
        val afterStmt = end
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        var consumedNewline = false
        if (end < text.length && text[end] == '\r') end++
        if (end < text.length && text[end] == '\n') { end++; consumedNewline = true }
        if (!consumedNewline) end = afterStmt  // don't strip trailing spaces unless the whole line was removed
        var start = range.start
        if (consumedNewline) {
            var ls = start
            while (ls > 0 && text[ls - 1] != '\n') ls--
            if (text.subSequence(ls, start).isBlank()) start = ls  // line was only this block → take its indent too
        }
        return DocumentEdit(start, end - start, "")
    }

    private fun insertOffset(slot: BlockSlot, index: Int): Int {
        val kids = slot.children
        return when {
            kids.isEmpty() -> slot.range.end
            index < kids.size -> kids[index].range.start
            else -> kids.last().range.end
        }
    }

    private fun insertEdit(text: CharSequence, slot: BlockSlot, index: Int, template: BlockTemplate): DocumentEdit {
        val off = insertOffset(slot, index)
        val body = template.defaultText.replace(BlockTemplate.PLACEHOLDER.toString(), "")
        // A statement lands on its own fresh line at the body's indent; an expression splices in place.
        val insert = if (slot.category == SlotCategory.STATEMENT) "\n" + indentAt(text, off) + body else body
        return DocumentEdit(off, 0, insert)
    }

    /** The leading whitespace of the line containing [offset] (the indent new siblings should match). */
    private fun indentAt(text: CharSequence, offset: Int): String {
        var ls = offset.coerceIn(0, text.length)
        while (ls > 0 && text[ls - 1] != '\n') ls--
        var i = ls
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.subSequence(ls, i).toString()
    }
}

// ---------------------------------------------------------------------------
// One projection pass — implements ProjectionContext and assigns block ids.
// ---------------------------------------------------------------------------

/**
 * The pass's [ValueKind] resolution, exposed to same-module mappings (the pass implements it): the
 * [ValueKindOracle] is consulted first, falling back to the syntactic [valueKindFor] heuristic, so a
 * mapping's hand-built parts (e.g. the call-chain collapse) also receive oracle refinement.
 */
internal interface ValueKindResolver {
    /** The [ValueKind] [node] produces as an expression ([ValueKind.UNKNOWN] when nothing knows). */
    fun produced(node: DomNode): ValueKind
}

private class ProjectionPass(
    override val source: CharSequence,
    private val mappingByKind: Map<NodeKind, BlockMapping>,
    private val oracle: ValueKindOracle?,
) : ProjectionContext, ValueKindResolver {

    val byId = LinkedHashMap<BlockId, BlockNode>()
    val all = ArrayList<BlockNode>()
    private var counter = 0

    /** The root never collapses to opaque: a mapping projects it, else generic carve. */
    fun projectRoot(node: DomNode): BlockNode =
        (mappingByKind[node.kind]?.project(node, this)) ?: carve(node)

    override fun textOf(range: TextRange): CharSequence =
        source.subSequence(range.start.coerceIn(0, source.length), range.end.coerceIn(0, source.length))

    /** Oracle first, syntactic heuristic on null (the [ValueKindOracle] contract). */
    override fun produced(node: DomNode): ValueKind = oracle?.producedKind(node) ?: valueKindFor(node)

    override fun carve(node: DomNode): BlockNode {
        val children = node.children
        if (children.isEmpty()) return leaf(node)
        val parts = ArrayList<BlockPart>()
        var pos = node.range.start
        for (c in children) {
            if (c.range.start > pos) parts += BlockPart.Field(chrome(pos, c.range.start))
            // The slot expects what the POSITION demands (an if condition → boolean); else what the content produces.
            val expected = expectedValueKind(node, c).takeIf { it != ValueKind.UNKNOWN } ?: produced(c)
            parts += BlockPart.Slot(slot(categoryFor(c.kind), listOf(child(c)), multiple = false, range = c.range, valueKind = expected))
            pos = c.range.end
        }
        if (node.range.end > pos) parts += BlockPart.Field(chrome(pos, node.range.end))
        return block(node, node.kind, parts, labelFor(node.kind), valueKind = produced(node))
    }

    override fun child(node: DomNode): BlockNode =
        mappingByKind[node.kind]?.project(node, this) ?: opaque(node)

    /** A handled leaf (a name, a literal): one editable field carrying its text. */
    private fun leaf(node: DomNode): BlockNode =
        block(node, node.kind, listOf(BlockPart.Field(field(roleFor(node.kind), textOf(node.range).toString(), editable = true, range = node.range))), labelFor(node.kind), valueKind = produced(node))

    /** An unmapped node: collapse to one editable text slot — never a dead end, explodes on reparse. */
    private fun opaque(node: DomNode): BlockNode =
        block(node, node.kind, listOf(BlockPart.Field(field("code", textOf(node.range).toString(), editable = true, range = node.range))), labelFor(node.kind), valueKind = produced(node))

    override fun block(anchor: DomNode?, kind: NodeKind, parts: List<BlockPart>, label: String?, valueKind: ValueKind): BlockNode {
        val node = BlockNodeImpl(BlockId("b${counter++}"), kind, anchor, anchor?.range ?: spanOf(parts), parts, label ?: labelFor(kind), valueKind)
        byId[node.id] = node
        all += node
        return node
    }

    override fun slot(category: SlotCategory, children: List<BlockNode>, multiple: Boolean, range: TextRange, valueKind: ValueKind): BlockSlot =
        BlockSlotImpl(category, children, multiple, range, valueKind)

    override fun field(role: String, text: String, editable: Boolean, range: TextRange?): BlockField =
        BlockField(role, text, editable, range)

    /** A read-only chrome field over the source span [start, end). */
    fun chrome(start: Int, end: Int): BlockField =
        BlockField.chrome(textOf(TextRange(start, end)).toString(), TextRange(start, end))

    private fun spanOf(parts: List<BlockPart>): TextRange {
        val ranges = parts.flatMap {
            when (it) {
                is BlockPart.Field -> listOfNotNull(it.field.range)
                is BlockPart.Slot -> listOf(it.slot.range)
            }
        }
        return if (ranges.isEmpty()) TextRange(0, 0) else TextRange(ranges.minOf { it.start }, ranges.maxOf { it.end })
    }
}

private class BlockNodeImpl(
    override val id: BlockId,
    override val kind: NodeKind,
    override val anchor: DomNode?,
    override val range: TextRange,
    override val parts: List<BlockPart>,
    override val label: String,
    override val valueKind: ValueKind,
) : BlockNode

private class BlockSlotImpl(
    override val category: SlotCategory,
    override val children: List<BlockNode>,
    override val multiple: Boolean,
    override val range: TextRange,
    override val valueKind: ValueKind,
) : BlockSlot

private class BlockTreeImpl(
    override val root: BlockNode,
    override val version: Long,
    private val byId: Map<BlockId, BlockNode>,
    private val all: List<BlockNode>,
) : BlockTree {
    override fun block(id: BlockId): BlockNode? = byId[id]
    /** Deepest (smallest-range) block whose range contains [offset]. */
    override fun blockAt(offset: Int): BlockNode? =
        all.filter { offset in it.range }.minByOrNull { it.range.length }
}
