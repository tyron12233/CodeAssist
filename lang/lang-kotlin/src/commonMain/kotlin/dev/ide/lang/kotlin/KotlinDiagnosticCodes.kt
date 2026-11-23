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
    /** A concrete class/object leaves an inherited abstract member of an INTERFACE (or enum) unimplemented
     *  (K2's ABSTRACT_MEMBER_NOT_IMPLEMENTED). */
    const val ABSTRACT_NOT_IMPLEMENTED = "kt.abstractNotImplemented"
    /** The same for an abstract member of an abstract CLASS (ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED). */
    const val ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED = "kt.abstractClassMemberNotImplemented"
    /** A member inherited with several implementations, one from a class (MANY_IMPL_MEMBER_NOT_IMPLEMENTED). */
    const val MANY_IMPL_MEMBER_NOT_IMPLEMENTED = "kt.manyImplMemberNotImplemented"
    /** A member inherited from several interfaces, one with a body (MANY_INTERFACES_MEMBER_NOT_IMPLEMENTED). */
    const val MANY_INTERFACES_MEMBER_NOT_IMPLEMENTED = "kt.manyInterfacesMemberNotImplemented"
    /** Every "the class must implement or override this" code: the ones the Implement-members fix answers. */
    val IMPLEMENT_MEMBERS: Set<String> = setOf(
        ABSTRACT_NOT_IMPLEMENTED, ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED,
        MANY_IMPL_MEMBER_NOT_IMPLEMENTED, MANY_INTERFACES_MEMBER_NOT_IMPLEMENTED,
    )
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
    /** A use-site target (`@field:`, `@receiver:`, …) naming something its declaration or type does not have. */
    const val WRONG_ANNOTATION_TARGET_WITH_USE_SITE_TARGET = "kt.wrongAnnotationTargetWithUseSiteTarget"
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

    // Round 6 of the K2 ports (KotlinRound6PortedChecks.kt), one code per K2 diagnostic.
    /** `foo<*>()` / `A<in Int>()`: a projection among a call's type arguments (PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT). */
    const val PROJECTION_ON_NON_CLASS_TYPE_ARGUMENT = "kt.projectionOnNonClassTypeArgument"
    /** `super.f()` in a public-API inline function (SUPER_CALL_FROM_PUBLIC_INLINE). */
    const val SUPER_CALL_FROM_PUBLIC_INLINE = "kt.superCallFromPublicInline"
    /** `In1<out A>` where the alias expands to a class with an `in` parameter there (CONFLICTING_PROJECTION_IN_TYPEALIAS_EXPANSION). */
    const val CONFLICTING_PROJECTION_IN_TYPEALIAS_EXPANSION = "kt.conflictingProjectionInTypealiasExpansion"
    /** `fun f() = run { return 1 }`: a return out of an expression body with no declared type
     *  (RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY_AND_IMPLICIT_TYPE). */
    const val RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY_AND_IMPLICIT_TYPE = "kt.returnInExpressionBodyWithImplicitType"
    /** `A1(A2)` / `val x = A` in an enum: an entry read before it is built (UNINITIALIZED_ENUM_ENTRY). */
    const val UNINITIALIZED_ENUM_ENTRY = "kt.uninitializedEnumEntry"
    /** A `fun interface` without exactly one plain abstract function (FUN_INTERFACE_* shape errors). */
    const val FUN_INTERFACE_SHAPE = "kt.funInterfaceShape"
    /** `<A : Array<Any>>` (UPPER_BOUND_CANNOT_BE_ARRAY). */
    const val UPPER_BOUND_CANNOT_BE_ARRAY = "kt.upperBoundCannotBeArray"
    /** `String & Any`, `T & String` (INCORRECT_LEFT/RIGHT_COMPONENT_OF_INTERSECTION). */
    const val DEFINITELY_NOT_NULL_COMPONENT = "kt.definitelyNotNullComponent"
    const val EXPECTED_PARAMETER_TYPE_MISMATCH = "kt.expectedParameterTypeMismatch"
    const val NAME_IN_CONSTRAINT_IS_NOT_A_TYPE_PARAMETER = "kt.nameInConstraintIsNotATypeParameter"
    /** `a name b` where the `name` it calls is not declared `infix`. */
    const val INFIX_MODIFIER_REQUIRED = "kt.infixModifierRequired"
    /** Explicit API mode: a public API declaration without a visibility modifier (error in strict mode). */
    const val NO_EXPLICIT_VISIBILITY_IN_API_MODE = "kt.explicitApiVisibility"
    const val NO_EXPLICIT_VISIBILITY_IN_API_MODE_WARNING = "kt.explicitApiVisibilityWarning"
    /** Explicit API mode: a public API callable whose type is inferred (error in strict mode). */
    const val NO_EXPLICIT_RETURN_TYPE_IN_API_MODE = "kt.explicitApiReturnType"
    const val NO_EXPLICIT_RETURN_TYPE_IN_API_MODE_WARNING = "kt.explicitApiReturnTypeWarning"
    const val INACCESSIBLE_OUTER_CLASS_RECEIVER = "kt.inaccessibleOuterClassReceiver"
    const val INNER_CLASS_CONSTRUCTOR_NO_RECEIVER = "kt.innerClassConstructorNoReceiver"
    const val ENUM_ENTRY_AS_TYPE = "kt.enumEntryAsType"
    const val IS_ENUM_ENTRY = "kt.isEnumEntry"
    const val CONFUSING_BRANCH_CONDITION = "kt.confusingBranchCondition"
    const val NAME_FOR_AMBIGUOUS_PARAMETER = "kt.nameForAmbiguousParameter"
    /** `val (a, b) = …` outside a block: in a class body or at the top level. */
    const val NON_LOCAL_DESTRUCTURING_DECLARATION = "kt.nonLocalDestructuringDeclaration"
    /** `interface A<T : A<*>>` (FINITE_BOUNDS_VIOLATION). */
    const val FINITE_BOUNDS_VIOLATION = "kt.finiteBoundsViolation"
    /** `super<String>` in a class that does not extend it (NOT_A_SUPERTYPE). */
    const val NOT_A_SUPERTYPE = "kt.notASupertype"
    /** `val x = T` for a type parameter `T` (TYPE_PARAMETER_IS_NOT_AN_EXPRESSION). */
    const val TYPE_PARAMETER_IS_NOT_AN_EXPRESSION = "kt.typeParameterIsNotAnExpression"
    /** `override fun <reified T> f()` (REIFIED_TYPE_PARAMETER_IN_OVERRIDE). */
    const val REIFIED_TYPE_PARAMETER_IN_OVERRIDE = "kt.reifiedTypeParameterInOverride"
    /** `@JvmName("with.dot")` (ILLEGAL_JVM_NAME). */
    const val ILLEGAL_JVM_NAME = "kt.illegalJvmName"
    /** `class A private (val x: Int)` (MISSING_CONSTRUCTOR_KEYWORD). */
    const val MISSING_CONSTRUCTOR_KEYWORD = "kt.missingConstructorKeyword"
    /** `@file:Foo` on a declaration (INAPPLICABLE_FILE_TARGET). */
    const val INAPPLICABLE_FILE_TARGET = "kt.inapplicableFileTarget"
    /** `x++` through an `inc()` returning Unit (INC_DEC_SHOULD_NOT_RETURN_UNIT). */
    const val INC_DEC_SHOULD_NOT_RETURN_UNIT = "kt.incDecShouldNotReturnUnit"
    /** `Int::ext` for a member extension (EXTENSION_IN_CLASS_REFERENCE_NOT_ALLOWED). */
    const val EXTENSION_IN_CLASS_REFERENCE_NOT_ALLOWED = "kt.extensionInClassReference"
    /** An accessor's visibility against its property's (GETTER_VISIBILITY_DIFFERS_FROM_PROPERTY_VISIBILITY,
     *  SETTER_VISIBILITY_INCONSISTENT_WITH_PROPERTY_VISIBILITY). */
    const val ACCESSOR_VISIBILITY = "kt.accessorVisibility"
    /** `val <T> a = 0` or `inline fun` in a block (LOCAL_VARIABLE_WITH_TYPE_PARAMETERS,
     *  NOT_YET_SUPPORTED_LOCAL_INLINE_FUNCTION). */
    const val LOCAL_DECLARATION_SHAPE = "kt.localDeclarationShape"
    /** `when (var x = …)` (ILLEGAL_DECLARATION_IN_WHEN_SUBJECT). */
    const val ILLEGAL_DECLARATION_IN_WHEN_SUBJECT = "kt.illegalDeclarationInWhenSubject"
    /** `E(1)` for an enum class `E` (ENUM_CLASS_CONSTRUCTOR_CALL). */
    const val ENUM_CLASS_CONSTRUCTOR_CALL = "kt.enumClassConstructorCall"
    /** An inline function calling itself (RECURSION_IN_INLINE). */
    const val RECURSION_IN_INLINE = "kt.recursionInInline"
    /** `value class A(val a: A)` (VALUE_CLASS_CANNOT_BE_RECURSIVE). */
    const val VALUE_CLASS_CANNOT_BE_RECURSIVE = "kt.valueClassCannotBeRecursive"
    /** `annotation class A(val x: Int = nonConst)` (ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT). */
    const val ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT = "kt.annotationDefaultNotConstant"
    /** `suspend fun() {}` (ANONYMOUS_SUSPEND_FUNCTION). */
    const val ANONYMOUS_SUSPEND_FUNCTION = "kt.anonymousSuspendFunction"
    /** `noinline x: Int`, or `noinline` in a function that is not inline (ILLEGAL_INLINE_PARAMETER_MODIFIER). */
    const val ILLEGAL_INLINE_PARAMETER_MODIFIER = "kt.illegalInlineParameterModifier"
    /** `@set:` on a `val`, `@get:` on a function, `@delegate:` without a delegate (INAPPLICABLE_TARGET_ON_PROPERTY,
     *  INAPPLICABLE_TARGET_PROPERTY_IMMUTABLE, INAPPLICABLE_TARGET_PROPERTY_HAS_NO_DELEGATE). */
    const val PROPERTY_USE_SITE_TARGET = "kt.propertyUseSiteTarget"
    /** `c += x` through a `plusAssign` returning a value (ASSIGNMENT_OPERATOR_SHOULD_RETURN_UNIT). */
    const val ASSIGNMENT_OPERATOR_SHOULD_RETURN_UNIT = "kt.assignmentOperatorShouldReturnUnit"
    /** `var x: Int … set(value: Long)` (WRONG_SETTER_PARAMETER_TYPE). */
    const val WRONG_SETTER_PARAMETER_TYPE = "kt.wrongSetterParameterType"
    /** An enum entry leaving an abstract member unimplemented (ABSTRACT_MEMBER_NOT_IMPLEMENTED_BY_ENUM_ENTRY). */
    const val ABSTRACT_MEMBER_NOT_IMPLEMENTED_BY_ENUM_ENTRY = "kt.abstractMemberNotImplementedByEnumEntry"
    /** `private open fun`, `Foo<in out T>`: the compatibility table's pairs not covered by [MODIFIERS] (INCOMPATIBLE_MODIFIERS). */
    const val INCOMPATIBLE_MODIFIERS = "kt.incompatibleModifiers"
    /** `::class` with nothing on its left (UNSUPPORTED_CLASS_LITERALS_WITH_EMPTY_LHS). */
    const val UNSUPPORTED_CLASS_LITERALS_WITH_EMPTY_LHS = "kt.emptyLhsClassLiteral"
    /** `typealias X<T> = T` (TYPEALIAS_SHOULD_EXPAND_TO_CLASS). */
    const val TYPEALIAS_SHOULD_EXPAND_TO_CLASS = "kt.typealiasShouldExpandToClass"
    /** `typealias A<T : Number> = …` (BOUND_ON_TYPE_ALIAS_PARAMETER_NOT_ALLOWED). */
    const val BOUND_ON_TYPE_ALIAS_PARAMETER_NOT_ALLOWED = "kt.boundOnTypeAliasParameter"
    /** `val f = fun ()` without a body (NON_MEMBER_FUNCTION_NO_BODY). */
    const val NON_MEMBER_FUNCTION_NO_BODY = "kt.nonMemberFunctionNoBody"
    /** `val a by d` with `get() = …` (ACCESSOR_FOR_DELEGATED_PROPERTY). */
    const val ACCESSOR_FOR_DELEGATED_PROPERTY = "kt.accessorForDelegatedProperty"
    /** `inline fun f(p: (() -> Unit)?)` (NULLABLE_INLINE_PARAMETER). */
    const val NULLABLE_INLINE_PARAMETER = "kt.nullableInlineParameter"
    /** `T<Int>` for a type parameter `T` (TYPE_ARGUMENTS_NOT_ALLOWED). */
    const val TYPE_ARGUMENTS_NOT_ALLOWED = "kt.typeArgumentsNotAllowed"
    /** `_@ expr`, `fun f(_: Int)` (UNDERSCORE_IS_RESERVED). */
    const val UNDERSCORE_IS_RESERVED = "kt.underscoreIsReserved"
    /** `@ExtensionFunctionType () -> Int` (WRONG_EXTENSION_FUNCTION_TYPE). */
    const val WRONG_EXTENSION_FUNCTION_TYPE = "kt.wrongExtensionFunctionType"
    /** `override val` over a `var` (VAR_OVERRIDDEN_BY_VAL). */
    const val VAR_OVERRIDDEN_BY_VAL = "kt.varOverriddenByVal"
    /** `@SinceKotlin("1")` (ILLEGAL_KOTLIN_VERSION_STRING_VALUE). */
    const val ILLEGAL_KOTLIN_VERSION_STRING_VALUE = "kt.illegalKotlinVersionString"
    /** `@JvmExposeBoxed` on a private declaration (JVM_EXPOSE_BOXED_CANNOT_EXPOSE_PRIVATE). */
    const val JVM_EXPOSE_BOXED_CANNOT_EXPOSE_PRIVATE = "kt.jvmExposeBoxedPrivate"
    /** `val a by A()` where `A` has no `getValue` (DELEGATE_SPECIAL_FUNCTION_MISSING). */
    const val DELEGATE_SPECIAL_FUNCTION_MISSING = "kt.delegateSpecialFunctionMissing"
    /** `val a: Int by A()` where no `getValue`/`setValue` of `A` can take the property's call. */
    const val DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE = "kt.delegateSpecialFunctionNoneApplicable"
    /** A captured local `val` assigned in a lambda or local function (CAPTURED_VAL_INITIALIZATION). */
    const val CAPTURED_VAL_INITIALIZATION = "kt.capturedValInitialization"
    /** `v += x` where both `plusAssign` and `plus` apply (ASSIGN_OPERATOR_AMBIGUITY). */
    const val ASSIGN_OPERATOR_AMBIGUITY = "kt.assignOperatorAmbiguity"
    /** `A()` calling a protected constructor from a subclass body (PROTECTED_CONSTRUCTOR_NOT_IN_SUPER_CALL). */
    const val PROTECTED_CONSTRUCTOR_NOT_IN_SUPER_CALL = "kt.protectedConstructorNotInSuperCall"

    /** A class whose supertypes mix a plain function type with a suspend one (MIXING_SUSPEND_AND_NON_SUSPEND_SUPERTYPES). */
    const val MIXING_SUSPEND_AND_NON_SUSPEND_SUPERTYPES = "kt.mixingSuspendAndNonSuspendSupertypes"

    /** A construct K2 parses but does not support (UNSUPPORTED): a dynamic type on the JVM, a local type alias, a
     *  default value or modifier on a function type's parameter, a callable reference to a local variable. */
    const val UNSUPPORTED = "kt.unsupported"

    /** An annotation use or an enum entry that leaves a constructor parameter without a value (NO_VALUE_FOR_PARAMETER). */
    const val NO_VALUE_FOR_PARAMETER = "kt.noValueForParameter"

    /** A reference closing a cycle of implicitly typed bodies (TYPECHECKER_HAS_RUN_INTO_RECURSIVE_PROBLEM). */
    const val RECURSIVE_IMPLICIT_TYPE = "kt.recursiveImplicitType"

    // The shape rules of a value class (FirValueClassDeclarationChecker), one code per K2 name.
    const val VALUE_CLASS_NOT_TOP_LEVEL = "kt.valueClassNotTopLevel"
    const val VALUE_CLASS_NOT_FINAL = "kt.valueClassNotFinal"
    const val VALUE_CLASS_CANNOT_EXTEND_CLASSES = "kt.valueClassCannotExtendClasses"
    const val VALUE_CLASS_CANNOT_IMPLEMENT_INTERFACE_BY_DELEGATION = "kt.valueClassCannotImplementInterfaceByDelegation"
    const val INNER_CLASS_INSIDE_VALUE_CLASS = "kt.innerClassInsideValueClass"
    const val DELEGATED_PROPERTY_INSIDE_VALUE_CLASS = "kt.delegatedPropertyInsideValueClass"
    const val PROPERTY_WITH_BACKING_FIELD_INSIDE_VALUE_CLASS = "kt.propertyWithBackingFieldInsideValueClass"
    const val ABSENCE_OF_PRIMARY_CONSTRUCTOR_FOR_VALUE_CLASS = "kt.absenceOfPrimaryConstructorForValueClass"
    const val INLINE_CLASS_CONSTRUCTOR_WRONG_PARAMETERS_SIZE = "kt.inlineClassConstructorWrongParametersSize"
    const val VALUE_CLASS_CONSTRUCTOR_NOT_FINAL_READ_ONLY_PARAMETER = "kt.valueClassConstructorNotFinalReadOnlyParameter"
    const val VALUE_CLASS_HAS_INAPPLICABLE_PARAMETER_TYPE = "kt.valueClassHasInapplicableParameterType"
    /** A member inherited from several supertypes whose types no one of them can stand for
     *  (RETURN_TYPE_MISMATCH_ON_INHERITANCE, PROPERTY_TYPE_MISMATCH_ON_INHERITANCE, VAR_TYPE_MISMATCH_ON_INHERITANCE). */
    const val INHERITANCE_TYPE_MISMATCH = "kt.inheritanceTypeMismatch"
    /** One generic class reached through the supertypes with two arguments for a parameter (INCONSISTENT_TYPE_PARAMETER_VALUES). */
    const val INCONSISTENT_TYPE_PARAMETER_VALUES = "kt.inconsistentTypeParameterValues"

    // The eighth round of ported K2 rules (KotlinRound8PortedChecks), one code per K2 name.
    const val VAL_REASSIGNMENT_VIA_BACKING_FIELD = "kt.valReassignmentViaBackingField"
    const val NOT_AN_ANNOTATION_CLASS = "kt.notAnAnnotationClass"
    const val NOT_A_CLASS = "kt.notAClass"
    const val ELSE_MISPLACED_IN_WHEN = "kt.elseMisplacedInWhen"
    const val DATA_OBJECT_CUSTOM_EQUALS_OR_HASH_CODE = "kt.dataObjectCustomEqualsOrHashCode"
    const val PRIVATE_FUNCTION_WITH_NO_BODY = "kt.privateFunctionWithNoBody"
    const val DEPRECATED_TYPE_PARAMETER_SYNTAX = "kt.deprecatedTypeParameterSyntax"
    const val VAL_WITH_SETTER = "kt.valWithSetter"
    const val WRONG_GETTER_RETURN_TYPE = "kt.wrongGetterReturnType"
    const val WRONG_SETTER_RETURN_TYPE = "kt.wrongSetterReturnType"
    const val SUPERTYPE_NOT_A_CLASS_OR_INTERFACE = "kt.supertypeNotAClassOrInterface"
    const val CALLABLE_REFERENCE_LHS_NOT_A_CLASS = "kt.callableReferenceLhsNotAClass"
    const val TYPE_PARAMETER_ON_LHS_OF_DOT = "kt.typeParameterOnLhsOfDot"
    const val CATCH_PARAMETER_WITH_DEFAULT_VALUE = "kt.catchParameterWithDefaultValue"
    const val TYPE_PARAMETER_IN_CATCH_CLAUSE = "kt.typeParameterInCatchClause"
    const val THROWABLE_TYPE_MISMATCH = "kt.throwableTypeMismatch"
    const val UNSUPPORTED_SEALED_FUN_INTERFACE = "kt.unsupportedSealedFunInterface"
    const val ABSTRACT_PROPERTY_IN_PRIMARY_CONSTRUCTOR_PARAMETERS = "kt.abstractPropertyInPrimaryConstructorParameters"
    const val UNEXPECTED_SAFE_CALL = "kt.unexpectedSafeCall"
    const val NULLABLE_ON_DEFINITELY_NOT_NULLABLE = "kt.nullableOnDefinitelyNotNullable"
    const val ANNOTATION_ARGUMENT_WITH_CONTROL_FLOW_NOT_SUPPORTED = "kt.annotationArgumentWithControlFlowNotSupported"
    const val ANNOTATION_ARGUMENT_KCLASS_LITERAL_OF_TYPE_PARAMETER_ERROR = "kt.annotationArgumentKclassLiteralOfTypeParameterError"
    const val TYPE_PARAMETER_AS_REIFIED_ARRAY_ERROR = "kt.typeParameterAsReifiedArrayError"
    const val DEFINITELY_NON_NULLABLE_AS_REIFIED = "kt.definitelyNonNullableAsReified"
    const val RESTRICTED_RETENTION_FOR_EXPRESSION_ANNOTATION_ERROR = "kt.restrictedRetentionForExpressionAnnotationError"
    const val CALLABLE_REFERENCE_TO_ANNOTATION_CONSTRUCTOR = "kt.callableReferenceToAnnotationConstructor"
    const val JAVA_SAM_INTERFACE_CONSTRUCTOR_REFERENCE = "kt.javaSamInterfaceConstructorReference"
    const val ABBREVIATED_NOTHING_RETURN_TYPE = "kt.abbreviatedNothingReturnType"
    const val ABBREVIATED_NOTHING_PROPERTY_TYPE = "kt.abbreviatedNothingPropertyType"
    const val PACKAGE_CONFLICTS_WITH_CLASSIFIER = "kt.packageConflictsWithClassifier"
    const val SEALED_INHERITOR_IN_DIFFERENT_PACKAGE = "kt.sealedInheritorInDifferentPackage"
    const val OVERRIDING_FINAL_MEMBER_BY_DELEGATION = "kt.overridingFinalMemberByDelegation"
    const val VAR_OVERRIDDEN_BY_VAL_BY_DELEGATION = "kt.varOverriddenByValByDelegation"
    const val MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES = "kt.multipleDefaultsInheritedFromSupertypes"
    const val SAFE_CALLABLE_REFERENCE_CALL = "kt.safeCallableReferenceCall"
    const val UNSAFE_CALLABLE_REFERENCE = "kt.unsafeCallableReference"
    const val INVALID_DEFAULT_FUNCTIONAL_PARAMETER_FOR_INLINE = "kt.invalidDefaultFunctionalParameterForInline"
    const val NOT_SUPPORTED_INLINE_PARAMETER_IN_INLINE_PARAMETER_DEFAULT_VALUE = "kt.notSupportedInlineParameterInInlineParameterDefaultValue"
    const val TYPEALIAS_EXPANSION_CAPTURES_OUTER_TYPE_PARAMETERS = "kt.typealiasExpansionCapturesOuterTypeParameters"
    const val DELEGATE_USES_EXTENSION_PROPERTY_TYPE_PARAMETER_ERROR = "kt.delegateUsesExtensionPropertyTypeParameterError"
    const val SUPERCLASS_NOT_ACCESSIBLE_FROM_INTERFACE = "kt.superclassNotAccessibleFromInterface"
    const val AMBIGUOUS_LABEL = "kt.ambiguousLabel"
    const val FUNCTION_DELEGATE_MEMBER_NAME_CLASH = "kt.functionDelegateMemberNameClash"
    const val CANNOT_BE_IMPORTED = "kt.cannotBeImported"
    const val CYCLIC_GENERIC_UPPER_BOUND = "kt.cyclicGenericUpperBound"
    const val CYCLIC_INHERITANCE_HIERARCHY = "kt.cyclicInheritanceHierarchy"
    const val IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL = "kt.impossibleIsCheckRelyingOnNull"
    const val ITERATOR_ON_NULLABLE = "kt.iteratorOnNullable"
    const val HAS_NEXT_MISSING = "kt.hasNextMissing"
    const val NEXT_MISSING = "kt.nextMissing"
    const val HAS_NEXT_FUNCTION_TYPE_MISMATCH = "kt.hasNextFunctionTypeMismatch"
    const val TYPE_VARIANCE_CONFLICT_IN_EXPANDED_TYPE = "kt.typeVarianceConflictInExpandedType"
    const val EXTERNAL_DECLARATION_IN_INTERFACE = "kt.externalDeclarationInInterface"
    const val EXTERNAL_DECLARATION_CANNOT_BE_ABSTRACT = "kt.externalDeclarationCannotBeAbstract"
    const val EXTERNAL_DECLARATION_CANNOT_HAVE_BODY = "kt.externalDeclarationCannotHaveBody"
    const val EXPECTED_DECLARATION_WITH_BODY = "kt.expectedDeclarationWithBody"
    const val EXPECTED_PROPERTY_INITIALIZER = "kt.expectedPropertyInitializer"
    const val EXPECT_AND_ACTUAL_IN_THE_SAME_MODULE = "kt.expectAndActualInTheSameModule"
    const val INVALID_CHARACTERS = "kt.invalidCharacters"
    const val DEPRECATED_SINCE_KOTLIN_OUTSIDE_KOTLIN_SUBPACKAGE = "kt.deprecatedSinceKotlinOutsideKotlinSubpackage"
    const val PLACEHOLDER_PROJECTION_IN_QUALIFIER = "kt.placeholderProjectionInQualifier"

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
