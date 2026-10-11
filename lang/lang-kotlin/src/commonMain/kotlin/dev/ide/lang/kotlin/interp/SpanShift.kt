package dev.ide.lang.kotlin.interp

/**
 * This function, as it lowers when its declaration sits [delta] characters further along the file: every
 * [SourceSpan] moved, everything else kept.
 *
 * A lowered tree depends on a declaration's own text, the file's signatures and offsets only through its spans
 * ([CallSiteKey]s are already relative to the declaration start). So when an edit ABOVE a function moves it
 * without touching it, shifting its spans gives exactly the tree a re-lower would, at the cost of one copy
 * instead of overload resolution for every call in it. Typing near the top of a big file used to re-lower
 * every function below the caret on each pause for this reason alone.
 */
internal fun ResolvedFunction.shiftedBy(delta: Int): ResolvedFunction =
    if (delta == 0) this else SpanShift(delta).function(this)

/** [shiftedBy] for a class: an anonymous `object : Foo {}` travels with the function that declared it. */
internal fun ResolvedClass.shiftedBy(delta: Int): ResolvedClass =
    if (delta == 0) this else SpanShift(delta).klass(this)

private class SpanShift(private val delta: Int) {

    private fun s(span: SourceSpan) = SourceSpan(span.start + delta, span.end + delta)

    fun function(f: ResolvedFunction): ResolvedFunction =
        f.copy(params = params(f.params), body = node(f.body), diagnostics = diagnostics(f.diagnostics))

    fun klass(c: ResolvedClass): ResolvedClass = c.copy(
        primaryParams = c.primaryParams.map { p -> p.copy(default = p.default?.let(::node)) },
        initSteps = nodes(c.initSteps),
        methods = c.methods.mapValues { (_, m) -> function(m) },
        superCall = c.superCall?.let { it.copy(args = args(it.args)) },
        enumEntries = c.enumEntries.map { it.copy(args = args(it.args)) },
        diagnostics = diagnostics(c.diagnostics),
        secondaryCtors = c.secondaryCtors.map {
            it.copy(
                params = params(it.params), delegationArgs = args(it.delegationArgs),
                body = node(it.body), diagnostics = diagnostics(it.diagnostics),
            )
        },
    )

    // Exhaustive on purpose: a new node kind has to say how its spans move, or this stops compiling.
    fun node(n: RNode): RNode = when (n) {
        is RNode.Const -> n.copy(source = s(n.source))
        is RNode.Name -> n.copy(source = s(n.source))
        is RNode.This -> n.copy(source = s(n.source))
        is RNode.Break -> n.copy(source = s(n.source))
        is RNode.Continue -> n.copy(source = s(n.source))
        is RNode.Unsupported -> n.copy(source = s(n.source))
        is RNode.Call -> n.copy(
            receiver = opt(n.receiver), dispatchReceiver = opt(n.dispatchReceiver), args = args(n.args),
            source = s(n.source),
        )
        is RNode.PropertyGet -> n.copy(receiver = opt(n.receiver), source = s(n.source))
        is RNode.PropertySet -> n.copy(receiver = opt(n.receiver), value = node(n.value), source = s(n.source))
        is RNode.If -> n.copy(condition = node(n.condition), then = node(n.then), otherwise = opt(n.otherwise), source = s(n.source))
        is RNode.Block -> n.copy(statements = nodes(n.statements), source = s(n.source))
        is RNode.Lambda -> n.copy(params = params(n.params), body = node(n.body), source = s(n.source))
        is RNode.StringConcat -> n.copy(parts = nodes(n.parts), source = s(n.source))
        is RNode.NotNull -> n.copy(value = node(n.value), source = s(n.source))
        is RNode.TypeCheck -> n.copy(value = node(n.value), source = s(n.source))
        is RNode.Cast -> n.copy(value = node(n.value), source = s(n.source))
        is RNode.ClassLiteral -> n.copy(receiver = opt(n.receiver), source = s(n.source))
        is RNode.Try -> n.copy(
            body = node(n.body), catches = n.catches.map { it.copy(body = node(it.body)) },
            finallyBlock = opt(n.finallyBlock), source = s(n.source),
        )
        is RNode.LocalVar -> n.copy(initializer = opt(n.initializer), source = s(n.source))
        is RNode.Assign -> n.copy(target = node(n.target), value = node(n.value), source = s(n.source))
        is RNode.Return -> n.copy(value = opt(n.value), source = s(n.source))
        is RNode.Throw -> n.copy(value = node(n.value), source = s(n.source))
        is RNode.While -> n.copy(condition = node(n.condition), body = node(n.body), source = s(n.source))
        is RNode.ForEach -> n.copy(
            loopVar = param(n.loopVar), iterable = node(n.iterable), body = node(n.body), source = s(n.source),
        )
    }

    private fun opt(n: RNode?): RNode? = n?.let(::node)
    private fun nodes(xs: List<RNode>): List<RNode> = xs.map(::node)
    private fun args(xs: List<RArg>): List<RArg> = xs.map { it.copy(value = node(it.value)) }
    private fun param(p: RParam): RParam = if (p.default == null) p else p.copy(default = node(p.default))
    private fun params(xs: List<RParam>): List<RParam> = xs.map(::param)
    private fun diagnostics(xs: List<LoweringDiagnostic>): List<LoweringDiagnostic> = xs.map { it.copy(source = s(it.source)) }
}
