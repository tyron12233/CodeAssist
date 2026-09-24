/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.kmp.tree

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.element.SyntaxTokenTypes
import com.intellij.platform.syntax.lexer.TokenList
import com.intellij.platform.syntax.parser.ProductionMarkerList
import com.intellij.platform.syntax.parser.SyntaxTreeBuilder
import com.intellij.platform.syntax.parser.prepareProduction
import org.jetbrains.annotations.TestOnly
import kotlin.jvm.JvmInline

/**
 * Identifier for a node within a [LightSyntaxTree], encoded in a single Int.
 *
 * Encoding:
 *  - Non-negative values `[0..markerCount-1]`: composite (START) marker index.
 *  - `markerCount` (i.e. [LightSyntaxTree.rootIndex]): the synthetic file root that wraps all
 *    top-level productions.
 *  - Negative values: token index encoded as `-(tokenIndex + 1)`. So `-1` is token at index 0.
 *  - `Int.MIN_VALUE`: invalid / "not computed" sentinel ([LightSyntaxTree.NO_NODE]).
 *
 * Since equality alone does not distinguish nodes from different trees. Higher-level abstractions
 * need to compare both the node and the owning tree.
 */
@JvmInline
value class LightNode(val index: Int)

/**
 * Flat-array AST representation produced from a [SyntaxTreeBuilder]. Holds the raw
 * [ProductionMarkerList] / [TokenList] plus precomputed lookup arrays that permit O(1)
 * access to parent, children, and node type,precomputed during construction (in
 * [buildLanguageSpecificLightTree]).
 *
 * Inspired by Kotlin's internal LightTree implementation, but with an eager lookup computation
 * and without using IJ platform dependencies, aside from new parsing infrastructure.
 */
class LightSyntaxTree(
    val tokens: TokenList,
    val source: CharSequence,
    /** For each START-marker index, the START-marker index of its parent (or [rootIndex] for top-level markers). */
    private val parentStartIndex: IntArray,
    /** For each token index, the START-marker index of its enclosing composite, or [rootIndex] for top-level tokens. */
    private val tokenParentStart: IntArray,
    /** Index used by [getRoot]: synthetic root wraps all top-level production markers. */
    val rootIndex: Int,
    /** [SyntaxElementType] used for the synthetic root node. */
    private val rootNodeType: SyntaxElementType,
    /**
     * Precomputed [SyntaxElementType] for each composite marker, indexed by START-marker index.
     * Entries for DONE markers and error markers are also populated during construction.
     */
    private val compositeTypes: Array<SyntaxElementType?>,
    /**
     * Precomputed children for each composite node, indexed by START-marker index.
     * Entry at [rootIndex] holds the synthetic root's children.
     * Each entry is a [ChildrenList] backed by an [IntArray] of child node indices.
     * Error markers and tokens map to [emptyList].
     */
    private val childrenByIndex: Array<List<LightNode>>,
    /**
     * Precomputed end offsets for composite nodes, indexed by START-marker index.
     * For normal composites this is the DONE marker's end offset; for error markers
     * it is the marker's own end offset.
     */
    private val compositeEndOffsets: IntArray,
    /**
     * Precomputed start offsets for composite nodes, indexed by START-marker index.
     */
    private val compositeStartOffsets: IntArray,
    /**
     * Builder of tree structure like `JavaLightTreeStructure`
     */
    buildLanguageSpecificTreeStructure: (LightSyntaxTree) -> Any
) {
    /**
     * Memoized language-specific structure adapter over this tree, used to build
     * `KtLightSourceElement`s for java-direct FIR declarations. One instance per tree, so all source
     * elements from the same file share a single tree structure (stable identity/equality).
     */
    val lightSourceTreeStructure: Any by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildLanguageSpecificTreeStructure(this)
    }

    fun getRoot(): LightNode = LightNode(rootIndex)

    private fun isSyntheticRoot(node: LightNode): Boolean = node.index == rootIndex

    fun isComposite(node: LightNode): Boolean = node.index in 0..rootIndex
    fun isToken(node: LightNode): Boolean = node.index < 0 && node.index != Int.MIN_VALUE

    fun getType(node: LightNode): SyntaxElementType {
        val idx = node.index
        if (idx >= 0) {
            // Composite (including synthetic root at idx == rootIndex)
            return if (idx == rootIndex) rootNodeType else compositeTypes[idx]!!
        }
        // Token
        val tokenIdx = -(idx + 1)
        return tokens.getTokenType(tokenIdx) ?: error("No token type at index $tokenIdx")
    }

    fun getStartOffset(node: LightNode): Int {
        val idx = node.index
        if (idx >= 0) return if (idx == rootIndex) 0 else compositeStartOffsets[idx]
        val tokenIdx = -(idx + 1)
        return tokens.getTokenStart(tokenIdx)
    }

    fun getEndOffset(node: LightNode): Int {
        val idx = node.index
        if (idx >= 0) return if (idx == rootIndex) source.length else compositeEndOffsets[idx]
        val tokenIdx = -(idx + 1)
        return tokens.getTokenEnd(tokenIdx)
    }

    fun getText(node: LightNode): CharSequence = source.subSequence(getStartOffset(node), getEndOffset(node))

    fun textEquals(node: LightNode, expected: String): Boolean {
        val start = getStartOffset(node)
        val end = getEndOffset(node)
        val length = end - start
        if (length != expected.length) return false
        for (i in 0 until length) {
            if (source[start + i] != expected[i]) return false
        }
        return true
    }

    /**
     * Returns the parent of [node], or `null` for the root.
     */
    fun getParent(node: LightNode): LightNode? {
        if (node.index == Int.MIN_VALUE) return null
        if (isSyntheticRoot(node)) return null
        return if (isComposite(node)) {
            LightNode(parentStartIndex[node.index])
        } else {
            val tokenIdx = -(node.index + 1)
            LightNode(tokenParentStart[tokenIdx])
        }
    }

    /**
     * Returns the immediate children of [node] in source order.
     */
    fun getChildren(node: LightNode): List<LightNode> {
        val idx = node.index
        if (idx < 0) return emptyList()
        return childrenByIndex[idx]
    }

    fun findChildByType(node: LightNode, type: SyntaxElementType): LightNode? {
        val children = getChildren(node)
        for (i in children.indices) {
            val child = children[i]
            if (getType(child) == type) return child
        }
        return null
    }

    // Index-returning twins of [getParent], [findChildByType] and [getChildren]. A `LightNode?` is a boxed
    // value, and so is every element read through a `List<LightNode>`, so the object-returning forms allocate
    // on each call; a tree walk (every parent chain, every child scan) made that most of what a pass allocated.
    // These answer [NO_INDEX] for "none" and never box.

    /** The parent's node index, or [NO_INDEX] for the root. */
    fun parentIndex(node: LightNode): Int {
        if (node.index == Int.MIN_VALUE || isSyntheticRoot(node)) return NO_INDEX
        return if (isComposite(node)) parentStartIndex[node.index] else tokenParentStart[-(node.index + 1)]
    }

    /** How many children [node] has. */
    fun childCount(node: LightNode): Int {
        val idx = node.index
        if (idx < 0) return 0
        // Branches rather than a `?.` chain: that one is typed `Int?` and boxes the answer.
        val list = childrenByIndex[idx]
        return if (list is ChildrenList) list.indices.size else list.size
    }

    /** The node index of [node]'s [i]th child. */
    fun childIndexAt(node: LightNode, i: Int): Int {
        val list = childrenByIndex[node.index]
        return if (list is ChildrenList) list.indices[i] else list[i].index
    }

    /** The node index of [node]'s first child of [type], or [NO_INDEX]. */
    fun childIndexByType(node: LightNode, type: SyntaxElementType): Int {
        val n = childCount(node)
        for (i in 0 until n) {
            val c = childIndexAt(node, i)
            if (getType(LightNode(c)) == type) return c
        }
        return NO_INDEX
    }

    fun getChildrenByType(node: LightNode, type: SyntaxElementType): List<LightNode> {
        val children = getChildren(node)
        if (children.isEmpty()) return emptyList()
        return children.filterTo(ArrayList(minOf(4, children.size))) { getType(it) == type }
    }

    fun hasChildOfType(node: LightNode, type: SyntaxElementType): Boolean = findChildByType(node, type) != null

    /**
     * This tree over [newSource], with [node]'s subtree replaced by [subNode]'s from [sub], a parse of
     * [node]'s new text in a context that builds it the way this file's parse would. [newSource] differs
     * from [source] only strictly inside [node].
     *
     * Markers outside [node] keep their types and their order, and every offset past it moves by the length
     * change; indices past it move by the change in marker and token counts. The caller proves the result is
     * the tree a parse of [newSource] builds (for a body the lazy grammar skipped by counting braces: that it
     * still closes exactly at its end, so nothing outside it reads differently). This checks only that the
     * shapes line up, and answers null when they do not.
     */
    fun withReplacedSubtree(node: LightNode, newSource: CharSequence, sub: LightSyntaxTree, subNode: LightNode): LightSyntaxTree? {
        val b = node.index
        if (b < 0 || b >= rootIndex || subNode.index < 0 || subNode.index >= sub.rootIndex) return null
        if (compositeTypes[b] != sub.compositeTypes[subNode.index]) return null
        val firstToken = edgeToken(node, first = true)
        val lastToken = edgeToken(node, first = false)
        val subFirst = sub.edgeToken(subNode, first = true)
        val subLast = sub.edgeToken(subNode, first = false)
        if (firstToken < 0 || lastToken < 0 || subFirst < 0 || subLast < 0) return null
        val bodyStart = compositeStartOffsets[b]
        val oldBodyEnd = compositeEndOffsets[b]
        val delta = newSource.length - source.length
        val subStart = sub.compositeStartOffsets[subNode.index]
        if (tokens.getTokenStart(firstToken) != bodyStart || tokens.getTokenEnd(lastToken) != oldBodyEnd) return null
        if (sub.tokens.getTokenStart(subFirst) != subStart ||
            sub.tokens.getTokenEnd(subLast) - subStart != oldBodyEnd + delta - bodyStart) return null

        // The markers inside [node]: every index from it to its last descendant's, in production order.
        var lastInner = b
        fun maxInner(i: Int) {
            val list = childrenByIndex[i]
            for (k in list.indices) {
                val c = list[k].index
                if (c >= 0) { if (c > lastInner) lastInner = c; maxInner(c) }
            }
        }
        maxInner(b)
        // The replacement's markers, numbered in pre-order right after [node].
        val subSlots = HashMap<Int, Int>()
        val subOrder = ArrayList<Int>()
        fun number(i: Int) {
            val list = sub.childrenByIndex[i]
            for (k in list.indices) {
                val c = list[k].index
                if (c >= 0) { subSlots[c] = b + 1 + subOrder.size; subOrder += c; number(c) }
            }
        }
        number(subNode.index)
        val markerShift = subOrder.size - (lastInner - b)
        fun marker(i: Int): Int = if (i > lastInner) i + markerShift else i

        val oldCount = tokens.tokenCount
        val m = subLast - subFirst + 1
        val tokenShift = m - (lastToken - firstToken + 1)
        fun token(t: Int): Int = if (t > lastToken) t + tokenShift else t
        fun subToken(t: Int): Int = firstToken + (t - subFirst)
        fun child(c: Int): Int = if (c >= 0) marker(c) else -(token(-(c + 1)) + 1)
        fun subChild(c: Int): Int = if (c >= 0) subSlots.getValue(c) else -(subToken(-(c + 1)) + 1)

        val newRoot = rootIndex + markerShift
        val parents = IntArray(newRoot)
        val types = arrayOfNulls<SyntaxElementType>(newRoot)
        val starts = IntArray(newRoot)
        val ends = IntArray(newRoot)
        @Suppress("UNCHECKED_CAST")
        val children = arrayOfNulls<List<LightNode>>(newRoot + 1) as Array<List<LightNode>>
        for (i in 0 until rootIndex) {
            if (i in (b + 1)..lastInner) continue
            val j = marker(i)
            parents[j] = marker(parentStartIndex[i])
            types[j] = compositeTypes[i]
            starts[j] = compositeStartOffsets[i].let { if (it >= oldBodyEnd) it + delta else it }
            ends[j] = compositeEndOffsets[i].let { if (it >= oldBodyEnd) it + delta else it }
            children[j] = remapped(childrenByIndex[i], lastInner, lastToken, ::child)
        }
        children[newRoot] = remapped(childrenByIndex[rootIndex], lastInner, lastToken, ::child)
        for (i in subOrder) {
            val j = subSlots.getValue(i)
            val p = sub.parentStartIndex[i]
            parents[j] = if (p == subNode.index) b else subSlots.getValue(p)
            types[j] = sub.compositeTypes[i]
            starts[j] = sub.compositeStartOffsets[i] - subStart + bodyStart
            ends[j] = sub.compositeEndOffsets[i] - subStart + bodyStart
            val list = sub.childrenByIndex[i]
            children[j] = if (list.isEmpty()) list else ChildrenList(IntArray(list.size) { subChild(list[it].index) })
        }
        val bodyChildren = sub.childrenByIndex[subNode.index]
        children[b] = ChildrenList(IntArray(bodyChildren.size) { subChild(bodyChildren[it].index) })

        val newCount = oldCount + tokenShift
        val tokenTypes = arrayOfNulls<SyntaxElementType>(newCount)
        val tokenStarts = IntArray(newCount)
        val tokenEnds = IntArray(newCount)
        val tokenParents = IntArray(newCount)
        for (t in 0 until firstToken) {
            tokenTypes[t] = tokens.getTokenType(t)
            tokenStarts[t] = tokens.getTokenStart(t)
            tokenEnds[t] = tokens.getTokenEnd(t)
            tokenParents[t] = marker(tokenParentStart[t])
        }
        for (st in subFirst..subLast) {
            val t = subToken(st)
            tokenTypes[t] = sub.tokens.getTokenType(st)
            tokenStarts[t] = sub.tokens.getTokenStart(st) - subStart + bodyStart
            tokenEnds[t] = sub.tokens.getTokenEnd(st) - subStart + bodyStart
            val p = sub.tokenParentStart[st]
            tokenParents[t] = if (p == subNode.index) b else subSlots[p] ?: newRoot
        }
        for (t in lastToken + 1 until oldCount) {
            val j = t + tokenShift
            tokenTypes[j] = tokens.getTokenType(t)
            tokenStarts[j] = tokens.getTokenStart(t) + delta
            tokenEnds[j] = tokens.getTokenEnd(t) + delta
            tokenParents[j] = marker(tokenParentStart[t])
        }

        return LightSyntaxTree(
            tokens = ArrayTokenList(newSource, tokenTypes, tokenStarts, tokenEnds),
            source = newSource,
            parentStartIndex = parents,
            tokenParentStart = tokenParents,
            rootIndex = newRoot,
            rootNodeType = rootNodeType,
            compositeTypes = types,
            childrenByIndex = children,
            compositeEndOffsets = ends,
            compositeStartOffsets = starts,
            buildLanguageSpecificTreeStructure = { it },
        )
    }

    /** [list] with [child] applied to each entry, or [list] itself when no entry lies past the replaced range. */
    private inline fun remapped(list: List<LightNode>, lastInner: Int, lastToken: Int, child: (Int) -> Int): List<LightNode> {
        if (list !is ChildrenList) return list
        val ints = list.indices
        var needs = false
        for (c in ints) if ((c >= 0 && c > lastInner) || (c < 0 && -(c + 1) > lastToken)) { needs = true; break }
        if (!needs) return list
        return ChildrenList(IntArray(ints.size) { child(ints[it]) })
    }

    /** The token index at [node]'s left or right edge, or -1 when there is none. */
    private fun edgeToken(node: LightNode, first: Boolean): Int {
        var n = node.index
        while (n >= 0) {
            val list = childrenByIndex[n]
            if (list.isEmpty()) return -1
            n = (if (first) list[0] else list[list.size - 1]).index
        }
        return if (n == Int.MIN_VALUE) -1 else -(n + 1)
    }

    companion object {
        /** Sentinel "no node" value for use as a not-computed marker in callers. */
        val NO_NODE: LightNode = LightNode(Int.MIN_VALUE)

        /** The "no node" answer of the index-returning lookups. */
        const val NO_INDEX: Int = Int.MIN_VALUE
    }
}

/** A [TokenList] over plain arrays: what [LightSyntaxTree.withRelexedBody] assembles. */
private class ArrayTokenList(
    override val tokenizedText: CharSequence,
    private val types: Array<SyntaxElementType?>,
    private val starts: IntArray,
    private val ends: IntArray,
) : TokenList {
    override val tokenCount: Int get() = types.size
    override fun getTokenStart(index: Int): Int = starts[index]
    override fun getTokenEnd(index: Int): Int = ends[index]
    override fun getTokenType(index: Int): SyntaxElementType? = if (index < 0 || index >= types.size) null else types[index]
    override fun slice(start: Int, end: Int): TokenList {
        val base = if (start < end) starts[start] else 0
        val limit = if (start < end) ends[end - 1] else 0
        return ArrayTokenList(
            tokenizedText.subSequence(base, limit),
            types.copyOfRange(start, end),
            IntArray(end - start) { starts[start + it] - base },
            IntArray(end - start) { ends[start + it] - base },
        )
    }
    override fun remap(index: Int, newValue: SyntaxElementType) {
        types[index] = newValue
    }
}

/**
 * Lightweight [List] view over a precomputed [IntArray] of child node indices.
 */
private class ChildrenList(val indices: IntArray) : AbstractList<LightNode>() {
    override val size: Int get() = indices.size
    override fun get(index: Int): LightNode = LightNode(indices[index])
}

/**
 * Builds a [LightSyntaxTree] from a populated [SyntaxTreeBuilder].
 *
 * Performs two passes over the production markers:
 * 1. Composite and token index computation (parent, done-index, type, offsets, token-to-parent mapping).
 * 2. Children list construction.
 */
fun buildLanguageSpecificLightTree(
    builder: SyntaxTreeBuilder,
    source: CharSequence,
    buildLanguageSpecificTreeStructure: (LightSyntaxTree) -> Any,
    isComment: (SyntaxElementType) -> Boolean,
): LightSyntaxTree {
    val productionMarkers = prepareProduction(builder).productionMarkers
    val tokens = builder.tokens
    val markerCount = productionMarkers.size
    if (markerCount == 0) error("No production markers")

    // The outermost marker pair is the `JAVA_FILE` node opened by [parse]; both passes skip it and attribute
    // its children to the tree root instead, so the root remains the compilation unit.
    val fileDoneIndex = markerCount - 1

    val parentStartIndex = IntArray(markerCount) { markerCount }
    val doneForStart = IntArray(markerCount) { -1 }
    val compositeTypes = arrayOfNulls<SyntaxElementType>(markerCount)
    val errorFlags = BooleanArray(markerCount)
    val compositeStartOffsets = IntArray(markerCount)
    val compositeEndOffsets = IntArray(markerCount)
    val tokenParentStart = IntArray(tokens.tokenCount) { markerCount }
    buildCompositeAndTokenIndices(
        productionMarkers, tokens, fileDoneIndex, markerCount,
        parentStartIndex, doneForStart, compositeTypes, errorFlags, compositeStartOffsets, compositeEndOffsets,
        tokenParentStart,
    )

    @Suppress("UNCHECKED_CAST")
    val childrenByIndex = arrayOfNulls<List<LightNode>>(markerCount + 1) as Array<List<LightNode>>
    val emptyChildren: List<LightNode> = emptyList()
    for (idx in 0..markerCount) {
        childrenByIndex[idx] = emptyChildren
    }
    buildChildrenIndex(
        productionMarkers, tokens, fileDoneIndex, markerCount,
        doneForStart, errorFlags, childrenByIndex, emptyChildren, isComment
    )

    val rootNodeType = productionMarkers.getMarker(fileDoneIndex).getNodeType()

    return LightSyntaxTree(
        tokens = tokens,
        source = source,
        parentStartIndex = parentStartIndex,
        tokenParentStart = tokenParentStart,
        rootIndex = markerCount,
        rootNodeType = rootNodeType,
        compositeTypes = compositeTypes,
        childrenByIndex = childrenByIndex,
        compositeEndOffsets = compositeEndOffsets,
        compositeStartOffsets = compositeStartOffsets,
        buildLanguageSpecificTreeStructure = buildLanguageSpecificTreeStructure,
    )
}

/**
 * Pass 1 of [buildLanguageSpecificLightTree]. Walks the production markers once, using a single
 * stack of pending START-marker indices to:
 * - determine each composite's parent and populate [doneForStart],
 * - extract node type / error flag / start / end offsets,
 * - assign each token to its innermost enclosing composite in [tokenParentStart].
 */
private fun buildCompositeAndTokenIndices(
    productionMarkers: ProductionMarkerList,
    tokens: TokenList,
    fileDoneIndex: Int,
    rootIndex: Int,
    parentStartIndex: IntArray,
    doneForStart: IntArray,
    compositeTypes: Array<SyntaxElementType?>,
    errorFlags: BooleanArray,
    compositeStartOffsets: IntArray,
    compositeEndOffsets: IntArray,
    tokenParentStart: IntArray,
) {
    var openStack = IntArray(64)
    var stackSize = 0
    fun push(v: Int) {
        if (stackSize >= openStack.size) {
            val grown = IntArray(openStack.size * 2)
            openStack.copyInto(grown)
            openStack = grown
        }
        openStack[stackSize++] = v
    }

    fun pop(): Int = openStack[--stackSize]
    fun peekOrRoot(): Int = if (stackSize == 0) rootIndex else openStack[stackSize - 1]

    var prevTokenIndex = 0
    fun assignTokens(upToExclusive: Int) {
        val parent = peekOrRoot()
        for (t in prevTokenIndex until upToExclusive) {
            tokens.getTokenType(t) ?: continue
            val s = tokens.getTokenStart(t)
            val e = tokens.getTokenEnd(t)
            if (s == e) continue
            tokenParentStart[t] = parent
        }
        prevTokenIndex = upToExclusive
    }

    for (i in 1 until fileDoneIndex) {
        val marker = productionMarkers.getMarker(i)
        when {
            productionMarkers.isDoneMarker(i) -> {
                assignTokens(marker.getEndTokenIndex())
                val startIdx = pop()
                doneForStart[startIdx] = i
                compositeEndOffsets[startIdx] = marker.getEndOffset()
            }
            marker.isErrorMarker() -> {
                assignTokens(marker.getStartTokenIndex())
                parentStartIndex[i] = peekOrRoot()
                compositeTypes[i] = marker.getNodeType()
                errorFlags[i] = true
                compositeStartOffsets[i] = marker.getStartOffset()
                compositeEndOffsets[i] = marker.getEndOffset()
            }
            else -> {
                // START marker
                assignTokens(marker.getStartTokenIndex())
                parentStartIndex[i] = peekOrRoot()
                compositeTypes[i] = marker.getNodeType()
                compositeStartOffsets[i] = marker.getStartOffset()
                push(i)
            }
        }
    }
    assignTokens(tokens.tokenCount)

    require(stackSize == 0) { "Unbalanced production markers: $stackSize unmatched START markers remain" }
}

/**
 * Pass 2 of [buildLanguageSpecificLightTree]: precompute children for each composite and the synthetic root.
 * Drops whitespace and bad-character tokens everywhere; drops comments only under the synthetic
 * root so declaration `DOC_COMMENT` children remain available for `isDeprecatedInJavaDoc`.
 */
private fun buildChildrenIndex(
    productionMarkers: ProductionMarkerList,
    tokens: TokenList,
    fileDoneIndex: Int,
    rootIndex: Int,
    doneForStart: IntArray,
    errorFlags: BooleanArray,
    childrenByIndex: Array<List<LightNode>>,
    emptyChildren: List<LightNode>,
    isComment: (SyntaxElementType) -> Boolean,
) {
    fun isIncludedToken(t: Int, isRoot: Boolean): Boolean {
        val type = tokens.getTokenType(t) ?: return false
        if (tokens.getTokenStart(t) == tokens.getTokenEnd(t)) return false
        if (type === SyntaxTokenTypes.WHITE_SPACE) return false
        if (type === SyntaxTokenTypes.BAD_CHARACTER) return false
        if (isRoot && isComment(type)) return false
        return true
    }

    fun addTokensInRange(childIndices: ArrayList<Int>, from: Int, to: Int, isRoot: Boolean) {
        for (t in from until to) {
            if (isIncludedToken(t, isRoot)) childIndices.add(-(t + 1))
        }
    }

    fun buildChildrenFor(startIdx: Int, doneIdx: Int, firstTokenIndex: Int, lastTokenIndex: Int, isRoot: Boolean = false) {
        val childIndices = ArrayList<Int>(8)

        var prevTokenIndex = firstTokenIndex
        var i = startIdx + 1
        while (i < doneIdx) {
            if (errorFlags[i]) {
                val errTokenStart = productionMarkers.getMarker(i).getStartTokenIndex()
                addTokensInRange(childIndices, prevTokenIndex, errTokenStart, isRoot)
                childIndices.add(i)
                prevTokenIndex = errTokenStart
                i++
            } else if (!productionMarkers.isDoneMarker(i)) {
                val childStartToken = productionMarkers.getMarker(i).getStartTokenIndex()
                addTokensInRange(childIndices, prevTokenIndex, childStartToken, isRoot)
                childIndices.add(i)
                val childDone = doneForStart[i]
                prevTokenIndex = productionMarkers.getMarker(childDone).getEndTokenIndex()
                i = childDone + 1
            } else {
                i++
            }
        }
        addTokensInRange(childIndices, prevTokenIndex, lastTokenIndex, isRoot)

        val slot = if (isRoot) rootIndex else startIdx
        childrenByIndex[slot] = if (childIndices.isEmpty()) emptyChildren
        else ChildrenList(childIndices.toIntArray())
    }

    // Build children for each non-error, non-DONE composite.
    for (i in 1 until fileDoneIndex) {
        if (productionMarkers.isDoneMarker(i) || errorFlags[i]) continue
        val doneIdx = doneForStart[i]
        val firstToken = productionMarkers.getMarker(i).getStartTokenIndex()
        val lastToken = productionMarkers.getMarker(doneIdx).getEndTokenIndex()
        buildChildrenFor(i, doneIdx, firstToken, lastToken)
    }

    // The `JAVA_FILE` children become the root's ones (routed to childrenByIndex[rootIndex]).
    buildChildrenFor(
        startIdx = 0, doneIdx = fileDoneIndex, firstTokenIndex = 0, lastTokenIndex = tokens.tokenCount, isRoot = true,
    )
}

/**
 * Convenience: pretty-prints the subtree rooted at [node] for debugging. Each line prints the
 * node type and (newline-escaped) text, indented by depth.
 */
@TestOnly
fun LightSyntaxTree.dump(node: LightNode = getRoot(), indent: String = ""): String {
    val sb = StringBuilder()
    sb.append(indent).append(getType(node)).append(": ").append(getText(node).toString().replace("\n", "\\n")).append("\n")
    for (child in getChildren(node)) {
        sb.append(dump(child, "$indent  "))
    }
    return sb.toString()
}
