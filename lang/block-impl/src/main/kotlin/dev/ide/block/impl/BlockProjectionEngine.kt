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
import dev.ide.block.DeleteRange
import dev.ide.block.InsertArgument
import dev.ide.block.InsertTemplate
import dev.ide.block.Move
import dev.ide.block.MoveRange
import dev.ide.block.ProjectionContext
import dev.ide.block.RemoveArgument
import dev.ide.block.ReplaceWithText
import dev.ide.block.SetField
import dev.ide.block.SlotCategory
import dev.ide.block.SlotRef
import dev.ide.block.ValueKind
import dev.ide.block.ValueKindOracle
import dev.ide.block.Wrap
import dev.ide.block.WrapRange
import dev.ide.lang.LanguageId
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
class BlockProjectionEngine(private val mappings: List<BlockMapping>, private val oracle: ValueKindOracle? = null) : BlockProjectionService {

    /** Per language: which mapping handles each kind. Java and Kotlin share neutral kinds, so never one map. */
    private val mappingsByLanguage: Map<LanguageId, Map<NodeKind, BlockMapping>> =
        mappings.flatMap { m -> m.languages.map { it to m } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ms) -> ms.flatMap { m -> m.handles.map { it to m } }.toMap() }

    private fun dialectOf(language: LanguageId): BlockDialect =
        mappings.firstNotNullOfOrNull { m -> if (language in m.languages) dialectOf(m) else null } ?: JavaDialect

    companion object {
        /** An engine wired with the built-in Java mapping (statements + key expressions). */
        fun withJava(oracle: ValueKindOracle? = null): BlockProjectionEngine =
            BlockProjectionEngine(listOf(JavaBlockMapping), oracle)
    }

    override fun project(file: ParsedFile): BlockTree {
        val language = languageOfPath(file.file.path)
        val pass = ProjectionPass(file.text(), mappingsByLanguage[language] ?: emptyMap(), oracle, dialectOf(language))
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
            when {
                block == null -> emptyList()
                // A statement in a list wraps as a one-block run, so the body is re-indented.
                locate(tree.root, block.id)?.first?.multiple == true -> wrapRun(tree, text, block.id, 1, edit.with)
                else -> {
                    val inner = text.subSequence(block.range.start, block.range.end).toString()
                    val wrapped = edit.with.defaultText.replace(BlockTemplate.PLACEHOLDER.toString(), inner)
                    listOf(DocumentEdit(block.range.start, block.range.length, wrapped))
                }
            }
        }

        is Move -> moveRun(tree, text, edit.block.id, 1, edit.to)
        is MoveRange -> moveRun(tree, text, edit.first.id, edit.count, edit.to)
        is DeleteRange -> run(tree, edit.first.id, edit.count)?.let { listOf(deleteEdit(text, it.span)) } ?: emptyList()
        is WrapRange -> wrapRun(tree, text, edit.first.id, edit.count, edit.with)
        is InsertArgument -> insertArgument(tree, edit)
        is RemoveArgument -> removeArgument(tree, edit)
    }

    // ---- call arguments ----

    /** The parts of segment [link] of [call]: its name field and the argument slots up to the next segment. */
    private fun segment(call: BlockNode, link: Int): Pair<BlockField, List<BlockSlot>>? {
        val parts = call.parts
        val role = if (link == 0) "name" else "name$link"
        val at = parts.indexOfFirst { it is BlockPart.Field && it.field.role == role && it.field.editable }
        if (at < 0) return null
        val end = (at + 1 until parts.size).firstOrNull { val p = parts[it]; p is BlockPart.Field && p.field.editable && NAME_ROLE.matches(p.field.role) } ?: parts.size
        val args = parts.subList(at + 1, end).mapNotNull { (it as? BlockPart.Slot)?.slot }.filter { it.category == SlotCategory.ARGUMENT }
        return (parts[at] as BlockPart.Field).field to args
    }

    /** An argument's whole span: a Kotlin named argument includes its `name =`. */
    private fun argSpan(slot: BlockSlot): TextRange {
        val anchor = slot.children.singleOrNull()?.anchor ?: return slot.range
        val parent = anchor.parent
        return if (parent != null && parent.kind == NodeKind.ARGUMENT && parent.range.start <= anchor.range.start) parent.range else slot.range
    }

    private fun insertArgument(tree: BlockTree, e: InsertArgument): List<DocumentEdit> {
        val call = tree.block(e.call.id) ?: return emptyList()
        val (name, args) = segment(call, e.link) ?: return emptyList()
        val written = args.filter { it.children.isNotEmpty() }
        val nameEnd = name.range?.end ?: return emptyList()
        return listOf(
            when {
                args.isEmpty() -> DocumentEdit(nameEnd, 0, "(" + e.text + ")")
                written.isEmpty() -> DocumentEdit(args.first().range.start, 0, e.text)
                e.index < written.size -> DocumentEdit(argSpan(written[e.index]).start, 0, e.text + ", ")
                else -> DocumentEdit(argSpan(written.last()).end, 0, ", " + e.text)
            },
        )
    }

    private fun removeArgument(tree: BlockTree, e: RemoveArgument): List<DocumentEdit> {
        val call = tree.block(e.call.id) ?: return emptyList()
        val slot = call.slots.getOrNull(e.slotIndex)?.takeIf { it.children.isNotEmpty() && it.category == SlotCategory.ARGUMENT } ?: return emptyList()
        // The written arguments of the segment holding this slot.
        val parts = call.parts
        val at = parts.indexOfFirst { it is BlockPart.Slot && it.slot === slot }
        var from = at
        while (from > 0 && !(parts[from] is BlockPart.Field && (parts[from] as BlockPart.Field).field.let { it.editable && NAME_ROLE.matches(it.role) })) from--
        var to = at
        while (to < parts.size && !(to > at && parts[to] is BlockPart.Field && (parts[to] as BlockPart.Field).field.let { it.editable && NAME_ROLE.matches(it.role) })) to++
        val siblings = parts.subList(from, to).mapNotNull { (it as? BlockPart.Slot)?.slot }.filter { it.category == SlotCategory.ARGUMENT && it.children.isNotEmpty() }
        val i = siblings.indexOf(slot)
        val span = argSpan(slot)
        val range = when {
            i + 1 < siblings.size -> TextRange(span.start, argSpan(siblings[i + 1]).start)
            i > 0 -> TextRange(argSpan(siblings[i - 1]).end, span.end)
            else -> span
        }
        return listOf(DocumentEdit(range.start, range.length, ""))
    }

    // ---- runs of siblings ----

    /** [count] consecutive siblings of a list slot, starting at [index]; [span] covers first start to last end. */
    private class Run(val slot: BlockSlot, val index: Int, val blocks: List<BlockNode>) {
        val span: TextRange get() = TextRange(blocks.first().range.start, blocks.last().range.end)
    }

    /** The slot holding [id] and its index there, found by walking down from [root]. */
    private fun locate(root: BlockNode, id: BlockId): Pair<BlockSlot, Int>? {
        for (slot in root.slots) {
            val i = slot.children.indexOfFirst { it.id == id }
            if (i >= 0) return slot to i
            for (c in slot.children) locate(c, id)?.let { return it }
        }
        return null
    }

    private fun run(tree: BlockTree, first: BlockId, count: Int): Run? {
        val (slot, i) = locate(tree.root, first) ?: return null
        val end = if (slot.multiple) (i + count.coerceAtLeast(1)).coerceAtMost(slot.children.size) else i + 1
        return Run(slot, i, slot.children.subList(i, end))
    }

    private fun moveRun(tree: BlockTree, text: CharSequence, first: BlockId, count: Int, to: SlotRef): List<DocumentEdit> {
        val run = run(tree, first, count) ?: return emptyList()
        val owner = tree.block(to.owner) ?: return emptyList()
        val target = owner.slots.getOrNull(to.slotIndex) ?: return emptyList()
        val span = run.span
        // Into itself (a C-block dropped into its own body) or onto the place it already occupies: no edit.
        if (owner.range.start >= span.start && owner.range.end <= span.end) return emptyList()
        if (target === run.slot && to.index in run.index..(run.index + run.blocks.size)) return emptyList()
        val moved = text.subSequence(span.start, span.end).toString()
        val del = deleteEdit(text, span)
        val ins = listInsert(text, target, to.index, moved, indentAt(text, span.start))
        // The two replaces must be disjoint for the caller's descending-offset application to be sound.
        if (ins.offset > del.offset && ins.offset < del.offset + del.oldLength) return emptyList()
        return listOf(ins, del).sortedByDescending { it.offset }
    }

    private fun wrapRun(tree: BlockTree, text: CharSequence, first: BlockId, count: Int, with: BlockTemplate): List<DocumentEdit> {
        val run = run(tree, first, count) ?: return emptyList()
        val span = run.span
        val base = indentAt(text, span.start)
        val inner = base + indentUnit(text)
        val body = inner + reindent(text.subSequence(span.start, span.end).toString(), base, inner)
        val marker = BlockTemplate.PLACEHOLDER.toString()
        val wrapped = with.defaultText.lines().mapIndexed { k, line ->
            when {
                marker in line -> body
                k == 0 -> line
                line.isBlank() -> ""
                else -> base + line
            }
        }.joinToString("\n")
        return listOf(DocumentEdit(span.start, span.length, wrapped))
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

    private fun insertEdit(text: CharSequence, slot: BlockSlot, index: Int, template: BlockTemplate): DocumentEdit {
        val body = template.defaultText.replace(BlockTemplate.PLACEHOLDER.toString(), "")
        // A statement or member lands on its own line at the list's indent; an expression splices in place.
        if (slot.category == SlotCategory.STATEMENT || slot.category == SlotCategory.DECLARATION) {
            return listInsert(text, slot, index, body, baseIndent = "")
        }
        val kids = slot.children
        val off = when {
            kids.isEmpty() -> slot.range.end
            index < kids.size -> kids[index].range.start
            else -> kids.last().range.end
        }
        return DocumentEdit(off, 0, body)
    }

    /**
     * Insert [code] (whose continuation lines are indented relative to [baseIndent]) as entry [index] of the
     * list [slot], on its own line at the list's indent: first, it takes the first entry's place on its line;
     * otherwise it goes on a new line after the entry above (after that line's trailing comment); into an
     * empty `{}` it opens the braces onto separate lines, one indent level inside the brace's line.
     */
    private fun listInsert(text: CharSequence, slot: BlockSlot, index: Int, code: String, baseIndent: String): DocumentEdit {
        val kids = slot.children
        return when {
            kids.isEmpty() -> {
                val anchor = slot.range.start
                val outer = indentAt(text, anchor)
                val inner = outer + indentUnit(text)
                val block = inner + reindent(code, baseIndent, inner)
                // `{}` or `{ }`: the closing brace shares the line, so it moves to its own line too.
                var close = anchor
                while (close < text.length && (text[close] == ' ' || text[close] == '\t')) close++
                if (close < text.length && text[close] == '}') DocumentEdit(anchor, close - anchor, "\n$block\n$outer")
                else DocumentEdit(lineEndAfter(text, anchor), 0, "\n$block")
            }
            index <= 0 -> {
                val off = kids[0].range.start
                val indent = indentAt(text, off)
                DocumentEdit(off, 0, reindent(code, baseIndent, indent) + "\n" + indent)
            }
            else -> {
                // Right after the entry above (past its `;` and trailing comment), so the new entry never
                // lands between the next one and a comment that belongs to it.
                val prev = kids[(index - 1).coerceAtMost(kids.size - 1)].range
                val indent = indentAt(text, (kids.getOrNull(index) ?: kids.last()).range.start)
                var end = prev.end
                if (end < text.length && text[end] == ';') end++
                DocumentEdit(lineEndAfter(text, end), 0, "\n" + indent + reindent(code, baseIndent, indent))
            }
        }
    }

    /**
     * Re-indent [code]'s continuation lines from [from] to [to]. The first line is left alone (it starts at
     * the insertion point); blank lines lose their whitespace.
     */
    private fun reindent(code: String, from: String, to: String): String {
        val lines = code.lines()
        if (lines.size == 1) return code
        return lines.mapIndexed { k, line ->
            when {
                k == 0 -> line
                line.isBlank() -> ""
                line.startsWith(from) -> to + line.substring(from.length)
                else -> to + line.trimStart()
            }
        }.joinToString("\n")
    }

    /** The file's indent step: a tab if lines are tab-indented, else the smallest space indent (default 4). */
    private fun indentUnit(text: CharSequence): String {
        var minSpaces = Int.MAX_VALUE
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j] == ' ') j++
            if (j < text.length && text[j] == '\t' && j == i) return "\t"
            if (j > i && j < text.length && text[j] != '\n' && text[j] != '\r') minSpaces = minOf(minSpaces, j - i)
            while (i < text.length && text[i] != '\n') i++
            i++
        }
        return " ".repeat(if (minSpaces in 2..8) minSpaces else 4)
    }

    /** End of the line at [offset] when the rest of it is blank or a `//` comment, else [offset] itself. */
    private fun lineEndAfter(text: CharSequence, offset: Int): Int {
        var eol = offset
        while (eol < text.length && text[eol] != '\n' && text[eol] != '\r') eol++
        val rest = text.subSequence(offset, eol).trim()
        return if (rest.isEmpty() || rest.startsWith("//")) eol else offset
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

/** The method-name roles of a collapsed call/chain: `name`, `name1`, `name2`, … */
private val NAME_ROLE = Regex("name\\d*")

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
    private val dialect: BlockDialect,
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
    override fun produced(node: DomNode): ValueKind = oracle?.producedKind(node) ?: dialect.valueKindFor(node)

    override fun carve(node: DomNode): BlockNode {
        val children = node.children
        if (children.isEmpty()) return leaf(node)
        val parts = ArrayList<BlockPart>()
        var pos = node.range.start
        for (c in children) {
            if (c.range.start > pos) parts += BlockPart.Field(chrome(pos, c.range.start))
            // The slot expects what the POSITION demands (an if condition → boolean); else what the content produces.
            val expected = dialect.expectedValueKind(node, c).takeIf { it != ValueKind.UNKNOWN } ?: produced(c)
            parts += BlockPart.Slot(slot(dialect.categoryFor(c.kind), listOf(child(c)), multiple = false, range = c.range, valueKind = expected))
            pos = c.range.end
        }
        if (node.range.end > pos) parts += BlockPart.Field(chrome(pos, node.range.end))
        return block(node, node.kind, parts, dialect.labelFor(node.kind), valueKind = produced(node))
    }

    override fun child(node: DomNode): BlockNode =
        mappingByKind[node.kind]?.project(node, this) ?: opaque(node)

    /** A handled leaf (a name, a literal): one editable field carrying its text. */
    private fun leaf(node: DomNode): BlockNode =
        block(node, node.kind, listOf(BlockPart.Field(field(dialect.roleFor(node.kind), textOf(node.range).toString(), editable = true, range = node.range))), dialect.labelFor(node.kind), valueKind = produced(node))

    /** An unmapped node: collapse to one editable text slot — never a dead end, explodes on reparse. */
    private fun opaque(node: DomNode): BlockNode =
        block(node, node.kind, listOf(BlockPart.Field(field("code", textOf(node.range).toString(), editable = true, range = node.range))), dialect.labelFor(node.kind), valueKind = produced(node))

    override fun block(anchor: DomNode?, kind: NodeKind, parts: List<BlockPart>, label: String?, valueKind: ValueKind): BlockNode {
        val node = BlockNodeImpl(BlockId("b${counter++}"), kind, anchor, anchor?.range ?: spanOf(parts), parts, label ?: dialect.labelFor(kind), valueKind)
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
