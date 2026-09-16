package dev.ide.kotlin.syntax

import org.jetbrains.kotlin.kmp.lexer.KtTokens
import org.jetbrains.kotlin.kmp.parser.KtNodeTypes
import org.jetbrains.kotlin.kmp.tree.LightNode
import org.jetbrains.kotlin.kmp.tree.LightSyntaxTree

/**
 * One expanded body: its tree, and where that tree sits in the file.
 *
 * The offset is carried rather than baked in because a block parsed on its own necessarily has offsets
 * relative to itself (see [KotlinSyntax.parseBlock]). Making the translation explicit is the point: a tree
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
 * is most of a 120 Hz frame and worse again on ART.
 *
 * **Three levels, each avoiding more work than the last.**
 *
 * 1. *Lazy parsing.* The grammar can skip function bodies entirely: it counts braces past each one and
 *    collapses it into a single `BLOCK`, as PSI's lazy-parseable elements do. Bodies are most of a file, so
 *    this alone is about twice as fast.
 * 2. *On-demand expansion.* A body's interior is parsed only when something asks, and per keystroke that is
 *    one body: the one under the caret.
 * 3. *Skipping the file parse.* Most keystrokes land inside a body and change nothing outside it. When that
 *    is provably so, the file is not reparsed AT ALL, and a keystroke costs one body rather than one file.
 *
 * Level 3 needs care, and the proof obligation is brace balance. Typing `}` inside a body ends it early and
 * changes everything after it, so before reusing anything the edited body's new text is re-lexed and checked:
 * the braces must balance, and must reach zero exactly at its end and nowhere before. Lexing rather than
 * scanning characters is not fussiness — a brace inside a string or a comment is not a brace, and a character
 * scan would count it.
 *
 * When the fast path applies, [fileTree] is left STALE and reparsed on first access. That is deliberate:
 * structure consumers (folding, breadcrumbs, the outline) do not run per keystroke, so they can pay once,
 * while the caret-local work that does run per keystroke pays nothing.
 *
 * Not thread-safe, and deliberately so: it models one editor buffer.
 */
class IncrementalKotlinParse(text: CharSequence, private val isScript: Boolean = false) {

    var text: CharSequence = text
        private set

    private var lazyTree: LightSyntaxTree = KotlinSyntax.parse(text, isScript, lazy = true)
    private var fileTreeStale = false

    /**
     * The file tree, with every function body collapsed.
     *
     * Reparsed on ACCESS when an edit was absorbed body-locally, rather than on the edit itself. Reading it
     * inside a tight typing loop therefore gives back everything the fast path saves.
     */
    val fileTree: LightSyntaxTree
        get() {
            if (fileTreeStale) {
                lazyTree = KotlinSyntax.parse(text, isScript, lazy = true)
                fileTreeStale = false
                fileReparses++
            }
            return lazyTree
        }

    /** The body the caret was last in, in CURRENT buffer coordinates. What the fast path steers by. */
    private var knownBody: IntRange? = null

    private val expanded = HashMap<Long, ExpandedBlock>()
    private val expandedText = HashMap<Long, String>()

    var blockCacheHits: Int = 0
        private set
    var blockParses: Int = 0
        private set

    /** Edits absorbed without reparsing the file. The point of the exercise, so it is worth being able to see. */
    var fileReparsesSkipped: Int = 0
        private set
    var fileReparses: Int = 1
        private set

    /**
     * Replace the buffer.
     *
     * Takes the whole new text rather than a splice, because an editor has it anyway and reconstructing it
     * from a range is a chance to be subtly wrong about something the caller already knows. The changed
     * region is recovered by comparing the two buffers from both ends, which costs the size of the change
     * rather than the size of the file.
     */
    fun edit(newText: CharSequence) {
        val previous = text
        text = newText

        val body = knownBody
        if (body != null && changeStaysInside(previous, newText, body)) {
            val grown = body.first..(body.last + (newText.length - previous.length))
            if (bodyStillBalanced(newText, grown)) {
                knownBody = grown
                fileTreeStale = true
                fileReparsesSkipped++
                return
            }
        }

        knownBody = null
        fileTreeStale = false
        fileReparses++
        lazyTree = KotlinSyntax.parse(newText, isScript, lazy = true)
    }

    /**
     * The parsed interior of the collapsed body containing [offset], or null when the offset is not inside
     * one.
     *
     * Note which body: lazy mode collapses the OUTERMOST one, so an `if` nested in a function is not its own
     * collapsed block. Asking from inside that `if` returns the whole function, expanded.
     */
    fun blockAt(offset: Int): ExpandedBlock? {
        // The fast path's whole value is answering without touching the file tree, which is stale by design.
        val known = knownBody
        val range = if (known != null && offset in known) {
            known
        } else {
            val tree = fileTree
            val block = innermostCollapsedBlock(tree, tree.getRoot(), offset) ?: return null
            (tree.getStartOffset(block) until tree.getEndOffset(block)).also { knownBody = it }
        }

        val start = range.first
        val end = range.last + 1
        val key = keyOf(start, end)
        val cached = expanded[key]
        if (cached != null && regionUnchanged(start, end)) {
            blockCacheHits++
            return cached
        }
        blockParses++
        val parsed = ExpandedBlock(KotlinSyntax.parseBlock(text.subSequence(start, end)), start)
        expanded[key] = parsed
        expandedText[key] = text.substring(start, end)
        return parsed
    }

    // -----------------------------------------------------------------------------------------------------

    /**
     * Did the edit touch only the INTERIOR of [body]?
     *
     * Found by matching the two buffers from both ends: everything before the first difference and after the
     * last one is untouched, so the change is the gap between them. The comparison is strict at the start and
     * inclusive at the end so that the body's own braces stay out of it, since editing one of those is
     * exactly the case the fast path must refuse.
     */
    private fun changeStaysInside(old: CharSequence, new: CharSequence, body: IntRange): Boolean {
        val limit = minOf(old.length, new.length)
        var prefix = 0
        while (prefix < limit && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        return prefix > body.first && old.length - suffix <= body.last
    }

    /**
     * Do the braces in [range] still balance, reaching zero exactly at its end?
     *
     * This is the proof obligation for reusing the surrounding structure, and it LEXES rather than scanning
     * characters, because a brace inside a string or a comment is not a brace.
     */
    private fun bodyStillBalanced(buffer: CharSequence, range: IntRange): Boolean {
        if (range.first < 0 || range.last >= buffer.length || range.isEmpty()) return false
        val slice = buffer.subSequence(range.first, range.last + 1)
        if (slice[0] != '{' || slice[slice.length - 1] != '}') return false

        val tokens = KotlinSyntax.lex(slice)
        var depth = 0
        for (i in 0 until tokens.tokenCount) {
            when (tokens.getTokenType(i)) {
                KtTokens.LBRACE -> depth++
                KtTokens.RBRACE -> {
                    depth--
                    if (depth < 0) return false
                    // Reaching zero before the end means the body now closes early, and everything after it
                    // belongs to whatever follows rather than to this body.
                    if (depth == 0 && tokens.getTokenEnd(i) != slice.length) return false
                }
            }
        }
        return depth == 0
    }

    /**
     * The deepest `BLOCK` covering [offset] that the lazy parse left unexpanded.
     *
     * "Unexpanded" is not "childless", which is the trap here. A collapsed marker still holds every TOKEN it
     * swallowed — `(BLOCK LBRACE val IDENTIFIER … RBRACE)` — and loses only the structure. A body the parser
     * did descend into has composite children. So the test is whether any child is a composite, and getting
     * it wrong means finding no bodies at all.
     */
    private fun innermostCollapsedBlock(tree: LightSyntaxTree, node: LightNode, offset: Int): LightNode? {
        if (offset < tree.getStartOffset(node) || offset > tree.getEndOffset(node)) return null
        for (child in tree.getChildren(node)) {
            innermostCollapsedBlock(tree, child, offset)?.let { return it }
        }
        if (tree.getType(node) != KtNodeTypes.BLOCK) return null
        val children = tree.getChildren(node)
        return if (children.isNotEmpty() && children.all { tree.isToken(it) }) node else null
    }

    /** Is the buffer's [start]..[end] region still the text the cached expansion was parsed from? */
    private fun regionUnchanged(start: Int, end: Int): Boolean {
        val was = expandedText[keyOf(start, end)] ?: return false
        if (was.length != end - start || end > text.length) return false
        for (i in was.indices) if (was[i] != text[start + i]) return false
        return true
    }

    private fun keyOf(start: Int, end: Int): Long =
        start.toLong() shl 32 or ((end - start).toLong() and 0xFFFFFFFFL)
}
