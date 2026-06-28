package dev.ide.lang.kotlin

/**
 * The `kt.*` diagnostic codes the Kotlin editor emits. Centralized so the catalog reads in one place and the
 * codes that quick-fixes and the analysis-api bridge key on cannot drift through a typo. The string VALUES
 * are part of the contract (quick-fix providers, the EP bridge, and tests match on them), so they must not
 * change even as the constant names evolve.
 */
object KotlinDiagnosticCodes {
    /** A syntax error from the tolerant parser. The only code produced outside [KotlinSemanticChecks]. */
    const val SYNTAX = "kt.syntax"

    // --- resolution / calls / types ---
    const val UNRESOLVED = "kt.unresolved"
    const val TYPE_MISMATCH = "kt.typeMismatch"
    const val ARGUMENT_COUNT = "kt.argumentCount"
    const val CONSTRUCTOR_ARGS = "kt.constructorArgs"
    const val NAMED_ARGUMENT = "kt.namedArgument"
    const val NOT_CALLABLE = "kt.notCallable"
    const val CANNOT_INFER_TYPE = "kt.cannotInferType"
    const val COMPOSABLE_INVOCATION = "kt.composableInvocation"
    const val SUSPEND_CONTEXT = "kt.suspendContext"
    const val DELEGATE_OPERATOR = "kt.delegateOperator"

    // --- declaration / modifier validity ---
    const val CONFLICTING_DECLARATION = "kt.conflictingDeclaration"
    const val CONFLICTING_IMPORT = "kt.conflictingImport"
    const val MODIFIERS = "kt.modifiers"
    const val ABSTRACT_MODIFIER = "kt.abstractModifier"
    const val LATEINIT = "kt.lateinit"
    const val VAL_VAR_PARAMETER = "kt.valVarParameter"
    const val VAL_REASSIGN = "kt.valReassign"
    const val MUST_BE_INITIALIZED = "kt.mustBeInitialized"
    const val NO_TYPE_NO_INITIALIZER = "kt.noTypeNoInitializer"
    const val MISSING_RETURN = "kt.missingReturn"
    const val WHEN_EXHAUSTIVE = "kt.whenExhaustive"
    const val UNSAFE_NULLABLE = "kt.unsafeNullable"
    const val USELESS_CAST = "kt.uselessCast"
    const val USELESS_ELVIS = "kt.uselessElvis"
    const val UNREACHABLE = "kt.unreachable"

    // --- unused (warnings / hints) ---
    const val UNUSED_IMPORT = "kt.unusedImport"
    const val UNUSED_PRIVATE = "kt.unusedPrivate"
    const val UNUSED_LOCAL = "kt.unusedLocal"
    const val VAR_COULD_BE_VAL = "kt.varCouldBeVal"

    const val PREVIEW_NOT_COMPOSABLE = "kt.previewNotComposable"
    const val PREVIEW_PARAMETERS = "kt.previewParameters"
}
