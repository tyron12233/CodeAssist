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
    /** A call that fits two or more overloads with no most-specific one to choose (OVERLOAD_RESOLUTION_AMBIGUITY). */
    const val OVERLOAD_AMBIGUITY = "kt.overloadAmbiguity"
    const val COMPOSABLE_INVOCATION = "kt.composableInvocation"
    const val SUSPEND_CONTEXT = "kt.suspendContext"
    const val DELEGATE_OPERATOR = "kt.delegateOperator"
    /** A destructuring declaration whose value's type lacks the required `componentN()` operator (too many
     *  entries for the type's components, or a non-destructurable type). */
    const val DESTRUCTURING = "kt.destructuring"

    // --- declaration / modifier validity ---
    const val CONFLICTING_DECLARATION = "kt.conflictingDeclaration"
    const val CONFLICTING_IMPORT = "kt.conflictingImport"
    const val MODIFIERS = "kt.modifiers"
    const val ABSTRACT_MODIFIER = "kt.abstractModifier"

    // --- inheritance / override correctness ---
    /** A concrete class/object leaves an inherited abstract member unimplemented. */
    const val ABSTRACT_NOT_IMPLEMENTED = "kt.abstractNotImplemented"
    /** A member is declared `override` but no supertype declares a member of that name. */
    const val NOTHING_TO_OVERRIDE = "kt.nothingToOverride"
    /** A member hides an inherited member of the same signature but is missing the `override` modifier. */
    const val OVERRIDE_REQUIRED = "kt.overrideRequired"
    /** A constructor call on an interface or abstract/sealed class (which cannot be instantiated). */
    const val ABSTRACT_INSTANTIATION = "kt.abstractInstantiation"
    /** A class supertype written without its constructor call (`class C : Base` → should be `Base()`). */
    const val SUPERTYPE_NOT_INITIALIZED = "kt.supertypeNotInitialized"
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

    /** A cast (`x as T`) between two unrelated final types that can never succeed at runtime. */
    const val CAST_NEVER_SUCCEEDS = "kt.castNeverSucceeds"
    /** A `==`/`!=` against `null` where the other operand is provably non-null (the condition is constant). */
    const val SENSELESS_COMPARISON = "kt.senselessComparison"
    /** A non-abstract function declared without a body (`fun foo()` in a concrete context). */
    const val FUNCTION_NO_BODY = "kt.functionNoBody"
    /** A use of a `@Deprecated` declaration (call, member read). */
    const val DEPRECATION = "kt.deprecation"
    /** A read of a local `val`/`var` that is (on some path) not yet initialized (control-flow definite-assignment). */
    const val UNINITIALIZED_VARIABLE = "kt.uninitializedVariable"
    /** An assignment whose left side is not something that can be assigned to (`foo() = 1`, `5 = x`). */
    const val VARIABLE_EXPECTED = "kt.variableExpected"
    /** A statement whose value is unused and which has no side effect (a bare reference, a comparison). */
    const val UNUSED_EXPRESSION = "kt.unusedExpression"
    /** Two `when` branches with the same constant condition — the second is dead. */
    const val DUPLICATE_WHEN_BRANCH = "kt.duplicateWhenBranch"
    /** A misuse of the `const` modifier (on a local, on a `var`, or with a non-compile-time initializer). */
    const val CONST_MISUSE = "kt.constMisuse"
    /** An assignment used where a value is expected (`val b = (x = 5)`) — assignments are not expressions. */
    const val ASSIGNMENT_IN_EXPRESSION = "kt.assignmentInExpression"
    /** A local declaration whose name shadows a visible outer local/parameter of the same name. */
    const val NAME_SHADOWING = "kt.nameShadowing"
    /** A `catch` clause unreachable because an earlier clause catches a supertype of its exception. */
    const val UNREACHABLE_CATCH = "kt.unreachableCatch"

    /** A type reference with the wrong number of type arguments for its classifier (`Map<Int>`, `List<A, B>`). */
    const val TYPE_ARGUMENT_COUNT = "kt.typeArgumentCount"
    /** A type argument that violates its type parameter's upper bound (`Box<String>` where `Box<T : Number>`). */
    const val UPPER_BOUND_VIOLATED = "kt.upperBoundViolated"

    // --- unused (warnings / hints) ---
    const val UNUSED_IMPORT = "kt.unusedImport"
    const val UNUSED_PRIVATE = "kt.unusedPrivate"
    const val UNUSED_LOCAL = "kt.unusedLocal"
    const val UNUSED_PARAMETER = "kt.unusedParameter"
    const val VAR_COULD_BE_VAL = "kt.varCouldBeVal"

    // --- redundancy (warnings / hints) ---
    /** A not-null assertion (`!!`) on a value that is already non-null. */
    const val REDUNDANT_NOT_NULL = "kt.redundantNotNull"
    /** A safe call (`?.`) on a value that is already non-null. */
    const val REDUNDANT_SAFE_CALL = "kt.redundantSafeCall"
    /** A string-template entry that wraps a bare name in braces needlessly (`"${name}"` → `"$name"`). */
    const val REDUNDANT_STRING_TEMPLATE = "kt.redundantStringTemplate"

    /** An `==`/`!=` between two unrelated final types that can never be equal (`"x" == 5`, `ColorA == ColorB`). */
    const val INCOMPARABLE_EQUALITY = "kt.incomparableEquality"
    /** An `is`/`!is` check whose outcome is statically known (`x is String` where `x: String`, or `x is Int`). */
    const val USELESS_IS_CHECK = "kt.uselessIsCheck"
    /** A Java bean accessor called explicitly (`view.setText("x")` / `view.getText()`) where Kotlin's
     *  synthetic-property syntax (`view.text = "x"` / `view.text`) is idiomatic. */
    const val USE_PROPERTY_ACCESS = "kt.usePropertyAccess"

    const val PREVIEW_NOT_COMPOSABLE = "kt.previewNotComposable"
    const val PREVIEW_PARAMETERS = "kt.previewParameters"
}
