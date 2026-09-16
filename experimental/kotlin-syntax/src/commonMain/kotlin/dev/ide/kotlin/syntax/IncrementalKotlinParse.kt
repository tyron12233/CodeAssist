package dev.ide.kotlin.syntax

import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.kmp.tree.LightNode
import org.jetbrains.kotlin.kmp.tree.LightSyntaxTree

/**
 * One expanded body: its tree, and where that tree sits in the file.
 *
 * The offset is carried rather than baked in because a block parsed on its own necessarily has offsets
 * relative to itself (see `KotlinSyntax.parseBlock`). Making the translation explicit is the point: a tree
 * whose numbers silently mean something different from the file's is the kind of thing that surfaces as a
 * completely unrelated bug three layers up.
 */
class ExpandedBlock(val tree: LightSyntaxTree, val fileOffset: Int) {
    /** [node]'s start, in the FILE's coordinates. */
    fun startOf(node: LightNode): Int = tree.getStartOffset(node) + fileOffset

    /** [node]'s end, in the FILE's coordinates. */
    fun endOf(node: LightNode): Int = tree.getEndOffset(node) + fileOffset
}

/**
 * A file under edit: a lazily parsed tree, plus the bodies that have been asked for.
 *
 * **Why this exists.** The vendored parser has no incremental mode, and `:lang-kotlin` reparses on every
 * keystroke. Measured on this repository's largest file, a full parse is 6.8 ms on a warm desktop JIT, which
 * is most of a 120 Hz frame and several times worse on ART. That is the gap this closes.
 *
 * **How, and why not the obvious way.** The obvious fix is to patch the old tree in place: shift the offsets
 * after the edit and re-parse only what changed. That is not available here, because a `LightSyntaxTree` is
 * flat immutable arrays with no way to read them back out and rebuild, and the vendored sources are not ours
 * to change. So this uses the structure the grammar already offers instead.
 *
 * In lazy mode the parser does not descend into function bodies at all: it counts braces past each one and
 * collapses it into a single `BLOCK` node. Bodies are most of a file, so that alone is about twice as fast.
 * The interior of a body is then parsed only when something asks for it — which, per keystroke, is one body:
 * the one holding the caret.
 *
 * So the cost of a keystroke becomes a lazy file parse plus one block, rather than a full parse of everything.
 * That is the same division of labour PSI makes with lazy-parseable elements, arrived at from the other side.
 *
 * **What is cached and what is not.** An expanded block is kept against its start offset AND its text. Text
 * alone is not enough: a block's tree carries absolute offsets, so the same body moved down a line is a
 * different answer. Typing in one body therefore keeps every body above the caret and drops those below,
 * which is the right trade, since what an editor asks about per keystroke is the one under the caret.
 *
 * Not thread-safe, and deliberately so: it models one editor buffer.
 */
class IncrementalKotlinParse(text: CharSequence, private val isScript: Boolean = false) {

    var text: CharSequence = text
        private set

    /** The buffer's tokens, lexed once per edit and reused by the file parse. */
    private var tokens = KotlinSyntax.lex(text)

    /** The file tree, with every function body collapsed. Reparsed on each [edit]. */
    var fileTree: LightSyntaxTree = KotlinSyntax.parse(text, isScript, lazy = true, tokens = tokens)
        private set

    private val expanded = HashMap<Long, ExpandedBlock>()

    /** What each cached expansion was parsed FROM, so a moved-but-identical body is still a miss. */
    private val expandedText = HashMap<Long, String>()

    /** How many block expansions were served from the cache, and how many were parsed. For tests and tuning. */
    var blockCacheHits: Int = 0
        private set
    var blockParses: Int = 0
        private set

    /**
     * Replace the buffer and reparse the file.
     *
     * Deliberately takes the whole new text rather than a splice: an editor has it anyway, and reconstructing
     * it from a range is a chance to be subtly wrong about something the caller already knows exactly.
     */
    fun edit(newText: CharSequence) {
        text = newText
        tokens = KotlinSyntax.lex(newText)
        fileTree = KotlinSyntax.parse(newText, isScript, lazy = true, tokens = tokens)
        // Entries are keyed by offset and validated by text, so a stale one cannot be returned; it simply
        // never matches again. Clearing here would throw away the bodies ABOVE the edit, which are the ones
        // still good.
    }

    /**
     * The parsed interior of the collapsed body containing [offset], or null when the offset is not inside
     * one.
     *
     * Note which body: lazy mode collapses the OUTERMOST one, so an `if` nested inside a function is not its
     * own block. Asking from inside that `if` returns the whole function, expanded.
     */
    fun blockAt(offset: Int): ExpandedBlock? {
        val block = innermostCollapsedBlock(fileTree.getRoot(), offset) ?: return null
        val start = fileTree.getStartOffset(block)
        val end = fileTree.getEndOffset(block)
        val key = keyOf(start, end)
        val cached = expanded[key]
        if (cached != null && regionUnchanged(cached, start, end)) {
            blockCacheHits++
            return cached
        }
        blockParses++
        val parsed = ExpandedBlock(KotlinSyntax.parseBlock(text.subSequence(start, end)), start)
        expanded[key] = parsed
        expandedText[key] = text.substring(start, end)
        return parsed
    }

    /**
     * The deepest `BLOCK` covering [offset] that the lazy parse left unexpanded.
     *
     * "Unexpanded" is not "childless", which is the trap here. A collapsed marker in this tree still holds
     * every TOKEN it swallowed — `(BLOCK LBRACE val IDENTIFIER … RBRACE)` — it just has no structure inside.
     * A body the parser did descend into has composite children (`PROPERTY`, `RETURN`, and so on). So the
     * test is whether any child is a composite, and getting that wrong means finding no bodies at all.
     */
    private fun innermostCollapsedBlock(node: LightNode, offset: Int): LightNode? {
        if (offset < fileTree.getStartOffset(node) || offset > fileTree.getEndOffset(node)) return null
        for (child in fileTree.getChildren(node)) {
            innermostCollapsedBlock(child, offset)?.let { return it }
        }
        if (fileTree.getType(node) != KtNodeTypes.BLOCK) return null
        val children = fileTree.getChildren(node)
        val collapsed = children.isNotEmpty() && children.all { fileTree.isToken(it) }
        return if (collapsed) node else null
    }

    /** Is the buffer's [start]..[end] region still the text this entry was parsed from? */
    private fun regionUnchanged(cached: ExpandedBlock, start: Int, end: Int): Boolean {
        val was = expandedText[keyOf(start, end)] ?: return false
        if (was.length != end - start) return false
        for (i in was.indices) if (was[i] != text[start + i]) return false
        return true
    }

    private fun keyOf(start: Int, end: Int): Long =
        start.toLong() shl 32 or ((end - start).toLong() and 0xFFFFFFFFL)
}
