package dev.ide.ui.editor.core

/**
 * A persistent (immutable, structurally-shared) **rope** — the editor's text store.
 *
 * The old buffer kept the document as one `String` and rebuilt it on every edit (`buildString` over the
 * whole text), so a keystroke in an N-char file copied N chars. A rope splits the text into small leaves
 * held in a balanced binary tree: an edit rebuilds only the O(log N) nodes on the path to the cut, sharing
 * the rest with the previous version. So **a keystroke is O(leafSize + log N), independent of file size**,
 * and the previous revision stays valid for free (which is exactly what Compose snapshot state wants — the
 * session swaps in a new [Rope] and the old one is still a legal value to read).
 *
 * It implements [CharSequence], so the near-caret edit helpers (smart-insert, word boundaries, bracket
 * match) read straight off the rope with O(log N) random access and never force a full-string copy. The
 * full [toString] materialization is the one O(N) step that remains, paid lazily and only when a consumer
 * that speaks `String` (the host's `TextFieldValue`, analysis, completion) actually asks for it — see
 * [EditorDocument], which caches it per revision.
 *
 * Balance is the classic Boehm–Atkinson–Plass scheme: a rope of [depth] d is balanced iff its length is at
 * least the (d+2)-th Fibonacci number; [concat] rebalances (collecting and re-splaying the leaves) only
 * when a join would break that bound, which keeps depth ≈ 1.44·log₂(length) at all times.
 */
internal sealed class Rope : CharSequence {
    abstract override val length: Int

    /** Tree height (0 for a leaf). Stored on branches so balance checks and [get] stay O(1)/O(depth). */
    internal abstract val depth: Int

    /** O(depth) indexed char access. */
    abstract override fun get(index: Int): Char

    /** Append chars `[start, end)` of this rope to [sb] without building any intermediate rope/string. */
    internal abstract fun appendTo(sb: StringBuilder, start: Int, end: Int)

    /** Sub-rope of `[start, end)` — callers go through [sub], which clamps and short-circuits first. */
    internal abstract fun doSub(start: Int, end: Int): Rope

    /** A balanced sub-rope covering `[start, end)` (clamped). Shares nodes with the receiver. */
    fun sub(start: Int, end: Int): Rope {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(s, length)
        if (s == 0 && e == length) return this
        if (s == e) return EMPTY
        return doSub(s, e)
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        sub(startIndex, endIndex)

    /** Materialize `[start, end)` into a `String` — O(end - start + log N). */
    fun substring(start: Int, end: Int): String {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(s, length)
        if (s == e) return ""
        return StringBuilder(e - s).also { appendTo(it, s, e) }.toString()
    }

    /** Replace `[start, end)` with [insertion]; O(log N + leafSize) — the edit hot path. */
    fun replace(start: Int, end: Int, insertion: Rope): Rope {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(s, length)
        return concat(concat(sub(0, s), insertion), sub(e, length))
    }

    fun replace(start: Int, end: Int, insertion: String): Rope = replace(start, end, of(insertion))

    final override fun toString(): String =
        if (isEmpty()) "" else StringBuilder(length).also { appendTo(it, 0, length) }.toString()

    /** True when this rope satisfies the Fibonacci balance bound (length ≥ F(depth + 2)). */
    internal val isBalanced: Boolean
        get() {
            val d = depth
            return d + 2 >= FIB.size || length >= FIB[d + 2]
        }

    companion object {
        /**
         * Max chars per leaf. An edit copies at most one leaf (split + merge at the cut), so this bounds the
         * per-keystroke char work; small enough that edits stay cheap, large enough to keep the node count
         * (and so traversal/`charAt` cost) low. 512 is the usual sweet spot.
         */
        internal const val MAX_LEAF = 512

        val EMPTY: Rope = Leaf("")

        fun of(text: String): Rope {
            if (text.isEmpty()) return EMPTY
            if (text.length <= MAX_LEAF) return Leaf(text)
            val leaves = ArrayList<Leaf>((text.length + MAX_LEAF - 1) / MAX_LEAF)
            var i = 0
            while (i < text.length) {
                val j = minOf(i + MAX_LEAF, text.length)
                leaves.add(Leaf(text.substring(i, j)))
                i = j
            }
            return buildBalanced(leaves, 0, leaves.size)
        }

        /** Join two ropes, merging tiny adjacent leaves and rebalancing only if the join breaks the bound. */
        fun concat(left: Rope, right: Rope): Rope {
            if (left.length == 0) return right
            if (right.length == 0) return left
            if (left is Leaf && right is Leaf && left.length + right.length <= MAX_LEAF) {
                return Leaf(left.text + right.text)
            }
            // Spine merge: appending a small leaf folds it into the neighbour's edge leaf rather than hanging
            // a new node off the spine. This is what keeps repeated single-char typing from fragmenting the
            // tree into a chain of 1-char leaves (which would balloon the node count and force rebalances).
            if (left is Branch && right is Leaf) {
                val lr = left.right
                if (lr is Leaf && lr.length + right.length <= MAX_LEAF) {
                    return concat(left.left, Leaf(lr.text + right.text))
                }
            }
            if (right is Branch && left is Leaf) {
                val rl = right.left
                if (rl is Leaf && rl.length + left.length <= MAX_LEAF) {
                    return concat(Leaf(left.text + rl.text), right.right)
                }
            }
            val node = Branch(left, right)
            return if (node.isBalanced) node else rebalance(node)
        }

        // Fibonacci numbers F(0)=0, F(1)=1, …; index n holds F(n). 90 entries cover any realistic length
        // (F(90) ≈ 2.9e18 > Long range of chars), past which a rope is simply treated as unbalanced.
        private val FIB: LongArray = LongArray(91).also { f ->
            f[1] = 1
            for (i in 2 until f.size) f[i] = f[i - 1] + f[i - 2]
        }

        private fun rebalance(rope: Rope): Rope {
            val leaves = ArrayList<Leaf>()
            collectLeaves(rope, leaves)
            return if (leaves.isEmpty()) EMPTY else buildBalanced(leaves, 0, leaves.size)
        }

        /** In-order leaf walk that coalesces small neighbours into ≤[MAX_LEAF] chunks (defragments on rebuild). */
        private fun collectLeaves(rope: Rope, out: ArrayList<Leaf>) {
            when (rope) {
                is Leaf -> {
                    if (rope.text.isEmpty()) return
                    val last = if (out.isEmpty()) null else out[out.size - 1]
                    if (last != null && last.text.length + rope.text.length <= MAX_LEAF) {
                        out[out.size - 1] = Leaf(last.text + rope.text)
                    } else {
                        out.add(rope)
                    }
                }

                is Branch -> {
                    collectLeaves(rope.left, out)
                    collectLeaves(rope.right, out)
                }
            }
        }

        /** Build a height-balanced tree from a contiguous leaf range by divide-and-conquer. */
        private fun buildBalanced(leaves: List<Leaf>, lo: Int, hi: Int): Rope =
            when (val n = hi - lo) {
                0 -> EMPTY
                1 -> leaves[lo]
                else -> {
                    val mid = lo + n / 2
                    Branch(buildBalanced(leaves, lo, mid), buildBalanced(leaves, mid, hi))
                }
            }
    }
}

/** A contiguous run of text — the rope's payload lives only here. */
private class Leaf(val text: String) : Rope() {
    override val length: Int get() = text.length
    override val depth: Int get() = 0
    override fun get(index: Int): Char = text[index]

    override fun appendTo(sb: StringBuilder, start: Int, end: Int) {
        sb.append(text, start, end)
    }

    override fun doSub(start: Int, end: Int): Rope =
        if (start == 0 && end == text.length) this else Leaf(text.substring(start, end))
}

/** An internal node — splits the index space at [left]'s length; holds no text of its own. */
private class Branch(val left: Rope, val right: Rope) : Rope() {
    override val length: Int = left.length + right.length
    override val depth: Int = 1 + maxOf(left.depth, right.depth)

    override fun get(index: Int): Char {
        val ll = left.length
        return if (index < ll) left[index] else right[index - ll]
    }

    override fun appendTo(sb: StringBuilder, start: Int, end: Int) {
        val ll = left.length
        if (start < ll) left.appendTo(sb, start, if (end < ll) end else ll)
        if (end > ll) right.appendTo(sb, if (start > ll) start - ll else 0, end - ll)
    }

    override fun doSub(start: Int, end: Int): Rope {
        val ll = left.length
        return when {
            end <= ll -> left.sub(start, end)
            start >= ll -> right.sub(start - ll, end - ll)
            else -> Rope.concat(left.sub(start, ll), right.sub(0, end - ll))
        }
    }
}
