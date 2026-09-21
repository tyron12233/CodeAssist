package dev.ide.lang.dom

/**
 * The Java [NodeKind]s beyond the shared constants in [NodeKind.Companion].
 *
 * Published beside [NodeKind] rather than kept in `:lang-java`, for the reason given on [KotlinNodeKinds]:
 * the ids are the contract, and a consumer that matches on them should not have to link a backend.
 *
 * Short, because Java's shapes line up with the neutral set almost everywhere. The two entries here are
 * a `new` expression and a catch-all; the diagnostic codes that used to share this file stayed with the
 * backend that emits them.
 */
object JavaNodeKinds {
    /** A `new Foo(...)` constructor call (the shared set has METHOD_CALL but no dedicated `new`). */
    val NEW_EXPR = NodeKind("new_expr")

    /** A represented Java element with no more specific neutral kind. */
    val OTHER = NodeKind("java.other")
}
