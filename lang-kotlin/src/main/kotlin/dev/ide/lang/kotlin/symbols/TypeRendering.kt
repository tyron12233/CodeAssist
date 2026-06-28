package dev.ide.lang.kotlin.symbols

/**
 * Renders a Kotlin type for display from a classifier FQN + already-rendered argument strings. A
 * `kotlin.FunctionN` shows as `(P1, P2) -> R` (and `suspend (…) -> R`), not `Function2<…>`, matching how
 * Kotlin source spells function types. Everything else is `SimpleName<args>` with `?` for nullability; a
 * type-parameter reference renders as its bare name (`T`).
 */
object TypeRendering {

    fun isFunctionType(fqn: String): Boolean {
        val tail = fqn.substringAfterLast('.')
        return (tail.startsWith("Function") && tail.removePrefix("Function").toIntOrNull() != null) ||
            isSuspendFunctionType(fqn)
    }

    /** A `suspend (…) -> R` function type, spelled `kotlin.SuspendFunctionN` (the source path produces this
     *  FQN; the binary/metadata path decodes a suspend type as a continuation-expanded plain `FunctionN`, so a
     *  non-match here is NOT proof of non-suspend for a binary callee). Used by the suspend calling-convention
     *  check to recognise a suspend lambda slot. */
    fun isSuspendFunctionType(fqn: String): Boolean {
        val tail = fqn.substringAfterLast('.')
        return tail.startsWith("SuspendFunction") && tail.removePrefix("SuspendFunction").toIntOrNull() != null
    }

    fun render(
        fqn: String,
        args: List<String>,
        nullable: Boolean,
        isTypeParameter: Boolean = false,
        isExtensionFunctionType: Boolean = false,
    ): String {
        if (isTypeParameter) return fqn + if (nullable) "?" else ""
        val core = when {
            isFunctionType(fqn) && args.isNotEmpty() -> {
                val suspend = fqn.substringAfterLast('.').startsWith("SuspendFunction")
                val ret = args.last()
                // Receiver function type `T.() -> R`: first arg is the receiver, the rest are value params.
                val arrow = if (isExtensionFunctionType && args.size >= 2) {
                    "${args.first()}.(${args.subList(1, args.size - 1).joinToString(", ")}) -> $ret"
                } else {
                    "(${args.dropLast(1).joinToString(", ")}) -> $ret"
                }
                if (suspend) "suspend $arrow" else arrow
            }
            args.isEmpty() -> fqn.substringAfterLast('.')
            else -> "${fqn.substringAfterLast('.')}<${args.joinToString(", ")}>"
        }
        return when {
            !nullable -> core
            isFunctionType(fqn) && args.isNotEmpty() -> "($core)?" // function types need parens before `?`
            else -> "$core?"
        }
    }
}
