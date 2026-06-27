package dev.ide.lang.jdt.completion

import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.dom.TextRange
import dev.ide.lang.jdt.JdtSourceAnalyzer
import org.eclipse.jdt.core.compiler.IProblem

/** The marker spliced at the caret. A valid identifier, unlikely to collide with real code. */
internal const val COMPLETION_MARKER = "__codeassist_completion__"

/**
 * Completion driven by the custom name environment (model + in-memory overlay + jars + jrt) via ecj's
 * internal compiler — no disk flush, true working copies. Splices a marker at the caret (verbatim and
 * `;`-terminated for statement position), resolves the focal unit, then
 * [ContextAnalyzer] → [CandidateCollector] → [CompletionRanker].
 */
class JdtCompletion(private val analyzer: JdtSourceAnalyzer) : CompletionContributor {

    override val id = "jdt.completion"

    private val resolver = JdtResolver(
        analyzer.completionSourceRoots, analyzer.classpathJarPaths, analyzer.jdkHome, analyzer.complianceLevel,
        indexProvider = { analyzer.indexService },
    )

    /** Release the resolver's shared environment cache (open library-jar handles). */
    fun dispose() = resolver.dispose()

    /**
     * The focal unit's binding-level problems, resolved over the same cached in-memory environment as
     * completion (no disk-environment scan, no shadow-file move). The analyzer maps these to neutral
     * diagnostics; this just exposes the resolver's diagnose so the analyzer need not hold one itself.
     */
    fun resolveProblems(focalFqcn: String, text: String, overlay: Map<String, CharArray>, options: Map<String, String>): Array<out IProblem> =
        resolver.diagnose(focalFqcn, text, overlay, options)

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val text = params.document.text.toString()
        val offset = params.offset.coerceIn(0, text.length)
        val prefix = identifierPrefix(text, offset)
        val replaceStart = offset - prefix.length
        val replacementRange = TextRange(replaceStart, offset)

        val focalFqcn = analyzer.fqcnFor(params.document.file)
        val overlay = analyzer.overlayProvider()
        val index = analyzer.indexService
        // A method completion appends `()` — unless the caret already sits in front of an argument list,
        // in which case the result would be `foo()()`. Decided once, from the live text, for every candidate.
        val appendCallParens = !alreadyFollowedByArgList(text, offset)

        // One pipeline: resolve → analyze the context → collect candidates (in-scope bindings AND the
        // index, chosen by the context kind) → rank. Several ways to splice the marker are tried, keeping
        // the richest result and stopping early on a definitive one. The shared replacement range is the
        // caret identifier. See [spliceVariants] for why more than one form is needed (lambdas break ecj's
        // statement recovery, so the marker must land in already-valid syntax).
        var best: List<CompletionItem> = emptyList()
        var bestRank = -1
        for (spliced in spliceVariants(text, replaceStart, offset)) {
            val cud = resolver.resolve(focalFqcn, spliced, overlay) ?: continue
            val ctx = ContextAnalyzer.analyze(cud, replaceStart, prefix)
            if (ctx.kind == CompletionKind.NONE) continue
            val importOffset = importOffset(ctx.unit.importAnchorEnd, text)
            var items = CompletionRanker.rank(CandidateCollector.collect(ctx, index, importOffset, appendCallParens, analyzer.sourceMethodResolver), ctx)
            // Postfix templates (expr.sout / expr.for / expr.var …) at a member-access position, mixed into
            // the same popup. They rewrite the whole `receiver.key` and step through tab stops on accept.
            if (ctx.kind == CompletionKind.MEMBER_ACCESS) {
                val postfix = PostfixTemplates.itemsFor(
                    text, replaceStart, offset, prefix, ctx.qualifierType, ctx.staticQualifier, importOffset,
                )
                if (postfix.isNotEmpty()) items = items + postfix
            }
            // Live templates (sout / psvm / fori …) at a name/statement position — no receiver needed.
            if (ctx.kind == CompletionKind.NAME_REFERENCE) {
                val live = LiveTemplates.itemsFor(prefix)
                if (live.isNotEmpty()) items = items + live
            }
            val rank = kindRank(ctx.kind)
            // Prefer a result that actually produced candidates: an empty higher-rank context (e.g. a
            // recovery mis-parse of `recv.x` as a package-qualified type → empty PACKAGE_REFERENCE) must not
            // shadow a non-empty one (the real MEMBER_ACCESS from the call-form splice). Among non-empty
            // results, the higher kind rank wins.
            if (items.isNotEmpty() && (best.isEmpty() || rank > bestRank)) { best = items; bestRank = rank }
            if (rank == 2 && items.isNotEmpty()) break // a resolved member/package/import context is definitive
        }
        result.addAllElements(best)
        result.setReplacementRange(replacementRange)
    }

    private fun kindRank(kind: CompletionKind) = when (kind) {
        CompletionKind.MEMBER_ACCESS, CompletionKind.PACKAGE_REFERENCE, CompletionKind.IMPORT_REFERENCE, CompletionKind.TYPE_REFERENCE -> 2
        CompletionKind.NAME_REFERENCE -> 1
        CompletionKind.NONE -> 0
    }

    /** Line after the last import/package decl (its AST end), where an auto-import is inserted; else top. */
    private fun importOffset(anchorEnd: Int, text: String): Int {
        if (anchorEnd in 0 until text.length) {
            val nl = text.indexOf('\n', anchorEnd)
            return if (nl < 0) text.length else nl + 1
        }
        return 0
    }
}

/**
 * The marker-splice forms tried, in priority order. The completion technique inserts a marker identifier
 * at the caret and re-resolves; the AST around it gives the context. The catch: a lambda anywhere in a
 * statement that ecj must *recover* (illegal or unterminated) makes ecj discard the whole statement, so
 * the marker — and the receiver's resolved type — vanish. The marker is therefore landed in syntax that is
 * already valid, three ways, cheapest/most-common first (the loop stops at the first definitive result):
 *
 *  1. Bare `recv.__m__` (and `;`-terminated) keeping the trailing text — what works for the vast majority:
 *     simple receivers, names, arguments, with ecj's recovery filling small gaps.
 *  2. After a `.`, the call form `recv.__m__()` — a bare field-access statement is illegal (JLS 14.8), but
 *     a method call is a legal statement-expression, so it parses with a lambda in the receiver.
 *  3. A balanced fallback: drop the trailing text and synthesise a bracket-balanced, `;`-terminated tail
 *     (see [balancedTail]). This rescues an *unterminated* statement around a lambda — e.g. the caret in
 *     `…map(x -> 1).collect(Colle|)` with no closing `;` — which otherwise needs recovery and is dropped.
 */
private fun spliceVariants(text: String, replaceStart: Int, offset: Int): List<String> {
    val head = text.substring(0, replaceStart)
    val tail = text.substring(offset)
    val afterDot = precededByDot(text, replaceStart)
    val token = if (afterDot) "$COMPLETION_MARKER()" else COMPLETION_MARKER
    return buildList {
        add(head + COMPLETION_MARKER + tail)
        add(head + COMPLETION_MARKER + ";" + tail)
        if (afterDot) {
            add(head + token + tail)
            add(head + token + ";" + tail)
        }
        add(head + token + balancedTail(head))
    }
}

/** True when completing a member after a `.` — the first non-whitespace char before [start] is a dot. */
private fun precededByDot(text: String, start: Int): Boolean {
    var i = start - 1
    while (i >= 0 && text[i].isWhitespace()) i--
    return i >= 0 && text[i] == '.'
}

/**
 * Closers that turn [head] (source up to, but not including, the marker) into a balanced, terminated unit:
 * the open `(`/`[` are closed in order, the enclosing statement is `;`-terminated before the first `}`, and
 * the enclosing `{` blocks are closed. Brackets inside comments/strings/chars are ignored. Lets the marker
 * be parsed without statement recovery even when a lambda sits in the same (otherwise unterminated) statement.
 */
private fun balancedTail(head: String): String {
    val stack = ArrayDeque<Char>()
    var i = 0
    val n = head.length
    while (i < n) {
        val c = head[i]
        when {
            c == '/' && i + 1 < n && head[i + 1] == '/' -> { i += 2; while (i < n && head[i] != '\n') i++ }
            c == '/' && i + 1 < n && head[i + 1] == '*' -> { i += 2; while (i + 1 < n && !(head[i] == '*' && head[i + 1] == '/')) i++; i += 2 }
            c == '"' -> { i++; while (i < n && head[i] != '"') { if (head[i] == '\\') i++; i++ }; i++ }
            c == '\'' -> { i++; while (i < n && head[i] != '\'') { if (head[i] == '\\') i++; i++ }; i++ }
            c == '(' || c == '[' || c == '{' -> { stack.addLast(c); i++ }
            c == ')' || c == ']' || c == '}' -> { stack.removeLastOrNull(); i++ }
            else -> i++
        }
    }
    val sb = StringBuilder()
    var terminated = false
    for (b in stack.reversed()) when (b) {
        '(' -> sb.append(')')
        '[' -> sb.append(']')
        '{' -> { if (!terminated) { sb.append(';'); terminated = true }; sb.append('}') }
    }
    return sb.toString()
}

private fun identifierPrefix(text: String, offset: Int): String {
    var i = offset
    while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_' || text[i - 1] == '$')) i--
    return text.substring(i, offset)
}

/** True if the next non-blank char after the caret (on this line) is `(` — an argument list already exists. */
private fun alreadyFollowedByArgList(text: String, offset: Int): Boolean {
    var i = offset
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    return i < text.length && text[i] == '('
}
