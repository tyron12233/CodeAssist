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
    /** A classifier (a class/interface with no companion object) used as a VALUE without being initialized —
     *  `columns = GridCells.Fixed` / `val x = Foo` (must be `GridCells.Fixed(2)` / `Foo(...)`). The compiler's
     *  "Classifier 'X' does not have a companion object, and thus must be initialized here". */
    const val CLASSIFIER_AS_VALUE = "kt.classifierAsValue"
    /** A FUNCTION name used as a VALUE without being invoked: a bare `LazyColumn` / `remember` in value
     *  position (must be `LazyColumn()`; a function is only a value via a `::` reference). The compiler's
     *  "Function invocation 'X(...)' expected". */
    const val FUNCTION_CALL_EXPECTED = "kt.functionCallExpected"

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
    /** An `override` function whose `suspend`-ness contradicts the member it overrides — either direction
     *  ("Non-suspend function 'f' cannot override suspend function" / "Suspend function 'f' cannot override
     *  non-suspend function"). */
    const val SUSPEND_OVERRIDE = "kt.suspendOverride"
    /** A constructor call on an interface or abstract/sealed class (which cannot be instantiated). */
    const val ABSTRACT_INSTANTIATION = "kt.abstractInstantiation"
    /** A class supertype written without its constructor call (`class C : Base` → should be `Base()`). */
    const val SUPERTYPE_NOT_INITIALIZED = "kt.supertypeNotInitialized"
    /** A supertype that is a `final` class (Kotlin's default) and so cannot be extended. */
    const val FINAL_SUPERTYPE = "kt.finalSupertype"
    const val LATEINIT = "kt.lateinit"
    const val VAL_VAR_PARAMETER = "kt.valVarParameter"
    const val VAL_REASSIGN = "kt.valReassign"
    const val MUST_BE_INITIALIZED = "kt.mustBeInitialized"
    const val NO_TYPE_NO_INITIALIZER = "kt.noTypeNoInitializer"
    const val MISSING_RETURN = "kt.missingReturn"
    const val WHEN_EXHAUSTIVE = "kt.whenExhaustive"
    const val UNSAFE_NULLABLE = "kt.unsafeNullable"
    /** A member reached through a check (`if (x is String) x.length`, `if (x != null) x.foo()`) on a value the
     *  compiler cannot smart cast: a `var` or open/custom-getter/delegated property, or a local `var` a closure
     *  may change. */
    const val SMARTCAST_IMPOSSIBLE = "kt.smartcastImpossible"
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
    /** A platform API newer than the module's `minSdk`, not covered by an `SDK_INT` guard or `@RequiresApi`.
     *  Shared with the Java editor. */
    const val NEW_API = dev.ide.lang.resolve.ApiGuards.NEW_API
    /** A JDK API newer than a JVM module's Java release (what `-Xjdk-release` rejects). Shared with Java. */
    const val NEW_JDK_API = dev.ide.lang.resolve.ApiGuards.NEW_JDK_API
    /** A platform API whose permission the manifest lacks, or a dangerous one nothing checks at run time
     *  (lint's `MissingPermission`). Shared with the Java editor. */
    const val MISSING_PERMISSION = dev.ide.lang.resolve.PermissionGuards.MISSING_PERMISSION
    /** Use of an experimental API (a `@RequiresOptIn(level = WARNING)` marker) without opting in — the
     *  compiler's OPT_IN_USAGE. Resolved via [KotlinSymbolService.optInMarkersOf]. */
    const val OPT_IN_USAGE = "kt.optInUsage"
    /** Use of an experimental API whose marker is `@RequiresOptIn(level = ERROR)` (the default) without opting
     *  in — the compiler's OPT_IN_USAGE_ERROR. */
    const val OPT_IN_USAGE_ERROR = "kt.optInUsageError"
    /** A reference to a declaration that exists but is not accessible from here — specifically a library
     *  `internal` member/type (invisible across the module boundary). The compiler's INVISIBLE_REFERENCE/
     *  INVISIBLE_MEMBER, reported instead of a bare "unresolved reference" when the only match is internal. */
    const val INVISIBLE_REFERENCE = "kt.invisibleReference"
    /** `a === b` where an operand has no identity (a value class, or a primitive against another final type):
     *  the compiler's FORBIDDEN_IDENTITY_EQUALS. */
    const val FORBIDDEN_IDENTITY_EQUALS = "kt.forbiddenIdentityEquals"
    /** `a is B` that can never hold: the declared types are unrelated and one is final, or both are classes.
     *  The compiler's IMPOSSIBLE_IS_CHECK_ERROR. */
    const val IMPOSSIBLE_IS_CHECK = "kt.impossibleIsCheck"
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
    /** A declaration-site variance conflict: an `out` type parameter occurring in an `in` (or invariant)
     *  position, or an `in` type parameter in an `out` (or invariant) position (TYPE_VARIANCE_CONFLICT). */
    const val VARIANCE_CONFLICT = "kt.varianceConflict"
    /** A use-site projection that conflicts with the type parameter's declaration-site variance
     *  (`Comparable<out T>` where `Comparable<in T>`, `List<in T>` where `List<out E>`) — CONFLICTING_PROJECTION. */
    const val CONFLICTING_PROJECTION = "kt.conflictingProjection"
    /** A member whose parameter takes the class's `T`, called on a receiver that projects `T` out (`out` or `*`:
     *  `MutableList<out Number>.add(42)`), so the parameter accepts nothing a real argument can be. */
    const val MEMBER_PROJECTED_OUT = "kt.memberProjectedOut"

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
    /** A use-site projection matching the type parameter's declaration-site variance, so it adds nothing
     *  (`List<out String>` where `List<out E>`, `Comparable<in T>` where `Comparable<in T>`) — REDUNDANT_PROJECTION. */
    const val REDUNDANT_PROJECTION = "kt.redundantProjection"

    /** An `==`/`!=` between two unrelated final types that can never be equal (`"x" == 5`, `ColorA == ColorB`). */
    const val INCOMPARABLE_EQUALITY = "kt.incomparableEquality"
    /** An `is`/`!is` check whose outcome is statically known (`x is String` where `x: String`, or `x is Int`). */
    const val USELESS_IS_CHECK = "kt.uselessIsCheck"
    /** A Java bean accessor called explicitly (`view.setText("x")` / `view.getText()`) where Kotlin's
     *  synthetic-property syntax (`view.text = "x"` / `view.text`) is idiomatic. */
    const val USE_PROPERTY_ACCESS = "kt.usePropertyAccess"

    // --- structural checks that need no types (see KotlinStructuralChecks) ---
    /** `break` / `continue` with no enclosing loop. */
    const val JUMP_OUTSIDE_LOOP = "kt.breakOrContinueOutsideLoop"
    /** A `break@x` / `continue@x` / `return@x` whose label names nothing that encloses it. */
    const val UNRESOLVED_LABEL = "kt.unresolvedLabel"
    /** `this` where no receiver exists: a top-level function or property with no extension receiver. */
    const val NO_THIS = "kt.noThis"
    /** An `if` with no `else` whose value is used. */
    const val IF_AS_EXPRESSION = "kt.ifAsExpression"
    /** A `data class` with no primary constructor parameter, or one that is not a `val`/`var`. */
    const val DATA_CLASS = "kt.dataClass"
    /** A bare `return` in a function that must return a value. */
    const val RETURN_VALUE_EXPECTED = "kt.returnValueExpected"

    // --- modifier and declaration-shape checks ported from the compiler's tables (see KotlinModifierChecks) ---
    /** A modifier written on a kind of declaration it does not apply to (`abstract` on a top-level function). */
    const val WRONG_MODIFIER_TARGET = "kt.wrongModifierTarget"
    /** A modifier that fits the declaration but not where it sits (`protected` inside an object). */
    const val WRONG_MODIFIER_CONTAINER = "kt.wrongModifierContainer"
    /** An annotation whose class's `@Target` does not allow where it is written. */
    const val WRONG_ANNOTATION_TARGET = "kt.wrongAnnotationTarget"
    /** An `operator` function no operator convention accepts (wrong name, arity, receiver or return type). */
    const val INAPPLICABLE_OPERATOR = "kt.inapplicableOperator"
    /** An `infix` function that is not a member or extension with exactly one non-vararg parameter. */
    const val INAPPLICABLE_INFIX = "kt.inapplicableInfix"
    /** A `return` that crosses a class, a property initializer, a default value or a non-inlined lambda on
     *  its way out to the function it returns from. */
    const val RETURN_NOT_ALLOWED = "kt.returnNotAllowed"
    /** An annotation argument, or a `const val` initializer, that reads a non-`const` property or calls a
     *  function (ANNOTATION_ARGUMENT_MUST_BE_CONST / NON_CONST_VAL_USED_IN_CONSTANT_EXPRESSION). */
    const val NOT_CONSTANT = "kt.notConstant"
    /** A declaration of a shape the compiler rejects outright: two `vararg` parameters, type parameters on an
     *  object, a local object or interface, a nested class where only an inner one may stand, a named
     *  function used as an expression, variance on a function's type parameter, a nullable annotation
     *  parameter, two labels on one expression, a non-repeatable annotation written twice. */
    const val DECLARATION_SHAPE = "kt.declarationShape"

    // --- typed checks added with the error-recall corpus (see KotlinTypedChecks) ---
    /** A `for` loop over a value with no `iterator()`. */
    const val NOT_ITERABLE = "kt.notIterable"
    /** A public declaration whose signature names a `private` class of the same file. */
    const val EXPOSED_VISIBILITY = "kt.exposedVisibility"
    /** `a[i] = v` on a type with no `set` operator (a read-only `List`, a `String`). */
    const val NO_SET_OPERATOR = "kt.noSetOperator"
    /** An `override` of a member that is final in the supertype. */
    const val OVERRIDING_FINAL = "kt.overridingFinal"
    /** An `override` whose return type is not a subtype of the overridden member's. */
    const val OVERRIDE_RETURN_TYPE = "kt.overrideReturnType"
    /** An `override` whose written visibility is narrower than the member it overrides (`protected` over a
     *  public member), or not comparable with it (`internal` over `protected`). */
    const val CANNOT_WEAKEN_ACCESS = "kt.cannotWeakenAccess"
    /** A constructor call on something that has none: `: Iface()` in a supertype list. */
    const val NO_CONSTRUCTOR = "kt.noConstructor"
    /** A lambda whose parameter count does not fit the function type it is written for. */
    const val LAMBDA_PARAMETERS = "kt.lambdaParameters"
    /** An instance member reached through its class name (`A.m()`), with no instance. */
    const val INSTANCE_ACCESS = "kt.instanceAccessViaType"
    /** A bare call that reaches past a closer implicit receiver carrying the same `@DslMarker`. */
    const val DSL_SCOPE_VIOLATION = "kt.dslScopeViolation"

    // --- construction order and inline bodies (see KotlinInitializationChecks, KotlinInlineChecks) ---
    /** An enum's companion object reached from the enum's own initialization (an entry's arguments or body
     *  initializers, the enum's property initializers), before the companion exists (UNINITIALIZED_ENUM_COMPANION). */
    const val UNINITIALIZED_ENUM_COMPANION = "kt.uninitializedEnumCompanion"
    /** `this`, `super` or an instance member used in a constructor's header: a parameter default, a delegation
     *  call's arguments or the superclass call's (INSTANCE_ACCESS_BEFORE_SUPER_CALL). */
    const val INSTANCE_ACCESS_BEFORE_SUPER_CALL = "kt.instanceAccessBeforeSuperCall"
    /** An inline function's inlinable functional parameter used other than by calling it or passing it on to
     *  another inline function (USAGE_IS_NOT_INLINABLE). */
    const val USAGE_IS_NOT_INLINABLE = "kt.usageIsNotInlinable"
    /** A public-API inline function's body touching a declaration outside the public API: private, internal
     *  without `@PublishedApi`, or a member of such (NON_PUBLIC_CALL_FROM_PUBLIC_INLINE). */
    const val NON_PUBLIC_CALL_FROM_PUBLIC_INLINE = "kt.nonPublicCallFromPublicInline"

    // --- declaration and type rules ported from K2's declaration/type checkers (see KotlinDeclarationPortedChecks) ---
    /** A parameter with no type where nothing can supply one: a named function's or a constructor's, or a lambda's
     *  with no expected type (VALUE_PARAMETER_WITHOUT_EXPLICIT_TYPE). */
    const val PARAMETER_TYPE_REQUIRED = "kt.parameterTypeRequired"
    /** A supertype list the class kind does not allow: an interface extending or initializing a class, two
     *  classes, a supertype written twice, a projection or an extension function type as a supertype. */
    const val SUPERTYPE_LIST = "kt.supertypeList"
    /** `@PublishedApi` on a declaration that is not `internal` (NON_INTERNAL_PUBLISHED_API). */
    const val PUBLISHED_API = "kt.publishedApi"
    /** An inline declaration the compiler cannot inline: a virtual member (DECLARATION_CANT_BE_INLINED), a local
     *  function or class in an inline body (NOT_YET_SUPPORTED_IN_INLINE). */
    const val INLINE_DECLARATION = "kt.inlineDeclaration"
    /** A malformed type parameter: one a property's receiver never mentions, a repeated bound, two class bounds. */
    const val TYPE_PARAMETER_SHAPE = "kt.typeParameterShape"
    /** A property initializer with nowhere to go: in an interface, without a backing field, on an extension. */
    const val PROPERTY_SHAPE = "kt.propertyShape"
    /** A constructor that cannot be: in an object or an interface, with the wrong visibility for an enum or a sealed
     *  class, delegating in a cycle, or not delegating to the primary one. */
    const val CONSTRUCTOR_SHAPE = "kt.constructorShape"
    /** An annotation class with supertypes, members, a non-`val` parameter or a parameter of a type annotations
     *  cannot hold. */
    const val ANNOTATION_CLASS_SHAPE = "kt.annotationClassShape"
    /** A function of a shape the compiler rejects: nameless where a name is required, an anonymous function with
     *  type parameters or default values, an interface implementing a method of `Any`. */
    const val FUNCTION_SHAPE = "kt.functionShape"
    /** A class body holding what its kind cannot: a second companion object, an `init` block in an interface. */
    const val DECLARATION_FORM = "kt.declarationForm"
    // --- ported from K2's expression and syntax checkers and its call resolution (see KotlinLiteralChecks,
    // KotlinExpressionPortedChecks, KotlinArgumentMappingChecks) ---
    /** A literal whose text denotes no value: a bad escape, a lowercase `l` suffix, a misplaced `_`, leading
     *  zeros, an integer past `Long`, an empty or overlong character literal (ILLEGAL_ESCAPE and kin). */
    const val ILLEGAL_LITERAL = "kt.illegalLiteral"
    /** Something other than a name or a call after a dot, `1."s"` or `a.{ }` (ILLEGAL_SELECTOR). */
    const val ILLEGAL_SELECTOR = "kt.illegalSelector"
    /** `super` used other than as a member access receiver, or where no class encloses it
     *  (SUPER_IS_NOT_AN_EXPRESSION, SUPER_NOT_AVAILABLE). */
    const val SUPER_MISUSE = "kt.superMisuse"
    /** A name made only of underscores used without backticks (UNDERSCORE_USAGE_WITHOUT_BACKTICKS). */
    const val UNDERSCORE_USAGE = "kt.underscoreUsage"
    /** A loop or a declaration where only an expression can stand, `val x = while (c) {}` (EXPRESSION_EXPECTED). */
    const val EXPRESSION_EXPECTED = "kt.expressionExpected"
    /** Arguments that cannot be mapped onto the callee's parameters: a positional one after a misplaced named
     *  one, a parameter given twice, a spread outside a vararg, a vararg or a second lambda outside the
     *  parentheses, a name where names are forbidden (MIXING_NAMED_AND_POSITIONAL_ARGUMENTS and kin). */
    const val ARGUMENT_MAPPING = "kt.argumentMapping"
    /** `x is List<String>` or `x is T` the runtime cannot check, or `x is List` whose arguments `x`'s type does
     *  not supply (CANNOT_CHECK_FOR_ERASED, NO_TYPE_ARGUMENTS_ON_RHS). */
    const val CAST_ERASED = "kt.castErased"
    /** `import a.O.*` from an `object` (CANNOT_ALL_UNDER_IMPORT_FROM_SINGLETON). */
    const val IMPORT_FROM_SINGLETON = "kt.importFromSingleton"
    /** `a + b` by convention on a `plus` not declared `operator` (OPERATOR_MODIFIER_REQUIRED). */
    const val OPERATOR_MODIFIER_REQUIRED = "kt.operatorModifierRequired"
    /** `A?::class`, `A<String>::class`, `T::class` or `foo<T>()` for a non-reified `T` where a class is needed
     *  (NULLABLE_TYPE_IN_CLASS_LITERAL_LHS, CLASS_LITERAL_LHS_NOT_A_CLASS, TYPE_PARAMETER_AS_REIFIED). */
    const val CLASS_LITERAL = "kt.classLiteral"
    /** An inline function's lambda parameter called from an object, a local function or a lambda invoked on the
     *  spot, where its non-local returns cannot work (NON_LOCAL_RETURN_NOT_ALLOWED). */
    const val NON_LOCAL_RETURN_NOT_ALLOWED = "kt.nonLocalReturnNotAllowed"

    const val PREVIEW_NOT_COMPOSABLE = "kt.previewNotComposable"
    const val PREVIEW_PARAMETERS = "kt.previewParameters"

    /**
     * The ERROR codes that must stop the Compose `@Preview` interpreter from running the file at all.
     *
     * Lowering already refuses a call it cannot resolve, but the errors listed here are the ones that lower
     * *cleanly* into a tree that is nonetheless wrong: a mid-typed argument list, an argument of the wrong
     * type, a name used as a value, an uninferable type variable. The interpreter then builds values the
     * previewed code never asked for and hands them to the REAL Compose runtime, which fails in its
     * measure/layout/semantics pass — after the interpreter has returned, outside any guard it owns, taking
     * the composition (and with it the IDE) down. Refusing up front is the only place that failure is
     * catchable, so the preview holds its last good render until the buffer is clean again.
     *
     * Deliberately a NAMED SET rather than "any ERROR": a broken supertype or a missing `override` elsewhere
     * in the file is a real error but cannot corrupt the values a preview composes, and blanking a working
     * preview over one would be worse than the risk it removes. These are the ones that can.
     */
    val PREVIEW_BLOCKING: Set<String> = setOf(
        SYNTAX,
        UNRESOLVED,
        TYPE_MISMATCH,
        ARGUMENT_COUNT,
        CONSTRUCTOR_ARGS,
        NAMED_ARGUMENT,
        ARGUMENT_MAPPING,
        NOT_CALLABLE,
        CANNOT_INFER_TYPE,
        OVERLOAD_AMBIGUITY,
        CLASSIFIER_AS_VALUE,
        FUNCTION_CALL_EXPECTED,
        DESTRUCTURING,
        DELEGATE_OPERATOR,
        NO_TYPE_NO_INITIALIZER,
        MUST_BE_INITIALIZED,
        UNINITIALIZED_VARIABLE,
        VARIABLE_EXPECTED,
        ABSTRACT_INSTANTIATION,
        TYPE_ARGUMENT_COUNT,
    )
}
