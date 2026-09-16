package dev.ide.kotlin.syntax.parsing

import dev.ide.kotlin.syntax.lexer.KotlinLexer
import dev.ide.kotlin.syntax.lexer.KtTokens
import dev.ide.kotlin.syntax.tree.AstNode
import dev.ide.kotlin.syntax.tree.CompositeNode
import dev.ide.kotlin.syntax.tree.IElementType
import dev.ide.kotlin.syntax.tree.LeafNode
import dev.ide.kotlin.syntax.tree.TokenType

/**
 * The [SyntaxTreeBuilder] implementation: lex once, record what the parser marks, then build the tree.
 *
 * **Events, not nodes.** The parser is speculative — it marks, parses, and frequently rewinds — so nothing is
 * allocated as a node until the parse is over. What the parser does is recorded as a doubly-linked list of
 * events, and `rollbackTo` truncates that list. The linked list (rather than an array with indices, which is
 * what the platform uses) is deliberate: `precede` and `doneBefore` INSERT events in the middle, and with
 * indices every marker held by the parser would need its stored position fixed up. Here insertion is a
 * pointer swap and nothing else moves.
 *
 * **Every token reaches the tree.** The parser only ever sees non-trivia tokens, but the builder keeps the
 * raw stream and re-attaches whitespace and comments while building, so the tree still covers the file
 * exactly. Leading and trailing trivia bind OUTSIDE an element, which is the platform's default and the
 * reason a declaration's range starts at its first keyword rather than at the blank line above it.
 */
class SyntaxTreeBuilderImpl(private val text: CharSequence) : SyntaxTreeBuilder {

    private val tokens: List<KotlinLexer.Token> = KotlinLexer(text).tokenize()

    /** Index into [tokens] of the current non-trivia token, or [tokens].size at end of input. */
    private var rawIndex: Int = 0

    /** Index just past the last non-trivia token consumed, so a done marker can trim trailing trivia. */
    private var consumedEnd: Int = 0

    /** Soft keywords the grammar promoted; the tree uses these in place of the lexer's IDENTIFIER. */
    private val remapped = HashMap<Int, IElementType>()

    private val newlinesEnabled = ArrayList<Boolean>().apply { add(true) }
    private val joiningEnabled = ArrayList<Boolean>().apply { add(true) }

    // The event list. Sentinels keep insertion and truncation free of null checks.
    private val head = Event()
    private val tail = Event()

    init {
        head.next = tail
        tail.prev = head
        rawIndex = skipTrivia(0)
        consumedEnd = rawIndex
    }

    // -----------------------------------------------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------------------------------------------

    private open class Event {
        var prev: Event? = null
        var next: Event? = null
    }

    private class StartEvent(val marker: MarkerImpl) : Event()
    private class DoneEvent(val marker: MarkerImpl) : Event()
    private class ErrorEvent(val message: String, val at: Int) : Event()

    private fun insertBefore(node: Event, at: Event) {
        val before = at.prev!!
        before.next = node
        node.prev = before
        node.next = at
        at.prev = node
    }

    private fun append(node: Event) = insertBefore(node, tail)

    private inner class MarkerImpl(val startRaw: Int) : SyntaxTreeBuilder.Marker {
        var type: IElementType? = null
        var errorMessage: String? = null
        var endRaw: Int = startRaw
        var dropped = false
        var collapsed = false
        lateinit var startEvent: StartEvent
        var doneEvent: DoneEvent? = null

        override fun done(type: IElementType) {
            this.type = type
            this.endRaw = trimTrailingTrivia(consumedEnd, startRaw)
            val event = DoneEvent(this)
            doneEvent = event
            append(event)
        }

        override fun doneBefore(type: IElementType, before: SyntaxTreeBuilder.Marker) {
            before as MarkerImpl
            this.type = type
            this.endRaw = trimTrailingTrivia(before.startRaw, startRaw)
            val event = DoneEvent(this)
            doneEvent = event
            insertBefore(event, before.startEvent)
        }

        override fun doneBefore(type: IElementType, before: SyntaxTreeBuilder.Marker, errorMessage: String) {
            before as MarkerImpl
            insertBefore(ErrorEvent(errorMessage, before.startRaw), before.startEvent)
            doneBefore(type, before)
        }

        override fun collapse(type: IElementType) {
            collapsed = true
            done(type)
        }

        override fun drop() {
            dropped = true
            // Unlink rather than flag, so a dropped marker costs nothing at build time.
            startEvent.prev!!.next = startEvent.next
            startEvent.next!!.prev = startEvent.prev
            doneEvent?.let {
                it.prev!!.next = it.next
                it.next!!.prev = it.prev
            }
        }

        override fun rollbackTo() {
            // Everything recorded since this marker opened is discarded, and the stream rewinds with it.
            val before = startEvent.prev!!
            before.next = tail
            tail.prev = before
            rawIndex = startRaw
            consumedEnd = startRaw
            // Soft keywords promoted during the abandoned attempt have to be demoted with it, or a `data`
            // that was speculatively read as a modifier would still say DATA_KEYWORD in the tree.
            remapped.keys.removeAll { it >= startRaw }
        }

        override fun precede(): SyntaxTreeBuilder.Marker {
            val marker = MarkerImpl(startRaw)
            val event = StartEvent(marker)
            marker.startEvent = event
            insertBefore(event, startEvent)
            return marker
        }

        override fun error(message: String) {
            this.errorMessage = message
            this.type = TokenType.ERROR_ELEMENT
            this.endRaw = trimTrailingTrivia(consumedEnd, startRaw)
            val event = DoneEvent(this)
            doneEvent = event
            append(event)
        }

        override fun errorBefore(message: String, before: SyntaxTreeBuilder.Marker) {
            before as MarkerImpl
            insertBefore(ErrorEvent(message, before.startRaw), before.startEvent)
            drop()
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // Token stream
    // -----------------------------------------------------------------------------------------------------

    override val tokenType: IElementType?
        get() {
            if (rawIndex >= tokens.size) return null
            val joined = joinedTokenType()
            return joined ?: remapped[rawIndex] ?: tokens[rawIndex].type
        }

    override val tokenText: String?
        get() {
            if (rawIndex >= tokens.size) return null
            val end = if (joinedTokenType() != null) tokens[stepRaw(rawIndex)].end else tokens[rawIndex].end
            return text.substring(tokens[rawIndex].start, end)
        }

    override val currentOffset: Int
        get() = if (rawIndex >= tokens.size) text.length else tokens[rawIndex].start

    override fun eof(): Boolean = rawIndex >= tokens.size

    override fun advanceLexer() {
        if (rawIndex >= tokens.size) return
        // A joined complex token is two raw tokens, so consuming it has to step over both.
        val step = if (joinedTokenType() != null) 2 else 1
        var next = rawIndex
        repeat(step) { next = stepRaw(next) }
        consumedEnd = next
        rawIndex = skipTrivia(next)
    }

    override fun mark(): SyntaxTreeBuilder.Marker {
        val marker = MarkerImpl(rawIndex)
        val event = StartEvent(marker)
        marker.startEvent = event
        append(event)
        return marker
    }

    override fun error(message: String) {
        append(ErrorEvent(message, rawIndex))
    }

    override fun remapCurrentToken(type: IElementType) {
        if (rawIndex < tokens.size) remapped[rawIndex] = type
    }

    override fun rawLookup(steps: Int): IElementType? {
        val index = rawIndex + steps
        return if (index in tokens.indices) tokens[index].type else null
    }

    override fun lookAhead(steps: Int): IElementType? {
        var index = rawIndex
        repeat(steps) { index = skipTrivia(stepRaw(index)) }
        return if (index < tokens.size) tokens[index].type else null
    }

    // -----------------------------------------------------------------------------------------------------
    // Newline sensitivity
    // -----------------------------------------------------------------------------------------------------

    override fun newlineBeforeCurrentToken(): Boolean {
        if (!newlinesEnabled.last()) return false
        if (rawIndex >= tokens.size) return true
        var i = rawIndex - 1
        while (i >= 0 && isTrivia(tokens[i].type)) {
            if (tokens[i].type === TokenType.WHITE_SPACE) {
                val slice = text.substring(tokens[i].start, tokens[i].end)
                if (slice.contains('\n')) return true
            }
            i--
        }
        return false
    }

    override fun disableNewlines() {
        newlinesEnabled.add(false)
    }

    override fun enableNewlines() {
        newlinesEnabled.add(true)
    }

    override fun restoreNewlinesState() {
        if (newlinesEnabled.size > 1) newlinesEnabled.removeAt(newlinesEnabled.size - 1)
    }

    override fun disableJoiningComplexTokens() {
        joiningEnabled.add(false)
    }

    override fun enableJoiningComplexTokens() {
        joiningEnabled.add(true)
    }

    override fun restoreJoiningComplexTokensState() {
        if (joiningEnabled.size > 1) joiningEnabled.removeAt(joiningEnabled.size - 1)
    }

    /**
     * `?.` and `?:`: two adjacent tokens the grammar wants as one operation.
     *
     * Adjacency is required, which is why this reads the RAW neighbour rather than the next non-trivia token.
     * These three are joined here rather than in the lexer for a reason that only shows up in types: `Foo?`
     * and `Foo?.()` share a prefix, and a lexer that committed to `?.` would make the nullable marker
     * unreachable. The grammar turns joining off while it reads a type, and back on for expressions.
     * (`!in`, `!is` and `as?` are different: adjacency is part of their spelling, so the lexer produces them.)
     */
    private fun joinedTokenType(): IElementType? {
        if (!joiningEnabled.last()) return null
        if (rawIndex + 1 >= tokens.size) return null
        val first = tokens[rawIndex]
        val second = tokens[rawIndex + 1]
        if (first.end != second.start) return null
        return when {
            first.type === KtTokens.QUEST && second.type === KtTokens.DOT -> KtTokens.SAFE_ACCESS
            first.type === KtTokens.QUEST && second.type === KtTokens.COLON -> KtTokens.ELVIS
            else -> null
        }
    }

    // -----------------------------------------------------------------------------------------------------
    // Tree building
    // -----------------------------------------------------------------------------------------------------

    override fun treeBuilt(rootType: IElementType): AstNode {
        val root = CompositeNode(rootType, 0, text)
        val stack = ArrayList<CompositeNode>()
        stack.add(root)
        // Markers that were collapsed: their tokens become one leaf, so building skips their interior.
        var cursor = 0

        fun emitTokensUpTo(limit: Int) {
            while (cursor < limit && cursor < tokens.size) {
                val token = tokens[cursor]
                val type = remapped[cursor] ?: token.type
                stack.last().addChild(LeafNode(type, token.start, token.end, text))
                cursor++
            }
        }

        var event = head.next
        while (event !== tail && event != null) {
            when (event) {
                is StartEvent -> {
                    val marker = event.marker
                    emitTokensUpTo(marker.startRaw)
                    val offset = if (marker.startRaw < tokens.size) tokens[marker.startRaw].start else text.length
                    val node = CompositeNode(marker.type ?: TokenType.ERROR_ELEMENT, offset, text)
                    stack.last().addChild(node)
                    stack.add(node)
                }

                is DoneEvent -> {
                    val marker = event.marker
                    if (marker.collapsed) {
                        // Swallow the interior: everything from the marker's start to its end is one leaf.
                        cursor = marker.endRaw
                        stack.removeAt(stack.size - 1)
                        val start = if (marker.startRaw < tokens.size) tokens[marker.startRaw].start else text.length
                        val end = if (marker.endRaw > 0 && marker.endRaw <= tokens.size) {
                            tokens[marker.endRaw - 1].end
                        } else {
                            start
                        }
                        stack.last().replaceLastChild(LeafNode(marker.type!!, start, end, text))
                    } else {
                        emitTokensUpTo(marker.endRaw)
                        stack.removeAt(stack.size - 1)
                    }
                }

                is ErrorEvent -> {
                    emitTokensUpTo(event.at)
                    val offset = if (event.at < tokens.size) tokens[event.at].start else text.length
                    stack.last().addChild(CompositeNode(TokenType.ERROR_ELEMENT, offset, text))
                }
            }
            event = event.next
        }
        emitTokensUpTo(tokens.size)
        return root
    }

    // -----------------------------------------------------------------------------------------------------

    private fun isTrivia(type: IElementType): Boolean =
        type === TokenType.WHITE_SPACE || type in KtTokens.COMMENTS

    private fun skipTrivia(from: Int): Int {
        var i = from
        while (i < tokens.size && isTrivia(tokens[i].type)) i++
        return i
    }

    /** One raw token forward, without skipping what follows it. */
    private fun stepRaw(from: Int): Int = if (from < tokens.size) from + 1 else from

    /** Pull [from] back over trailing trivia, never past [notBefore], so an element ends on real syntax. */
    private fun trimTrailingTrivia(from: Int, notBefore: Int): Int {
        var i = minOf(from, tokens.size)
        while (i > notBefore && isTrivia(tokens[i - 1].type)) i--
        return i
    }
}

