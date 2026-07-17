package dev.ide.lang.java.parse

import dev.ide.lang.dom.NodeKind

/** Java-specific [NodeKind]s beyond the shared constants in [NodeKind.Companion]. */
object JavaNodeKinds {
    /** A `new Foo(...)` constructor call (the shared set has METHOD_CALL but no dedicated `new`). */
    val NEW_EXPR = NodeKind("new_expr")

    /** A represented Java element with no more specific neutral kind. */
    val OTHER = NodeKind("java.other")
}

/** Diagnostic codes emitted by the Java backend, so quick-fixes can key off them. */
object JavaDiagnosticCodes {
    const val SYNTAX = "java.syntax"

    /** An unresolved reference (undefined type / method / field / variable / import) — the resolution-derived
     *  semantic error. Value is the neutral `dev.ide.analysis.Codes.UNRESOLVED_REFERENCE` string so the
     *  existing (index-backed, language-gated) "Add import" quick-fix fires on lang-java's unresolved types
     *  exactly as it does for JDT. */
    const val UNRESOLVED = "UNRESOLVED_REFERENCE"

    /** An assignment-conversion failure (initializer / assignment / return value whose type can't be converted
     *  to the target). Reported only when both types are fully resolved and non-generic (see the strict gate in
     *  [dev.ide.lang.java.resolve.JavaSemanticDiagnostics]). */
    const val TYPE_MISMATCH = "java.typeMismatch"

    /** A `return` with a value in a `void` method, or without one in a value-returning method. */
    const val RETURN_VALUE = "java.returnValue"

    /** A value-returning method whose body can complete without returning (control-flow analysis). */
    const val MISSING_RETURN = "java.missingReturn"

    /** A statement that control flow can never reach (after a `return`/`throw`/`break`/`continue`). */
    const val UNREACHABLE = "java.unreachable"

    /** A checked exception thrown in a context that neither catches it nor declares it in `throws`. */
    const val UNHANDLED_EXCEPTION = "java.unhandledException"

    /** An assignment / increment to a `final` variable already definitely assigned — a final parameter, or a
     *  final local with an initializer (blank finals + final fields need flow analysis, so are NOT flagged). */
    const val FINAL_REASSIGNMENT = "java.finalReassignment"

    /** A concrete class (or enum-agnostic) that inherits an abstract method it doesn't implement, or declares
     *  an abstract method while non-abstract. Keyed by the implement-members fix. */
    const val ABSTRACT_NOT_IMPLEMENTED = "java.abstractNotImplemented"

    /** An illegal override: overriding a `final` method, `@Override` on a non-override, or reducing visibility /
     *  return-type incompatibility. */
    const val INVALID_OVERRIDE = "java.invalidOverride"

    /** A duplicate class member — a method (same erased signature) or field (same name) declared twice. */
    const val DUPLICATE_MEMBER = "java.duplicateMember"

    /** An illegal modifier / member shape — an abstract method with a body, or a concrete method missing one. */
    const val ILLEGAL_MEMBER = "java.illegalMember"

    /** A local variable read where it may not have been initialized, or a blank `final` field never assigned
     *  in the class (definite-assignment analysis). */
    const val NOT_INITIALIZED = "java.notInitialized"

    /** A `new` of an abstract class or interface (without an anonymous body) — nothing to instantiate. */
    const val ABSTRACT_INSTANTIATION = "java.abstractInstantiation"

    /** A `break`/`continue` with no enclosing loop (or, for `break`, `switch`) to jump out of. */
    const val ILLEGAL_JUMP = "java.illegalJump"

    /** A `var` local whose type can't be inferred — no initializer, or a `null` initializer. */
    const val CANNOT_INFER_VAR = "java.cannotInferVar"

    /** A class that extends/implements itself transitively. */
    const val CYCLIC_INHERITANCE = "java.cyclicInheritance"

    /** A method/constructor call whose arguments don't fit any applicable overload of the resolved name. */
    const val CANNOT_APPLY = "java.cannotApply"

    /** A binary/polyadic operator applied to an operand of an incompatible type (`"x" - 1`). */
    const val BAD_OPERAND = "java.badOperand"
}
