package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.parse.hasAncestor
import dev.ide.lang.kotlin.parse.inTypeReference
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.*
import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.DefaultImports
import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.SymbolKind
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBinaryExpressionWithTypeRHS
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtTypeParameterListOwner
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtProjectionKind
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeEntry
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import dev.ide.lang.kotlin.symbols.KotlinType

/**
 * The Kotlin editor's semantic diagnostics: the per-declaration checks (unresolved references, type
 * mismatches, call applicability, modifier and declaration validity, when-exhaustiveness, and so on)
 * plus the incremental-analyze engine that reuses unchanged declarations' results across keystrokes.
 * Extracted from [KotlinSourceAnalyzer]; one instance is held per analyzer (the incremental cache lives
 * here), built over the module's [KotlinSymbolService] with a fresh [KotlinResolver] per file. Every
 * check is conservative: it backs off when the parse-only symbol model cannot decide, so it never
 * false-positives. See [semanticDiagnostics] for the incremental-reuse contract.
 */
internal class KotlinSemanticChecks(private val service: KotlinSymbolService) {

    /** The whole-file checks (duplicate declarations, unused/conflicting imports, unused privates). Cheap (no
     *  type resolution), so [IncrementalSemanticAnalysis] recomputes them every analyze rather than caching. */
    fun fileLevelDiagnostics(ktFile: KtFile): List<Diagnostic> {
        val refNames = referencedNames(ktFile) // names used in the body, for unused-import / unused-private
        val out = ArrayList<Diagnostic>()
        out += duplicateDeclarations(ktFile.declarations)
        out += unusedImports(ktFile, refNames)
        out += conflictingImports(ktFile)
        unusedPrivateDeclarations(ktFile, refNames, out)
        return out
    }

    /**
     * The semantic checkers, one per PSI element kind, run by a [dev.ide.lang.kotlin.analysis.KotlinCheckerDriver]
     * in a single read-only walk (replacing the old hand-written dispatch). Only declaration-LOCAL checks live
     * here; whole-file ones (unused-private, unused-import, top-level duplicates) are in [fileLevelDiagnostics]
     * so a body edit elsewhere can't leave a stale reused result. Registration order is the report order.
     *
     * Gating mirrors the checks' preconditions: `if (resolveReady)` guards the checks that need the classpath
     * index (unresolved symbol/type, inheritance) so "dumb mode" (index still building) never false-positives;
     * `if (!skipCrossStatement)` guards the two whole-body local checks (unused-local / var-could-be-val), which
     * the incremental path runs once over a function instead of per statement (see [localDeclarationChecks]).
     * The `KtDeclaration` checker runs for every declaration node (class/member/parameter), like the old
     * pre-dispatch modifier check. Built once; the checkers are stateless beyond the per-pass caches on `this`.
     */
    fun checkers(): List<KotlinChecker> = listOf(
        KotlinChecker(KtDeclaration::class.java) { psi ->
            KotlinPerf.span("sem.modifiers") { reportAll(modifierConflicts(psi as KtDeclaration)) }
        },
        KotlinChecker(KtNameReferenceExpression::class.java) { psi ->
            psi as KtNameReferenceExpression
            KotlinPerf.span("sem.nameRef") {
                if (resolveReady) {
                    val unresolved = unresolvedMember(psi, resolver) ?: unresolvedBareReference(psi, resolver)
                    report(unresolved)
                    // Only when the reference DOES resolve (to a classifier): a class used as a value must be
                    // initialized (`GridCells.Fixed` → `GridCells.Fixed(2)`).
                    if (unresolved == null) report(classifierUsedAsValue(psi, resolver))
                }
            }
        },
        KotlinChecker(KtUserType::class.java) { psi ->
            psi as KtUserType
            KotlinPerf.span("sem.type") {
                if (resolveReady) {
                    report(unresolvedTypeReference(psi, resolver, localAliases))
                    report(typeArgumentCountMismatch(psi, resolver))
                    reportAll(typeArgumentBoundViolation(psi, resolver))
                    reportAll(useSiteProjectionMisuse(psi, resolver))
                }
            }
        },
        KotlinChecker(KtProperty::class.java) { psi ->
            psi as KtProperty
            KotlinPerf.span("sem.prop") {
                // `typeMismatch` infers the initializer's type — the per-declaration resolution cost — so it's
                // split out from the cheap syntactic property checks to make that cost visible.
                KotlinPerf.span("prop.typeMismatch") { report(typeMismatch(psi.typeReference?.text, psi.initializer, resolver)) }
                if (!skipCrossStatement) report(unusedLocal(psi))
                report(missingInitializer(psi))
                report(noTypeNoInitializer(psi))
                report(lateinitMisuse(psi))
                report(abstractMisuse(psi))
                if (!skipCrossStatement) report(varCouldBeVal(psi))
                report(delegateOperatorNotInScope(psi, resolver))
                report(constMisuse(psi))
                report(localNameShadowing(psi)) // a cheap per-declaration ancestor walk, not a whole-body scan
            }
        },
        KotlinChecker(KtParameter::class.java) { psi ->
            psi as KtParameter
            KotlinPerf.span("sem.param") {
                report(typeMismatch(psi.typeReference?.text, psi.defaultValue, resolver))
                report(valVarOnParameter(psi))
            }
        },
        KotlinChecker(KtNamedFunction::class.java) { psi ->
            psi as KtNamedFunction
            KotlinPerf.span("sem.func") {
                report(abstractMisuse(psi))
                report(functionWithoutBody(psi))
                // a block-body function must return a value (missing-return); an expression body is type-checked.
                if (psi.hasBlockBody()) report(missingReturn(psi, resolver))
                else report(typeMismatch(psi.typeReference?.text, psi.bodyExpression, resolver))
                report(previewMisuse(psi))
                reportAll(unusedParameters(psi)) // function-level (whole-body), so not gated by skipCrossStatement
                reportAll(uninitializedVariables(psi, resolver)) // CFG definite-assignment (whole-body)
            }
        },
        KotlinChecker(KtFunctionLiteral::class.java) { psi ->
            KotlinPerf.span("sem.lambda") { reportAll(unusedLambdaParameters(psi as KtFunctionLiteral)) }
        },
        KotlinChecker(KtReturnExpression::class.java) { psi ->
            KotlinPerf.span("sem.return") { report(returnTypeMismatch(psi as KtReturnExpression, resolver)) }
        },
        KotlinChecker(KtBinaryExpression::class.java) { psi ->
            psi as KtBinaryExpression
            KotlinPerf.span("sem.binary") {
                report(valReassignment(psi))
                report(variableExpected(psi))
                report(assignmentInExpression(psi))
                report(uselessElvis(psi, resolver))
                report(assignmentTypeMismatch(psi, resolver))
                report(senselessNullComparison(psi, resolver))
                if (resolveReady) report(incomparableEquality(psi, resolver))
            }
        },
        KotlinChecker(KtWhenExpression::class.java) { psi ->
            psi as KtWhenExpression
            KotlinPerf.span("sem.when") {
                report(whenNotExhaustive(psi, resolver))
                reportAll(duplicateWhenBranches(psi))
            }
        },
        KotlinChecker(KtBinaryExpressionWithTypeRHS::class.java) { psi ->
            psi as KtBinaryExpressionWithTypeRHS
            KotlinPerf.span("sem.cast") {
                report(uselessCast(psi, resolver))
                if (resolveReady) report(castNeverSucceeds(psi, resolver))
            }
        },
        KotlinChecker(org.jetbrains.kotlin.psi.KtIsExpression::class.java) { psi ->
            KotlinPerf.span("sem.isCheck") { if (resolveReady) report(uselessIsCheck(psi as org.jetbrains.kotlin.psi.KtIsExpression, resolver)) }
        },
        // A destructuring (`val (a, b) = …`, `for ((k, v) in …)`, `{ (k, v) -> }`) needs a componentN() per
        // entry; gated on resolveReady since library/builtin component operators come from the classpath.
        KotlinChecker(KtDestructuringDeclaration::class.java) { psi ->
            KotlinPerf.span("sem.destructuring") { if (resolveReady) reportAll(destructuringMismatch(psi as KtDestructuringDeclaration, resolver)) }
        },
        KotlinChecker(KtCallExpression::class.java) { psi ->
            psi as KtCallExpression
            KotlinPerf.span("sem.call") {
                KotlinPerf.span("call.argCount") {
                    // The same-file PSI arity check first (precise for a unique same-file function); then the
                    // overload-aware applicability check (wrong-typed / too-many args for an overloaded, library,
                    // or member call); then the missing-required check (which the applicability check defers to).
                    report(argumentCountMismatch(psi) ?: callNotApplicable(psi, resolver) ?: missingRequiredArgument(psi, resolver))
                }
                KotlinPerf.span("call.ambiguity") { if (resolveReady) report(overloadAmbiguity(psi, resolver)) }
                KotlinPerf.span("call.typeArgBounds") { if (resolveReady) reportAll(callTypeArgumentBoundViolation(psi, resolver)) }
                KotlinPerf.span("call.notCallable") { report(notCallable(psi, resolver)) }
                KotlinPerf.span("call.ctor") { report(constructorCallMismatch(psi, resolver) ?: sameFileConstructorMismatch(psi, resolver)) }
                KotlinPerf.span("call.namedArgs") { reportAll(unknownNamedArguments(psi, resolver)) }
                KotlinPerf.span("call.propertyAccess") { report(usePropertyAccess(psi, resolver)) }
                KotlinPerf.span("call.composable") { report(composableInvocation(psi, resolver)) }
                KotlinPerf.span("call.suspend") { report(suspendInvocation(psi, resolver)) }
                KotlinPerf.span("call.deprecation") { if (resolveReady) report(deprecatedCall(psi, resolver)) }
                KotlinPerf.span("call.inferType") { report(cannotInferType(psi, resolver)) }
                KotlinPerf.span("call.abstractInst") { if (resolveReady) report(abstractInstantiation(psi, resolver)) }
            }
        },
        // `unsafeNullableAccess` infers the receiver's type — another per-node resolution cost, so spanned.
        KotlinChecker(KtDotQualifiedExpression::class.java) { psi ->
            KotlinPerf.span("sem.qualified") { report(unsafeNullableAccess(psi as KtDotQualifiedExpression, resolver)) }
        },
        KotlinChecker(KtSafeQualifiedExpression::class.java) { psi ->
            KotlinPerf.span("sem.safeCall") { report(redundantSafeCall(psi as KtSafeQualifiedExpression, resolver)) }
        },
        KotlinChecker(KtPostfixExpression::class.java) { psi ->
            psi as KtPostfixExpression
            KotlinPerf.span("sem.notNull") { report(redundantNotNull(psi, resolver)) }
            KotlinPerf.span("sem.valIncDec") { report(valIncDecReassignment(psi) ?: incrementTargetExpected(psi)) }
        },
        KotlinChecker(KtPrefixExpression::class.java) { psi ->
            psi as KtPrefixExpression
            KotlinPerf.span("sem.valIncDec") { report(valIncDecReassignment(psi) ?: incrementTargetExpected(psi)) }
        },
        KotlinChecker(org.jetbrains.kotlin.psi.KtTryExpression::class.java) { psi ->
            KotlinPerf.span("sem.tryCatch") { if (resolveReady) reportAll(unreachableCatch(psi as org.jetbrains.kotlin.psi.KtTryExpression, resolver)) }
        },
        KotlinChecker(KtStringTemplateExpression::class.java) { psi ->
            KotlinPerf.span("sem.template") { reportAll(redundantTemplateBraces(psi as KtStringTemplateExpression)) }
        },
        // Conflicting declarations within a scope below the file: a parameter list, a block (locals), or a
        // class body. (Top-level conflicts are a file-level check in fileLevelDiagnostics.)
        KotlinChecker(KtParameterList::class.java) { psi ->
            KotlinPerf.span("sem.dup") { reportAll(duplicateParams(psi as KtParameterList)) }
        },
        KotlinChecker(KtBlockExpression::class.java) { psi ->
            psi as KtBlockExpression
            KotlinPerf.span("sem.block") {
                reportAll(duplicateDeclarations(psi.statements.filterIsInstance<KtDeclaration>()))
                reportAll(unreachableCode(psi, resolver))
                reportAll(unusedExpressions(psi))
            }
        },
        KotlinChecker(KtClassBody::class.java) { psi ->
            KotlinPerf.span("sem.dup") { reportAll(duplicateDeclarations((psi as KtClassBody).declarations)) }
        },
        // Declaration-site variance conflicts (an `out` param in an `in` position, etc.). On KtClass (only a
        // class/interface can declare variant type parameters); resolution-gated because nested-generic
        // composition resolves other classifiers' variance.
        KotlinChecker(KtClass::class.java) { psi ->
            KotlinPerf.span("sem.variance") { if (resolveReady) reportAll(declarationSiteVarianceConflicts(psi as KtClass, resolver)) }
        },
        // Class/object-level inheritance correctness (abstract-not-implemented, nothing-to-override,
        // override-required). Resolution-gated: needs the supertype closure, so it runs only when ready.
        KotlinChecker(KtClassOrObject::class.java) { psi ->
            psi as KtClassOrObject
            KotlinPerf.span("sem.inherit") {
                if (resolveReady) {
                    val diags = ArrayList<Diagnostic>()
                    inheritanceDiagnostics(psi, resolver, diags)
                    reportAll(diags)
                }
            }
            KotlinPerf.span("sem.superInit") { reportAll(supertypeNotInitialized(psi, resolver)) }
        },
    )

    /**
     * A class-type supertype written WITHOUT its constructor call — Kotlin's SUPERTYPE_NOT_INITIALIZED
     * (`class C : Base` must be `class C : Base()`). Fires only when the supertype must be initialized in the
     * supertype list — i.e. the declaring type has a primary constructor (explicit or implicit); a class with
     * ONLY secondary constructors initializes it via `: super(...)` instead, and an interface's supertypes are
     * never initialized. The supertype must resolve to a CLASS (an interface is bare-legal; abstract classes
     * still need `()`); an unknown or type-parameter supertype backs off. Star/generic supertypes still count.
     */
    private fun supertypeNotInitialized(cls: KtClassOrObject, resolver: KotlinResolver): List<Diagnostic> {
        if (cls is KtClass && (cls.isInterface() || cls.isAnnotation())) return emptyList()
        if (cls is KtClass && cls.primaryConstructor == null && cls.secondaryConstructors.isNotEmpty()) return emptyList()
        val out = ArrayList<Diagnostic>()
        for (entry in cls.superTypeListEntries) {
            if (entry !is KtSuperTypeEntry) continue // a call entry has `()`; a delegated entry is `by` (interfaces)
            val userType = entry.typeReference?.typeElement as? KtUserType ?: continue
            val name = userType.referenceExpression?.getReferencedName() ?: continue
            if (resolver.isTypeParameterInScope(name, entry.textRange.startOffset)) continue
            val fqn = service.resolveTypeName(name, resolver.fileContext) ?: continue
            if (service.isInterfaceType(fqn) != false) continue // interface (bare-legal) or unknown → back off
            val r = entry.textRange
            out += Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "This type has a constructor, and thus must be initialized here", KotlinDiagnosticCodes.SUPERTYPE_NOT_INITIALIZED,
            )
        }
        return out
    }

    /** Validate a `@Preview` composable: the target must be `@Composable`, and a previewed composable must have
     *  no required value parameters (each must default or be fed by `@PreviewParameter`) or it can't be rendered.
     *  Best-effort and conservative (detects direct `@Preview` + built-in MultiPreview annotations by name). */
    private fun previewMisuse(fn: KtNamedFunction): Diagnostic? {
        val previewAnn = fn.annotationEntries.firstOrNull { e ->
            val n = e.shortName?.asString()
            n == "Preview" || (n != null && n in dev.ide.lang.kotlin.interp.PreviewConstants.builtinMultiPreviews)
        } ?: return null
        if (fn.annotationEntries.none { it.shortName?.asString() == "Composable" }) {
            val r = previewAnn.textRange
            return Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.WARNING,
                "A @Preview function must be annotated @Composable to render",
                KotlinDiagnosticCodes.PREVIEW_NOT_COMPOSABLE,
            )
        }
        val unfed = fn.valueParameters.firstOrNull { p ->
            p.defaultValue == null && p.annotationEntries.none { it.shortName?.asString() == "PreviewParameter" }
        } ?: return null
        val r = unfed.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.WARNING,
            "Preview parameter '${unfed.name}' has no default and no @PreviewParameter — the preview can't be rendered",
            KotlinDiagnosticCodes.PREVIEW_PARAMETERS,
        )
    }

    // --- inheritance / override correctness (all resolution-gated; see KotlinResolver.inheritanceProblems) ---

    /**
     * The class/object inheritance diagnostics in one pass: unimplemented inherited abstract members (concrete
     * declarations only), an `override` that overrides nothing, and a member that hides an inherited one but is
     * missing `override`. Conservative throughout — [KotlinResolver.inheritanceProblems] backs off (emits
     * nothing) whenever the supertype closure can't be resolved, so the parse-only model never false-positives.
     */
    private fun inheritanceDiagnostics(cls: KtClassOrObject, resolver: KotlinResolver, out: MutableList<Diagnostic>) {
        if (cls is KtEnumEntry) return // an enum entry's abstract impls belong to the enum constant's own body
        val report = resolver.inheritanceProblems(cls, concrete = isConcreteImplementor(cls))
        if (report.isEmpty) return
        if (report.missing.isNotEmpty()) {
            val names = report.missing.joinToString(", ") { if (it.kind == SymbolKind.METHOD) "${it.name}()" else it.name }
            val word = if (report.missing.size == 1) "member" else "members"
            val noun = if (cls is KtObjectDeclaration) "Object" else "Class"
            out += Diagnostic(
                declNameRange(cls), Severity.ERROR,
                "$noun '${cls.name ?: "<anonymous>"}' must implement abstract $word: $names",
                KotlinDiagnosticCodes.ABSTRACT_NOT_IMPLEMENTED,
            )
        }
        report.overridesNothing.forEach { m ->
            out += Diagnostic(
                memberNameRange(m), Severity.ERROR,
                "'${m.name}' overrides nothing", KotlinDiagnosticCodes.NOTHING_TO_OVERRIDE,
            )
        }
        report.needsOverride.forEach { (m, _) ->
            out += Diagnostic(
                memberNameRange(m), Severity.ERROR,
                "'${m.name}' hides a supertype member and must be marked with the 'override' modifier",
                KotlinDiagnosticCodes.OVERRIDE_REQUIRED,
            )
        }
    }

    /** A concrete, instantiable declaration that MUST implement its inherited abstract members — a plain class
     *  or any object; NOT an interface / abstract / sealed / enum / annotation class / `expect` declaration. */
    private fun isConcreteImplementor(cls: KtClassOrObject): Boolean {
        if (cls.hasModifier(KtTokens.EXPECT_KEYWORD)) return false
        return when (cls) {
            is KtObjectDeclaration -> true
            is KtClass -> !cls.isInterface() && !cls.isEnum() && !cls.isAnnotation() &&
                !cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !cls.hasModifier(KtTokens.SEALED_KEYWORD)
            else -> false
        }
    }

    /** A constructor call `Type(...)` on an `interface` or `abstract`/`sealed` class — which cannot be created.
     *  Conservative: fires only when [Type] resolves to a known non-instantiable type, has no companion object
     *  (a companion `invoke` would make the call valid), is not a SAM conversion, and is not shadowed by a
     *  same-named factory FUNCTION. */
    private fun abstractInstantiation(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        val name = callee.getReferencedName()
        val fqn = service.resolveTypeName(name, resolver.fileContext) ?: return null
        if (service.isNonInstantiableType(fqn) != true) return null
        if (service.typeHasCompanionObject(fqn)) return null
        // SAM conversion: `Runnable { … }`, `Comparator(::cmp)`, `OnClickListener { … }` build a functional-
        // interface instance from a single function value — valid Kotlin (any Java single-abstract-method
        // interface; a Kotlin `fun interface`). Only INTERFACES are SAM-convertible (an abstract class with a
        // trailing lambda is still an error), so back off only when the callee is an interface invoked with a
        // lone functional argument.
        if (service.isInterfaceType(fqn) == true && isSamConversion(call)) return null
        // A factory FUNCTION of the same name (`MutableList(…)`, a user factory) means this is a call, not a
        // constructor — callTargets surfaces it as a METHOD. Only flag a pure constructor call.
        if (runCatching { resolver.callTargets(call) }.getOrDefault(emptyList()).any { it.kind == SymbolKind.METHOD }) return null
        val r = callee.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Cannot create an instance of abstract class or interface '$name'",
            KotlinDiagnosticCodes.ABSTRACT_INSTANTIATION,
        )
    }

    /** A SAM conversion: the call's SOLE argument is a function value — a lambda (`Runnable { … }`), a callable
     *  reference (`Comparator(::cmp)`), or an anonymous function (`Runnable(fun() { … })`). Those forms are only
     *  meaningful as a SAM conversion / functional argument, so a functional interface is being constructed. */
    private fun isSamConversion(call: KtCallExpression): Boolean {
        val args = call.valueArguments // includes the trailing lambda argument
        val arg = args.singleOrNull()?.getArgumentExpression() ?: return false
        return arg is org.jetbrains.kotlin.psi.KtLambdaExpression ||
            arg is org.jetbrains.kotlin.psi.KtCallableReferenceExpression ||
            (arg is KtNamedFunction && arg.name == null)
    }

    /**
     * A CLASSIFIER (a class/interface) used as a VALUE without being initialized — the reported `columns =
     * GridCells.Fixed` (where `Fixed` is a `class Fixed(count: Int)`), or a bare `val x = Foo`. A class name in
     * value position is not itself a value: it must be instantiated (`GridCells.Fixed(2)`); only a class WITH a
     * companion object refers to that companion. The compiler's "Classifier 'X' does not have a companion object,
     * and thus must be initialized here". Conservative — fires only when the reference CONFIDENTLY resolves to a
     * known classifier that is neither an object, a companion, nor a companion-bearing class, sits in a plain
     * value position (not a constructor call, a further `.member`/`::` access, a type reference, or an import),
     * and — for a bare name — is not shadowed by a value binding in scope.
     */
    private fun classifierUsedAsValue(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
        val name = ref.getReferencedName()
        // Classifiers are conventionally capitalized; the gate keeps this off the hot path for the many lowercase
        // value reads (locals, properties, calls) that can never be a classifier.
        if (name.isEmpty() || !name[0].isUpperCase() || name == "Companion") return null
        if (inImportOrPackage(ref) || inTypeReference(ref)) return null
        if (ref.parent is org.jetbrains.kotlin.psi.KtInstanceExpressionWithLabel) return null // this/super
        if (hasAncestor(ref) { it is org.jetbrains.kotlin.psi.KtAnnotationEntry }) return null

        // The full value expression + the name to resolve: for `Outer.Inner` it's the enclosing dot-qualified
        // expression ("Outer.Inner"); for a bare `Foo` it's the reference itself.
        val parent = ref.parent
        val full: KtExpression
        val qualifiedName: String
        when {
            parent is KtDotQualifiedExpression && parent.selectorExpression === ref -> { full = parent; qualifiedName = parent.text }
            parent is KtQualifiedExpression && parent.selectorExpression === ref -> return null // `a?.X` selector
            parent is KtQualifiedExpression && parent.receiverExpression === ref -> return null // `X.member` — X is a receiver
            else -> { full = ref; qualifiedName = name }
        }
        // Value-position guards on the FULL expression: not a call callee, a further-member receiver, a `::`
        // reference, or inside a type reference.
        val fp = full.parent
        if (fp is KtCallExpression && fp.calleeExpression === full) return null              // `Foo(...)` / `X.Y(...)`
        if (fp is KtQualifiedExpression && fp.receiverExpression === full) return null        // `X.Y.more`
        if (fp is org.jetbrains.kotlin.psi.KtDoubleColonExpression) return null               // `Foo::class` / `Foo::member`
        if (inTypeReference(full)) return null

        // Resolve to a classifier; back off unless it is confidently a known class that is NOT a valid value.
        val fqn = runCatching { service.resolveTypeName(qualifiedName, resolver.fileContext) }.getOrNull() ?: return null
        if (!service.isKnownType(fqn)) return null
        if (service.isObject(fqn) || service.typeHasCompanionObject(fqn)) return null
        if (fqn.substringAfterLast('.') == "Companion") return null

        // A bare name might be shadowed by a value in scope (a local/param, an enclosing member, an in-scope
        // top-level value/function, or a local type) — then it's a legitimate value read, not a classifier. A
        // qualified `X.Y` can't be shadowed this way.
        if (full === ref) {
            val off = ref.textRange.startOffset
            if (resolver.localsAt(off).any { it.name == name }) return null
            if (resolver.enclosingClassMembersContain(off, name)) return null
            if (resolver.localTypesInScope(off).containsKey(name)) return null
            if (service.topLevelByName(name).any {
                    (it.kind == SymbolKind.FIELD || it.kind == SymbolKind.METHOD) && resolver.topLevelInScope(it, resolver.fileContext)
                }
            ) return null
        }

        val r = full.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Classifier '$name' does not have a companion object, and thus must be initialized here",
            KotlinDiagnosticCodes.CLASSIFIER_AS_VALUE,
        )
    }

    /** The class/object NAME identifier range (for an anonymous object, a short range over its `object` keyword). */
    private fun declNameRange(cls: KtClassOrObject): TextRange {
        cls.nameIdentifier?.let { return TextRange(it.textRange.startOffset, it.textRange.endOffset) }
        val s = cls.textRange.startOffset
        return TextRange(s, minOf(s + 6, cls.textRange.endOffset))
    }

    private fun memberNameRange(m: KtCallableDeclaration): TextRange {
        val r = (m.nameIdentifier ?: m).textRange
        return TextRange(r.startOffset, r.endOffset)
    }

    /** Emit unused-local / var-could-be-val for every LOCAL property anywhere in [root]'s subtree. These read
     *  sibling statements (an unused `val` becomes used by an edit elsewhere in the block), so they live OUTSIDE
     *  the per-statement cache and are recomputed over the whole function on any body edit. Cheap (PSI name
     *  scans, no resolution). */
    fun localDeclarationChecks(root: PsiElement, out: MutableList<Diagnostic>) {
        fun rec(p: PsiElement) {
            if (p is KtProperty && p.parent is KtBlockExpression) {
                unusedLocal(p)?.let { out += it }
                varCouldBeVal(p)?.let { out += it }
            }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(root)
    }

    /** Unused `private` declarations — a whole-file check (its used-ness depends on references ANYWHERE in the
     *  file, so it can't be cached per declaration). Recurses the file flagging each private decl absent from
     *  [refNames]; locals can't carry a visibility modifier, so only top-level/member declarations match. */
    private fun unusedPrivateDeclarations(root: PsiElement, refNames: Set<String>, out: MutableList<Diagnostic>) {
        if (root is KtNamedDeclaration && (root is KtProperty || root is KtNamedFunction) && isPrivateDeclaration(root)) {
            unusedPrivate(root, refNames)?.let { out += it }
        }
        var c = root.firstChild
        while (c != null) { unusedPrivateDeclarations(c, refNames, out); c = c.nextSibling }
    }

    /** Every identifier referenced in the file body (outside import/package directives), for the
     *  unused-import and unused-private checks. A declaration's own name identifier is NOT a reference. */
    private fun referencedNames(file: KtFile): Set<String> {
        val names = HashSet<String>()
        fun rec(p: PsiElement) {
            if (p is KtImportDirective || p is KtPackageDirective) return
            // KtSimpleNameExpression covers plain references AND operation references (`a shl b` → `shl`),
            // so an infix function imported and used as an operator still counts as referenced.
            if (p is org.jetbrains.kotlin.psi.KtSimpleNameExpression) names += p.getReferencedName()
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(file)
        return names
    }

    /**
     * Same-scope redeclarations among [decls] — `val x = 1; val x = 2`, two properties of the same name, or
     * two functions with the same name AND signature (receiver + parameter types; overloads that differ in
     * parameter types are fine). Conservative: keys are textual (`Int` ≠ `kotlin.Int` won't merge), classes/
     * objects/enum entries and property-vs-function clashes are left alone. Every member of a clashing group
     * is flagged (matching the editor underlining each).
     */
    private fun duplicateDeclarations(decls: List<KtDeclaration>): List<Diagnostic> {
        // Not `size < 2`: a single destructuring (`val (x, x) = …`) can conflict with itself across its entries.
        if (decls.isEmpty()) return emptyList()
        val byKey = LinkedHashMap<String, MutableList<KtNamedDeclaration>>()
        fun record(key: String, d: KtNamedDeclaration) { byKey.getOrPut(key) { ArrayList() }.add(d) }
        for (d in decls) {
            when (d) {
                // A destructuring `val (a, b) = …` binds each entry as a local in the property namespace, so an
                // entry clashes with a same-scope `val`/other entry of that name (`val a = …; val (a, b) = …`, or
                // `val (a, a) = …`). `_` is the ignore hole — never a real binding, so it never conflicts.
                is KtDestructuringDeclaration -> d.entries.forEach { e ->
                    val name = e.name
                    if (e.nameIdentifier != null && name != null && name != "_") record("P::$name", e)
                }
                is KtProperty -> {
                    val name = d.name
                    // `val _ = …` is the ignore hole (a discarded value), never a real binding — never conflicts.
                    if (d.nameIdentifier != null && name != null && name != "_")
                        record("P:${d.receiverTypeReference?.text ?: ""}:$name", d)
                }
                is KtNamedFunction -> {
                    val name = d.name
                    if (d.nameIdentifier != null && name != null)
                        record("F:${d.receiverTypeReference?.text ?: ""}:$name(${d.valueParameters.joinToString(",") { it.typeReference?.text ?: "?" }})", d)
                }
                else -> {} // classes/objects/enum entries: not handled here
            }
        }
        return conflicts(byKey.values)
    }

    /** Duplicate parameter names within one parameter list (`fun f(x: Int, x: String)`, `{ a, a -> }`). The
     *  `_` placeholder (an unused lambda parameter, `{ _, _ -> … }`) is the ignore hole, not a real binding, so
     *  repeats of it never conflict. */
    private fun duplicateParams(list: KtParameterList): List<Diagnostic> {
        if (list.parameters.size < 2) return emptyList()
        val byName = LinkedHashMap<String, MutableList<KtNamedDeclaration>>()
        for (p in list.parameters) {
            if (p.nameIdentifier == null) continue
            val name = p.name ?: continue
            if (name == "_") continue
            byName.getOrPut(name) { ArrayList() }.add(p)
        }
        return conflicts(byName.values)
    }

    private fun conflicts(groups: Collection<List<KtNamedDeclaration>>): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        for (group in groups) {
            if (group.size < 2) continue
            for (d in group) {
                val r = (d.nameIdentifier ?: d).textRange
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                    "Conflicting declarations: '${d.name}'", KotlinDiagnosticCodes.CONFLICTING_DECLARATION,
                )
            }
        }
        return out
    }

    /**
     * `val x = …; x = …` — assigning to an immutable binding. Conservative: only a plain `=` to a SIMPLE name
     * (not `this.x =`, not `x += …` which may desugar to a legal `plusAssign` on a `val`) that resolves to a
     * nearby local `val` or a parameter (function/lambda/for/catch, all immutable). Resolution stops
     * at the enclosing class so a (possibly `var`) member never trips it.
     */
    private fun valReassignment(expr: KtBinaryExpression): Diagnostic? {
        if (expr.operationToken != KtTokens.EQ) return null
        val lhs = expr.left as? KtNameReferenceExpression ?: return null
        val decl = nearestLocalDecl(lhs.getReferencedName(), lhs.textRange.startOffset, lhs) ?: return null
        val immutable = when (decl) {
            // A `val` WITHOUT an initializer permits one deferred assignment (`val x: Int; x = 1`), so only a
            // `val` that already has a value is truly un-reassignable. (Double deferred-assignment isn't caught.)
            is KtProperty -> !decl.isVar && (decl.hasInitializer() || decl.hasDelegate())
            is KtParameter -> true
            else -> false
        }
        if (!immutable) return null
        val r = lhs.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN)
    }

    /** `x++` / `x--` / `++x` / `--x` on a `val` local or parameter — inc/dec desugars to a reassignment, and
     *  (unlike `+=`, which can be a `plusAssign` on a `val`) there is NO operator that lets a `val` stand in, so
     *  this is always an error. Same immutable resolution as [valReassignment]; backs off at the class boundary. */
    private fun valIncDecReassignment(expr: KtUnaryExpression): Diagnostic? {
        if (expr.operationToken !in INCDEC) return null
        val ref = expr.baseExpression as? KtNameReferenceExpression ?: return null
        val decl = nearestLocalDecl(ref.getReferencedName(), ref.textRange.startOffset, ref) ?: return null
        val immutable = when (decl) {
            is KtProperty -> !decl.isVar && (decl.hasInitializer() || decl.hasDelegate())
            is KtParameter -> true
            else -> false
        }
        if (!immutable) return null
        val r = ref.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Val cannot be reassigned", KotlinDiagnosticCodes.VAL_REASSIGN)
    }

    /** An assignment whose left side can't be assigned to (Kotlin's VARIABLE_EXPECTED): `foo() = 1`, `5 = x`,
     *  `(a + b) = c`. Assignable targets are a name, an indexed access (`a[i]`), or a property selector (`a.b`);
     *  a call, a literal, or anything else is not. (A `val` reassignment to a NAME is a separate check.) */
    private fun variableExpected(e: KtBinaryExpression): Diagnostic? {
        if (e.operationToken != KtTokens.EQ && e.operationToken !in ASSIGN_OPS) return null
        val lhs = e.left?.let { unwrapParen(it) } ?: return null
        val assignable = when (lhs) {
            is KtNameReferenceExpression -> true
            is org.jetbrains.kotlin.psi.KtArrayAccessExpression -> true
            is KtQualifiedExpression -> lhs.selectorExpression is KtNameReferenceExpression // `a.b` yes, `a.b()` no
            else -> false
        }
        if (assignable) return null
        val r = lhs.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Variable expected", KotlinDiagnosticCodes.VARIABLE_EXPECTED)
    }

    /** Non-last statements in [block] whose value is unused and which have no side effect — a bare name reference,
     *  a bare literal, or a comparison (`a == b`, `a < b`). Only NON-last statements, so a block's trailing value
     *  expression is never flagged; qualified reads / calls are excluded (a getter could have an effect). */
    private fun unusedExpressions(block: KtBlockExpression): List<Diagnostic> {
        val stmts = block.statements
        if (stmts.size < 2) return emptyList()
        val out = ArrayList<Diagnostic>()
        for (i in 0 until stmts.lastIndex) {
            val st = stmts[i]
            val pure = when (val e = unwrapParen(st)) {
                is KtNameReferenceExpression -> true
                is KtConstantExpression -> true
                is KtBinaryExpression -> e.operationToken in COMPARISON_OPS
                else -> false
            }
            if (pure) {
                val r = st.textRange
                out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "The expression is unused", KotlinDiagnosticCodes.UNUSED_EXPRESSION)
            }
        }
        return out
    }

    /** Two `when` branches with the same CONSTANT-like condition (`1 -> …; 1 -> …`, `Color.RED -> …; Color.RED -> …`):
     *  the later one is dead. Restricted to deterministic conditions (literals, name/enum-constant references) so a
     *  possibly-side-effecting call condition is never flagged. */
    private fun duplicateWhenBranches(w: KtWhenExpression): List<Diagnostic> {
        val seen = HashSet<String>()
        val out = ArrayList<Diagnostic>()
        for (entry in w.entries) {
            if (entry.isElse) continue
            for (cond in entry.conditions) {
                if (cond !is KtWhenConditionWithExpression) continue
                val expr = cond.expression?.let { unwrapParen(it) } ?: continue
                val constant = when (expr) {
                    is KtConstantExpression -> true
                    is KtNameReferenceExpression -> true
                    is KtQualifiedExpression -> expr.selectorExpression is KtNameReferenceExpression // Enum.X
                    is KtStringTemplateExpression -> expr.entries.all { it is KtLiteralStringTemplateEntry }
                    else -> false
                }
                if (!constant) continue
                if (!seen.add(expr.text)) {
                    val r = cond.textRange
                    out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "Duplicate branch condition '${expr.text}'", KotlinDiagnosticCodes.DUPLICATE_WHEN_BRANCH)
                }
            }
        }
        return out
    }

    /** `g()++` / `5++` — an increment/decrement of something that can't be assigned to (Kotlin's VARIABLE_EXPECTED).
     *  Assignable operands (name / `a[i]` / `a.b`) are fine; a `val` operand is the separate [valIncDecReassignment]. */
    private fun incrementTargetExpected(expr: KtUnaryExpression): Diagnostic? {
        if (expr.operationToken !in INCDEC) return null
        val operand = expr.baseExpression?.let { unwrapParen(it) } ?: return null
        val assignable = when (operand) {
            is KtNameReferenceExpression -> true
            is org.jetbrains.kotlin.psi.KtArrayAccessExpression -> true
            is KtQualifiedExpression -> operand.selectorExpression is KtNameReferenceExpression
            else -> false
        }
        if (assignable) return null
        val r = operand.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Variable expected", KotlinDiagnosticCodes.VARIABLE_EXPECTED)
    }

    /** A misuse of the `const` modifier — Kotlin's const errors. Purely syntactic: `const` on a `var`, on a local
     *  variable, on an extension property, with a custom getter/delegate, without an initializer, or with an
     *  initializer that clearly isn't a compile-time constant (a call / constructor / complex expression). A bare
     *  name / `Enum.X` / arithmetic-of-literals initializer is left alone (it may be another `const val`). */
    private fun constMisuse(prop: KtProperty): Diagnostic? {
        val mod = prop.modifierList?.getModifier(KtTokens.CONST_KEYWORD) ?: return null
        val r = mod.textRange
        fun d(msg: String) = Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.CONST_MISUSE)
        if (prop.isVar) return d("Modifier 'const' is applicable only to 'val'")
        if (prop.parent is KtBlockExpression) return d("Modifier 'const' is not applicable to a local variable")
        if (prop.receiverTypeReference != null) return d("Modifier 'const' is not applicable to an extension property")
        if (prop.getter != null || prop.hasDelegate()) return d("A 'const val' may not have a custom getter or a delegate")
        val init = prop.initializer ?: return d("A 'const val' must have an initializer")
        if (!isCompileTimeConstant(init)) return d("A 'const val' initializer must be a compile-time constant")
        return null
    }

    /** Whether [e] is (conservatively) a compile-time constant: a literal, a plain string, an arithmetic/prefix of
     *  constants, or a bare name / `A.B` reference (which MAY be another `const val` — not flagged to avoid FP). */
    private fun isCompileTimeConstant(e: KtExpression): Boolean = when (val u = unwrapParen(e)) {
        is KtConstantExpression -> true
        is KtStringTemplateExpression -> u.entries.all { it is KtLiteralStringTemplateEntry }
        is KtNameReferenceExpression, is KtQualifiedExpression -> true // possibly a const val / enum — conservative
        is KtBinaryExpression -> u.left?.let { isCompileTimeConstant(it) } == true && u.right?.let { isCompileTimeConstant(it) } == true
        is KtPrefixExpression -> u.baseExpression?.let { isCompileTimeConstant(it) } == true
        else -> false
    }

    /** A local `val`/`var` whose name shadows a visible outer local variable or parameter — Kotlin's NAME_SHADOWING
     *  (`fun f(x: Int) { val x = 5 }`). Only local shadowing is flagged (shadowing a member/top-level is not); `_`
     *  is exempt. Resolution-free. */
    private fun localNameShadowing(prop: KtProperty): Diagnostic? {
        if (prop.parent !is KtBlockExpression) return null
        val name = prop.name ?: return null
        if (name == "_") return null
        val id = prop.nameIdentifier ?: return null
        val offset = prop.textRange.startOffset
        var node: PsiElement? = prop.parent?.parent // start ABOVE the declaring block (an outer scope shadows)
        while (node != null) {
            when (node) {
                is KtBlockExpression -> if (node.statements.any { it is KtProperty && it.name == name && it.textRange.endOffset <= offset }) return shadowDiag(id, name)
                is KtFunction -> if (node.valueParameters.any { it.name == name }) return shadowDiag(id, name)
                is KtForExpression -> if (node.loopParameter?.name == name) return shadowDiag(id, name)
                is KtCatchClause -> if (node.catchParameter?.name == name) return shadowDiag(id, name)
                is KtClassOrObject -> return null // a member: not warned as shadowing
            }
            node = node.parent
        }
        return null
    }

    private fun shadowDiag(id: PsiElement, name: String): Diagnostic {
        val r = id.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "Name shadowed: '$name'", KotlinDiagnosticCodes.NAME_SHADOWING)
    }

    /** An assignment used where a value is expected (`val b = (x = 5)`, `f(x = 5)` as a positional arg, `x = (y = 1)`)
     *  — Kotlin's ASSIGNMENT_IN_EXPRESSION_CONTEXT. Detected at the clear expression positions (a property
     *  initializer, a return/throw value, a value argument, or an operand of another expression). */
    private fun assignmentInExpression(e: KtBinaryExpression): Diagnostic? {
        if (e.operationToken != KtTokens.EQ && e.operationToken !in ASSIGN_OPS) return null
        var child: PsiElement = e
        var parent = e.parent
        while (parent is org.jetbrains.kotlin.psi.KtParenthesizedExpression) { child = parent; parent = parent.parent }
        val inExprContext = when (parent) {
            is KtProperty -> parent.initializer === child
            is KtReturnExpression -> parent.returnedExpression === child
            is KtThrowExpression -> parent.thrownExpression === child
            is org.jetbrains.kotlin.psi.KtValueArgument -> parent.getArgumentExpression() === child
            is KtBinaryExpression -> parent.operationToken !in ASSIGN_OPS && parent.operationToken != KtTokens.EQ // an operand of a non-assignment binary
            is KtBinaryExpressionWithTypeRHS -> true
            else -> false
        }
        if (!inExprContext) return null
        val r = e.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Assignments are not expressions, and only expressions are allowed here", KotlinDiagnosticCodes.ASSIGNMENT_IN_EXPRESSION)
    }

    /** `catch` clauses ordered so a later one is unreachable because an earlier one catches a supertype
     *  (`catch (e: Exception) {} catch (e: IllegalStateException) {}`). resolveReady (needs the supertype closure). */
    private fun unreachableCatch(t: org.jetbrains.kotlin.psi.KtTryExpression, resolver: KotlinResolver): List<Diagnostic> {
        if (t.catchClauses.size < 2) return emptyList()
        val seen = ArrayList<KotlinType>()
        val out = ArrayList<Diagnostic>()
        for (clause in t.catchClauses) {
            val typeRef = clause.catchParameter?.typeReference ?: continue
            val type = service.typeFromText(typeRef.text.removeSuffix("?").trim(), resolver.fileContext)
            if (type == null || type.isTypeParameter || !service.isKnownType(type.qualifiedName)) continue
            if (seen.any { it.isAssignableFrom(type) }) {
                val r = typeRef.textRange
                out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "This catch is unreachable: a supertype is caught by an earlier clause", KotlinDiagnosticCodes.UNREACHABLE_CATCH)
            }
            seen.add(type)
        }
        return out
    }

    /**
     * A function declared WITHOUT a body where Kotlin requires one — "Function 'x' without a body must be
     * abstract". Purely syntactic (no resolution, so it runs in dumb mode too): fires when a [KtNamedFunction]
     * has no block/expression body and is NOT `abstract`/`external`/`expect`, and its container is NOT an
     * interface or annotation class (whose members are implicitly abstract) nor an `expect`/`external` container.
     * A member of an *abstract class* still needs a body unless it is itself `abstract`, so only the function's
     * own modifiers exempt it there.
     */
    private fun functionWithoutBody(fn: KtNamedFunction): Diagnostic? {
        if (fn.hasBody()) return null
        if (fn.hasModifier(KtTokens.ABSTRACT_KEYWORD) || fn.hasModifier(KtTokens.EXTERNAL_KEYWORD) ||
            fn.hasModifier(KtTokens.EXPECT_KEYWORD) || fn.hasModifier(KtTokens.ACTUAL_KEYWORD)
        ) return null
        val container = fn.getStrictParentOfType<KtClassOrObject>()
        if (container is KtClass && (container.isInterface() || container.isAnnotation() ||
                container.hasModifier(KtTokens.EXPECT_KEYWORD) || container.hasModifier(KtTokens.EXTERNAL_KEYWORD))
        ) return null
        val anchor = fn.nameIdentifier ?: return null
        val r = anchor.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Function '${fn.name}' without a body must be abstract", KotlinDiagnosticCodes.FUNCTION_NO_BODY,
        )
    }

    /** Reads of a local declared without an initializer that the control-flow analysis proves are (on some path)
     *  not yet assigned — Kotlin's "Variable must be initialized". Sound + conservative (see
     *  [KotlinControlFlow.uninitializedReads]); purely PSI, so it runs in dumb mode. */
    private fun uninitializedVariables(fn: KtNamedFunction, resolver: KotlinResolver): List<Diagnostic> {
        val body = fn.bodyBlockExpression ?: return emptyList()
        return KotlinControlFlow(resolver).uninitializedReads(body).map { ref ->
            val r = ref.textRange
            Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Variable '${ref.getReferencedName()}' must be initialized before it is used",
                KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE,
            )
        }
    }

    /** The nearest in-scope local property / parameter named [name] declared before [offset], or null if the
     *  binding is a class member / top-level (resolution backs off at the class boundary). */
    private fun nearestLocalDecl(name: String, offset: Int, from: PsiElement): PsiElement? {
        var node: PsiElement? = from.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression ->
                    node.statements.firstOrNull { it is KtProperty && it.name == name && it.textRange.endOffset <= offset }?.let { return it }
                is KtFunction -> node.valueParameters.firstOrNull { it.name == name }?.let { return it }
                is KtForExpression -> node.loopParameter?.takeIf { it.name == name }?.let { return it }
                is KtCatchClause -> node.catchParameter?.takeIf { it.name == name }?.let { return it }
                is KtClassOrObject -> return null
            }
            node = node.parent
        }
        return null
    }

    /**
     * A block-body function whose declared return type needs a value but whose body can fall off the end without
     * returning one. Uses the control-flow reachability analysis ([KotlinControlFlow]): it flags only when EVERY
     * path is proven to fall through (`blockLiveness == LIVE`). A body where all paths return/throw (`DEAD`) or
     * where reachability can't be decided (`UNKNOWN` — e.g. a `when` of unknown exhaustiveness) is left alone, so
     * this never false-positives — while now catching partial-return bodies the old heuristic missed
     * (`if (c) return 1` with no `else`), which it couldn't because it backed off on the first `return`.
     */
    private fun missingReturn(fn: KtNamedFunction, resolver: KotlinResolver): Diagnostic? {
        val declared = service.typeFromText(fn.typeReference?.text ?: return null, resolver.fileContext) ?: return null
        if (declared.qualifiedName == "kotlin.Unit" || declared.qualifiedName == "kotlin.Nothing") return null
        if (declared.isTypeParameter || !service.isKnownType(declared.qualifiedName)) return null
        val body = fn.bodyBlockExpression ?: return null
        if (KotlinControlFlow(resolver).blockLiveness(body) != Liveness.LIVE) return null
        val anchor = fn.nameIdentifier ?: fn.typeReference ?: return null
        val r = anchor.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "A 'return' expression required in a function with a block body ('{...}')", KotlinDiagnosticCodes.MISSING_RETURN,
        )
    }

    /** A LOCAL `val`/`var` never referenced anywhere in its declaring block (a warning). Skips `_` and
     *  destructuring (modelled separately); counts any same-name reference as a use (so it never over-reports).
     *  Uses the per-block usage scan ([usageOf]) so N locals in one block cost ONE block walk, not N (this was
     *  the dominant cost of the analyze pass on a large function body — see [usageOf]). */
    private fun unusedLocal(prop: KtProperty): Diagnostic? {
        val name = prop.name ?: return null
        if (name == "_") return null
        val block = prop.parent as? KtBlockExpression ?: return null // local declarations only
        val nameId = prop.nameIdentifier ?: return null
        if ((usageOf(block).refCounts[name] ?: 0) > 0) return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "Variable '$name' is never used", KotlinDiagnosticCodes.UNUSED_LOCAL)
    }

    /** A block's reference-name counts + reassigned-name set, computed in ONE walk and memoized per analyze
     *  pass — so [unusedLocal] / [varCouldBeVal] over N locals in a block cost one walk, not N (they used to
     *  each re-walk the whole block, which was O(locals × blockSize) and dominated a large function's analyze). */
    private class BlockUsage(val refCounts: Map<String, Int>, val reassigned: Set<String>)
    private val blockUsageMemo = HashMap<KtBlockExpression, BlockUsage>()

    /** Reset the per-pass caches (PSI-keyed, so they must not survive a reparse). Called at the start of each
     *  analyze pass by [IncrementalSemanticAnalysis.diagnostics]. */
    fun resetPassCaches() { blockUsageMemo.clear() }

    private fun usageOf(block: KtBlockExpression): BlockUsage = blockUsageMemo.getOrPut(block) {
        val refs = HashMap<String, Int>()
        val reassigned = HashSet<String>()
        fun rec(p: PsiElement) {
            when (p) {
                is KtNameReferenceExpression -> { val n = p.getReferencedName(); refs[n] = (refs[n] ?: 0) + 1 }
                is KtBinaryExpression -> if (p.operationToken in ASSIGN_OPS) (p.left as? KtNameReferenceExpression)?.let { reassigned += it.getReferencedName() }
                is KtPrefixExpression -> if (p.operationToken in INCDEC) (p.baseExpression as? KtNameReferenceExpression)?.let { reassigned += it.getReferencedName() }
                is KtPostfixExpression -> if (p.operationToken in INCDEC) (p.baseExpression as? KtNameReferenceExpression)?.let { reassigned += it.getReferencedName() }
            }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(block)
        BlockUsage(refs, reassigned)
    }

    /** Count bare name references (`KtNameReferenceExpression`) under [root], reusing the per-block memo when
     *  [root] is a block body. Member selectors (`obj.name`) count `name` too — harmless here, it only ever
     *  causes a use to be OVER-counted, i.e. a false "used" (safe: no false unused-flag). */
    private fun referenceCounts(root: PsiElement): Map<String, Int> {
        if (root is KtBlockExpression) return usageOf(root).refCounts
        val refs = HashMap<String, Int>()
        fun rec(p: PsiElement) {
            if (p is KtNameReferenceExpression) { val n = p.getReferencedName(); refs[n] = (refs[n] ?: 0) + 1 }
            var c = p.firstChild; while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(root)
        return refs
    }

    /** Value parameters of [fn] that its body never references. Skips `override` (the signature is contractual),
     *  `operator`/`external`/`abstract`/`expect`/`actual` and bodyless functions (no body to use them in),
     *  `main`, and `_`. A parameter referenced only in a sibling parameter's default value counts as used. */
    private fun unusedParameters(fn: KtNamedFunction): List<Diagnostic> {
        if (fn.name == "main") return emptyList()
        for (kw in UNUSED_PARAM_EXEMPT_MODIFIERS) if (fn.hasModifier(kw)) return emptyList()
        // An interface member's parameters are contractual (an implementor uses them) even with a default body.
        if (fn.getStrictParentOfType<KtClass>()?.isInterface() == true) return emptyList()
        val body = fn.bodyExpression ?: return emptyList() // abstract / interface / expect → nothing to use
        val params = fn.valueParameters
        if (params.isEmpty()) return emptyList()
        val refs = HashMap<String, Int>(referenceCounts(body))
        for (p in params) p.defaultValue?.let { d -> referenceCounts(d).forEach { (k, v) -> refs[k] = (refs[k] ?: 0) + v } }
        return params.mapNotNull { p -> unusedParamDiag(p, refs) }
    }

    /** Explicit value parameters of a lambda that its body never references (`list.map { x -> 1 }`). The
     *  implicit `it` (no `KtParameter`) is never flagged; `_` and destructuring entries are skipped. */
    private fun unusedLambdaParameters(lambda: KtFunctionLiteral): List<Diagnostic> {
        val params = lambda.valueParameters
        if (params.isEmpty()) return emptyList() // implicit `it` or no params
        if (params.any { it.destructuringDeclaration != null }) return emptyList() // skip destructured lambdas
        val body = lambda.bodyExpression ?: return emptyList()
        val refs = referenceCounts(body)
        return params.mapNotNull { p -> unusedParamDiag(p, refs) }
    }

    private fun unusedParamDiag(p: KtParameter, refs: Map<String, Int>): Diagnostic? {
        val name = p.name ?: return null
        if (name == "_") return null
        if (p.hasValOrVar()) return null // a `val`/`var` parameter is a property (public state), not "unused"
        if (p.annotationEntries.isNotEmpty()) return null // an annotated param may be used by a processor/framework
        if ((refs[name] ?: 0) > 0) return null
        val nameId = p.nameIdentifier ?: return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "Parameter '$name' is never used", KotlinDiagnosticCodes.UNUSED_PARAMETER)
    }

    /** `==`/`!=` between two types that can NEVER be equal — so the comparison is always false. Strictly
     *  conservative to avoid false positives over the parse-only model: BOTH operands must infer to a known,
     *  non-nullable, DEFINITELY-FINAL type (a curated builtin value type, or an enum — finals can't have a
     *  subtype that bridges them), with different classifiers, neither assignable to the other, and not a
     *  numeric/numeric pair (integer literals adapt). So `"x" == 5` / `Color.RED == Status.OK` flag; anything
     *  involving an interface, open class, `Any`, a type parameter, or a null literal backs off. */
    private fun incomparableEquality(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
        if (e.operationToken != KtTokens.EQEQ && e.operationToken != KtTokens.EXCLEQ) return null
        val left = e.left ?: return null
        val right = e.right ?: return null
        if (isNullLiteral(left) || isNullLiteral(right)) return null
        val lt = resolver.inferType(left) ?: return null
        val rt = resolver.inferType(right) ?: return null
        if (lt.nullable || rt.nullable || lt.isTypeParameter || rt.isTypeParameter) return null
        if (lt.qualifiedName == rt.qualifiedName) return null
        if (lt.isAssignableFrom(rt) || rt.isAssignableFrom(lt)) return null // a subtype relation → may be equal
        if (lt.qualifiedName in NUMERIC && rt.qualifiedName in NUMERIC) return null // integer-literal adaptation
        if (!comparisonFinal(lt) || !comparisonFinal(rt)) return null // an interface/open class could bridge them
        val r = e.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.WARNING,
            "Comparing incompatible types '${renderType(lt)}' and '${renderType(rt)}': always ${if (e.operationToken == KtTokens.EXCLEQ) "true" else "false"}",
            KotlinDiagnosticCodes.INCOMPARABLE_EQUALITY,
        )
    }

    /** `x == null` / `x != null` where `x` is PROVABLY non-null — the comparison is constant. Kotlin's
     *  SENSELESS_COMPARISON warning. Reuses [provablyNonNull]: a literal, `this`, an explicitly non-null
     *  local/parameter, or a value flow-smart-cast to non-null (`if (x != null) { … x == null … }`). */
    private fun senselessNullComparison(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
        if (e.operationToken != KtTokens.EQEQ && e.operationToken != KtTokens.EXCLEQ) return null
        val left = e.left ?: return null
        val right = e.right ?: return null
        val operand = when {
            isNullLiteral(right) && !isNullLiteral(left) -> left
            isNullLiteral(left) && !isNullLiteral(right) -> right
            else -> return null // not a `<expr> ==/!= null`, or `null == null`
        }
        if (!provablyNonNull(operand, resolver)) return null
        val always = if (e.operationToken == KtTokens.EXCLEQ) "true" else "false"
        val r = e.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.WARNING,
            "Condition is always '$always': the compared value can never be null", KotlinDiagnosticCodes.SENSELESS_COMPARISON,
        )
    }

    /**
     * An `is`/`!is` check whose result is statically known — Kotlin's USELESS_IS_CHECK. Two sound cases:
     *  - ALWAYS TRUE: the operand's type is (non-null) already a subtype of the target (`x is String` / `x is Any`
     *    where `x: String`);
     *  - ALWAYS FALSE: the operand and target are unrelated DEFINITELY-FINAL types (`x is Int` where `x: String`),
     *    reusing [comparisonFinal] (a curated final value type or an enum — an interface / open class could bridge).
     * Conservative: both types must be known, non-type-parameter, and non-generic (a parameterized target is an
     * unchecked cast); a nullable operand skips the always-true case. `!is` flips the verdict.
     */
    private fun uselessIsCheck(e: org.jetbrains.kotlin.psi.KtIsExpression, resolver: KotlinResolver): Diagnostic? {
        val typeRef = e.typeReference ?: return null
        if (typeRef.text.contains('<')) return null // a parameterized target → unchecked, don't judge
        val target = service.typeFromText(typeRef.text.removeSuffix("?").trim(), resolver.fileContext) ?: return null
        val operand = resolver.inferType(e.leftHandSide) ?: return null
        if (operand.isTypeParameter || target.isTypeParameter) return null
        if (!service.isKnownType(operand.qualifiedName) || !service.isKnownType(target.qualifiedName)) return null
        val alwaysTrue = !operand.nullable && (target.qualifiedName == operand.qualifiedName || target.isAssignableFrom(operand))
        val alwaysFalse = target.qualifiedName != operand.qualifiedName &&
            !target.isAssignableFrom(operand) && !operand.isAssignableFrom(target) &&
            comparisonFinal(operand) && comparisonFinal(target)
        val holds = when {
            alwaysTrue -> !e.isNegated  // `x is String` true, `x !is String` false
            alwaysFalse -> e.isNegated  // `x is Int` false, `x !is Int` true
            else -> return null
        }
        val r = e.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.WARNING,
            "Check for instance is always '$holds'", KotlinDiagnosticCodes.USELESS_IS_CHECK,
        )
    }

    /** A type whose value set can't overlap a DIFFERENT such type's: a curated builtin final value type, or an
     *  enum (enums are final and distinct). Used by [incomparableEquality]; deliberately excludes interfaces /
     *  open classes (a single subtype could implement both) and `Any`. */
    private fun comparisonFinal(t: KotlinType): Boolean =
        t.qualifiedName in FINAL_VALUE_TYPES || runCatching { service.enumConstantsOf(t.qualifiedName).isNotEmpty() }.getOrDefault(false)

    private val FINAL_VALUE_TYPES = setOf(
        "kotlin.String", "kotlin.Boolean", "kotlin.Char",
        "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float",
    )

    /** `"${name}"` → `"$name"`: a block template entry wrapping a BARE name, where dropping the braces wouldn't
     *  merge into the following text (no identifier char follows). Purely syntactic — never a false positive. */
    private fun redundantTemplateBraces(t: KtStringTemplateExpression): List<Diagnostic> {
        val entries = t.entries
        val out = ArrayList<Diagnostic>()
        for ((i, entry) in entries.withIndex()) {
            if (entry !is KtBlockStringTemplateEntry) continue
            val nameRef = entry.expression as? KtNameReferenceExpression ?: continue // `${a.b}`/`${f()}` keep braces
            val nextChar = (entries.getOrNull(i + 1) as? KtLiteralStringTemplateEntry)?.text?.firstOrNull()
            if (nextChar != null && (nextChar.isLetterOrDigit() || nextChar == '_')) continue // `${a}b` ≠ `$ab`
            val n = nameRef.getReferencedName()
            val r = entry.textRange
            out += Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.HINT,
                "Redundant braces in string template: '\${$n}' can be '\$$n'", KotlinDiagnosticCodes.REDUNDANT_STRING_TEMPLATE,
            )
        }
        return out
    }

    /** A `!!` on a value that is already non-null (`"x"!!`, `this!!`, a `val s: String` then `s!!`). Conservative
     *  about PLATFORM types (a Java value typed non-null is really flexibly-nullable, so `!!` on it is NOT
     *  redundant): only flags when the operand is PROVABLY non-null Kotlin ([provablyNonNull]). */
    private fun redundantNotNull(e: KtPostfixExpression, resolver: KotlinResolver): Diagnostic? {
        if (e.operationToken != KtTokens.EXCLEXCL) return null
        val base = e.baseExpression ?: return null
        if (!provablyNonNull(base, resolver)) return null
        val opRange = e.operationReference.textRange
        return Diagnostic(
            TextRange(opRange.startOffset, opRange.endOffset), Severity.WARNING,
            "Redundant non-null assertion ('!!') on a non-null value", KotlinDiagnosticCodes.REDUNDANT_NOT_NULL,
        )
    }

    /** A `?.` whose receiver is already non-null (`"x"?.length`, a `val s: String` then `s?.length`). Same
     *  platform-type conservatism as [redundantNotNull]. */
    private fun redundantSafeCall(e: KtSafeQualifiedExpression, resolver: KotlinResolver): Diagnostic? {
        val receiver = e.receiverExpression
        if (!provablyNonNull(receiver, resolver)) return null
        val op = e.operationTokenNode.textRange
        return Diagnostic(
            TextRange(op.startOffset, op.endOffset), Severity.WARNING,
            "Redundant safe call ('?.') on a non-null receiver", KotlinDiagnosticCodes.REDUNDANT_SAFE_CALL,
        )
    }

    /** Whether [expr] is PROVABLY a non-null Kotlin value — with zero platform-type risk. True for a literal
     *  (string/number/boolean/char, but not the `null` literal), `this`, a name reference to a local/param whose
     *  declared type is an EXPLICIT non-null annotation, OR a name that flow-analysis smart-casts to non-null
     *  ([KotlinResolver.smartCastNonNull], gated by the Kotlin stability rules — a `val` local / parameter guarded
     *  by an enclosing null check). An inferred/platform type or an unstable value is skipped (back off). */
    private fun provablyNonNull(expr: KtExpression, resolver: KotlinResolver): Boolean = when (expr) {
        is KtStringTemplateExpression -> true
        is KtConstantExpression -> expr.text.trim() != "null"
        is KtThisExpression -> true
        is KtNameReferenceExpression -> explicitNonNullLocalOrParam(expr) || resolver.smartCastNonNull(expr)
        else -> false
    }

    /** A name reference to a block-local `val`/`var` or a function/lambda parameter whose type is written
     *  explicitly and is non-null. An inferred type (no annotation) is skipped — it could be a Java platform
     *  type, where `!!`/`?.` is legitimate. Class/constructor properties are skipped (back off). */
    private fun explicitNonNullLocalOrParam(ref: KtNameReferenceExpression): Boolean {
        val name = ref.getReferencedName()
        val offset = ref.textRange.startOffset
        var node: PsiElement? = ref.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression -> node.statements.firstOrNull { it is KtProperty && it.name == name && it.textRange.endOffset <= offset }
                    ?.let { return isExplicitNonNull((it as KtProperty).typeReference) }
                is KtFunction -> node.valueParameters.firstOrNull { it.name == name }?.let { return isExplicitNonNull(it.typeReference) }
                is KtClassOrObject -> return false // class/ctor property — could be platform-typed; back off
            }
            node = node.parent
        }
        return false
    }

    private fun isExplicitNonNull(tr: KtTypeReference?): Boolean {
        val t = tr?.text?.trim() ?: return false // no explicit annotation → could be a platform type → back off
        return !t.endsWith("?")
    }

    private val UNUSED_PARAM_EXEMPT_MODIFIERS = listOf(
        // override / open / abstract → the signature is a contract (an overrider uses the params); operator has
        // a fixed convention shape; external / expect / actual bodies live elsewhere.
        KtTokens.OVERRIDE_KEYWORD, KtTokens.OPEN_KEYWORD, KtTokens.ABSTRACT_KEYWORD, KtTokens.OPERATOR_KEYWORD,
        KtTokens.EXTERNAL_KEYWORD, KtTokens.EXPECT_KEYWORD, KtTokens.ACTUAL_KEYWORD,
    )

    /**
     * A property that Kotlin requires to be initialized but isn't — a top-level or concrete-class property with
     * no initializer, delegate, or getter (`class C { val x: Int }`). Skips locals (deferred init is legal),
     * `abstract`/`lateinit`/`expect`/`external`, and members of an interface/abstract/expect class. A class
     * member that is definitely assigned in an `init { }` block or a secondary constructor
     * (`val x: Int; init { x = 1 }`) is legal deferred initialization, so it backs off there too.
     */
    private fun missingInitializer(prop: KtProperty): Diagnostic? {
        if (prop.hasInitializer() || prop.hasDelegate() || prop.getter != null || prop.setter != null) return null
        if (prop.typeReference == null) return null // no type AND no initializer won't parse as a property
        if (prop.hasModifier(KtTokens.ABSTRACT_KEYWORD) || prop.hasModifier(KtTokens.LATEINIT_KEYWORD) ||
            prop.hasModifier(KtTokens.EXPECT_KEYWORD) || prop.hasModifier(KtTokens.EXTERNAL_KEYWORD)
        ) return null
        val owner = prop.parent
        when (owner) {
            is KtFile -> {}
            is KtClassBody -> {
                val cls = owner.parent as? KtClassOrObject ?: return null
                if (cls is org.jetbrains.kotlin.psi.KtClass &&
                    (cls.isInterface() || cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
                        cls.hasModifier(KtTokens.SEALED_KEYWORD) || cls.hasModifier(KtTokens.EXPECT_KEYWORD) ||
                        cls.hasModifier(KtTokens.EXTERNAL_KEYWORD))
                ) return null
                // Deferred initialization in the constructor (`val x: Int; init { x = … }` or a secondary
                // constructor body) is legal. Conservative: any assignment to the name in an init block or
                // secondary constructor backs off (no full definite-assignment analysis), so a real "not on
                // every path" gap is missed rather than false-flagged — matching the parse-only model's contract.
                prop.name?.let { if (assignedInConstructor(cls, it)) return null }
            }
            else -> return null // local / other: deferred init is legal
        }
        val nameId = prop.nameIdentifier ?: return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Property must be initialized", KotlinDiagnosticCodes.MUST_BE_INITIALIZED)
    }

    /** Whether [name] is the target of a plain assignment (`name = …` or `this.name = …`) anywhere in one of
     *  [cls]'s `init { }` blocks or secondary-constructor bodies — the deferred-initialization forms. */
    private fun assignedInConstructor(cls: KtClassOrObject, name: String): Boolean {
        val bodies = ArrayList<PsiElement>()
        cls.getAnonymousInitializers().mapNotNullTo(bodies) { it.body }
        cls.secondaryConstructors.mapNotNullTo(bodies) { it.bodyExpression }
        return bodies.any { assignsName(it, name) }
    }

    /** Whether [root]'s subtree contains a plain `=` assignment whose target is the simple name [name] or
     *  `this.[name]` (an augmented assign / `field` is not deferred initialization of the property). */
    private fun assignsName(root: PsiElement, name: String): Boolean {
        var found = false
        fun rec(e: PsiElement) {
            if (found) return
            if (e is KtBinaryExpression && e.operationToken == KtTokens.EQ) {
                when (val lhs = e.left?.let { unwrapParen(it) }) {
                    is KtNameReferenceExpression -> if (lhs.getReferencedName() == name) { found = true; return }
                    is KtDotQualifiedExpression -> if (lhs.receiverExpression is KtThisExpression &&
                        (lhs.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() == name
                    ) { found = true; return }
                    else -> {}
                }
            }
            var c = e.firstChild
            while (c != null && !found) { rec(c); c = c.nextSibling }
        }
        rec(root)
        return found
    }

    /**
     * A `val`/`var` with NO type annotation AND no initializer, delegate, or accessor (`val test`) — Kotlin's
     * PROPERTY/VARIABLE_WITH_NO_TYPE_NO_INITIALIZER. Purely syntactic (no resolution), so it runs in dumb mode
     * too. Disjoint from [missingInitializer] (which handles a TYPED property with no initializer). `lateinit`
     * has its own diagnostic ([lateinitMisuse]); destructuring entries / loop / catch parameters are never
     * `KtProperty`, so they don't reach here.
     */
    private fun noTypeNoInitializer(prop: KtProperty): Diagnostic? {
        if (prop.typeReference != null) return null
        if (prop.hasInitializer() || prop.hasDelegate()) return null
        if (prop.getter != null || prop.setter != null) return null
        if (prop.hasModifier(KtTokens.LATEINIT_KEYWORD)) return null // reported by lateinitMisuse
        val nameId = prop.nameIdentifier ?: return null
        val isMember = prop.parent is KtFile || prop.parent is KtClassBody
        val msg = if (isMember) "This property must either have a type annotation, be initialized or be delegated"
        else "This variable must either have a type annotation or be initialized"
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.NO_TYPE_NO_INITIALIZER)
    }

    /**
     * Misuse of the `lateinit` modifier, all syntactic: it is allowed only on a mutable (`var`) property with
     * an explicit, non-nullable type and no initializer or delegate. (Local `lateinit` is legal since Kotlin
     * 1.2, so a local var is not flagged here for the modifier itself.) The diagnostic is anchored on the
     * `lateinit` keyword.
     */
    private fun lateinitMisuse(prop: KtProperty): Diagnostic? {
        val range = modifierRange(prop, KtTokens.LATEINIT_KEYWORD) ?: return null
        fun d(msg: String) = Diagnostic(range, Severity.ERROR, msg, KotlinDiagnosticCodes.LATEINIT)
        if (!prop.isVar) return d("'lateinit' modifier is allowed only on mutable properties")
        if (prop.hasInitializer()) return d("'lateinit' modifier is not allowed on properties with an initializer")
        if (prop.hasDelegate()) return d("'lateinit' modifier is not allowed on delegated properties")
        val typeRef = prop.typeReference ?: return d("'lateinit' modifier requires the property to have an explicit type")
        if (typeRef.typeElement is KtNullableType) return d("'lateinit' modifier is not allowed on properties of nullable types")
        return null
    }

    /**
     * Misuse of the `abstract` modifier on a member, all syntactic:
     *  - an abstract function with a body, or an abstract property with an initializer / delegate / accessor body
     *    (an abstract member declares no implementation);
     *  - an `abstract` member inside a plain (non-abstract, non-sealed, non-interface) class — Kotlin's
     *    ABSTRACT_*_IN_NON_ABSTRACT_CLASS. Enum/expect/external containers can carry abstract members, so they
     *    back off. Anchored on the `abstract` keyword.
     */
    private fun abstractMisuse(decl: KtDeclaration): Diagnostic? {
        val range = modifierRange(decl, KtTokens.ABSTRACT_KEYWORD) ?: return null
        fun d(msg: String) = Diagnostic(range, Severity.ERROR, msg, KotlinDiagnosticCodes.ABSTRACT_MODIFIER)
        when (decl) {
            is KtNamedFunction -> if (decl.bodyExpression != null) return d("Abstract function '${decl.name}' cannot have a body")
            is KtProperty -> {
                if (decl.hasInitializer()) return d("Abstract property '${decl.name}' cannot have an initializer")
                if (decl.hasDelegate()) return d("Abstract property '${decl.name}' cannot be delegated")
                if (decl.getter?.bodyExpression != null || decl.setter?.bodyExpression != null)
                    return d("Abstract property '${decl.name}' cannot have an accessor with a body")
            }
            else -> return null
        }
        // An abstract member is only legal in a container that can hold one.
        val cls = (decl.parent as? KtClassBody)?.parent as? KtClass ?: return null
        if (cls.isInterface() || cls.isSealed() || cls.isEnum() ||
            cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) || cls.hasModifier(KtTokens.EXPECT_KEYWORD) ||
            cls.hasModifier(KtTokens.EXTERNAL_KEYWORD)
        ) return null
        val kind = if (decl is KtNamedFunction) "function" else "property"
        return d("Abstract $kind '${decl.name}' in non-abstract class '${cls.name}'")
    }

    /**
     * `val`/`var` on a parameter outside a primary constructor (`fun f(val x: Int)`, a lambda/catch/secondary-
     * constructor parameter) — Kotlin's VAL_OR_VAR_ON_*_PARAMETER. Property parameters are legal only on a
     * primary constructor. Anchored on the `val`/`var` keyword.
     */
    private fun valVarOnParameter(param: KtParameter): Diagnostic? {
        val kw = param.valOrVarKeyword ?: return null
        if (param.parent?.parent is KtPrimaryConstructor) return null
        val place = when (param.parent?.parent) {
            is KtSecondaryConstructor -> "a secondary constructor parameter"
            is KtFunction -> "a function parameter"
            else -> "this parameter"
        }
        val r = kw.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "'${kw.text}' is not allowed on $place", KotlinDiagnosticCodes.VAL_VAR_PARAMETER,
        )
    }

    /** The text range of [decl]'s [kw] modifier keyword (e.g. `lateinit`, `abstract`), or null if absent. */
    private fun modifierRange(decl: KtDeclaration, kw: KtModifierKeywordToken): TextRange? {
        val el = decl.modifierList?.getModifier(kw) ?: return null
        val r = el.textRange
        return TextRange(r.startOffset, r.endOffset)
    }

    /**
     * Modifier-list errors on a declaration, all syntactic (no resolution):
     *  - a repeated modifier (`open open fun`) — REPEATED_MODIFIER;
     *  - two incompatible inheritance modifiers (`final` with `open`/`abstract`/`sealed`) — INCOMPATIBLE_MODIFIERS;
     *  - more than one visibility modifier (`private public`) — REDUNDANT/INCOMPATIBLE visibility.
     * Each offending keyword is flagged on its own range (so the editor underlines each).
     */
    private fun modifierConflicts(decl: KtDeclaration): List<Diagnostic> {
        val ml = decl.modifierList ?: return emptyList()
        val present = LinkedHashMap<KtModifierKeywordToken, PsiElement>()
        val out = ArrayList<Diagnostic>()
        var c = ml.firstChild
        while (c != null) {
            val et = c.node.elementType
            if (et is KtModifierKeywordToken) {
                if (present.containsKey(et)) {
                    val r = c.textRange
                    out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Repeated '${c.text}' modifier", KotlinDiagnosticCodes.MODIFIERS)
                } else {
                    present[et] = c
                }
            }
            c = c.nextSibling
        }
        fun flag(a: PsiElement, b: PsiElement, msg: String) {
            for (e in listOf(a, b)) {
                val r = e.textRange
                out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.MODIFIERS)
            }
        }
        for ((x, y) in INCOMPATIBLE_MODIFIERS) {
            val ex = present[x]; val ey = present[y]
            if (ex != null && ey != null) flag(ex, ey, "Modifier '${ex.text}' is incompatible with '${ey.text}'")
        }
        val visible = VISIBILITY_MODIFIERS.mapNotNull { present[it] }
        if (visible.size > 1) for (e in visible) {
            val r = e.textRange
            out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Multiple visibility modifiers", KotlinDiagnosticCodes.MODIFIERS)
        }
        return out
    }

    /** A LOCAL `var` never reassigned could be a `val` (a hint). Reassignment = `name = …`, an augmented
     *  assignment, or `++`/`--` anywhere in the declaring block. Uses the per-block usage scan ([usageOf]). */
    private fun varCouldBeVal(prop: KtProperty): Diagnostic? {
        if (!prop.isVar) return null
        val name = prop.name ?: return null
        val block = prop.parent as? KtBlockExpression ?: return null // locals only
        if (name in usageOf(block).reassigned) return null
        val nameId = prop.nameIdentifier ?: return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.HINT, "Variable '$name' is never reassigned and can be a 'val'", KotlinDiagnosticCodes.VAR_COULD_BE_VAL)
    }

    private fun isPrivateDeclaration(d: KtNamedDeclaration): Boolean =
        d.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
            !d.hasModifier(KtTokens.OPERATOR_KEYWORD) && !d.hasModifier(KtTokens.OVERRIDE_KEYWORD)

    /** A `private` top-level / member function or property never referenced in the file (a warning). `private`
     *  is file- or class-scoped, so a whole-file reference scan is sound. */
    private fun unusedPrivate(decl: KtNamedDeclaration, refNames: Set<String>): Diagnostic? {
        // only top-level or class-body members (not a local, not a primary-constructor parameter)
        if (decl.parent !is KtFile && decl.parent !is KtClassBody) return null
        val name = decl.name ?: return null
        if (name in refNames) return null
        val kind = if (decl is KtNamedFunction) "Function" else "Property"
        val nameId = decl.nameIdentifier ?: return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "$kind '$name' is never used", KotlinDiagnosticCodes.UNUSED_PRIVATE)
    }

    /** An import whose name is never referenced in the body (a warning). Skips star imports and imports of
     *  operator/convention names (used implicitly via `+`, `[]`, `in`, destructuring, which aren't visible here). */
    private fun unusedImports(file: KtFile, refNames: Set<String>): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        for (imp in file.importDirectives) {
            if (imp.isAllUnder) continue // star import: can't tell
            val fq = imp.importedFqName ?: continue
            val name = imp.aliasName ?: fq.shortName().asString()
            if (name.isEmpty() || name in OPERATOR_NAMES || name in refNames) continue
            val r = (imp.importedReference ?: imp).textRange
            out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "Unused import directive", KotlinDiagnosticCodes.UNUSED_IMPORT)
        }
        return out
    }

    /**
     * Two or more explicit imports that bring the SAME simple name (or alias) into scope but resolve to
     * DIFFERENT fully-qualified targets — Kotlin's `CONFLICTING_IMPORT` (`import java.util.Date` +
     * `import java.sql.Date`: a bare `Date` is then ambiguous, and the file does not compile until one is
     * aliased or removed). Every member of a conflicting group is flagged (matching the editor underlining
     * each). Conservative — purely textual on the import list, so it never resolves anything:
     *  - star imports are skipped (they don't conflict at import time; use-site ambiguity is a separate matter);
     *  - an ALIAS changes the effective name, so `import java.sql.Date as SqlDate` no longer collides;
     *  - identical duplicate imports (same target) are NOT a conflict (a single distinct target), they're
     *    merely redundant — left to the unused/duplicate handling, not flagged as ambiguous.
     */
    private fun conflictingImports(file: KtFile): List<Diagnostic> {
        val byName = LinkedHashMap<String, MutableList<KtImportDirective>>()
        for (imp in file.importDirectives) {
            if (imp.isAllUnder) continue // star import: no name brought in at import time
            val fq = imp.importedFqName ?: continue
            val name = imp.aliasName ?: fq.shortName().asString()
            if (name.isEmpty()) continue
            byName.getOrPut(name) { ArrayList() }.add(imp)
        }
        val out = ArrayList<Diagnostic>()
        for ((name, imports) in byName) {
            // Only a genuine ambiguity — two DIFFERENT targets sharing one name — conflicts.
            if (imports.mapNotNullTo(HashSet()) { it.importedFqName?.asString() }.size < 2) continue
            for (imp in imports) {
                val r = (imp.importedReference ?: imp).textRange
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                    "Conflicting import, imported name '$name' is ambiguous", KotlinDiagnosticCodes.CONFLICTING_IMPORT,
                )
            }
        }
        return out
    }

    /**
     * Statements in [block] that follow a statement which never completes normally: a bare `return`/`throw`/
     * `break`/`continue` or a `Nothing`-typed terminal (`TODO()`). Only DIRECT block statements count as
     * terminators (an `if (c) return` doesn't end the block), so this stays false-positive-free. Reported as
     * one warning spanning the dead range.
     */
    private fun unreachableCode(block: KtBlockExpression, resolver: KotlinResolver): List<Diagnostic> {
        // The control-flow analysis marks every statement after the first that never completes normally — now
        // including branch-level terminators (`if (c) return else return`, an exhaustive-with-else `when` whose
        // arms all jump), not just a top-level `return`/`throw`/`Nothing`-call. Dead statements are contiguous.
        val dead = KotlinControlFlow(resolver).deadStatements(block)
        if (dead.isEmpty()) return emptyList()
        val first = dead.first()
        val last = dead.last()
        return listOf(Diagnostic(TextRange(first.textRange.startOffset, last.textRange.endOffset), Severity.WARNING, "Unreachable code", KotlinDiagnosticCodes.UNREACHABLE))
    }

    /**
     * An argument-count mismatch for a call to a function declared in THIS file, the only place the exact
     * arity is readable from the PSI (default values, varargs, trailing lambda), so it's safe. A unique
     * same-file candidate by name (skipped if overloaded or has a vararg) whose required/maximum arity the
     * call violates is flagged.
     */
    /**
     * A call (member, top-level, or constructor — source OR binary) that omits a REQUIRED argument: `Button { }`
     * without `onClick`. Delegates to [KotlinResolver.missingRequiredArgument], which is sound across overloads
     * and backs off whenever per-parameter defaults aren't known. Reported as `kt.argumentCount` (the same code
     * as the same-file check), so quick-fixes keyed on it apply uniformly. The span is the argument list, or the
     * callee name for a bare trailing-lambda call (`Button { }`, which has no `(...)`).
     */
    private fun missingRequiredArgument(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val missing = runCatching { resolver.missingRequiredArgument(call) }.getOrNull() ?: return null
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        val r = call.valueArgumentList?.textRange ?: callee.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "No value passed for required parameter $missing of '${callee.getReferencedName()}'", KotlinDiagnosticCodes.ARGUMENT_COUNT,
        )
    }

    private fun argumentCountMismatch(call: KtCallExpression): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        if (call.parent is KtQualifiedExpression && (call.parent as KtQualifiedExpression).selectorExpression === call) return null // member call: receiver unknown
        if (call.valueArguments.any { it.isNamed() || it.getSpreadElement() != null }) return null // named/spread: arity rules differ
        val name = callee.getReferencedName()
        val candidates = call.containingKtFile.declarations.filterIsInstance<KtNamedFunction>()
            .filter { it.name == name && it.receiverTypeReference == null }
        val fn = candidates.singleOrNull() ?: return null // overloaded or not same-file → don't guess
        val params = fn.valueParameters
        if (params.any { it.isVarArg }) return null
        val required = params.count { !it.hasDefaultValue() && !it.isVarArg }
        val max = params.size
        val n = call.valueArguments.size
        if (n in required..max) return null
        val r = call.valueArgumentList?.textRange ?: callee.textRange
        val msg = if (n > max) "Too many arguments for '$name' (expected ${if (required == max) "$max" else "$required..$max"})"
        else "No value passed for ${required - n} required argument(s) of '$name'"
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.ARGUMENT_COUNT)
    }

    /**
     * A constructor call (`TextView(this)`, `Foo(1, 2)`) whose arguments don't fit any of the type's
     * constructors. Conservative — designed to never false-positive over the parse-only model:
     *  - only a capitalized callee that resolves to a KNOWN type whose constructors are enumerable;
     *  - **count**: flagged only when NO constructor has the argument count AND none could be variadic
     *    (an array/vararg param makes the arity open); skipped entirely for binary Kotlin classes (their
     *    metadata doesn't surface default arguments, so a same-arity check would be unsound);
     *  - **type**: an argument whose inferred type is not assignable to the parameter type of the unique
     *    arity-matching constructor (same [isMismatch] rule as a declaration's `val a: T = …` — both types
     *    must be fully-known concrete types, so an unmodeled/partial hierarchy backs off instead of
     *    false-flagging). Named/spread arguments are skipped.
     */
    private fun constructorCallMismatch(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        if (call.valueArguments.any { it.isNamed() || it.getSpreadElement() != null }) return null
        // The type name: a bare `Foo(…)`, or a qualified `pkg.Foo(…)`/`Outer.Inner(…)` where the call is the
        // selector (the receiver text + the callee name). `constructorTypeFqn` rejects it unless it resolves to
        // a known type, so a real member call (`list.add(x)`, `obj.method()`) is left to other checks.
        val parent = call.parent
        val name = if (parent is KtQualifiedExpression && parent.selectorExpression === call) {
            parent.receiverExpression.text + "." + callee.getReferencedName()
        } else {
            callee.getReferencedName()
        }
        val fqn = resolver.constructorTypeFqn(name, callee.textRange.startOffset) ?: return null
        if (service.hasKotlinMetadata(fqn)) return null // binary Kotlin: default args not visible → don't guess
        val ctors = service.constructorsOf(fqn)
        if (ctors.isEmpty()) return null
        val n = call.valueArguments.size
        // A vararg/array parameter (the display keeps `[]`) makes the arity open — don't flag the count.
        val variadic = ctors.any { it.signature?.contains("[]") == true }
        if (!variadic && ctors.none { it.paramTypes.size == n }) {
            val arities = ctors.map { it.paramTypes.size }.toSortedSet().joinToString("/")
            val r = call.valueArgumentList?.textRange ?: callee.textRange
            return Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "No constructor of '$name' takes $n argument(s) (expected $arities)", KotlinDiagnosticCodes.CONSTRUCTOR_ARGS,
            )
        }
        // Per-argument type check against the unique arity-matching constructor.
        val match = ctors.singleOrNull { it.paramTypes.size == n } ?: return null
        for ((i, arg) in call.valueArguments.withIndex()) {
            val expr = arg.getArgumentExpression() ?: continue
            val pt = match.paramTypes.getOrNull(i) as? KotlinType ?: continue
            val at = resolver.inferType(expr) ?: continue
            if (isMismatch(pt, at)) {
                val r = expr.textRange
                return mismatchDiagnostic(r.startOffset, r.endOffset, at, pt)
            }
        }
        return null
    }

    /**
     * The same check as [constructorCallMismatch] but for a class declared in THIS file, where the PSI gives
     * exact arity (default values + varargs) — the binary path can't (the typeShape index/decode has no source
     * class, and binary metadata hides defaults). A bare `Foo(args)` whose callee is a same-file top-level
     * `KtClass`: its argument count must fit some constructor's required..max, and each argument's inferred
     * type must be assignable to the corresponding parameter of the unique arity-fitting constructor (the
     * [isMismatch] rule). Backs off on overloads it can't reason about: a companion object (a possible
     * `invoke` operator), any variadic constructor, and non-instantiable kinds.
     */
    private fun sameFileConstructorMismatch(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        if (call.parent is KtQualifiedExpression && (call.parent as KtQualifiedExpression).selectorExpression === call) return null
        if (call.valueArguments.any { it.isNamed() || it.getSpreadElement() != null }) return null
        val name = callee.getReferencedName()
        if (name.firstOrNull()?.isUpperCase() != true) return null
        val cls = call.containingKtFile.declarations.filterIsInstance<KtClass>().firstOrNull { it.name == name } ?: return null
        if (cls.isInterface() || cls.isAnnotation() || cls.isEnum() || cls.companionObjects.isNotEmpty()) return null
        val ctors = constructorParameterLists(cls)
        if (ctors.any { params -> params.any { it.isVarArg } }) return null // open arity → don't guess
        val n = call.valueArguments.size
        if (ctors.none { n in it.count { p -> !p.hasDefaultValue() }..it.size }) {
            val arities = ctors.flatMap { it.count { p -> !p.hasDefaultValue() }..it.size }.toSortedSet().joinToString("/")
            val r = call.valueArgumentList?.textRange ?: callee.textRange
            return Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "No constructor of '$name' takes $n argument(s) (expected $arities)", KotlinDiagnosticCodes.CONSTRUCTOR_ARGS,
            )
        }
        // Type-check against the unique constructor whose arity fits.
        val match = ctors.singleOrNull { n in it.count { p -> !p.hasDefaultValue() }..it.size } ?: return null
        for ((i, arg) in call.valueArguments.withIndex()) {
            val expr = arg.getArgumentExpression() ?: continue
            val pt = service.typeFromText(match.getOrNull(i)?.typeReference?.text, resolver.fileContext) ?: continue
            val at = resolver.inferType(expr) ?: continue
            if (isMismatch(pt, at)) {
                val r = expr.textRange
                return mismatchDiagnostic(r.startOffset, r.endOffset, at, pt)
            }
        }
        return null
    }

    /** A same-file class's constructor parameter lists: the primary (or an implicit no-arg when there are no
     *  explicit constructors at all) plus every secondary constructor. */
    private fun constructorParameterLists(cls: KtClass): List<List<KtParameter>> {
        val lists = ArrayList<List<KtParameter>>()
        cls.primaryConstructor?.let { lists.add(it.valueParameters) }
        cls.secondaryConstructors.forEach { c: KtSecondaryConstructor -> lists.add(c.valueParameters) }
        if (lists.isEmpty()) lists.add(emptyList()) // no explicit constructor → implicit no-arg
        return lists
    }

    /** Why a single overload candidate does NOT accept a call's arguments — or [Ok] when it does. Drives
     *  [callNotApplicable]'s cross-overload verdict + message selection. */
    private sealed interface Applicability {
        object Ok : Applicability
        object TooMany : Applicability        // a positional argument falls past the (non-vararg) arity
        object MissingRequired : Applicability // a required parameter is unfilled (deferred to missingRequiredArgument)
        object NamedUnknown : Applicability    // a named argument matches no parameter (deferred to unknownNamedArguments)
        object LambdaNotApplicable : Applicability // a trailing lambda with no function-typed last parameter to land on
        /** A positional argument's type isn't assignable to its parameter; [prefix] = how many leading
         *  arguments this overload matched (higher = better fit, used to pick the overload to report against). */
        class TypeBad(val arg: KtExpression, val expected: KotlinType, val actual: KotlinType, val prefix: Int) : Applicability
    }

    /**
     * A call to a regular function or member whose arguments fit NONE of the resolved overloads — Kotlin's
     * TOO_MANY_ARGUMENTS / ARGUMENT_TYPE_MISMATCH / NONE_APPLICABLE for the non-constructor case (constructors
     * go through [constructorCallMismatch]; a missing required argument through [missingRequiredArgument]). This
     * catches what the narrow same-file [argumentCountMismatch] misses: an OVERLOADED or library callable like
     * Compose's `Text(...)` invoked as `Text("x", 1231)`, where no overload takes `(String, Int)`.
     *
     * Conservative over the parse-only model — judged across the full overload set from the index-backed
     * [KotlinResolver.callTargets], and flagged only when EVERY candidate is provably inapplicable:
     *  - only METHOD candidates (a constructor call resolves to CONSTRUCTOR candidates → none here → backs off,
     *    leaving it to the constructor checks);
     *  - backs off entirely on a spread argument (arity opaque) or if ANY candidate has unknown per-parameter
     *    defaults (its `paramHasDefault` size ≠ its arity — Java bytecode / a stale cache might accept the call);
     *  - the per-argument type check is the same [isMismatch] rule used everywhere else (both types fully-known
     *    concrete, numeric/numeric + `Nothing` excused, type parameters skipped), so an unmodeled type backs off.
     * Reports the most specific failure: a positional TYPE mismatch against the best-fitting overload (the one
     * that matched the most leading arguments — so `Text("x", 1231)` blames the `1231`, not the `"x"`), else a
     * "too many arguments". Reused codes (`kt.typeMismatch` / `kt.argumentCount`) keep quick-fixes uniform.
     */
    private fun callNotApplicable(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        if (call.valueArguments.any { it.getSpreadElement() != null }) return null
        val candidates = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
            .filter { it.kind == SymbolKind.METHOD }
        if (candidates.isEmpty()) return null
        val verdicts = ArrayList<Applicability>(candidates.size)
        for (c in candidates) verdicts += applicability(c, call, resolver) ?: return null // unjudgeable → back off
        if (verdicts.any { it is Applicability.Ok }) return null // some overload accepts the call
        // Prefer the precise type mismatch from the overload that matched the most leading arguments.
        verdicts.filterIsInstance<Applicability.TypeBad>().maxByOrNull { it.prefix }?.let { tb ->
            val r = tb.arg.textRange
            return mismatchDiagnostic(r.startOffset, r.endOffset, tb.actual, tb.expected)
        }
        // The trailing lambda can't be placed in ANY overload (every one's last parameter is non-functional) —
        // `Text("Title") { }`. Reported only when unanimous, so a sibling overload that DOES take a lambda (which
        // failed for another reason) doesn't get blamed here; the span is the offending lambda.
        if (verdicts.all { it is Applicability.LambdaNotApplicable }) {
            val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
            val r = (call.valueArguments.lastOrNull() as? KtLambdaArgument)?.textRange ?: return null
            return Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "'${callee.getReferencedName()}' has no function-type parameter for this trailing lambda", KotlinDiagnosticCodes.ARGUMENT_COUNT,
            )
        }
        // No candidate failed on a type; defer the named/missing cases to their dedicated checks and only own the
        // "too many arguments" verdict (which no other check reports for an overloaded / library / member call).
        if (verdicts.all { it is Applicability.TooMany }) {
            val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
            val arities = candidates.mapNotNull { c ->
                if (c.varargParamIndex >= 0) null else maxOf(c.paramTypes.size, c.paramNames.size)
            }.toSortedSet().joinToString("/")
            val r = call.valueArgumentList?.textRange ?: callee.textRange
            val expected = if (arities.isEmpty()) "" else " (expected $arities)"
            return Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Too many arguments for '${callee.getReferencedName()}'$expected", KotlinDiagnosticCodes.ARGUMENT_COUNT,
            )
        }
        return null
    }

    /**
     * A call that fits TWO OR MORE overloads with no most-specific one to pick — Kotlin's
     * OVERLOAD_RESOLUTION_AMBIGUITY (`f(1, 1)` where both `f(Int, Any)` and `f(Any, Int)` apply; `p(null)` where
     * both `p(String?)` and `p(Int?)` apply). The counterpart to [callNotApplicable] (which owns the NONE-apply
     * case): here two-plus candidates ARE applicable but none is more specific than the rest.
     *
     * Ruthlessly conservative so it never false-positives over the parse-only model:
     *  - only same-EXACT-arity, vararg-free candidates are compared (defaults/varargs make specificity subtle);
     *  - every compared PARAMETER must be a known, concrete, non-type-parameter, non-functional type, and every
     *    ARGUMENT must be a null literal or concretely typed — otherwise applicability may have skipped a slot;
     *  - identical signatures are de-duplicated (the same declaration surfaced twice is not an ambiguity);
     *  - if a unique most-specific candidate exists ([uniqueMostSpecific]) the call resolves — not flagged.
     */
    private fun overloadAmbiguity(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        if (call.valueArguments.any { it.getSpreadElement() != null }) return null
        if (call.valueArguments.any { it.getArgumentName() != null }) return null // named args → positional map invalid; back off
        val candidates = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
            .filter { it.kind == SymbolKind.METHOD }
        if (candidates.size < 2) return null
        val applicable = ArrayList<KotlinSymbol>()
        for (c in candidates) {
            val v = applicability(c, call, resolver) ?: return null // unjudgeable → back off
            if (v is Applicability.Ok) applicable += c
        }
        val n = call.valueArguments.size
        var distinct = applicable
            .filter { it.paramTypes.size == n && it.varargParamIndex < 0 }
            .distinctBy { c -> c.paramTypes.joinToString(",") { (it as? KotlinType)?.qualifiedName ?: "?" } }
        if (distinct.size < 2) return null
        // Every compared parameter must be precisely comparable, else specificity isn't decidable → back off.
        if (distinct.any { c -> c.paramTypes.any { p -> p !is KotlinType || p.isTypeParameter || isFunctional(p) || !service.isKnownType(p.qualifiedName) } }) return null
        // Every argument must be a null literal or a concretely-typed value (applicability skips unknown/functional).
        val argTypes = call.valueArguments.map { a ->
            if (a is KtLambdaArgument) return null
            val e = a.getArgumentExpression() ?: return null
            if (isNullLiteral(e)) null
            else resolver.inferType(e)?.takeIf { !it.isTypeParameter && !isFunctional(it) && service.isKnownType(it.qualifiedName) } ?: return null
        }
        // Numeric-exact refinement: isMismatch excuses ALL numeric/numeric pairs (for integer-literal adaptation),
        // so `println(anInt)` admits the Byte/Short/Long/… overloads too. A non-literal numeric argument is really
        // applicable only to the SAME numeric parameter or a non-numeric (supertype) one — drop the rest so the
        // exact overload (`println(Int)`) is the unique winner instead of a false Byte-vs-Short ambiguity.
        distinct = distinct.filter { c ->
            argTypes.indices.all { i ->
                val an = argTypes[i]?.let { Builtins.kotlinTypeFor(it.qualifiedName) ?: it.qualifiedName }
                if (an == null || an !in NUMERIC_RANK) return@all true
                val pn = (c.paramTypes.getOrNull(i) as? KotlinType)?.let { Builtins.kotlinTypeFor(it.qualifiedName) ?: it.qualifiedName } ?: return@all true
                pn !in NUMERIC_RANK || pn == an
            }
        }
        if (distinct.size < 2) return null
        if (uniqueMostSpecific(distinct) != null) return null // a most-specific overload resolves it
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        val r = callee.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Overload resolution ambiguity: '${callee.getReferencedName()}' matches multiple functions", KotlinDiagnosticCodes.OVERLOAD_AMBIGUITY,
        )
    }

    /** The single candidate that is at-least-as-specific as EVERY other (each of its parameter types is a subtype
     *  of the corresponding parameter type of the other), or null when zero or several such maximal candidates
     *  exist (the ambiguous case). Mirrors Kotlin's most-specific-candidate rule over value-parameter types. */
    private fun uniqueMostSpecific(cands: List<KotlinSymbol>): KotlinSymbol? =
        cands.filter { a -> cands.all { b -> a === b || atLeastAsSpecific(a, b) } }.singleOrNull()

    /** Whether [a] is at least as specific as [b]: same arity and each of [a]'s parameter types is [b]'s type or
     *  a subtype of it (a value of [a]'s parameter could be passed where [b]'s is expected). Numeric parameters
     *  are ordered by NUMERIC_RANK (`Int` more specific than `Long` …) so that a numeric-coercible argument — for
     *  which applicability treats every numeric overload as applicable — still has a unique most-specific overload
     *  (`println(Int)` for `println(anInt)`), rather than a false ambiguity between the incomparable numerics. */
    private fun atLeastAsSpecific(a: KotlinSymbol, b: KotlinSymbol): Boolean {
        if (a.paramTypes.size != b.paramTypes.size) return false
        return a.paramTypes.indices.all { i ->
            val ap = a.paramTypes[i] as? KotlinType ?: return false
            val bp = b.paramTypes[i] as? KotlinType ?: return false
            // Canonicalize JVM names to Kotlin classifiers (`java.lang.Integer` → `kotlin.Int`), as isMismatch
            // does — otherwise a numeric overload from bytecode wouldn't match NUMERIC_RANK and would look
            // incomparable, producing a false ambiguity on `println(anInt)`.
            val apn = Builtins.kotlinTypeFor(ap.qualifiedName) ?: ap.qualifiedName
            val bpn = Builtins.kotlinTypeFor(bp.qualifiedName) ?: bp.qualifiedName
            val ar = NUMERIC_RANK[apn]
            val br = NUMERIC_RANK[bpn]
            when {
                bpn == apn -> true
                bpn == "kotlin.Any" -> true                              // Any is the top type; anything ⪯ Any
                ar != null && br != null -> ar <= br                     // both numeric → smaller is more specific
                ar != null && bpn in NUMERIC_SUPERTYPES -> true          // a numeric ⪯ Number/Comparable
                else -> bp.isAssignableFrom(ap)
            }
        }
    }

    private val NUMERIC_SUPERTYPES = setOf("kotlin.Number", "kotlin.Comparable")

    /** Evaluate one overload [c] against [call]: map each argument to a parameter (named by name, a trailing
     *  lambda to the last parameter, the rest by position), type-check the positional non-vararg ones, and
     *  report the first blocking reason. Returns null when [c] can't be judged soundly (unknown defaults). */
    private fun applicability(c: KotlinSymbol, call: KtCallExpression, resolver: KotlinResolver): Applicability? {
        if (c.paramTypes.isNotEmpty() && c.paramHasDefault.size != c.paramTypes.size) return null // unknown defaults
        val paramCount = maxOf(c.paramTypes.size, c.paramNames.size)
        val vararg = c.varargParamIndex
        // A trailing lambda must land on the LAST parameter (Kotlin's rule), which must therefore be a function
        // type or a SAM. If there's no parameter for it, or the last parameter is a KNOWN non-functional, non-SAM
        // type (`Text("x") { }` → the last param is `TextStyle`), the lambda can't be placed in this overload.
        // An unknown last-param type (or a vararg, whose arity is open) backs off — could legitimately take it.
        val trailingLambda = call.valueArguments.lastOrNull() as? KtLambdaArgument
        if (trailingLambda != null && vararg < 0) {
            if (paramCount == 0) return Applicability.LambdaNotApplicable
            val lpt = c.paramTypes.getOrNull(paramCount - 1) as? KotlinType
            if (lpt != null && service.isKnownType(lpt.qualifiedName) && !isFunctional(lpt) && service.functionalShape(lpt) == null)
                return Applicability.LambdaNotApplicable
        }
        val supplied = HashSet<Int>()
        var typeBad: Applicability.TypeBad? = null
        var prefix = 0
        call.valueArguments.forEachIndexed { i, arg ->
            val named = arg.getArgumentName()?.asName?.identifier
            val idx = when {
                named != null -> c.paramNames.indexOf(named).let { if (it < 0) return Applicability.NamedUnknown else it }
                arg is KtLambdaArgument -> (paramCount - 1).coerceAtLeast(0)
                else -> i
            }
            // A positional argument past the fixed arity with no vararg to absorb it → too many.
            if (named == null && arg !is KtLambdaArgument && idx >= paramCount && vararg < 0) return Applicability.TooMany
            supplied += idx
            // Type-check a positional/named argument bound to a non-vararg parameter slot. The vararg slot (and
            // anything folding into it) is open-typed here — left to other reasoning.
            if (arg !is KtLambdaArgument && (vararg < 0 || idx < vararg)) {
                val expr = arg.getArgumentExpression()
                val pt = c.paramTypes.getOrNull(idx) as? KotlinType
                val at = expr?.let { resolver.inferType(it) }
                // Skip functional positions entirely (a lambda / callable reference / function-typed parameter or
                // SAM): a function value's inferred shape vs. the expected type is too imprecise here to flag
                // soundly, and these dominate Compose call sites.
                if (expr != null && pt != null && at != null && !isFunctional(pt) && !isFunctional(at)) {
                    if (isMismatch(pt, at)) { if (typeBad == null) typeBad = Applicability.TypeBad(expr, pt, at, prefix) }
                    else if (typeBad == null) prefix++ // a matched leading argument (only before the first mismatch)
                }
            }
        }
        typeBad?.let { return it }
        val missing = c.paramTypes.indices.any {
            it !in supplied && it != vararg && c.paramHasDefault.getOrElse(it) { true } == false
        }
        return if (missing) Applicability.MissingRequired else Applicability.Ok
    }

    /** A Kotlin function type (`(T) -> R`), a receiver function type, or a `@Composable` function type — a
     *  functional argument/parameter slot, excluded from the positional type check (see [applicability]). */
    private fun isFunctional(t: KotlinType): Boolean =
        t.qualifiedName.startsWith("kotlin.Function") || t.isExtensionFunctionType || t.isComposable

    /**
     * An assignment (`x = expr`) whose right-hand side type isn't assignable to the target's declared type
     * (`var n: Int = 0; n = "s"`). Only the property INITIALIZER was type-checked before ([typeMismatch]); a
     * later assignment went unchecked. Same conservative [isMismatch] rule (both types fully-known concrete);
     * the target must be a simple/qualified reference whose type the resolver pins (else it backs off). Plain
     * `=` only — augmented assignments (`+=`, …) have operator semantics that aren't modeled here.
     */
    private fun assignmentTypeMismatch(expr: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
        if (expr.operationToken != KtTokens.EQ) return null
        val left = expr.left ?: return null
        if (left !is KtNameReferenceExpression && left !is KtDotQualifiedExpression) return null
        val right = expr.right ?: return null
        val declared = resolver.inferType(left) ?: return null
        nullForNonNull(declared, right)?.let { return it }
        val actual = resolver.inferType(right) ?: return null
        if (!isMismatch(declared, actual)) return null
        val r = right.textRange
        return mismatchDiagnostic(r.startOffset, r.endOffset, actual, declared)
    }

    /**
     * A Java bean accessor called explicitly on a Java receiver where Kotlin's synthetic-property syntax reads
     * better — Kotlin's USE_PROPERTY_ACCESS_SYNTAX (`view.setText("x")` → `view.text = "x"`, `view.getText()` →
     * `view.text`). A WARNING, never an error. Conservative over the parse-only model:
     *  - only a member call `recv.getX()` / `recv.isX()` (0 args) / `recv.setX(v)` (1 positional arg);
     *  - the receiver must be a plain JAVA type ([KotlinSymbolService.isJavaType]) — a Kotlin `fun getX()` is a
     *    real function, not an accessor, so it's left alone;
     *  - the corresponding synthetic property must actually resolve on the receiver (so the suggested form is
     *    valid), which it does only for a Java type's bean accessors (see [KotlinSymbolService.membersFromShape]).
     */
    private fun usePropertyAccess(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        val qualified = call.parent as? KtDotQualifiedExpression ?: return null
        if (qualified.selectorExpression !== call) return null // must be `recv.accessor(...)`
        if (call.valueArguments.any { it.isNamed() || it.getSpreadElement() != null || it is KtLambdaArgument }) return null
        val name = callee.getReferencedName()
        val args = call.valueArguments.size
        val isSetter: Boolean
        val candidates: List<String> // candidate Kotlin property names (the setter may pair with an `is` getter)
        when {
            name.length > 3 && name.startsWith("get") && name[3].isUpperCase() && args == 0 ->
                { isSetter = false; candidates = listOf(decapitalizeAccessor(name.substring(3))) }
            name.length > 2 && name.startsWith("is") && name[2].isUpperCase() && args == 0 ->
                { isSetter = false; candidates = listOf(name) }
            name.length > 3 && name.startsWith("set") && name[3].isUpperCase() && args == 1 ->
                { isSetter = true; candidates = listOf(decapitalizeAccessor(name.substring(3)), "is" + name.substring(3)) }
            else -> return null
        }
        val recvType = resolver.inferType(qualified.receiverExpression) ?: return null
        if (recvType.isTypeParameter || !service.isJavaType(recvType.qualifiedName)) return null
        // Only when the synthetic property actually resolves — so applying the suggestion can't break the code.
        val prop = candidates.firstOrNull { cand ->
            service.membersNamedForCheck(recvType.qualifiedName, recvType.typeArguments, cand).any { it.kind == SymbolKind.FIELD }
        } ?: return null
        val suggestion = if (isSetter) {
            "$prop = ${call.valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: "value"}"
        } else prop
        return Diagnostic(
            TextRange(callee.textRange.startOffset, call.textRange.endOffset), Severity.WARNING,
            "Use property access syntax: '$suggestion'", KotlinDiagnosticCodes.USE_PROPERTY_ACCESS,
        )
    }

    /** Kotlin's accessor-name decapitalization (`Text` → `text`, `URL` → `URL`). Mirrors
     *  [KotlinSymbolService.decapitalizeAccessor] (kept local — the analyzer doesn't reach the private one). */
    private fun decapitalizeAccessor(suffix: String): String =
        if (suffix.length > 1 && suffix[1].isUpperCase()) suffix
        else suffix.replaceFirstChar { it.lowercaseChar() }

    /**
     * A bare `name(...)` call where `name` is not callable — it resolves to a VALUE (a local/parameter/property)
     * of a non-function type, so it can't be invoked (Kotlin's FUNCTION_EXPECTED: `val x = 5; x()`). Conservative:
     *  - only a bare callee (a member call `a.b()` is left to [unresolvedMember]);
     *  - backs off if ANY function/constructor overload by that name is in scope (it's callable);
     *  - backs off when the value's type is unknown (an unresolved name is [unresolvedBareReference]'s job), a
     *    function type, or carries an `invoke` operator (so a functional value / callable object isn't flagged).
     */
    private fun notCallable(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        val parent = call.parent
        if (parent is KtQualifiedExpression && parent.selectorExpression === call) return null // member call
        // A capitalized callee that resolves to a known TYPE is a CONSTRUCTOR call, not a value invocation —
        // never "not callable" (a class with no explicit constructor, esp. cross-file where callTargets may not
        // yet carry the synthesized no-arg ctor; and so an abstract type isn't mislabeled "not a function").
        if (resolver.constructorTypeFqn(callee.getReferencedName(), callee.textRange.startOffset) != null) return null
        val callable = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
        if (callable.any { it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CONSTRUCTOR }) return null
        val type = resolver.inferType(callee) ?: return null // unknown → unresolvedBareReference handles it
        if (type.isTypeParameter || isInvocable(type) || !service.isKnownType(type.qualifiedName)) return null
        val r = callee.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Expression '${callee.getReferencedName()}' of type ${renderType(type)} cannot be invoked as a function", KotlinDiagnosticCodes.NOT_CALLABLE,
        )
    }

    /** Whether a value of [type] can be invoked with `()`: a Kotlin function type, a receiver function type, or
     *  a type declaring an `invoke` operator. Kept permissive so a callable value is never flagged. */
    private fun isInvocable(type: KotlinType): Boolean {
        if (type.qualifiedName.startsWith("kotlin.Function") || type.isExtensionFunctionType) return true
        return service.membersNamedForCheck(type.qualifiedName, type.typeArguments, "invoke").isNotEmpty()
    }

    /**
     * A `when (subject) { … }` used AS AN EXPRESSION that is not exhaustive and has no `else` — Kotlin's
     * NO_ELSE_IN_WHEN. Handles the cases the parse-only model can decide soundly:
     *  - **Boolean** subject → needs both `true` and `false`;
     *  - **enum** subject → needs every constant (enumerated via the index-backed [KotlinSymbolService.enumConstantsOf]);
     *  - **sealed** subject whose subclasses are all in THIS file → needs every subclass (`is Sub`).
     * Backs off entirely on a subjectless `when`, a statement-position `when` (no exhaustiveness requirement), a
     * nullable subject (the extra `null` branch isn't modeled), and any other / cross-file-sealed subject type —
     * so it never false-positives.
     */
    private fun whenNotExhaustive(expr: KtWhenExpression, resolver: KotlinResolver): Diagnostic? {
        val subject = expr.subjectExpression ?: return null
        if (expr.entries.any { it.isElse }) return null
        if (!whenUsedAsExpression(expr)) return null
        // Back off if any branch uses a condition we can't map to a concrete label/type — a range (`in …`), a
        // negated `!is`, or a computed expression. We can't tell what it covers, so we must not claim
        // non-exhaustiveness (soundness over completeness).
        if (expr.entries.any { e -> e.conditions.any { !isModeledWhenCondition(it) } }) return null
        val type = resolver.inferType(subject) ?: return null
        if (type.nullable) return null
        val covered = coveredConstants(expr)
        if (type.qualifiedName == "kotlin.Boolean") {
            if ("true" in covered && "false" in covered) return null
            return whenDiag(expr, "'when' on a Boolean must be exhaustive; add 'true'/'false' branches or an 'else'")
        }
        val enumConsts = runCatching { service.enumConstantsOf(type.qualifiedName) }.getOrDefault(emptyList())
        if (enumConsts.isNotEmpty()) {
            val missing = enumConsts.map { it.name }.filter { it !in covered }
            if (missing.isEmpty()) return null
            return whenDiag(expr, "'when' expression must be exhaustive; add branches for ${missing.joinToString(", ")} or an 'else'")
        }
        // Cross-file sound: a sealed type's subclasses are same-module, so the project model enumerates them
        // ALL (null → not a known source sealed type → back off, e.g. a library sealed class).
        val sealedSubs = service.sealedSubclassesOf(type.qualifiedName) ?: return null
        if (sealedSubs.isEmpty()) return null
        val coveredTypes = coveredTypeNames(expr)
        val missing = sealedSubs.filter { it.substringAfterLast('.') !in coveredTypes }
        if (missing.isEmpty()) return null
        return whenDiag(expr, "'when' expression must be exhaustive; add branches for ${missing.joinToString(", ") { it.substringAfterLast('.') }} or an 'else'")
    }

    private fun whenDiag(expr: KtWhenExpression, msg: String): Diagnostic {
        val kw = expr.whenKeyword.textRange
        return Diagnostic(TextRange(kw.startOffset, kw.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.WHEN_EXHAUSTIVE)
    }

    /** A syntactic, false-positive-free subset of "this `when` is used as an expression" (its value is consumed):
     *  a property initializer, a `return`, a call argument, an operand of a binary expression, or an expression
     *  function body. A `when` that is a block statement (its parent is a [KtBlockExpression]) needs no
     *  exhaustiveness, so it isn't flagged; ambiguous nestings simply aren't reported. */
    private fun whenUsedAsExpression(expr: KtWhenExpression): Boolean = when (val parent = expr.parent) {
        is KtProperty -> parent.initializer === expr
        is KtReturnExpression -> parent.returnedExpression === expr
        is org.jetbrains.kotlin.psi.KtValueArgument -> true
        is KtBinaryExpression -> parent.left === expr || parent.right === expr
        is KtNamedFunction -> parent.bodyExpression === expr && !parent.hasBlockBody()
        else -> false
    }

    /** A `when` branch condition this check can reason about: a simple label (a name / qualified name / literal,
     *  for enum + Boolean) or a non-negated `is` pattern (for sealed). A range (`in …`), a negated `!is`, or a
     *  computed-expression label is NOT modeled — its presence makes [whenNotExhaustive] back off. */
    private fun isModeledWhenCondition(c: org.jetbrains.kotlin.psi.KtWhenCondition): Boolean = when (c) {
        is KtWhenConditionWithExpression ->
            c.expression.let { it is KtNameReferenceExpression || it is KtDotQualifiedExpression || it is KtConstantExpression }
        is KtWhenConditionIsPattern -> !c.isNegated
        else -> false
    }

    /** The constant/literal labels a `when`'s branches cover: a bare name (`RED`), the selector of a qualified
     *  one (`Color.RED` → `RED`), or a literal (`true`/`false`). */
    private fun coveredConstants(expr: KtWhenExpression): Set<String> {
        val out = HashSet<String>()
        for (e in expr.entries) for (c in e.conditions) {
            if (c is KtWhenConditionWithExpression) when (val ce = c.expression) {
                is KtNameReferenceExpression -> out += ce.getReferencedName()
                is KtDotQualifiedExpression -> (ce.selectorExpression as? KtNameReferenceExpression)?.let { out += it.getReferencedName() }
                is KtConstantExpression -> out += ce.text
                else -> {}
            }
        }
        return out
    }

    /** The simple type names a `when`'s `is`-pattern branches cover (`is Loading` → `Loading`). */
    private fun coveredTypeNames(expr: KtWhenExpression): Set<String> {
        val out = HashSet<String>()
        for (e in expr.entries) for (c in e.conditions) {
            if (c is KtWhenConditionIsPattern) c.typeReference?.text?.let { out += it.substringBefore('<').substringAfterLast('.').trim() }
        }
        return out
    }

    /**
     * `recv.member` on a nullable receiver without `?.`/`!!` (`val s: String? = …; s.length`). Conservative:
     * smart-casts are not modeled, so if the receiver is a simple name with any null-guard in the enclosing
     * function (`s != null`, `s ?:`, `s?.`, `s!!`), this backs off entirely to avoid the common false positive.
     */
    private fun unsafeNullableAccess(expr: KtDotQualifiedExpression, resolver: KotlinResolver): Diagnostic? {
        val receiver = expr.receiverExpression
        val recvType = resolver.inferType(receiver) ?: return null
        if (!recvType.nullable) return null
        if (receiver is KtNameReferenceExpression) {
            // Flow is now the authority: `smartCastNonNull` precisely models the direct guards (if/else, early
            // exit, `&&`/`||`, elvis, `!!`, `require`, `is`, and — for a `var` — reassignment). Only forms it does
            // NOT model (a `when`-based guard, or a scope function like `s?.let { … }`) suppress the error, so a
            // useless or absent guard no longer hides a real unsafe call (`if (s == null) {}; s.length` now errors).
            if (resolver.smartCastNonNull(receiver)) return null
            if (hasUnmodeledNullGuard(expr, receiver.getReferencedName())) return null
        } else {
            // A qualified receiver (`b.s`): a member deref is smart-cast by a dominating null guard on that exact
            // access path (`if (b.s != null) b.s.length`). Suppress on that, and on a when/scope-function guard.
            val path = receiver.text
            if (resolver.pathGuardedNonNullAt(path, receiver.textRange.startOffset)) return null
            if (hasUnmodeledNullGuard(expr, path)) return null
        }
        val r = receiver.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Only safe (?.) or non-null asserted (!!) calls are allowed on a nullable receiver", KotlinDiagnosticCodes.UNSAFE_NULLABLE,
        )
    }

    /** Whether the enclosing function guards [name] with a construct the flow analysis does NOT model — a `when`
     *  that tests it, or a scope function (`let`/`also`/`run`/`apply`/`takeIf`/`takeUnless`) on it. Those genuinely
     *  smart-cast in Kotlin, so [unsafeNullableAccess] backs off for them (no false positive) — but, unlike the
     *  old catch-all, a plain `== null` / `!= null` / `if` guard is left to the precise flow analysis, so a
     *  non-guarding occurrence no longer suppresses a real error. */
    private fun hasUnmodeledNullGuard(from: PsiElement, name: String): Boolean {
        val fn = from.getStrictParentOfType<KtNamedFunction>() ?: from.containingFile
        val text = fn.text ?: return true
        val n = Regex.escape(name)
        // A scope function invoked on the value (`s.let`, `s?.also`, …) — flow doesn't follow the receiver in.
        if (Regex("\\b$n\\b\\s*\\??\\.(let|also|run|apply|takeIf|takeUnless)\\b").containsMatchIn(text)) return true
        // A `when` present AND the value tested by it (as its subject, or in an `is`/`== null` condition).
        if (Regex("\\bwhen\\b").containsMatchIn(text) &&
            (Regex("\\bwhen\\s*\\(\\s*$n\\b").containsMatchIn(text) ||
                Regex("\\b$n\\b\\s*(is\\b|!is\\b|[!=]=\\s*null)").containsMatchIn(text) ||
                Regex("null\\s*[!=]=\\s*\\b$n\\b").containsMatchIn(text))
        ) return true
        return false
    }

    /**
     * A declared-type vs. initializer type mismatch (`val a: Int = ""`). Conservative to avoid false
     * positives over the parse-only model: flags only when BOTH the declared type and the inferred initializer
     * type are fully-known concrete types (no type parameters, no unknown names) and the value is not
     * assignable to the declaration. Numeric/numeric pairs are skipped entirely (integer literals adapt to
     * the expected numeric type, e.g. `val a: Long = 5`, which is not modeled here) and `Nothing`
     * (`TODO()`/`throw`) is assignable to everything, so it's skipped too.
     */
    private val NUMERIC = setOf(
        "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float",
    )

    private val ASSIGN_OPS = setOf(
        KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ,
    )
    private val INCDEC = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)
    private val COMPARISON_OPS = setOf(
        KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ,
        KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ,
    )

    private val VISIBILITY_MODIFIERS = listOf(
        KtTokens.PUBLIC_KEYWORD, KtTokens.PRIVATE_KEYWORD, KtTokens.PROTECTED_KEYWORD, KtTokens.INTERNAL_KEYWORD,
    )

    // Inheritance-modifier pairs that cannot co-occur (Kotlin's INCOMPATIBLE_MODIFIERS). `final` excludes every
    // form of openness; `abstract` and `open`/`sealed` together is merely redundant, so it's left alone here.
    private val INCOMPATIBLE_MODIFIERS = listOf(
        KtTokens.FINAL_KEYWORD to KtTokens.OPEN_KEYWORD,
        KtTokens.FINAL_KEYWORD to KtTokens.ABSTRACT_KEYWORD,
        KtTokens.FINAL_KEYWORD to KtTokens.SEALED_KEYWORD,
    )

    // Convention/operator function names: imported for use via `+`, `[]`, `in`, `by`, destructuring, etc.,
    // which don't surface the name as a textual reference — so an unused-import check must not flag them.
    private val OPERATOR_NAMES = setOf(
        "plus", "minus", "times", "div", "rem", "mod", "plusAssign", "minusAssign", "timesAssign", "divAssign",
        "remAssign", "inc", "dec", "unaryPlus", "unaryMinus", "not", "get", "set", "invoke", "contains",
        "iterator", "next", "hasNext", "compareTo", "equals", "rangeTo", "rangeUntil", "provideDelegate",
        "getValue", "setValue", "component1", "component2", "component3", "component4", "component5",
    )

    private fun typeMismatch(declaredText: String?, init: KtExpression?, resolver: KotlinResolver): Diagnostic? {
        if (declaredText == null || init == null) return null
        val declared = service.typeFromText(declaredText, resolver.fileContext)
        nullForNonNull(declared, init)?.let { return it }
        val actual = resolver.inferType(init)
        if (!isMismatch(declared, actual)) return null
        val r = init.textRange
        return mismatchDiagnostic(r.startOffset, r.endOffset, actual!!, declared!!)
    }

    /** `return <expr>` whose value type isn't assignable to the enclosing block-body function's declared
     *  return type (`fun f(): Int { return "" }`), OR a value returned from a function whose return type is
     *  `Unit` — explicitly (`fun f(): Unit`) or implicitly (a block body with no declared type) — which is
     *  Kotlin's RETURN_TYPE_MISMATCH (`fun f() { return 5 }`). Skips labeled returns (they target a lambda). */
    private fun returnTypeMismatch(ret: KtReturnExpression, resolver: KotlinResolver): Diagnostic? {
        if (ret.getTargetLabel() != null) return null
        val value = ret.returnedExpression ?: return null
        val fn = ret.getStrictParentOfType<KtNamedFunction>() ?: return null
        if (!fn.hasBlockBody()) return null
        val actual = resolver.inferType(value)
        val declaredText = fn.typeReference?.text
        // A block body with no declared return type has the type Unit; returning a value from it is an error.
        if (declaredText == null) return unitReturnMismatch(value, actual)
        val declared = service.typeFromText(declaredText, resolver.fileContext)
        if (declared?.qualifiedName == "kotlin.Unit") return unitReturnMismatch(value, actual)
        nullForNonNull(declared, value)?.let { return it }
        if (!isMismatch(declared, actual)) return null
        val r = value.textRange
        return mismatchDiagnostic(r.startOffset, r.endOffset, actual!!, declared!!)
    }

    /** A value returned where `Unit` was expected (see [returnTypeMismatch]). Conservative: the returned value's
     *  type must be a fully-known, non-`Unit`/`Nothing`, non-type-parameter type — so an unmodeled expression
     *  (whose type the parse-only model can't pin) never false-flags. */
    private fun unitReturnMismatch(value: KtExpression, actual: KotlinType?): Diagnostic? {
        if (actual == null || actual.isTypeParameter) return null
        if (actual.qualifiedName == "kotlin.Unit" || actual.qualifiedName == "kotlin.Nothing") return null
        if (!service.isKnownType(actual.qualifiedName)) return null
        val r = value.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Type mismatch: inferred type is ${renderType(actual)} but Unit was expected", KotlinDiagnosticCodes.TYPE_MISMATCH,
        )
    }

    /**
     * Destructuring diagnostics: each entry needs a `componentN()` on the destructured value's type, and an
     * explicitly-typed entry (`val (a: Int, b) = …`) must accept its component's type. Conservative:
     *  - the source type must be inferable, known, and CONFIRMED destructurable (it has a `component1()`, or it
     *    is a project source class we fully model) — so a library type whose `componentN` extension the
     *    parse-only model can't see is never false-flagged as non-destructurable;
     *  - a `_` entry requires no component (the compiler skips it);
     *  - the type check reuses [isMismatch], which itself backs off on any unknown/type-parameter type.
     * This catches the classic too-many-entries mistake (`val (a, b, c) = pair`) and a wrong entry type.
     */
    private fun destructuringMismatch(d: KtDestructuringDeclaration, resolver: KotlinResolver): List<Diagnostic> {
        val source = resolver.destructuringSourceType(d) ?: return emptyList()
        if (source.isTypeParameter || !service.isKnownType(source.qualifiedName)) return emptyList()
        val destructurable = resolver.componentTypeFor(source, 0) != null || service.isSourceClass(source.qualifiedName)
        if (!destructurable) return emptyList()
        val out = ArrayList<Diagnostic>()
        d.entries.forEachIndexed { i, e ->
            val name = e.name
            if (name == null || name == "_") return@forEachIndexed // an ignored entry calls no componentN
            val comp = resolver.componentTypeFor(source, i)
            if (comp == null) {
                val r = e.textRange
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                    "Destructuring of '${renderType(source)}' requires a 'component${i + 1}()' function",
                    KotlinDiagnosticCodes.DESTRUCTURING,
                )
            } else {
                val declared = e.typeReference?.text?.let { service.typeFromText(it, resolver.fileContext) }
                if (declared != null && isMismatch(declared, comp)) {
                    val r = (e.typeReference ?: e).textRange
                    out += mismatchDiagnostic(r.startOffset, r.endOffset, comp, declared)
                }
            }
        }
        return out
    }

    /** Whether [actual] is a confidently-incompatible value for a declaration of type [declared].
     *  Both must be fully-known concrete types; numeric/numeric and `Nothing` are excused (see [typeMismatch]). */
    private fun isMismatch(declared: KotlinType?, actual: KotlinType?): Boolean {
        if (declared == null || actual == null) return false
        if (declared.isTypeParameter || actual.isTypeParameter) return false
        if (actual.qualifiedName == "kotlin.Nothing") return false
        // A type read from Java bytecode keeps its JVM name (`java.lang.String`); it IS the Kotlin type it maps
        // to. Canonicalize both sides so a `java.lang.String` target accepts a `kotlin.String` value (and is
        // `isKnownType`), matching the mapping member/supertype lookup already applies (`Builtins.kotlinTypeFor`).
        val d = canonicalForCheck(declared)
        val a = canonicalForCheck(actual)
        if (!service.isKnownType(d.qualifiedName) || !service.isKnownType(a.qualifiedName)) return false
        // Base types compatible — but a generic TYPE ARGUMENT may still definitely clash (`List<String>` vs
        // `List<Int>`). No variance can bridge two unrelated final argument types, so that is a real mismatch.
        if (d.isAssignableFrom(a)) return genericArgsMismatch(d, a)
        return !(d.qualifiedName in NUMERIC && a.qualifiedName in NUMERIC)
    }

    /** Whether same-classifier generic types clash on a type argument — each compared position holding two
     *  UNRELATED FINAL types (`List<String>` vs `List<Int>`, `Map<String, …>` vs `Map<Int, …>`). Requires the
     *  same base classifier and arity (a subtype base's argument positions may not align → back off), and recurses
     *  into nested arguments. Only unrelated finals count, so covariance/contravariance/invariance is irrelevant
     *  (none can relate two disjoint final types) — no variance model needed and no false positive. */
    private fun genericArgsMismatch(d: KotlinType, a: KotlinType): Boolean {
        if (d.qualifiedName != a.qualifiedName || d.typeArguments.size != a.typeArguments.size) return false
        return d.typeArguments.indices.any { i ->
            val da = d.typeArguments[i] as? KotlinType ?: return@any false
            val aa = a.typeArguments[i] as? KotlinType ?: return@any false
            when {
                da.isTypeParameter || aa.isTypeParameter -> false
                !service.isKnownType(da.qualifiedName) || !service.isKnownType(aa.qualifiedName) -> false
                da.qualifiedName == aa.qualifiedName -> genericArgsMismatch(da, aa) // same base → check nested args
                da.isAssignableFrom(aa) || aa.isAssignableFrom(da) -> false          // a subtype relation → variance may allow it
                da.qualifiedName in NUMERIC && aa.qualifiedName in NUMERIC -> false  // numeric adaptation
                else -> comparisonFinal(da) && comparisonFinal(aa)                   // two disjoint finals → clash
            }
        }
    }

    /** Canonicalize a JVM scalar/value type to its Kotlin classifier for the mismatch comparison. The flexible
     *  platform COLLECTION types (a Java `List`/`Map` is assignable to both `List` and `MutableList`) are left
     *  as their JVM name on purpose, so [isMismatch] backs off rather than falsely flag `javaListField = listOf(…)`. */
    private fun canonicalForCheck(t: KotlinType): KotlinType {
        val mapped = Builtins.kotlinTypeFor(t.qualifiedName) ?: return t
        return if (mapped.startsWith("kotlin.collections")) t else t.withClassifier(mapped)
    }

    /**
     * The `null` literal assigned where a known NON-nullable type is expected — Kotlin's NULL_FOR_NONNULL_TYPE
     * (`var s: String = null`, `s = null`, `return null` in a non-null function). The `null` literal has no
     * inferred type (`inferType` returns null), so the plain [isMismatch] path backs off; this catches it
     * directly. Conservative: the target must be a fully-known, non-nullable, non-type-parameter type — a
     * nullable target (`String?`), a type parameter, or an unknown/aliased type backs off.
     */
    private fun nullForNonNull(target: KotlinType?, value: KtExpression?): Diagnostic? {
        if (target == null || value == null || !isNullLiteral(value)) return null
        if (target.nullable || target.isTypeParameter) return null
        if (!service.isKnownType(canonicalForCheck(target).qualifiedName)) return null
        val r = value.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Null can not be a value of a non-null type ${renderType(target)}", KotlinDiagnosticCodes.TYPE_MISMATCH,
        )
    }

    /** Whether [expr] (ignoring parentheses) is the `null` literal. */
    private fun isNullLiteral(expr: KtExpression): Boolean {
        val e = unwrapParen(expr)
        return e is KtConstantExpression && e.text.trim() == "null"
    }

    private fun mismatchDiagnostic(start: Int, end: Int, actual: KotlinType, declared: KotlinType) = Diagnostic(
        TextRange(start, end), Severity.ERROR,
        "Type mismatch: inferred type is ${renderType(actual)} but ${renderType(declared)} was expected",
        KotlinDiagnosticCodes.TYPE_MISMATCH,
    )

    private fun renderType(t: KotlinType): String {
        // A synthetic local/anonymous type key must not leak into the message — show `<anonymous …>` / the
        // local class's real name instead of the internal `$L<ordinal>` id.
        service.localTypeDisplayName(t.qualifiedName)?.let { return it + if (t.nullable) "?" else "" }
        val simple = t.qualifiedName.substringAfterLast('.')
        val args = if (t.typeArguments.isEmpty()) ""
        else t.typeArguments.joinToString(", ", "<", ">") { it.qualifiedName.substringAfterLast('.') }
        return simple + args + if (t.nullable) "?" else ""
    }

    /**
     * A cast (`x as T`) whose operand is already exactly type `T` — Kotlin's USELESS_CAST ("No cast needed").
     * Conservative: only an unchecked `as` cast (not `as?`) where both the operand's inferred type and the
     * target are fully-known concrete types that are structurally identical (FQN + nullability + type
     * arguments). A type-parameter / unknown / generics-differing case backs off, so a real narrowing or
     * unchecked cast is never flagged. A warning (not an error), matching Kotlin.
     */
    private fun uselessCast(expr: KtBinaryExpressionWithTypeRHS, resolver: KotlinResolver): Diagnostic? {
        if (expr.operationReference.getReferencedNameElementType() != KtTokens.AS_KEYWORD) return null // skip `as?`
        val typeRef = expr.right ?: return null
        val target = service.typeFromText(typeRef.text, resolver.fileContext) ?: return null
        val actual = resolver.inferType(expr.left) ?: return null
        if (actual.isTypeParameter || target.isTypeParameter) return null
        if (!service.isKnownType(actual.qualifiedName) || !service.isKnownType(target.qualifiedName)) return null
        if (!sameType(actual, target)) return null
        val r = expr.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "No cast needed", KotlinDiagnosticCodes.USELESS_CAST)
    }

    /**
     * A cast (`x as T`) between two DEFINITELY-final, unrelated types — Kotlin's CAST_NEVER_SUCCEEDS
     * ("This cast can never succeed"). Reuses [incomparableEquality]'s soundness bar: both the operand's inferred
     * type and the target must be known, non-type-parameter, [comparisonFinal] (a curated builtin value type or
     * an enum — no subtype can bridge them), with different classifiers and no subtype relation, and not a
     * numeric/numeric pair. Only an unchecked `as` (not `as?`, which yields null); a NULLABLE target is skipped
     * (a `null` value could satisfy it). Anything involving an interface/open class/`Any` backs off.
     */
    private fun castNeverSucceeds(expr: KtBinaryExpressionWithTypeRHS, resolver: KotlinResolver): Diagnostic? {
        if (expr.operationReference.getReferencedNameElementType() != KtTokens.AS_KEYWORD) return null // skip `as?`
        val typeRef = expr.right ?: return null
        if (typeRef.text.trim().endsWith("?")) return null // `as T?` — a null value could satisfy it
        val target = service.typeFromText(typeRef.text, resolver.fileContext) ?: return null
        val actual = resolver.inferType(expr.left) ?: return null
        if (actual.isTypeParameter || target.isTypeParameter) return null
        if (actual.qualifiedName == target.qualifiedName) return null
        if (actual.isAssignableFrom(target) || target.isAssignableFrom(actual)) return null // a subtype relation
        if (actual.qualifiedName in NUMERIC && target.qualifiedName in NUMERIC) return null
        if (!comparisonFinal(actual) || !comparisonFinal(target)) return null // interface/open could bridge them
        val r = expr.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "This cast can never succeed: '${renderType(actual)}' cannot be '${renderType(target)}'",
            KotlinDiagnosticCodes.CAST_NEVER_SUCCEEDS,
        )
    }

    /** Structural type equality: same FQN, nullability, and (recursively) type arguments. */
    private fun sameType(a: KotlinType, b: KotlinType): Boolean {
        if (a.qualifiedName != b.qualifiedName || a.nullable != b.nullable) return false
        if (a.typeArguments.size != b.typeArguments.size) return false
        return a.typeArguments.indices.all { i ->
            val x = a.typeArguments[i] as? KotlinType
            val y = b.typeArguments[i] as? KotlinType
            if (x == null || y == null) (x?.qualifiedName == y?.qualifiedName) else sameType(x, y)
        }
    }

    /**
     * An elvis (`x ?: y`) whose left operand can never be null — Kotlin's USELESS_ELVIS. To stay
     * false-positive-free over the parse-only model (a Java platform type can be inferred non-null when it is
     * actually nullable), this fires ONLY when the left operand is STRUCTURALLY non-null: a `!!` assertion or a
     * non-`null` literal (`s!! ?: x`, `"a" ?: x`). A warning, matching Kotlin.
     */
    private fun uselessElvis(expr: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
        if (expr.operationToken != KtTokens.ELVIS) return null
        val left = expr.left
        val nonNull = isStructurallyNonNull(left) || (left is KtNameReferenceExpression && resolver.smartCastNonNull(left))
        if (!nonNull) return null
        val op = expr.operationReference.textRange
        return Diagnostic(
            TextRange(op.startOffset, expr.textRange.endOffset), Severity.WARNING,
            "Elvis operator (?:) always returns the left operand of non-nullable type", KotlinDiagnosticCodes.USELESS_ELVIS,
        )
    }

    /** Whether [expr] is structurally guaranteed non-null: a `!!` assertion, or a literal other than `null`. */
    private fun isStructurallyNonNull(expr: KtExpression?): Boolean = when (val e = expr?.let { unwrapParen(it) }) {
        is KtPostfixExpression -> e.operationToken == KtTokens.EXCLEXCL
        is KtConstantExpression -> e.text != "null"
        is KtStringTemplateExpression -> true
        else -> false
    }

    /**
     * A bare reference (no explicit receiver) in value position whose name resolves to nothing: a likely typo
     * or missing import, whether it's a call (`prinltn("x")`), a constructor / composable call (`Foo()`,
     * `Greeting()`), an assignment target, or a plain read (`val x = bogus` / a bare `ComponentActivity`).
     * Both lower- and upper-case names are checked (a capitalized name resolves via a type/object/import, a
     * same-file class, or a constructor — [KotlinResolver.bareNameResolves] knows all of these). Skipped for:
     * member selectors (handled by [unresolvedMember]), the receiver of a qualified expression (could be a
     * package or a type's static access), type-position references (handled by [unresolvedTypeReference]) and
     * annotations, import/package directives, named-argument labels, the implicit lambda `it`, a property
     * accessor's `field`, and any scope with a companion object (whose members are bare-accessible but not modeled).
     */
    private fun unresolvedBareReference(expr: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
        val parent = expr.parent
        // `a.b` selector, or the callee `b` of `a.b()` — a member, resolved by unresolvedMember (not here).
        if (parent is KtQualifiedExpression && parent.selectorExpression === expr) return null
        if (parent is KtCallExpression && parent.calleeExpression === expr) {
            (parent.parent as? KtQualifiedExpression)?.let { if (it.selectorExpression === parent) return null }
        }
        // `x.foo` / `x.foo()` — the receiver `x` of a qualified expression. Usually left alone: it may be a
        // package segment (`androidx.compose.…`) or a not-yet-built same-package class (Android `R`/`BuildConfig`),
        // neither of which is an error. But it may ALSO be a TYPE the file forgot to import, used for companion/
        // static access (`FontWeight.Bold` with no `import …FontWeight`) — which Kotlin reports as "Unresolved
        // reference", and which the selector's [unresolvedMember] can't flag (it can't type the unresolved
        // receiver, so it backs off). Defer the decision: flag such a receiver ONLY if it's confidently a known,
        // unimported LIBRARY type (the gate near the end) — never a package/generated-class receiver.
        val isQualifiedReceiver = parent is KtQualifiedExpression && parent.receiverExpression === expr
        // A callable reference `Type::member` / `::top` — its selector resolves against the receiver type or
        // top-level scope (see [KotlinResolver.callableReferenceTarget]), not the bare-name scope, so it must
        // never be flagged here (both the receiver and the referenced callable are children of the `::`).
        if (parent is org.jetbrains.kotlin.psi.KtCallableReferenceExpression) return null
        if (parent is KtValueArgumentName) return null // a named-argument label, not a reference
        // `this`/`super` parse as a KtNameReferenceExpression under a KtInstanceExpressionWithLabel — they are
        // keywords (a receiver, typed by the resolver), never an unresolved name.
        if (parent is org.jetbrains.kotlin.psi.KtInstanceExpressionWithLabel) return null
        if (inImportOrPackage(expr) || inTypeReference(expr)) return null
        val name = expr.getReferencedName()
        if (name.isEmpty()) return null
        if (name == "it" && hasAncestor(expr) { it is org.jetbrains.kotlin.psi.KtLambdaExpression }) return null
        if (name == "field" && hasAncestor(expr) { it is KtPropertyAccessor }) return null
        val off = expr.textRange.startOffset
        if (resolver.companionInScope(off) || resolver.bareNameResolves(name, off)) return null
        // A qualified-expression receiver (see above) is flagged only when it's confidently a known, unimported
        // LIBRARY type — a real missing import — never a package segment or a generated/same-package class the
        // index doesn't hold as a library type.
        if (isQualifiedReceiver && !service.hasLibraryType(name)) return null
        val r = expr.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Unresolved reference: $name", KotlinDiagnosticCodes.UNRESOLVED)
    }

    /**
     * A TYPE reference whose simple name resolves to nothing in scope — a missing import or a typo
     * (`val a: ComponentActivity` / `class X : AnyClass()` with no matching import). The outermost SIMPLE
     * (unqualified) user type is checked. Conservative back-offs that are NOT errors: an explicitly-imported
     * name, a generic type parameter in scope, a project `typealias`, and annotation type references (their own
     * rules). Qualified / nested names (`java.util.Locale`, `Outer.Inner`) are left to other checks.
     */
    /**
     * A type reference whose explicit type-argument list has the wrong count for its classifier (`Map<Int>`,
     * `List<A, B>`, `Int<String>`). Conservative: only a simple (non-qualified) user type with an explicit
     * `<…>`, resolving to a KNOWN class, and — to dodge a classpath decode gap that under-reports type params
     * — the "expected 0" case is flagged only for SOURCE classes (where the count is read straight from PSI).
     */
    private fun typeArgumentCountMismatch(userType: KtUserType, resolver: KotlinResolver): Diagnostic? {
        if (userType.qualifier != null || userType.parent is KtUserType) return null // nested/qualified → back off
        val argList = userType.typeArgumentList ?: return null // no `<>` → not a wrong-count case
        val ref = userType.referenceExpression ?: return null
        val name = ref.getReferencedName()
        if (name.isEmpty()) return null
        val ctx = resolver.fileContext
        if (service.isProjectTypeAlias(name)) return null // a typealias has its own (aliased) arity
        if (resolver.isTypeParameterInScope(name, userType.textRange.startOffset)) return null
        val fqn = service.resolveTypeName(name, ctx) ?: return null
        if (!service.isKnownType(fqn)) return null // unknown classifier → back off
        // The builtin collections come from `.kotlin_builtins`, which doesn't surface type parameters through
        // the shape index (returns []); their arities are fixed and well-known, so fall back to a hardcoded map
        // for them (makes the common `Map<Int>` / `List<A, B>` mistakes flag).
        val decoded = service.classTypeParameters(fqn).size
        val expected = if (decoded == 0) (BUILTIN_TYPE_ARITY[fqn] ?: 0) else decoded
        val actual = argList.arguments.size
        if (actual == expected) return null
        // `expected == 0` for a non-source class that isn't a known builtin could be a classpath decode gap → back off.
        if (expected == 0 && !service.isSourceClass(fqn) && fqn !in BUILTIN_TYPE_ARITY) return null
        val r = argList.textRange
        val msg = if (expected == 0) "Type '$name' does not take type arguments"
        else "$expected type argument${if (expected == 1) "" else "s"} expected for '$name', but $actual found"
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT)
    }

    /**
     * A type argument that violates its type parameter's declared UPPER BOUND — Kotlin's UPPER_BOUND_VIOLATED
     * (`Box<String>` where `class Box<T : Number>`). Conservative: only when the classifier's bounds are known
     * ([KotlinSymbolService.classTypeParameterBounds]) and the violation is DEFINITE — the argument is a final
     * type not in the bound's hierarchy (its supertype closure is complete), or a nullable argument under a
     * non-nullable bound. A star projection, a type-parameter argument, or an unknown/interface-bounded case
     * backs off (see [boundViolated]).
     */
    private fun typeArgumentBoundViolation(userType: KtUserType, resolver: KotlinResolver): List<Diagnostic> {
        if (userType.parent is KtUserType) return emptyList() // a qualifier segment
        val argList = userType.typeArgumentList ?: return emptyList()
        val name = userType.referenceExpression?.getReferencedName()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (service.isProjectTypeAlias(name) || resolver.isTypeParameterInScope(name, userType.textRange.startOffset)) return emptyList()
        val fqn = service.resolveTypeName(name, resolver.fileContext) ?: return emptyList()
        val bounds = service.classTypeParameterBounds(fqn)
        if (bounds.isEmpty() || bounds.all { it == null }) return emptyList()
        return checkArgsAgainstBounds(argList.arguments, bounds, resolver)
    }

    /** As [typeArgumentBoundViolation] but for a call's EXPLICIT type arguments (`g<String>()` where
     *  `fun <T : Number> g()`), checked against the resolved callee's own type-parameter bounds. */
    private fun callTypeArgumentBoundViolation(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
        val args = call.typeArgumentList?.arguments ?: return emptyList()
        if (args.isEmpty()) return emptyList()
        val callee = resolver.calleeFunctionOf(call) ?: return emptyList()
        val bounds = callee.typeParameterBounds.map { it as? KotlinType }
        if (bounds.isEmpty() || bounds.all { it == null }) return emptyList()
        return checkArgsAgainstBounds(args, bounds, resolver)
    }

    private fun checkArgsAgainstBounds(args: List<org.jetbrains.kotlin.psi.KtTypeProjection>, bounds: List<KotlinType?>, resolver: KotlinResolver): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        args.forEachIndexed { i, proj ->
            if (proj.projectionKind == org.jetbrains.kotlin.psi.KtProjectionKind.STAR) return@forEachIndexed
            val bound = bounds.getOrNull(i) ?: return@forEachIndexed
            val argRef = proj.typeReference ?: return@forEachIndexed
            val argType = service.typeFromText(argRef.text, resolver.fileContext) ?: return@forEachIndexed
            if (boundViolated(argType, bound)) {
                val r = argRef.textRange
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                    "Type argument is not within its bounds: should be a subtype of '${renderType(bound)}'",
                    KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED,
                )
            }
        }
        return out
    }

    /** Whether [arg] DEFINITELY violates the upper [bound]: a base-type violation provable only for a final arg
     *  (complete supertype closure), or a nullable arg under a non-nullable bound. Unknown/type-parameter/
     *  non-final-unrelated cases back off (return false) so this never false-positives. */
    private fun boundViolated(arg: KotlinType, bound: KotlinType): Boolean {
        if (arg.isTypeParameter || bound.isTypeParameter) return false
        if (!service.isKnownType(arg.qualifiedName) || !service.isKnownType(bound.qualifiedName)) return false
        return when (typeWithinBound(arg, bound)) {
            false -> true                                        // base type is definitely not within (final arg)
            true -> !bound.nullable && arg.nullable              // base ok, but nullable arg can't satisfy a non-null bound
            null -> false                                        // undecidable → back off
        }
    }

    /** Whether [arg]'s base type is within [bound]: true (definitely), false (definitely not — only for a final
     *  arg whose supertype closure is complete), or null (undecidable). */
    private fun typeWithinBound(arg: KotlinType, bound: KotlinType): Boolean? {
        val an = Builtins.kotlinTypeFor(arg.qualifiedName) ?: arg.qualifiedName
        val bn = Builtins.kotlinTypeFor(bound.qualifiedName) ?: bound.qualifiedName
        if (bn == "kotlin.Any" || an == bn) return true
        if (bound.isAssignableFrom(arg)) return true
        if (an in NUMERIC_RANK && bn in NUMERIC_SUPERTYPES) return true
        if (service.supertypesOf(an).any { (Builtins.kotlinTypeFor(it.qualifiedName) ?: it.qualifiedName) == bn }) return true
        return if (comparisonFinal(arg)) false else null // a final arg's closure is complete → definitely not within
    }

    /** Fixed arities of the builtin collection/array types (their type parameters aren't surfaced by the shape
     *  index, see [typeArgumentCountMismatch]). Non-generic builtins (`String`, `Int`) are deliberately ABSENT
     *  — they'd be `0`, but an absent entry there backs the check off rather than risk a decode-gap false flag. */
    private val BUILTIN_TYPE_ARITY = mapOf(
        "kotlin.collections.Iterable" to 1, "kotlin.collections.MutableIterable" to 1,
        "kotlin.collections.Collection" to 1, "kotlin.collections.MutableCollection" to 1,
        "kotlin.collections.List" to 1, "kotlin.collections.MutableList" to 1,
        "kotlin.collections.Set" to 1, "kotlin.collections.MutableSet" to 1,
        "kotlin.collections.Map" to 2, "kotlin.collections.MutableMap" to 2,
        "kotlin.collections.Iterator" to 1, "kotlin.collections.MutableIterator" to 1,
        "kotlin.collections.ListIterator" to 1, "kotlin.collections.MutableListIterator" to 1,
        "kotlin.Comparable" to 1, "kotlin.Array" to 1,
    )

    // --- variance / projection misuse (declaration-site TYPE_VARIANCE_CONFLICT + use-site CONFLICTING/REDUNDANT_PROJECTION) ---

    /** Declaration-site variance of the well-known builtin generics (not surfaced by the shape index). `"out"`/
     *  `"in"`/`""` per type parameter. Read-only collections are covariant; MUTABLE ones invariant (`add`/`set`
     *  consume the element); `Comparable` contravariant; `Map` is `[inv K, out V]`; `Array` invariant. */
    private val BUILTIN_TYPE_VARIANCE = mapOf(
        "kotlin.collections.Iterable" to listOf("out"), "kotlin.collections.MutableIterable" to listOf("out"),
        "kotlin.collections.Collection" to listOf("out"), "kotlin.collections.MutableCollection" to listOf(""),
        "kotlin.collections.List" to listOf("out"), "kotlin.collections.MutableList" to listOf(""),
        "kotlin.collections.Set" to listOf("out"), "kotlin.collections.MutableSet" to listOf(""),
        "kotlin.collections.Iterator" to listOf("out"), "kotlin.collections.MutableIterator" to listOf("out"),
        "kotlin.collections.ListIterator" to listOf("out"), "kotlin.collections.MutableListIterator" to listOf(""),
        "kotlin.collections.Map" to listOf("", "out"), "kotlin.collections.MutableMap" to listOf("", ""),
        "kotlin.Comparable" to listOf("in"), "kotlin.Array" to listOf(""),
        "kotlin.sequences.Sequence" to listOf("out"),
    )

    /** A variance position for the declaration-site walk. UNKNOWN = an unresolved-variance slot, which never
     *  yields a conflict (so an unknown classifier in a member signature backs off instead of false-positive). */
    private enum class VPos {
        OUT, IN, INV, UNKNOWN;
        fun flip() = when (this) { OUT -> IN; IN -> OUT; else -> this } // INV/UNKNOWN unchanged
    }

    /** Compose the incoming [pos] with a nested slot's declaration-site variance ([slot]: `"out"`/`"in"`/`""`
     *  invariant, or null = unknown): `out` keeps, `in` flips, invariant erases to INV, unknown erases to UNKNOWN. */
    private fun composeVariance(pos: VPos, slot: String?): VPos = when (slot) {
        "out" -> pos
        "in" -> pos.flip()
        "" -> if (pos == VPos.UNKNOWN) VPos.UNKNOWN else VPos.INV
        else -> VPos.UNKNOWN
    }

    /**
     * Declaration-site variance conflicts (Kotlin's TYPE_VARIANCE_CONFLICT): an `out` (covariant) type parameter
     * must occur only in `out` positions, an `in` (contravariant) one only in `in` positions. Positions: a
     * function's value parameters (and receiver) are `in`, its return type `out`; a `val` property is `out`, a
     * `var` property invariant; supertype arguments are `out`. Nested generics COMPOSE (via [composeVariance]),
     * a function type `(A) -> B` contributes A at `in` and B at `out` (syntactic), a use-site projection on an
     * argument overrides its slot, and a star projection erases the occurrence. `@UnsafeVariance` suppresses it.
     *
     * Conservative: variance is known only for source classes ([KotlinSymbolService.classTypeParameterVariance])
     * and the builtins ([BUILTIN_TYPE_VARIANCE]); an unknown classifier's slot becomes UNKNOWN (no conflict), so
     * a classpath generic backs the composition off rather than false-positive. Private members and plain
     * (non-`val`/`var`) constructor parameters are exempt; a member's own type parameter shadows the class's.
     */
    private fun declarationSiteVarianceConflicts(cls: KtClass, resolver: KotlinResolver): List<Diagnostic> {
        val params = cls.typeParameters
            .filter { it.variance != Variance.INVARIANT }
            .mapNotNull { tp -> tp.name?.let { it to tp.variance } }
            .toMap()
        if (params.isEmpty()) return emptyList()
        val out = ArrayList<Diagnostic>()
        for (member in cls.declarations) {
            if (member.hasModifier(KtTokens.PRIVATE_KEYWORD)) continue
            val scoped = shadowedRemoved(params, member)
            if (scoped.isEmpty()) continue
            when (member) {
                is KtNamedFunction -> {
                    walkVariance(member.receiverTypeReference, VPos.IN, scoped, resolver, out)
                    member.valueParameters.forEach { walkVariance(it.typeReference, VPos.IN, scoped, resolver, out) }
                    walkVariance(member.typeReference, VPos.OUT, scoped, resolver, out)
                }
                is KtProperty -> walkVariance(member.typeReference, if (member.isVar) VPos.INV else VPos.OUT, scoped, resolver, out)
                else -> {}
            }
        }
        // Primary-constructor `val`/`var` parameters are properties; plain params are private (exempt).
        cls.primaryConstructor?.valueParameters?.forEach { p ->
            if (!p.hasValOrVar() || p.hasModifier(KtTokens.PRIVATE_KEYWORD)) return@forEach
            walkVariance(p.typeReference, if (p.isMutable) VPos.INV else VPos.OUT, params, resolver, out)
        }
        // Supertype type arguments are produced (`out` position).
        cls.superTypeListEntries.forEach { walkVariance(it.typeReference, VPos.OUT, params, resolver, out) }
        return out
    }

    /** [params] minus any name shadowed by [member]'s own type parameters (a member `fun <T> f()` shadows the
     *  class's `T` within it, so that name is unconstrained there). */
    private fun shadowedRemoved(params: Map<String, Variance>, member: KtDeclaration): Map<String, Variance> {
        val own = (member as? KtTypeParameterListOwner)?.typeParameters?.mapNotNull { it.name }?.toSet().orEmpty()
        return if (own.isEmpty()) params else params.filterKeys { it !in own }
    }

    /** Walk a member type reference at variance [pos], recording any variant-parameter occurrence in a wrong
     *  position. `@UnsafeVariance` and an UNKNOWN position short-circuit the walk. */
    private fun walkVariance(typeRef: KtTypeReference?, pos: VPos, params: Map<String, Variance>, resolver: KotlinResolver, out: MutableList<Diagnostic>) {
        if (typeRef == null || pos == VPos.UNKNOWN) return
        if (typeRef.annotationEntries.any { it.shortName?.asString() == "UnsafeVariance" }) return
        walkVarianceElement(typeRef.typeElement, pos, params, resolver, out)
    }

    private fun walkVarianceElement(el: KtTypeElement?, pos: VPos, params: Map<String, Variance>, resolver: KotlinResolver, out: MutableList<Diagnostic>) {
        when (el) {
            null -> {}
            is KtNullableType -> walkVarianceElement(el.innerType, pos, params, resolver, out)
            is KtFunctionType -> {
                walkVariance(el.receiverTypeReference, pos.flip(), params, resolver, out) // receiver consumed → in
                el.parameters.forEach { walkVariance(it.typeReference, pos.flip(), params, resolver, out) } // params → in
                walkVariance(el.returnTypeReference, pos, params, resolver, out) // return → out
            }
            is KtUserType -> {
                val name = el.referenceExpression?.getReferencedName()
                if (el.qualifier == null && name != null && params.containsKey(name)) {
                    recordVarianceUse(el, name, params.getValue(name), pos, out) // a direct occurrence of the param
                    return
                }
                val args = el.typeArgumentList?.arguments ?: return
                if (args.isEmpty()) return
                val declared = name?.let { declaredVarianceOf(el, it, resolver) } // null → classifier variance unknown
                args.forEachIndexed { i, proj ->
                    val slotPos = when (proj.projectionKind) {
                        KtProjectionKind.STAR -> return@forEachIndexed // T does not occur under a star projection
                        KtProjectionKind.OUT -> composeVariance(pos, "out")
                        KtProjectionKind.IN -> composeVariance(pos, "in")
                        KtProjectionKind.NONE -> if (declared == null) VPos.UNKNOWN else composeVariance(pos, declared.getOrNull(i))
                    }
                    walkVariance(proj.typeReference, slotPos, params, resolver, out)
                }
            }
            else -> {}
        }
    }

    private fun recordVarianceUse(el: KtUserType, name: String, declared: Variance, pos: VPos, out: MutableList<Diagnostic>) {
        val conflict = when (declared) {
            Variance.OUT_VARIANCE -> pos == VPos.IN || pos == VPos.INV
            Variance.IN_VARIANCE -> pos == VPos.OUT || pos == VPos.INV
            else -> false
        }
        if (!conflict) return
        val declStr = if (declared == Variance.OUT_VARIANCE) "out" else "in"
        val occStr = when (pos) { VPos.OUT -> "out"; VPos.IN -> "in"; else -> "invariant" }
        val r = (el.referenceExpression ?: el).textRange
        out += Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Type parameter '$name' is declared as '$declStr' but occurs in '$occStr' position",
            KotlinDiagnosticCodes.VARIANCE_CONFLICT,
        )
    }

    /** The declaration-site variance of the classifier named [name] in [userType] (`"out"`/`"in"`/`""` per
     *  parameter), or null when unknown (a type-parameter name, an unresolvable name, or a classpath binary) so
     *  the caller backs off. Builtins first, then the source model. */
    private fun declaredVarianceOf(userType: KtUserType, name: String, resolver: KotlinResolver): List<String>? {
        if (resolver.isTypeParameterInScope(name, userType.textRange.startOffset)) return null
        val fqn = service.resolveTypeName(name, resolver.fileContext) ?: return null
        BUILTIN_TYPE_VARIANCE[fqn]?.let { return it }
        return service.classTypeParameterVariance(fqn).ifEmpty { null }
    }

    /**
     * Use-site projection misuse: a projection conflicting with the type parameter's declaration-site variance
     * (Kotlin's CONFLICTING_PROJECTION, an error — `List<in String>`, `Comparable<out T>`), or one matching it
     * and thus redundant (REDUNDANT_PROJECTION, a warning — `List<out String>`). Per type-argument on a simple
     * user type whose classifier's variance is known ([declaredVarianceOf]); an invariant slot (where a
     * projection IS meaningful, `Array<out Any>`), an unknown classifier, a star/plain argument, or an arity gap
     * backs off.
     */
    private fun useSiteProjectionMisuse(userType: KtUserType, resolver: KotlinResolver): List<Diagnostic> {
        if (userType.parent is KtUserType) return emptyList() // a qualifier segment
        val args = userType.typeArgumentList?.arguments ?: return emptyList()
        if (args.isEmpty()) return emptyList()
        val name = userType.referenceExpression?.getReferencedName()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val declared = declaredVarianceOf(userType, name, resolver) ?: return emptyList()
        val out = ArrayList<Diagnostic>()
        args.forEachIndexed { i, proj ->
            val use = when (proj.projectionKind) {
                KtProjectionKind.IN -> "in"
                KtProjectionKind.OUT -> "out"
                else -> return@forEachIndexed // NONE / STAR: nothing to check
            }
            val decl = declared.getOrNull(i)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed // invariant/unknown slot
            val r = proj.textRange
            if (decl == use) {
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.WARNING,
                    "Projection is redundant: the corresponding type parameter of '$name' is already declared as '$decl'",
                    KotlinDiagnosticCodes.REDUNDANT_PROJECTION,
                )
            } else {
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                    "Projection '$use' conflicts with the variance '$decl' of the corresponding type parameter of '$name'",
                    KotlinDiagnosticCodes.CONFLICTING_PROJECTION,
                )
            }
        }
        return out
    }

    private fun unresolvedTypeReference(userType: KtUserType, resolver: KotlinResolver, localAliases: Set<String>): Diagnostic? {
        // A qualified type (`java.util.Locale`, `Outer.Inner`) or a qualifier SEGMENT of one (the `gen` in
        // `gen.Txt`) — left alone; only a standalone simple name is checked.
        if (userType.qualifier != null || userType.parent is KtUserType) return null
        // Annotation type references (`@Foo`) ARE checked here — an unknown/unimported annotation is as much an
        // error as any unresolved type. They resolve through the same paths below (import / same-package /
        // builtin / star-import / `resolveTypeName`), so a real annotation (`@Composable`, `@Deprecated`, a
        // same-package annotation class) never false-positives, while `@Undefined` is flagged.
        val ref = userType.referenceExpression ?: return null
        val name = ref.getReferencedName()
        if (name.isEmpty()) return null
        val ctx = resolver.fileContext
        if (ctx.imports.any { !it.isStar && it.simpleName == name }) return null // explicitly imported → trust it
        if (name in localAliases || service.isProjectTypeAlias(name)) return null // a typealias, not a class
        val off = userType.textRange.startOffset
        if (resolver.isTypeParameterInScope(name, off)) return null
        if (resolver.localTypesInScope(off).containsKey(name)) return null // a local `class Foo` / `object O` in scope
        // In scope (imported / same-package / source / default / builtin / star-imported) → resolves; don't flag.
        val resolved = service.resolveTypeName(name, ctx)
        if (resolved != null && service.isKnownType(resolved)) return null
        val r = ref.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Unresolved reference: $name", KotlinDiagnosticCodes.UNRESOLVED)
    }

    private fun inImportOrPackage(expr: PsiElement): Boolean =
        hasAncestor(expr) { it is KtImportDirective || it is KtPackageDirective }

    private fun unresolvedMember(expr: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
        val parent = expr.parent
        // `recv.name` (property) or `recv.name(...)` (call — expr is the callee under the selector call).
        val receiver = when {
            parent is KtQualifiedExpression && parent.selectorExpression === expr -> parent.receiverExpression
            parent is KtCallExpression && parent.calleeExpression === expr ->
                (parent.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === parent }?.receiverExpression
            else -> null
        } ?: return null
        val inferred = resolver.inferType(receiver) ?: return null // unknown receiver → don't flag
        // A bare type-parameter receiver declared in scope (`t.member` where `t: T`, `<T : Bound>`) validates
        // against the parameter's upper bound — whether or not the inferred `T` is metadata/source-MARKED (a
        // class field's `T` is marked, a function parameter's isn't; both must resolve). `receiverForMembers`
        // returns the bound (in-scope + bounded), null (in-scope but unbounded → can't enumerate → back off),
        // or `inferred` unchanged (not a scope type parameter). Run FIRST so the marked-T back-off below only
        // catches a LEAKED `T` (a stdlib/library generic inference left unbound, not declared here): its
        // universal `T`-receiver extensions (`let`/`run`/…) would falsely "resolve" everything, so back off.
        val recvType = resolver.receiverForMembers(inferred, receiver.textRange.startOffset) ?: return null
        if (recvType.isTypeParameter) return null
        val name = expr.getReferencedName()
        if (name.isEmpty()) return null // an incomplete `recv.` — not a real member reference (and avoids a full scan)
        // `Owner.Nested` — a nested type/object reached through a member selector (Compose's `Icons.Filled`,
        // `Icons.AutoMirrored`, `Icons.AutoMirrored.Filled`): a classifier, not an instance member, so it never
        // appears in membersOf. Probe the candidate nested FQN (mirrors KotlinResolver.nestedType) so a chain
        // bottoming out at an icon extension property (`Icons.AutoMirrored.Filled.List`) isn't falsely flagged.
        if (name.isNotEmpty() && service.isKnownType("${recvType.qualifiedName}.$name")) return null
        // A static enum-constant access (`SomeEnum.A`): the entry resolves on the enum type, but enum entries
        // aren't instance members so membersNamed misses them. Resolve via the TYPE DENOTATION (not the
        // inferred [recvType], which can be a value shadowing the type — e.g. an enum `E` shadowed by the
        // top-level `kotlin.math.E`, whose type is Double) so it isn't false-flagged.
        resolver.typeDenotationFqn(receiver)?.let { if (name in resolver.enumConstantNames(it)) return null }
        // `super.member` (e.g. `super.onCreate(...)`): a SOURCE supertype's members are fully enumerable, but a
        // binary/framework supertype (`ComponentActivity`) reaches inherited members through boot-classpath
        // ancestors (`android.app.Activity`) the symbol reader may not have read — its chain enumeration is
        // best-effort, so don't risk a false "unresolved" on a valid override call.
        if (unwrapParen(receiver) is KtSuperExpression && service.sourceClass(recvType.qualifiedName) == null) return null
        // A member-extension in scope (`fun Map<…>.printMap()` declared in this class, `Modifier.weight` inside a
        // `Row { }`) resolves on a matching receiver WITHOUT an import — its dispatch receiver is an implicit
        // `this` here — so it must not be flagged unresolved (the import gate below applies only to top-level
        // extensions). Checked first since these never need an import.
        if (resolver.scopeMemberExtensions(expr.textRange.startOffset, recvType, name).any { it.name == name }) return null
        // Does the receiver have a member named `name`? Push the NAME into the lookup so only same-named
        // members/extensions are materialized + receiver-bound — not the type's whole extension set (the
        // `kotlin.Any` bucket alone is thousands on a Compose classpath). A `Type.member` reference (`Color.Red`)
        // also sees the type's companion-object members/statics, which instance membersNamed doesn't list.
        val matching = service.membersNamedForCheck(recvType.qualifiedName, recvType.typeArguments, name) +
            if (resolver.isTypeReceiver(receiver)) service.companionMembersFor(recvType.qualifiedName, name).filter { it.name == name } else emptyList()
        if (matching.isNotEmpty()) {
            // A plain member resolves outright. An EXTENSION resolves only when it is actually in scope —
            // imported, same-package, or default-imported — so an unimported `16.dp` / `14.sp` (the extension is
            // on the classpath but not brought in) stays unresolved, as Kotlin reports.
            val ctx = resolver.fileContext
            if (matching.any { !it.isExtension || extensionInScope(it, ctx) }) return null
        }
        // No same-named member. Flag ONLY if the receiver type is actually enumerable — an unknown type (which
        // membersNamed can't see into) must not yield a false "unresolved" (the old `membersOf(…)` returned an
        // empty set for an unknown type and was skipped; `isKnownType` is that same guard without enumerating).
        if (!service.isKnownType(recvType.qualifiedName)) return null
        val r = expr.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Unresolved reference: $name", KotlinDiagnosticCodes.UNRESOLVED)
    }

    /**
     * Whether an extension [sym] is in scope at the use site. Kotlin resolves an extension only when it is
     * imported (explicitly or via a star/default import) or declared in the file's own package, so a
     * classpath extension that was never imported (`16.dp` without `import androidx.compose.ui.unit.dp`) does
     * NOT resolve. No package info → don't guess a rejection (treat as in scope). Mirrors the interpreter's
     * `KotlinTreeResolver.extensionInScope`.
     */
    private fun extensionInScope(sym: KotlinSymbol, ctx: FileContext): Boolean {
        val pkg = sym.packageName ?: sym.declaringClassFqn?.substringBeforeLast('.', "")?.ifEmpty { null } ?: return true
        if (pkg == ctx.packageName || DefaultImports.isDefaultImported(pkg)) return true
        return ctx.imports.any { imp -> if (imp.isStar) imp.packageName == pkg else imp.fqn == "$pkg.${sym.name}" }
    }

    /**
     * A named argument (`foo(bar = 1)`) whose name matches no parameter of any function/constructor the call
     * could resolve to — a typo like `Text(colour = …)`. Conservative to avoid false positives over the
     * parse-only model: it backs off entirely when the target is uncertain — a member call whose receiver
     * can't be typed, a callee that resolves to nothing, or any plausible target whose parameter names were
     * stripped (Java bytecode surfaces `p0`/`p1`, so a name can't be validated). A genuinely zero-parameter
     * target contributes no names but doesn't suppress the check. A name is flagged only when it definitely
     * belongs to no candidate overload.
     */
    private fun unknownNamedArguments(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
        val named = call.valueArguments.mapNotNull { it.getArgumentName() }
        if (named.isEmpty()) return emptyList()
        // A member call we can't type → the target is unknown; don't risk a false positive.
        val parent = call.parent
        if (parent is KtQualifiedExpression && parent.selectorExpression === call &&
            resolver.inferType(parent.receiverExpression) == null
        ) return emptyList()
        val targets = resolver.callTargets(call)
        if (targets.isEmpty()) return emptyList()
        // A target whose parameter NAMES are unavailable (count mismatch / synthetic / blank) makes the check
        // unsound: the actually-resolved overload's names may be unknowable. Skip the whole call then.
        if (targets.any { t ->
                t.paramTypes.isNotEmpty() &&
                    (t.paramNames.size != t.paramTypes.size || t.paramNames.any { it.isEmpty() || isSyntheticParamName(it) })
            }
        ) return emptyList()
        val known = targets.flatMapTo(HashSet()) { it.paramNames.filter { n -> n.isNotEmpty() } }
        return named.mapNotNull { argName ->
            val id = argName.asName?.identifier ?: return@mapNotNull null
            if (id in known) return@mapNotNull null
            val ref = (argName as? KtValueArgumentName)?.referenceExpression ?: return@mapNotNull null
            val r = ref.textRange
            Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Cannot find a parameter with this name: $id", KotlinDiagnosticCodes.NAMED_ARGUMENT)
        }
    }

    /** ASM surfaces stripped Java parameters as `p0`, `p1`, … — useless as named arguments and not validatable. */
    private fun isSyntheticParamName(n: String): Boolean = n.length >= 2 && n[0] == 'p' && n.drop(1).all { it.isDigit() }

    /**
     * A call to a `@Composable` function from a non-`@Composable` context — Compose's calling-convention error
     * (the compiler's `COMPOSABLE_INVOCATION`: "@Composable invocations can only happen from the context of a
     * @Composable function"). Conservative: it fires only when the callee is confidently `@Composable` AND the
     * surrounding context is confidently non-composable. An unknown lambda context (the parse-only model can't
     * resolve the enclosing call/expected type) backs off, so this never false-positives. Inline lambdas
     * (`repeat`/`with`/`forEach`) are transparent — a composable call inside one within a composable scope is fine.
     */
    private fun composableInvocation(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        // The error fires only when a @Composable callee is invoked from a NON_COMPOSABLE context, so check the
        // context FIRST: it's a cheap ancestor walk (the @Composable test is syntactic), whereas resolving the
        // callee is overload resolution against scope + classpath. In a real Compose UI file most calls sit in a
        // @Composable function/lambda, so this skips the expensive callee resolution for the vast majority.
        if (resolver.composableContextAt(call.textRange.startOffset) != ComposableContext.NON_COMPOSABLE) return null
        val callee = resolver.calleeFunctionOf(call) ?: return null
        if (!callee.isComposable) return null // a non-composable call from a plain context → nothing to flag
        val anchor = call.calleeExpression ?: call
        val r = anchor.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "@Composable invocation can only happen from the context of a @Composable function",
            KotlinDiagnosticCodes.COMPOSABLE_INVOCATION,
        )
    }

    /**
     * A call to a `suspend` function from a non-suspend context, Kotlin's coroutine calling-convention error
     * ("Suspend function … should be called only from a coroutine or another suspend function"). Mirrors
     * [composableInvocation]: it fires only when the surrounding context is CONFIDENTLY non-suspend AND the
     * callee is confidently `suspend`. A [SuspendContext.UNKNOWN] context backs off, so this never false-positives
     * over the parse-only model, crucially on coroutine builders (`launch`/`withContext` are binary, and the
     * metadata path can't prove their lambda is a suspend slot, so [KotlinResolver.suspendContextAt] returns
     * UNKNOWN there rather than NON_SUSPEND). Inline lambdas (`repeat`/`coroutineScope`/`with`) are transparent.
     */
    private fun suspendInvocation(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        // Context first (a cheap ancestor walk), like composableInvocation, before the costlier callee resolution.
        if (resolver.suspendContextAt(call.textRange.startOffset) != SuspendContext.NON_SUSPEND) return null
        val callee = resolver.calleeFunctionOf(call) ?: return null
        if (!callee.isSuspend) return null
        val anchor = call.calleeExpression ?: call
        val r = anchor.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Suspend function '${callee.name}' can only be called from a coroutine or another suspend function",
            KotlinDiagnosticCodes.SUSPEND_CONTEXT,
        )
    }

    /**
     * A call to a `@Deprecated` function / method / constructor — Kotlin's DEPRECATION warning. Fires only when
     * the callee resolves confidently to a symbol carrying `isDeprecated` (source `@Deprecated`, or Java
     * `ACC_DEPRECATED`); an unresolved/unknown callee backs off, so this never false-positives. Warning-level:
     * the deprecation LEVEL isn't tracked and the default (and vast majority) is WARNING. Property/type-reference
     * deprecation is a separate concern (a bare `@Deprecated` val read / type usage is not resolved here).
     */
    private fun deprecatedCall(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val callee = resolver.calleeFunctionOf(call) ?: return null
        if (!callee.isDeprecated) return null
        val anchor = call.calleeExpression ?: return null
        val r = anchor.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.WARNING,
            "'${callee.name}' is deprecated", KotlinDiagnosticCodes.DEPRECATION,
        )
    }

    /**
     * A generic call whose type arguments can't be inferred — Kotlin's "Not enough information to infer type
     * variable T" (`val text by remember { mutableStateOf() }`: the inner `mutableStateOf()` has no argument to
     * pin `T`, no explicit type argument, and no expected type, so its result type is undetermined). The check
     * lives in [KotlinResolver.uninferableTypeParameters] (shared with the interpreter) and is conservative —
     * it reports a parameter only when nothing at the site could have inferred it.
     */
    private fun cannotInferType(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
        val uninferable = resolver.uninferableTypeParameters(call)
        if (uninferable.isEmpty()) return null
        val anchor = call.calleeExpression ?: call
        val r = anchor.textRange
        val vars = uninferable.joinToString(", ")
        val msg = if (uninferable.size == 1) "Not enough information to infer type variable $vars"
        else "Not enough information to infer type variables $vars"
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.CANNOT_INFER_TYPE)
    }

    /**
     * A `by`-delegated property whose delegate-accessor operator isn't in scope — Kotlin desugars `val x by d`
     * to `d.getValue(thisRef, prop)` (and a `var` write to `d.setValue(…)`), so the operator must be callable.
     * For Compose's `MutableState` it is an EXTENSION in `androidx.compose.runtime`: `val text by remember {
     * mutableStateOf(0) }` does not compile without `import androidx.compose.runtime.getValue`. The detection
     * lives in [KotlinResolver.missingDelegateOperators] (shared with the interpreter) and is conservative —
     * an unmodeled operator never flags, so it only fires when the operator exists on the classpath but isn't
     * brought into scope.
     */
    private fun delegateOperatorNotInScope(prop: KtProperty, resolver: KotlinResolver): Diagnostic? {
        val missing = resolver.missingDelegateOperators(prop)
        if (missing.isEmpty()) return null
        val anchor = prop.delegateExpression ?: return null
        val r = anchor.textRange
        val ops = missing.joinToString("' and '")
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "This delegate has no '$ops' operator in scope; import the delegate's '$ops' extension to use it",
            KotlinDiagnosticCodes.DELEGATE_OPERATOR,
        )
    }
}
