package dev.ide.lang.resolve

/**
 * This provider, then [next] for what it does not know: real sources first, a bundled pack behind them. A
 * method found here with a different overload's arity still wins, as it would alone. A blank doc counts as not
 * known: a class whose attached source has no doc comment must not hide the pack's.
 */
fun SourceDocProvider.orElse(next: SourceDocProvider): SourceDocProvider {
    if (next === SourceDocProvider.NONE) return this
    if (this === SourceDocProvider.NONE) return next
    val first = this
    return object : SourceDocProvider {
        override fun method(declaringFqn: String, methodName: String, arity: Int) =
            first.method(declaringFqn, methodName, arity) ?: next.method(declaringFqn, methodName, arity)

        override fun classDoc(fqn: String) = first.classDoc(fqn)?.takeIf { it.isNotBlank() } ?: next.classDoc(fqn)

        override fun methodRaw(declaringFqn: String, methodName: String, arity: Int) =
            first.methodRaw(declaringFqn, methodName, arity)?.takeIf { it.isNotBlank() } ?: next.methodRaw(declaringFqn, methodName, arity)

        override fun classDocRaw(fqn: String) = first.classDocRaw(fqn)?.takeIf { it.isNotBlank() } ?: next.classDocRaw(fqn)
    }
}
