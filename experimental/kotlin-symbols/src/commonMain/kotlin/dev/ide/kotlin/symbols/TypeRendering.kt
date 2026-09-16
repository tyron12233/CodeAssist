package dev.ide.kotlin.symbols

/**
 * Renders a Kotlin type for display from a classifier FQN and already-rendered argument strings.
 *
 * A PORT of `:lang-kotlin-index`'s `TypeRendering`, verbatim in behaviour. A `kotlin.FunctionN` shows as
 * `(P1, P2) -> R` and not `Function2<...>`, matching how Kotlin source spells a function type; everything
 * else is `SimpleName<args>` with a `?` for nullability.
 */
object TypeRendering {

    fun isFunctionType(fqn: String): Boolean {
        val tail = fqn.substringAfterLast('.')
        return (tail.startsWith("Function") && tail.removePrefix("Function").toIntOrNull() != null) ||
            isSuspendFunctionType(fqn)
    }

    /**
     * A `suspend (...) -> R`, spelled `kotlin.SuspendFunctionN`.
     *
     * The source path produces this FQN directly; the metadata path decodes a suspend type as a
     * continuation-expanded plain `FunctionN`, so a non-match here is NOT proof that a binary callee is not
     * suspend.
     */
    fun isSuspendFunctionType(fqn: String): Boolean {
        val tail = fqn.substringAfterLast('.')
        return tail.startsWith("SuspendFunction") && tail.removePrefix("SuspendFunction").toIntOrNull() != null
    }

    /** Whether [simpleName] is the synthetic key a local or anonymous type is registered under. */
    fun isSyntheticLocalName(simpleName: String): Boolean =
        simpleName.length > 2 && simpleName.startsWith("\$L") && simpleName.drop(2).all { it.isDigit() }

    fun render(
        fqn: String,
        args: List<String>,
        nullable: Boolean,
        isTypeParameter: Boolean = false,
        isExtensionFunctionType: Boolean = false,
    ): String {
        if (isTypeParameter) return fqn + if (nullable) "?" else ""
        // A synthetic local or anonymous type key must never leak into display. Without a resolution
        // context to recover a real name this is the safe fallback.
        if (isSyntheticLocalName(fqn.substringAfterLast('.'))) return "<anonymous>" + if (nullable) "?" else ""
        val core = when {
            isFunctionType(fqn) && args.isNotEmpty() -> {
                val suspend = fqn.substringAfterLast('.').startsWith("SuspendFunction")
                val returned = args.last()
                // A receiver function type `T.() -> R`: the first argument is the receiver, not a parameter.
                val arrow = if (isExtensionFunctionType && args.size >= 2) {
                    "${args.first()}.(${args.subList(1, args.size - 1).joinToString(", ")}) -> $returned"
                } else {
                    "(${args.dropLast(1).joinToString(", ")}) -> $returned"
                }
                if (suspend) "suspend $arrow" else arrow
            }

            args.isEmpty() -> fqn.substringAfterLast('.')
            else -> "${fqn.substringAfterLast('.')}<${args.joinToString(", ")}>"
        }
        return when {
            !nullable -> core
            // A function type needs parentheses before the question mark.
            isFunctionType(fqn) && args.isNotEmpty() -> "($core)?"
            else -> "$core?"
        }
    }
}
