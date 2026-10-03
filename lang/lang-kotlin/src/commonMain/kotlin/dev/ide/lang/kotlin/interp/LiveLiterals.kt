package dev.ide.lang.kotlin.interp

/**
 * Live literals: apply an edit that only changes the text of ONE literal (a number, string, char or boolean) to
 * an already-lowered preview program directly, without re-parsing, re-analysing or re-lowering the file.
 *
 * Changing `16.dp` to `24.dp` or `"Hello"` to `"Hello, world"` cannot change what any name resolves to, which
 * overload is picked, or the shape of the tree: the only thing that moves is the value of one [RNode.Const]. So
 * the edited constant is swapped in place and every other node, including every unaffected function instance,
 * is kept. The renderer's identity diff then re-runs only the edited function (and what depends on it), and the
 * edit skips the full lowering pipeline, which is what lets the preview follow the keyboard.
 *
 * The patch is refused (null), sending the caller down the full lowering path, whenever it can't PROVE the edit
 * is a literal edit:
 *  - the changed range must lie inside the source range of a constant that was lowered from a literal in the
 *    edited file, and the text there must still read back as exactly that constant's value (so a constant the
 *    lowering synthesized, like the `true`/`false` of a desugared `&&`, is never mistaken for a literal);
 *  - the new token text must be a complete, valid Kotlin literal of the SAME type (`16` → `16.5` changes Int to
 *    Double, which changes overload resolution, so it re-lowers);
 *  - a string fragment must not gain `"`, `\`, `$` or a line break (any of those changes the template's
 *    structure).
 *
 * Source spans are NOT rewritten: the patched constants keep their original span, and later nodes keep theirs,
 * so a [LiveLiteralState] tracks how much each edited literal grew or shrank and maps original spans to the
 * current text. Spans are only read for diagnostics at runtime, so the lag is harmless; the next full lowering
 * produces fresh ones.
 */
class LiveLiteralState(
    /** The buffer text [program] corresponds to (after any patches). */
    val text: String,
    /** The lowered program, `name/arity` to function. */
    val program: Map<String, ResolvedFunction>,
    /** Keys of [program] declared in the edited file: only their literals are patchable, since a function from
     *  another file carries spans into a different text. */
    val editableKeys: Set<String>,
    /** The current text length of each literal patched so far, keyed by its ORIGINAL span. */
    internal val lengths: Map<SourceSpan, Int> = emptyMap(),
) {
    /** Where an original span [s] sits in [text] now that earlier literals may have changed length. */
    internal fun currentRange(s: SourceSpan): IntRange {
        var shift = 0
        for ((span, len) in lengths) if (span.end <= s.start) shift += len - (span.end - span.start)
        val start = s.start + shift
        return start until start + (lengths[s] ?: (s.end - s.start))
    }
}

object LiveLiterals {

    /**
     * Apply [newText] to [state] as a single literal edit, or null when it isn't one (see [LiveLiterals]).
     * Returns the state for [newText]; the functions whose body changed are new instances in its program, every
     * other function is the same instance as in [state].
     */
    fun patch(state: LiveLiteralState, newText: String): LiveLiteralState? {
        val oldText = state.text
        if (oldText == newText) return null
        // The single changed region: [from, oldTo) in the old text became [from, newTo) in the new one.
        val max = minOf(oldText.length, newText.length)
        var from = 0
        while (from < max && oldText[from] == newText[from]) from++
        var tail = 0
        while (tail < max - from && oldText[oldText.length - 1 - tail] == newText[newText.length - 1 - tail]) tail++
        val oldTo = oldText.length - tail
        val delta = newText.length - oldText.length

        // The literal the edit falls inside. Desugaring can repeat a constant (one source literal, several
        // nodes), so collect by span; two different spans both claiming the edit means it isn't a literal edit.
        var target: Target? = null
        for (key in state.editableKeys) {
            val fn = state.program[key] ?: continue
            fn.forEachConst { c, inTemplate ->
                if (c.value == null || c.value !is Number && c.value !is String && c.value !is Char && c.value !is Boolean) return@forEachConst
                val range = state.currentRange(c.source)
                if (from < range.first || oldTo > range.last + 1) return@forEachConst
                val t = target
                if (t != null) {
                    if (t.span != c.source) target = Target.AMBIGUOUS
                    return@forEachConst
                }
                target = Target(c.source, c.value, inTemplate, range)
            }
        }
        val t = target?.takeIf { it !== Target.AMBIGUOUS } ?: return null
        val oldToken = oldText.substring(t.range.first, t.range.last + 1)
        val newToken = newText.substring(t.range.first, t.range.last + 1 + delta)
        // Proof the constant really came from this literal: its source text reads back as its value.
        if (literalValue(oldToken, t.value, t.inTemplate) != t.value) return null
        val newValue = literalValue(newToken, t.value, t.inTemplate) ?: return null
        // `"$name"` + a letter typed right after the name extends the NAME (`$namex`), not the fragment after it.
        if (t.inTemplate && from == t.range.first && from > 0 && isIdentifierChar(oldText[from - 1])) return null

        val program = LinkedHashMap<String, ResolvedFunction>(state.program)
        for (key in state.editableKeys) {
            val fn = state.program[key] ?: continue
            val patched = fn.replaceConst(t.span, t.value, newValue)
            if (patched !== fn) program[key] = patched
        }
        return LiveLiteralState(newText, program, state.editableKeys, state.lengths + (t.span to newToken.length))
    }

    private class Target(val span: SourceSpan, val value: Any, val inTemplate: Boolean, val range: IntRange) {
        companion object {
            val AMBIGUOUS = Target(SourceSpan(-1, -1), Unit, false, IntRange.EMPTY)
        }
    }

    /**
     * The value of literal source [token], read as the same kind of literal as [like] (whose runtime class it must
     * keep), or null when [token] isn't a complete literal of that kind. [inTemplate] reads a string as a raw
     * fragment of a template (no quotes) rather than a whole quoted literal.
     */
    internal fun literalValue(token: String, like: Any, inTemplate: Boolean): Any? = when (like) {
        is Boolean -> when (token) { "true" -> true; "false" -> false; else -> null }
        is String -> if (inTemplate) token.takeIf { isPlainFragment(it) } else quotedString(token)
        is Char -> charLiteral(token)
        is Number -> numberLiteral(token)?.takeIf { it::class == like::class }
        else -> null
    }

    private fun numberLiteral(token: String): Any? {
        if (!NUMBER.matches(token)) return null
        return parseKotlinNumberLiteral(token)?.first
    }

    // A complete Kotlin numeric literal, no sign (a leading `-` is a separate operator in the tree): decimal
    // (no leading zeros), hex or binary integers with the `u`/`L` suffixes, and floating-point literals.
    private val NUMBER = Regex(
        "(?:" +
            "(?:0|[1-9](?:[0-9_]*[0-9])?)(?:[uU]?L|[uU])?" +
            "|0[xX][0-9a-fA-F](?:[0-9a-fA-F_]*[0-9a-fA-F])?(?:[uU]?L|[uU])?" +
            "|0[bB][01](?:[01_]*[01])?(?:[uU]?L|[uU])?" +
            "|(?:[0-9](?:[0-9_]*[0-9])?)?\\.[0-9](?:[0-9_]*[0-9])?(?:[eE][+-]?[0-9](?:[0-9_]*[0-9])?)?[fF]?" +
            "|[0-9](?:[0-9_]*[0-9])?[eE][+-]?[0-9](?:[0-9_]*[0-9])?[fF]?" +
            "|[0-9](?:[0-9_]*[0-9])?[fF]" +
            ")"
    )

    /** A whole `"…"` / `"""…"""` literal with no escapes or templates (the only shape lowered to ONE constant). */
    private fun quotedString(token: String): String? {
        if (token.length >= 6 && token.startsWith("\"\"\"") && token.endsWith("\"\"\"")) {
            val inner = token.substring(3, token.length - 3)
            return inner.takeIf { '$' !in it && '"' !in it }
        }
        if (token.length < 2 || token[0] != '"' || token.last() != '"') return null
        val inner = token.substring(1, token.length - 1)
        return inner.takeIf { isPlainFragment(it) }
    }

    /** Text that reads as itself inside a string: nothing that would end it, escape, interpolate or break it. */
    private fun isPlainFragment(s: String): Boolean =
        s.none { it == '"' || it == '\\' || it == '$' || it == '\n' || it == '\r' }

    private fun charLiteral(token: String): Char? {
        if (token.length < 3 || token[0] != '\'' || token.last() != '\'') return null
        val body = token.substring(1, token.length - 1)
        if (body.length == 1) return body[0].takeIf { it != '\'' && it != '\\' && it != '\n' && it != '\r' }
        if (body[0] != '\\') return null
        if (body.length == 2) return when (body[1]) {
            't' -> '\t'; 'b' -> '\b'; 'n' -> '\n'; 'r' -> '\r'
            '\'' -> '\''; '"' -> '"'; '\\' -> '\\'; '$' -> '$'
            else -> null
        }
        if (body.length == 6 && body[1] == 'u') return body.substring(2).toIntOrNull(16)?.toChar()
        return null
    }

    private fun isIdentifierChar(c: Char) = c == '_' || c.isLetterOrDigit()
}

/**
 * Parse a Kotlin numeric literal to its boxed value + type FQN. Handles hex (`0xFFD32F2F`, a `Color(Long)`
 * argument) / binary (`0b1010`) prefixes, digit separators (`1_000`), the `u`/`U` (unsigned) and `L` (long)
 * suffixes, and floats (`1.5`, `1e5`, `1.5f`). An integer literal that overflows `Int` widens to `Long`, matching
 * Kotlin, so a 32-bit ARGB hex like `0xFFD32F2F` types as `Long`. Null when unparseable.
 */
internal fun parseKotlinNumberLiteral(raw: String): Pair<Any, String>? {
    val t = raw.replace("_", "")
    val lower = t.lowercase()
    // Hex / binary INTEGER literal: radix-prefixed, so its a-f / e digits are NOT a float exponent/suffix.
    if (lower.startsWith("0x") || lower.startsWith("0b")) {
        val radix = if (lower[1] == 'x') 16 else 2
        var body = t.substring(2)
        val isLong = body.endsWith("L") || body.endsWith("l")
        if (isLong) body = body.dropLast(1)
        if (body.endsWith("u") || body.endsWith("U")) body = body.dropLast(1) // model UInt/ULong as Int/Long
        val asLong = body.toLongOrNull(radix) ?: body.toULongOrNull(radix)?.toLong() ?: return null
        return integerLiteralValue(asLong, isLong)
    }
    // Float / Double: a fractional point, a decimal exponent, or an f/F suffix.
    if ('.' in t || 'e' in lower || t.endsWith("f") || t.endsWith("F")) {
        return if (t.endsWith("f") || t.endsWith("F")) t.dropLast(1).toFloatOrNull()?.let { it to "kotlin.Float" }
        else t.toDoubleOrNull()?.let { it to "kotlin.Double" }
    }
    // Decimal integer.
    var body = t
    val isLong = body.endsWith("L") || body.endsWith("l")
    if (isLong) body = body.dropLast(1)
    if (body.endsWith("u") || body.endsWith("U")) body = body.dropLast(1)
    val asLong = body.toLongOrNull() ?: return null
    return integerLiteralValue(asLong, isLong)
}

/** An integer literal's boxed value + type: `Long` when an `L` suffix is present or the value doesn't fit `Int`
 *  (Kotlin's auto-widening), else `Int`. */
private fun integerLiteralValue(value: Long, isLong: Boolean): Pair<Any, String> =
    if (isLong || value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value to "kotlin.Long"
    else value.toInt() to "kotlin.Int"

/** Visit every [RNode.Const] in this function (body and parameter defaults); `inTemplate` is true for a
 *  fragment of a string template (a direct part of an [RNode.StringConcat]). */
private fun ResolvedFunction.forEachConst(visit: (RNode.Const, inTemplate: Boolean) -> Unit) {
    fun go(n: RNode, inTemplate: Boolean) {
        if (n is RNode.Const) { visit(n, inTemplate); return }
        val template = n is RNode.StringConcat
        n.children().forEach { go(it, template) }
        when (n) {
            is RNode.Lambda -> n.params.forEach { p -> p.default?.let { go(it, false) } }
            is RNode.ForEach -> n.loopVar.default?.let { go(it, false) }
            else -> {}
        }
    }
    params.forEach { p -> p.default?.let { go(it, false) } }
    go(body, false)
}

/** This function with every constant at [span] holding [old] replaced by one holding [new]; `this` when there is
 *  none. Unchanged subtrees keep their instances. */
private fun ResolvedFunction.replaceConst(span: SourceSpan, old: Any, new: Any): ResolvedFunction {
    val swap = ConstSwap(span, old, new)
    val newParams = swap.params(params)
    val newBody = swap.node(body)
    return if (newParams === params && newBody === body) this else copy(params = newParams, body = newBody)
}

private class ConstSwap(val span: SourceSpan, val old: Any, val new: Any) {

    fun node(n: RNode): RNode = when (n) {
        is RNode.Const -> if (n.source == span && n.value == old && n.value!!::class == old::class) n.copy(value = new) else n
        is RNode.Name, is RNode.This, is RNode.Break, is RNode.Continue, is RNode.Unsupported -> n
        is RNode.Call -> {
            val dr = opt(n.dispatchReceiver); val r = opt(n.receiver); val a = args(n.args)
            if (dr === n.dispatchReceiver && r === n.receiver && a === n.args) n
            else n.copy(dispatchReceiver = dr, receiver = r, args = a)
        }
        is RNode.PropertyGet -> opt(n.receiver).let { if (it === n.receiver) n else n.copy(receiver = it) }
        is RNode.PropertySet -> {
            val r = opt(n.receiver); val v = node(n.value)
            if (r === n.receiver && v === n.value) n else n.copy(receiver = r, value = v)
        }
        is RNode.If -> {
            val c = node(n.condition); val t = node(n.then); val o = opt(n.otherwise)
            if (c === n.condition && t === n.then && o === n.otherwise) n else n.copy(condition = c, then = t, otherwise = o)
        }
        is RNode.Block -> nodes(n.statements).let { if (it === n.statements) n else n.copy(statements = it) }
        is RNode.Lambda -> {
            val p = params(n.params); val b = node(n.body)
            if (p === n.params && b === n.body) n else n.copy(params = p, body = b)
        }
        is RNode.StringConcat -> nodes(n.parts).let { if (it === n.parts) n else n.copy(parts = it) }
        is RNode.NotNull -> node(n.value).let { if (it === n.value) n else n.copy(value = it) }
        is RNode.TypeCheck -> node(n.value).let { if (it === n.value) n else n.copy(value = it) }
        is RNode.Cast -> node(n.value).let { if (it === n.value) n else n.copy(value = it) }
        is RNode.ClassLiteral -> opt(n.receiver).let { if (it === n.receiver) n else n.copy(receiver = it) }
        is RNode.Try -> {
            val b = node(n.body)
            val cs = n.catches.map { c -> node(c.body).let { if (it === c.body) c else c.copy(body = it) } }
            val catches = if (cs.indices.all { cs[it] === n.catches[it] }) n.catches else cs
            val f = opt(n.finallyBlock)
            if (b === n.body && catches === n.catches && f === n.finallyBlock) n
            else n.copy(body = b, catches = catches, finallyBlock = f)
        }
        is RNode.LocalVar -> opt(n.initializer).let { if (it === n.initializer) n else n.copy(initializer = it) }
        is RNode.Assign -> {
            val t = node(n.target); val v = node(n.value)
            if (t === n.target && v === n.value) n else n.copy(target = t, value = v)
        }
        is RNode.Return -> opt(n.value).let { if (it === n.value) n else n.copy(value = it) }
        is RNode.Throw -> node(n.value).let { if (it === n.value) n else n.copy(value = it) }
        is RNode.While -> {
            val c = node(n.condition); val b = node(n.body)
            if (c === n.condition && b === n.body) n else n.copy(condition = c, body = b)
        }
        is RNode.ForEach -> {
            val lv = param(n.loopVar); val i = node(n.iterable); val b = node(n.body)
            if (lv === n.loopVar && i === n.iterable && b === n.body) n else n.copy(loopVar = lv, iterable = i, body = b)
        }
    }

    private fun opt(n: RNode?): RNode? = n?.let { node(it) }

    private fun nodes(xs: List<RNode>): List<RNode> {
        val mapped = xs.map { node(it) }
        return if (mapped.indices.all { mapped[it] === xs[it] }) xs else mapped
    }

    private fun args(xs: List<RArg>): List<RArg> {
        val mapped = xs.map { a -> node(a.value).let { if (it === a.value) a else a.copy(value = it) } }
        return if (mapped.indices.all { mapped[it] === xs[it] }) xs else mapped
    }

    private fun param(p: RParam): RParam = p.default?.let { d -> node(d).let { if (it === d) p else p.copy(default = it) } } ?: p

    fun params(xs: List<RParam>): List<RParam> {
        val mapped = xs.map { param(it) }
        return if (mapped.indices.all { mapped[it] === xs[it] }) xs else mapped
    }
}

/**
 * The program keys that must re-run after a live edit changed the functions [changed]: the changed functions
 * plus every function that can reach one, directly or transitively (a call to it, or a read of a changed
 * top-level property).
 *
 * The callers matter because a composable re-runs only when its caller does: an unchanged no-argument
 * `Screen()` would be skipped (its arguments didn't change), so a changed `Card()` it calls would never run
 * with its new body. Forcing the callers re-runs the path down to the edit and nothing else.
 *
 * Matching is by simple name, so an overload or a same-named member pulls in more than necessary, never less.
 * A source class whose members reach a changed name can't be followed into the composables that use the
 * class, so then every function is returned.
 */
fun liveEditDirtyClosure(
    program: Map<String, ResolvedFunction>,
    classes: List<ResolvedClass>,
    changed: Set<String>,
): Set<String> {
    if (changed.isEmpty()) return emptySet()
    val refs = program.mapValues { (_, fn) -> referencedNames(listOfNotNull(fn.body) + fn.params.mapNotNull { it.default }) }
    val dirty = HashSet(changed)
    val dirtyNames = changed.mapTo(HashSet()) { it.substringBeforeLast('/') }
    var grew = true
    while (grew) {
        grew = false
        for ((key, names) in refs) {
            if (key in dirty || names.none { it in dirtyNames }) continue
            dirty += key
            dirtyNames += key.substringBeforeLast('/')
            grew = true
        }
    }
    val classReach = classes.any { c ->
        val roots = c.initSteps + c.primaryParams.mapNotNull { it.default } +
            c.methods.values.flatMap { m -> listOf(m.body) + m.params.mapNotNull { it.default } } +
            c.enumEntries.flatMap { e -> e.args.map { it.value } } +
            (c.superCall?.args?.map { it.value } ?: emptyList()) +
            c.secondaryCtors.flatMap { s -> listOf(s.body) + s.delegationArgs.map { it.value } + s.params.mapNotNull { it.default } }
        referencedNames(roots).any { it in dirtyNames }
    }
    return if (classReach) program.keys else dirty
}

private fun referencedNames(roots: List<RNode>): Set<String> {
    val names = HashSet<String>()
    fun binding(b: Binding) { if (b is Binding.Property) names += b.name }
    fun go(n: RNode) {
        when (n) {
            is RNode.Call -> names += n.callee.displayName
            is RNode.PropertyGet -> binding(n.binding)
            is RNode.PropertySet -> binding(n.binding)
            is RNode.Name -> binding(n.binding)
            is RNode.Lambda -> n.params.forEach { p -> p.default?.let(::go) }
            else -> {}
        }
        n.children().forEach(::go)
    }
    roots.forEach(::go)
    return names
}
