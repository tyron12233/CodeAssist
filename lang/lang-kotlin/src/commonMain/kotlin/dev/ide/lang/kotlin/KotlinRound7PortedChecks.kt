package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotation
import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtDynamicType
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunctionType
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParameterList
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtSuperTypeList
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtTypeArgumentList
import dev.ide.kotlin.syntax.psi.KtTypeProjection
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enclosingClassMembersContain
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.localFunctionsInScope
import dev.ide.lang.kotlin.resolve.localTypesInScope
import dev.ide.lang.kotlin.resolve.localsAt
import dev.ide.lang.resolve.SymbolKind

/**
 * A seventh round of rules ported from the K2 compiler's checkers (`compiler/fir/checkers`, Apache 2.0) and from
 * the diagnostics its resolution attaches. Each function names the K2 code and checker it follows; where K2
 * decides on resolution this editor does not have, the rule keeps to the cases it can decide and reports
 * nothing on the rest. Registered from [KotlinSemanticChecks.checkers], after the earlier rounds.
 */
internal fun KotlinSemanticChecks.round7PortedCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtClassOrObject::class) { psi ->
        KotlinPerf.span("sem.r7.class") {
            report(mixingSuspendSupertypes(psi as KtClassOrObject, resolver.takeIf { resolveReady }))
            if (psi is KtClass) {
                reportAll(dataClassMemberConflicts(psi))
                reportAll(valueClassShape(psi))
            }
        }
    },
    KotlinChecker(KtParameter::class) { psi ->
        KotlinPerf.span("sem.r7.param") { reportAll(functionTypeParameterShape(psi as KtParameter)) }
    },
    KotlinChecker(KtDynamicType::class) { psi ->
        report(error(psi, "Unsupported [dynamic type]", KotlinDiagnosticCodes.UNSUPPORTED))
    },
    KotlinChecker(KtTypeAlias::class) { psi ->
        if (psi.parent is KtBlockExpression) report(error(psi, "Unsupported [local type aliases]", KotlinDiagnosticCodes.UNSUPPORTED))
    },
    KotlinChecker(KtDelegatedSuperTypeEntry::class) { psi ->
        report(delegationWithoutPrimaryConstructor(psi as KtDelegatedSuperTypeEntry))
    },
    KotlinChecker(KtTypeProjection::class) { psi ->
        reportAll(annotatedUnderscoreTypeArgument(psi as KtTypeProjection))
    },
    KotlinChecker(KtCallableReferenceExpression::class) { psi ->
        KotlinPerf.span("sem.r7.callableRef") {
            report(referenceToLocalVariable(psi as KtCallableReferenceExpression, resolver))
            if (resolveReady) report(doubleColonLhsTypeArgumentCount(psi, resolver))
        }
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtClassLiteralExpression::class) { psi ->
        if (resolveReady) report(doubleColonLhsTypeArgumentCount(psi as dev.ide.kotlin.syntax.psi.KtDoubleColonExpression, resolver))
    },
    KotlinChecker(KtAnnotationEntry::class) { psi ->
        KotlinPerf.span("sem.r7.annotation") {
            if (resolveReady) report(annotationMissingArgument(psi as KtAnnotationEntry, resolver))
        }
    },
    KotlinChecker(KtNameReferenceExpression::class) { psi ->
        KotlinPerf.span("sem.r7.recursion") {
            if (resolveReady) report(recursiveImplicitType(psi as KtNameReferenceExpression, resolver))
        }
    },
    // The definite-assignment analysis of a function body, over the other block bodies a local can live in: an
    // accessor's, an `init` block's, a secondary constructor's.
    KotlinChecker(KtPropertyAccessor::class) { psi ->
        (psi as KtPropertyAccessor).bodyBlockExpression?.let { reportAll(uninitializedVariablesIn(it, resolver)) }
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtClassInitializer::class) { psi ->
        (psi as dev.ide.kotlin.syntax.psi.KtClassInitializer).body?.let { reportAll(uninitializedVariablesIn(it, resolver)) }
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtSecondaryConstructor::class) { psi ->
        (psi as dev.ide.kotlin.syntax.psi.KtSecondaryConstructor).bodyBlockExpression?.let { reportAll(uninitializedVariablesIn(it, resolver)) }
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtEnumEntry::class) { psi -> report(enumEntryMissingArgument(psi as dev.ide.kotlin.syntax.psi.KtEnumEntry)) },
    KotlinChecker(KtNamedFunction::class) { psi -> report(enumFinalOverride(psi as KtDeclaration)) },
    KotlinChecker(KtProperty::class) { psi -> report(enumFinalOverride(psi as KtDeclaration)) },
    KotlinChecker(KtBinaryExpression::class) { psi ->
        if (resolveReady) report(compoundAssignmentOnValue(psi as KtBinaryExpression, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtUnaryExpression::class) { psi ->
        if (resolveReady) report(incrementOfObject(psi as dev.ide.kotlin.syntax.psi.KtUnaryExpression, resolver))
    },
)

/** The K2 names each code of this file stands for, merged into [KotlinSuppression.K2_NAMES]. */
internal val ROUND7_K2_NAMES: Map<String, Set<String>> = mapOf(
    KotlinDiagnosticCodes.MIXING_SUSPEND_AND_NON_SUSPEND_SUPERTYPES to setOf("MIXING_SUSPEND_AND_NON_SUSPEND_SUPERTYPES"),
    KotlinDiagnosticCodes.UNSUPPORTED to setOf("UNSUPPORTED"),
    KotlinDiagnosticCodes.NO_VALUE_FOR_PARAMETER to setOf("NO_VALUE_FOR_PARAMETER"),
    KotlinDiagnosticCodes.RECURSIVE_IMPLICIT_TYPE to setOf("TYPECHECKER_HAS_RUN_INTO_RECURSIVE_PROBLEM"),
    KotlinDiagnosticCodes.VARIABLE_EXPECTED to setOf("VARIABLE_EXPECTED"),
    KotlinDiagnosticCodes.VALUE_CLASS_NOT_TOP_LEVEL to setOf("VALUE_CLASS_NOT_TOP_LEVEL"),
    KotlinDiagnosticCodes.VALUE_CLASS_NOT_FINAL to setOf("VALUE_CLASS_NOT_FINAL"),
    KotlinDiagnosticCodes.VALUE_CLASS_CANNOT_EXTEND_CLASSES to setOf("VALUE_CLASS_CANNOT_EXTEND_CLASSES"),
    KotlinDiagnosticCodes.VALUE_CLASS_CANNOT_IMPLEMENT_INTERFACE_BY_DELEGATION to setOf("VALUE_CLASS_CANNOT_IMPLEMENT_INTERFACE_BY_DELEGATION"),
    KotlinDiagnosticCodes.INNER_CLASS_INSIDE_VALUE_CLASS to setOf("INNER_CLASS_INSIDE_VALUE_CLASS"),
    KotlinDiagnosticCodes.DELEGATED_PROPERTY_INSIDE_VALUE_CLASS to setOf("DELEGATED_PROPERTY_INSIDE_VALUE_CLASS"),
    KotlinDiagnosticCodes.PROPERTY_WITH_BACKING_FIELD_INSIDE_VALUE_CLASS to setOf("PROPERTY_WITH_BACKING_FIELD_INSIDE_VALUE_CLASS"),
    KotlinDiagnosticCodes.ABSENCE_OF_PRIMARY_CONSTRUCTOR_FOR_VALUE_CLASS to setOf("ABSENCE_OF_PRIMARY_CONSTRUCTOR_FOR_VALUE_CLASS"),
    KotlinDiagnosticCodes.INLINE_CLASS_CONSTRUCTOR_WRONG_PARAMETERS_SIZE to setOf("INLINE_CLASS_CONSTRUCTOR_WRONG_PARAMETERS_SIZE"),
    KotlinDiagnosticCodes.VALUE_CLASS_CONSTRUCTOR_NOT_FINAL_READ_ONLY_PARAMETER to setOf("VALUE_CLASS_CONSTRUCTOR_NOT_FINAL_READ_ONLY_PARAMETER"),
    KotlinDiagnosticCodes.VALUE_CLASS_HAS_INAPPLICABLE_PARAMETER_TYPE to setOf("VALUE_CLASS_HAS_INAPPLICABLE_PARAMETER_TYPE"),
)

private fun error(at: KtElement, message: String, code: String): Diagnostic {
    val r = at.textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, code)
}

private fun KtElement.parents(): Sequence<KtElement> = generateSequence(parent as? KtElement) { it.parent as? KtElement }

// --- FirMixedFunctionalTypesInSupertypesChecker ---

private enum class FunctionKind { FUNCTION, SUSPEND }

private val FUNCTION_N = Regex("""(?:kotlin\.)?Function\d+""")
private val K_FUNCTION_N = Regex("""(?:kotlin\.reflect\.)?KFunction\d+""")
private val SUSPEND_FUNCTION_N = Regex("""(?:kotlin\.coroutines\.)?SuspendFunction\d+""")
private val K_SUSPEND_FUNCTION_N = Regex("""(?:kotlin\.reflect\.)?KSuspendFunction\d+""")

/**
 * MIXING_SUSPEND_AND_NON_SUSPEND_SUPERTYPES: a class whose supertypes, followed up through its supertypes' own
 * supertype lists, include both a plain function type (`() -> Unit`, `FunctionN`, `KFunctionN`) and a suspend one
 * (`suspend () -> Unit`, `SuspendFunctionN`, `KSuspendFunctionN`). Reported on the supertype list. A supertype
 * named by a class of this file is followed into its own list; one of a library or another file counts as
 * neither kind, so a mix seen only through it is not reported.
 */
internal fun KotlinSemanticChecks.mixingSuspendSupertypes(cls: KtClassOrObject, resolver: KotlinResolver?): Diagnostic? {
    val list = cls.superTypeList ?: return null
    if (cls.hasModifier(KtTokens.EXPECT_KEYWORD)) return null
    val entries = list.entries
    // One supertype cannot mix kinds that it does not already mix itself (reported on it).
    if (entries.size < 2) return null
    val file = cls.containingFile as? KtFile ?: return null
    val kinds = HashSet<FunctionKind>()
    val visited = HashSet<KtClassOrObject>().apply { add(cls) }
    for (e in entries) collectFunctionKinds(e.typeReference, file, resolver, kinds, visited, 0)
    if (kinds.size < 2) return null
    return error(list, "Mixing suspend and non-suspend supertypes is not allowed", KotlinDiagnosticCodes.MIXING_SUSPEND_AND_NON_SUSPEND_SUPERTYPES)
}

private fun KotlinSemanticChecks.collectFunctionKinds(
    ref: KtTypeReference?, file: KtFile, resolver: KotlinResolver?, out: MutableSet<FunctionKind>,
    visited: MutableSet<KtClassOrObject>, depth: Int,
) {
    if (ref == null || depth > 6) return
    when (val t = ref.typeElement) {
        is KtFunctionType -> out += if (ref.hasModifier(KtTokens.SUSPEND_KEYWORD)) FunctionKind.SUSPEND else FunctionKind.FUNCTION
        is KtUserType -> {
            val text = t.text.substringBefore('<').filterNot { it.isWhitespace() }
            val short = text.substringAfterLast('.')
            when {
                FUNCTION_N.matches(text) -> if (builtinNameIsFree(short, file)) out += FunctionKind.FUNCTION
                K_FUNCTION_N.matches(text) -> if (importedFrom(text, "kotlin.reflect", file)) out += FunctionKind.FUNCTION
                SUSPEND_FUNCTION_N.matches(text) -> if (importedFrom(text, "kotlin.coroutines", file)) out += FunctionKind.SUSPEND
                K_SUSPEND_FUNCTION_N.matches(text) -> if (importedFrom(text, "kotlin.reflect", file)) out += FunctionKind.SUSPEND
                else -> {
                    val r = resolver ?: return
                    val fqn = classifierFqnOf(ref, r) ?: return
                    val src = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: return
                    // Only a class of this file: another file's supertypes resolve against that file's imports.
                    if (src.containingFile !== file || !visited.add(src)) return
                    for (e in src.superTypeListEntries) collectFunctionKinds(e.typeReference, file, resolver, out, visited, depth + 1)
                }
            }
        }
        else -> {}
    }
}

/** A default-imported `kotlin` name ([name]) that no classifier or import of [file] takes over. */
private fun builtinNameIsFree(name: String, file: KtFile): Boolean {
    if (file.declarations.any { it is KtClassOrObject && it.name == name || it is KtTypeAlias && it.name == name }) return false
    return file.importDirectives.none { imp ->
        imp.aliasName == name || (!imp.isAllUnder && imp.importedFqName?.asString()?.substringAfterLast('.') == name)
    }
}

/** [text] (a short or qualified name) names the class of that name in package [pkg]: written qualified, or
 *  imported by name or by a star import of the package, and not taken over by a classifier of [file]. */
private fun importedFrom(text: String, pkg: String, file: KtFile): Boolean {
    if (text.startsWith("$pkg.")) return true
    if (!builtinNameIsFree(text, file)) {
        return file.importDirectives.any { !it.isAllUnder && it.aliasName == null && it.importedFqName?.asString() == "$pkg.$text" }
    }
    return file.importDirectives.any { it.aliasName == null && it.importedFqName?.asString() == if (it.isAllUnder) pkg else "$pkg.$text" }
}

// --- FirFunctionTypeParametersSyntaxChecker, FirSupertypesChecker (named function-type parameters),
//     FirTypeAliasChecker (local), FirDelegationWithoutPrimaryConstructor, underscore type arguments, references to
//     local variables: the shapes K2 reports UNSUPPORTED for ---

/**
 * UNSUPPORTED on a parameter of a function TYPE (`(x: Int = 0) -> Unit`, `(vararg x: Int) -> Unit`,
 * `(@Ann x: Int) -> Unit`): a default value, any modifier, and an annotation on a named parameter (an unnamed
 * one's annotation belongs to its type) mean nothing there. A function type written directly as a supertype may
 * not name its parameters either: the override that implements `invoke` names them.
 */
internal fun functionTypeParameterShape(p: KtParameter): List<Diagnostic> {
    val list = p.parent as? KtParameterList ?: return emptyList()
    val fnType = list.parent as? KtFunctionType ?: return emptyList()
    val out = ArrayList<Diagnostic>()
    p.defaultValue?.let { out += error(it, "Unsupported [default value of parameter in function type]", KotlinDiagnosticCodes.UNSUPPORTED) }
    val mods = p.modifierList
    if (mods != null) {
        var c = mods.firstChild
        while (c != null) {
            when {
                c is KtAnnotationEntry -> if (p.nameIdentifier != null) out += error(c, "Unsupported [annotation on parameter in function type]", KotlinDiagnosticCodes.UNSUPPORTED)
                c is KtAnnotation -> if (p.nameIdentifier != null) c.annotationEntries.forEach { out += error(it, "Unsupported [annotation on parameter in function type]", KotlinDiagnosticCodes.UNSUPPORTED) }
                c.text.isNotBlank() && c.text.all { it.isLetter() } ->
                    out += error(c, "Unsupported [modifier on parameter in function type]", KotlinDiagnosticCodes.UNSUPPORTED)
            }
            c = c.nextSibling
        }
    }
    val name = p.nameIdentifier
    if (name != null) {
        val typeRef = fnType.parent as? KtTypeReference
        if (typeRef != null && typeRef.parent is dev.ide.kotlin.syntax.psi.KtSuperTypeListEntry) {
            out += error(name, "Unsupported [named parameter in function type in supertype position]", KotlinDiagnosticCodes.UNSUPPORTED)
        }
    }
    return out
}

/** UNSUPPORTED: `class B : A by impl { constructor() }`. Delegation stores the delegate in a field the primary
 *  constructor initializes, so a class with only secondary constructors cannot delegate. */
internal fun delegationWithoutPrimaryConstructor(entry: KtDelegatedSuperTypeEntry): Diagnostic? {
    val cls = (entry.parent as? KtSuperTypeList)?.parent as? KtClass ?: return null
    if (cls.isInterface() || cls.primaryConstructor != null || cls.secondaryConstructors.isEmpty()) return null
    if (cls.hasModifier(KtTokens.EXPECT_KEYWORD) || cls.hasModifier(KtTokens.EXTERNAL_KEYWORD)) return null
    return error(entry, "Unsupported [delegation without primary constructor]", KotlinDiagnosticCodes.UNSUPPORTED)
}

/** UNSUPPORTED: an annotation on a `_` type argument of a call (`foo<@Anno _>()`): the argument is inferred, so
 *  there is no written type for the annotation to sit on. */
internal fun annotatedUnderscoreTypeArgument(proj: KtTypeProjection): List<Diagnostic> {
    val call = (proj.parent as? KtTypeArgumentList)?.parent as? KtCallExpression ?: return emptyList()
    if (call.typeArgumentList !== proj.parent) return emptyList()
    val ref = proj.typeReference ?: return emptyList()
    val user = ref.typeElement as? KtUserType ?: return emptyList()
    if (user.qualifier != null || user.referencedName != "_" || user.typeArgumentList != null) return emptyList()
    val mods = ref.modifierList ?: return emptyList()
    val out = ArrayList<Diagnostic>()
    var c = mods.firstChild
    while (c != null) {
        when (c) {
            is KtAnnotationEntry -> out += error(c, "Unsupported [annotation on underscored type argument]", KotlinDiagnosticCodes.UNSUPPORTED)
            is KtAnnotation -> c.annotationEntries.forEach { out += error(it, "Unsupported [annotation on underscored type argument]", KotlinDiagnosticCodes.UNSUPPORTED) }
            else -> {}
        }
        c = c.nextSibling
    }
    return out
}

/** UNSUPPORTED: `::x` naming a local variable or a value parameter (or `::field` in an accessor). Callable
 *  references reach properties and functions only. A local function of the name makes the reference mean it. */
internal fun referenceToLocalVariable(ref: KtCallableReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    if (ref.receiverExpression != null) return null
    val callee = ref.callableReference ?: return null
    val name = callee.getReferencedName()
    if (name.isEmpty()) return null
    val locals = resolver.localsAt(callee.textRange.startOffset, name)
    if (locals.isEmpty() || locals.any { it.kind == SymbolKind.METHOD }) return null
    if (resolver.localFunctionsInScope(callee.textRange.startOffset, name).isNotEmpty()) return null
    return error(callee, "Unsupported [references to variables aren't supported yet]", KotlinDiagnosticCodes.UNSUPPORTED)
}

// --- FirAnnotationArgumentChecker / call resolution of an annotation's constructor ---

/**
 * NO_VALUE_FOR_PARAMETER on an annotation use (`@Ann` or `@Ann(1)` where `annotation class Ann(val x: Int, val
 * y: Int)`): a parameter of the annotation's constructor with no default value that no argument fills. Arguments
 * map as a call's do: positional ones in order (a `vararg` takes every positional argument from it on), named ones
 * by name. Judged for an annotation class of the project's sources; a spread or a positional argument after a
 * named one leaves the use alone.
 */
internal fun KotlinSemanticChecks.annotationMissingArgument(entry: KtAnnotationEntry, resolver: KotlinResolver): Diagnostic? {
    val typeRef = entry.typeReference ?: return null
    val fqn = classifierFqnOf(typeRef, resolver) ?: return null
    val cls = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClass ?: return null
    if (!cls.isAnnotation() || cls.hasModifier(KtTokens.EXPECT_KEYWORD)) return null
    val params = cls.primaryConstructor?.valueParameters ?: return null
    if (params.isEmpty() || params.any { it.name == null }) return null
    val supplied = HashSet<String>()
    var pos = 0
    var named = false
    for (a in entry.valueArguments) {
        if (a.isSpread) return null
        val argName = a.getArgumentName()?.asName?.asString()
        if (argName != null) { named = true; supplied += argName; continue }
        if (named || pos >= params.size) return null
        val p = params[pos]
        supplied += p.name!!
        if (!p.isVarArg) pos++
    }
    val missing = params.firstOrNull { it.name !in supplied && !it.hasDefaultValue() && !it.isVarArg } ?: return null
    val at: KtElement = (typeRef.typeElement as? KtUserType)?.referenceExpression ?: typeRef
    return error(at, "No value passed for parameter '${missing.name}'", KotlinDiagnosticCodes.NO_VALUE_FOR_PARAMETER)
}

// --- FirValueClassDeclarationChecker (value classes without the full-value-classes feature) ---

/**
 * The shape a `value class` (or a deprecated `inline class`) must have: top level or nested, never inner or local
 * (VALUE_CLASS_NOT_TOP_LEVEL); final (VALUE_CLASS_NOT_FINAL); interfaces only as supertypes
 * (VALUE_CLASS_CANNOT_EXTEND_CLASSES), delegating only to its constructor property
 * (VALUE_CLASS_CANNOT_IMPLEMENT_INTERFACE_BY_DELEGATION); no inner classes (INNER_CLASS_INSIDE_VALUE_CLASS), no
 * delegated properties (DELEGATED_PROPERTY_INSIDE_VALUE_CLASS) and no properties with a backing field
 * (PROPERTY_WITH_BACKING_FIELD_INSIDE_VALUE_CLASS); a primary constructor
 * (ABSENCE_OF_PRIMARY_CONSTRUCTOR_FOR_VALUE_CLASS) with exactly one parameter
 * (INLINE_CLASS_CONSTRUCTOR_WRONG_PARAMETERS_SIZE) that is a final `val` (VALUE_CLASS_CONSTRUCTOR_NOT_FINAL_READ_ONLY_PARAMETER)
 * of a type that can be stored (VALUE_CLASS_HAS_INAPPLICABLE_PARAMETER_TYPE, for `Unit` and `Nothing`).
 */
internal fun valueClassShape(cls: KtClass): List<Diagnostic> {
    val valueMod = cls.modifierList?.getModifier(KtTokens.VALUE_KEYWORD)
    val inlineMod = cls.modifierList?.getModifier(KtTokens.INLINE_KEYWORD)
    val marker = valueMod ?: inlineMod ?: return emptyList()
    if (cls.isInterface() || cls.isEnum() || cls.isAnnotation() || cls is dev.ide.kotlin.syntax.psi.KtEnumEntry) return emptyList()
    if (cls.hasModifier(KtTokens.EXPECT_KEYWORD) || cls.hasModifier(KtTokens.EXTERNAL_KEYWORD)) return emptyList()
    val out = ArrayList<Diagnostic>()
    fun add(at: KtElement, message: String, code: String) { out += error(at, message, code) }
    if (cls.isInner() || cls.parent is KtBlockExpression) {
        add(marker, "Value classes cannot be local or inner", KotlinDiagnosticCodes.VALUE_CLASS_NOT_TOP_LEVEL)
    }
    val nonFinal = listOf(KtTokens.OPEN_KEYWORD, KtTokens.ABSTRACT_KEYWORD, KtTokens.SEALED_KEYWORD).firstNotNullOfOrNull { cls.modifierList?.getModifier(it) }
    nonFinal?.let { add(it, "Value classes can only be final", KotlinDiagnosticCodes.VALUE_CLASS_NOT_FINAL) }
    val ctorParams = cls.primaryConstructorParameters
    for (entry in cls.superTypeListEntries) {
        when (entry) {
            is dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry -> {
                val ref = entry.typeReference ?: continue
                val name = ref.text.substringBefore('<').filterNot { it.isWhitespace() }
                if (name == "Any" || name == "kotlin.Any") continue
                add(ref, "Value classes can only implement interfaces", KotlinDiagnosticCodes.VALUE_CLASS_CANNOT_EXTEND_CLASSES)
            }
            is KtDelegatedSuperTypeEntry -> {
                val delegate = entry.delegateExpression?.let { dev.ide.lang.kotlin.parse.unwrapParen(it) }
                val toParam = delegate is KtNameReferenceExpression && nonFinal == null &&
                    ctorParams.any { it.hasValOrVar() && it.name == delegate.getReferencedName() }
                if (!toParam) add(entry.typeReference ?: entry, "Value class cannot implement an interface by delegation if expression is not a parameter", KotlinDiagnosticCodes.VALUE_CLASS_CANNOT_IMPLEMENT_INTERFACE_BY_DELEGATION)
            }
            else -> {}
        }
    }
    for (d in cls.declarations) {
        when (d) {
            is KtClass -> if (d.isInner()) add(d.nameIdentifier ?: d, "Value class cannot have inner classes", KotlinDiagnosticCodes.INNER_CLASS_INSIDE_VALUE_CLASS)
            is KtProperty -> {
                if (d.hasModifier(KtTokens.CONST_KEYWORD)) continue
                val delegate = d.delegate
                when {
                    delegate != null -> add(delegate, "Value class cannot have delegated properties", KotlinDiagnosticCodes.DELEGATED_PROPERTY_INSIDE_VALUE_CLASS)
                    d.initializer != null || d.hasModifier(KtTokens.LATEINIT_KEYWORD) ->
                        add(d.nameIdentifier ?: d, "Value class cannot have properties with backing fields", KotlinDiagnosticCodes.PROPERTY_WITH_BACKING_FIELD_INSIDE_VALUE_CLASS)
                }
            }
            else -> {}
        }
    }
    val ctor = cls.primaryConstructor
    if (ctor == null) {
        add(marker, "Primary constructor is required for value class", KotlinDiagnosticCodes.ABSENCE_OF_PRIMARY_CONSTRUCTOR_FOR_VALUE_CLASS)
        return out
    }
    if (ctorParams.size != 1) {
        // Several parameters of a `value` class without `@JvmInline` is the unsupported full-value-classes feature.
        val jvmInline = cls.annotationEntries.any { it.shortName?.asString() == "JvmInline" }
        if (ctorParams.size > 1 && valueMod != null && inlineMod == null && !jvmInline) return out
        add(ctor.valueParameterList ?: ctor, "Value class must have exactly one primary constructor parameter", KotlinDiagnosticCodes.INLINE_CLASS_CONSTRUCTOR_WRONG_PARAMETERS_SIZE)
        return out
    }
    val p = ctorParams.single()
    if (nonFinal == null && (!p.hasValOrVar() || p.isMutable || p.isVarArg || p.hasModifier(KtTokens.OPEN_KEYWORD))) {
        add(p, "Value class primary constructor must only have final read-only (val) property parameter", KotlinDiagnosticCodes.VALUE_CLASS_CONSTRUCTOR_NOT_FINAL_READ_ONLY_PARAMETER)
    } else {
        val type = p.typeReference?.text?.filterNot { it.isWhitespace() }
        if (type == "Unit" || type == "Nothing" || type == "kotlin.Unit" || type == "kotlin.Nothing") {
            add(p.typeReference!!, "Value class cannot have value parameter of type '$type'", KotlinDiagnosticCodes.VALUE_CLASS_HAS_INAPPLICABLE_PARAMETER_TYPE)
        }
    }
    return out
}

// --- WRONG_NUMBER_OF_TYPE_ARGUMENTS on the left of `::` (the type resolver's argument count check) ---

/**
 * WRONG_NUMBER_OF_TYPE_ARGUMENTS: `Y<Int, Int>::foo` or `L<Int>::class`, where the class named on the left of `::`
 * declares another number of type parameters. An inner class takes only its own there, never its outer class's.
 * Judged for a project class (local ones included) named by a simple name that no value or type parameter of the
 * scope takes.
 */
internal fun KotlinSemanticChecks.doubleColonLhsTypeArgumentCount(e: dev.ide.kotlin.syntax.psi.KtDoubleColonExpression, resolver: KotlinResolver): Diagnostic? {
    val lhs = e.receiverExpression as? KtCallExpression ?: return null
    if (lhs.valueArgumentList != null || lhs.lambdaArguments.isNotEmpty()) return null
    val args = lhs.typeArgumentList ?: return null
    val callee = lhs.calleeExpression as? KtNameReferenceExpression ?: return null
    val name = callee.getReferencedName()
    if (name.isEmpty()) return null
    val offset = callee.textRange.startOffset
    if (resolver.isTypeParameterInScope(name, offset) || service.isProjectTypeAlias(name)) return null
    if (resolver.localsAt(offset, name).isNotEmpty() || resolver.enclosingClassMembersContain(offset, name)) return null
    val file = e.containingFile as? KtFile ?: return null
    if (topLevelNamed(file, name).any { it !is KtClassOrObject }) return null
    val fqn = resolver.localTypesInScope(offset)[name]
        ?: service.resolveTypeName(name, resolver.fileContext, dev.ide.lang.kotlin.resolve.enclosingClassFqnOf(e))?.takeIf { service.isKnownType(it) }
        ?: return null
    val cls = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: return null
    if (cls.name != name) return null
    val expected = cls.typeParameters.size
    val actual = args.arguments.size
    if (actual == expected) return null
    val msg = if (expected == 0) "Type '$name' does not take type arguments" else "$expected type argument${if (expected == 1) "" else "s"} expected for '$name', but $actual found"
    return error(args, msg, KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT)
}

// --- FirOverrideChecker.checkFinal, for the members of an enum ---

/** `Enum`'s final members, by name, with their value-parameter counts (-1 for a property). */
private val ENUM_FINAL_MEMBERS = mapOf("name" to -1, "ordinal" to -1, "compareTo" to 1, "equals" to 1, "hashCode" to 0, "clone" to 0)

/**
 * OVERRIDING_FINAL_MEMBER inside an enum: an `override` of one of `Enum`'s final members (`name`, `ordinal`,
 * `compareTo`, `equals`, `hashCode`, `clone`) in the enum class or an entry's body, or an entry's `override` of a
 * member its enum class declares without `open` or `abstract`. Reported on the `override` keyword.
 */
internal fun enumFinalOverride(d: KtDeclaration): Diagnostic? {
    val kw = d.modifierList?.getModifier(KtTokens.OVERRIDE_KEYWORD) ?: return null
    val owner = (d.parent as? KtClassBody)?.parent as? KtClassOrObject ?: return null
    val enumClass: KtClass = when {
        owner is dev.ide.kotlin.syntax.psi.KtEnumEntry -> ((owner.parent as? KtClassBody)?.parent as? KtClass)?.takeIf { it.isEnum() } ?: return null
        owner is KtClass && owner.isEnum() -> owner
        else -> return null
    }
    val name = (d as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name ?: return null
    val arity = when (d) {
        is KtNamedFunction -> if (d.receiverTypeReference != null || d.typeParameters.isNotEmpty()) return null else d.valueParameters.size
        is KtProperty -> if (d.receiverTypeReference != null) return null else -1
        else -> return null
    }
    val message = "'$name' in '${enumClass.name}' is final and cannot be overridden"
    if (ENUM_FINAL_MEMBERS[name] == arity) return error(kw, message, KotlinDiagnosticCodes.OVERRIDING_FINAL)
    if (owner === enumClass) return null
    // An entry overriding a member of its enum class: final unless the enum declares it open, abstract or override.
    val declared = enumClass.declarations.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }
    val member = declared.singleOrNull() ?: return null
    val memberArity = when (member) {
        is KtNamedFunction -> if (member.receiverTypeReference != null || member.typeParameters.isNotEmpty()) return null else member.valueParameters.size
        is KtProperty -> if (member.receiverTypeReference != null) return null else -1
        else -> return null
    }
    if (memberArity != arity) return null
    if (member.hasModifier(KtTokens.OPEN_KEYWORD) || member.hasModifier(KtTokens.ABSTRACT_KEYWORD) || member.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
    return error(kw, message, KotlinDiagnosticCodes.OVERRIDING_FINAL)
}

// --- VARIABLE_EXPECTED from the resolution of `a op= b` and `a++` (FirExpressionsResolveTransformer) ---

private val COMPOUND_OPERATORS = mapOf("+=" to "plus", "-=" to "minus", "*=" to "times", "/=" to "div", "%=" to "rem")

/**
 * VARIABLE_EXPECTED: `B() += 1` where `B` has a member `plus` and no `plusAssign`. With no `plusAssign` the
 * operator means `B() = B() + 1`, which needs a variable on the left. Judged for a constructor call of a project
 * class with no supertypes (so no inherited `plusAssign`) when no extension `plusAssign` of this file could apply.
 */
internal fun KotlinSemanticChecks.compoundAssignmentOnValue(e: KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    val op = COMPOUND_OPERATORS[e.operationReference.text] ?: return null
    val lhs = e.left?.let { dev.ide.lang.kotlin.parse.unwrapParen(it) } as? KtCallExpression ?: return null
    if (lhs.parent is KtQualifiedExpression) return null
    val cls = implicitClassOf(lhs, resolver) as? KtClass ?: return null
    if (cls.superTypeListEntries.isNotEmpty() || cls.typeParameters.isNotEmpty()) return null
    val assignOp = op + "Assign"
    if (cls.declarations.any { it is KtNamedFunction && it.name == assignOp }) return null
    if (cls.declarations.none { it is KtNamedFunction && it.name == op && it.hasModifier(KtTokens.OPERATOR_KEYWORD) }) return null
    val file = e.containingFile as? KtFile ?: return null
    if (topLevelNamed(file, assignOp).isNotEmpty() || file.importDirectives.any { it.isAllUnder.not() && it.importedFqName?.asString()?.endsWith(".$assignOp") == true }) return null
    if (!noTopLevelCallableNamed(cls.name ?: return null)) return null
    return error(lhs, "Variable expected", KotlinDiagnosticCodes.VARIABLE_EXPECTED)
}

/** VARIABLE_EXPECTED: `AAA++` where `AAA` names an object. The increment assigns its result back, and an object
 *  is not a variable. Judged when the name means a project object and nothing else in scope. */
internal fun KotlinSemanticChecks.incrementOfObject(e: dev.ide.kotlin.syntax.psi.KtUnaryExpression, resolver: KotlinResolver): Diagnostic? {
    val op = e.operationReference.text
    if (op != "++" && op != "--") return null
    val operand = e.baseExpression?.let { dev.ide.lang.kotlin.parse.unwrapParen(it) } as? KtNameReferenceExpression ?: return null
    val obj = implicitClassOf(operand, resolver) as? KtObjectDeclaration ?: return null
    val file = e.containingFile as? KtFile ?: return null
    if (topLevelNamed(file, operand.getReferencedName()).any { it !is KtObjectDeclaration }) return null
    if (obj.name != operand.getReferencedName() || !noTopLevelCallableNamed(operand.getReferencedName())) return null
    return error(operand, "Variable expected", KotlinDiagnosticCodes.VARIABLE_EXPECTED)
}

/**
 * NO_VALUE_FOR_PARAMETER on an enum entry (`B` or `B()` in `enum class E(val i: Int) { A(12), B }`): the entry
 * calls its enum's constructor, and a parameter without a default goes unfilled. Judged for an enum class whose only
 * constructor is the primary one, and entries whose arguments are all positional.
 */
internal fun enumEntryMissingArgument(entry: dev.ide.kotlin.syntax.psi.KtEnumEntry): Diagnostic? {
    val enumClass = (entry.parent as? KtClassBody)?.parent as? KtClass ?: return null
    if (!enumClass.isEnum() || enumClass.secondaryConstructors.isNotEmpty()) return null
    if (enumClass.hasModifier(KtTokens.EXPECT_KEYWORD) || enumClass.hasModifier(KtTokens.EXTERNAL_KEYWORD)) return null
    val params = enumClass.primaryConstructor?.valueParameters ?: return null
    val call = entry.initializerList?.initializers?.singleOrNull() as? dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
    if (entry.initializerList != null && call == null) return null
    val args = call?.valueArguments.orEmpty()
    if (args.any { it.isSpread || it.getArgumentName() != null }) return null
    if (params.any { it.isVarArg }) return null
    val missing = params.drop(args.size).firstOrNull { !it.hasDefaultValue() } ?: return null
    return error(entry.nameIdentifier ?: entry, "No value passed for parameter '${missing.name}'", KotlinDiagnosticCodes.NO_VALUE_FOR_PARAMETER)
}

// --- TYPECHECKER_HAS_RUN_INTO_RECURSIVE_PROBLEM (the implicit-type body resolver's cycle error) ---

/**
 * TYPECHECKER_HAS_RUN_INTO_RECURSIVE_PROBLEM: a reference, inside the body a declaration's implicit type is
 * inferred from, to a declaration whose own implicit type depends (through other implicitly typed bodies) on that
 * body again: `fun foo() = bar()` with `fun bar() = foo()`, `fun fact(n: Int) = if (n < 2) 1 else n * fact(n - 1)`,
 * `val x = { x }`. Every reference along such a cycle is reported, as K2 does.
 *
 * Judged only where the reference plainly means one declaration of this file: a bare call (or read, or `::name`)
 * whose name a single declaration of the arity takes in the innermost scope that declares it, with no local
 * variable, extension receiver, lambda receiver or inherited member that could take the name first. The graph of
 * implicitly typed bodies is followed lazily from the reference's target and memoized for the pass.
 */
internal fun KotlinSemanticChecks.recursiveImplicitType(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val shape = referenceShape(ref) ?: return null
    val hosts = implicitHostsOf(ref)
    if (hosts.isEmpty()) return null
    val target = implicitTargetOf(ref, shape, resolver) ?: return null
    if (!reachesAny(target, hosts, resolver)) return null
    val at: KtElement = if (shape == RefShape.CALL) ref.parent as KtElement else ref
    return error(at, "Type checking has run into a recursive problem", KotlinDiagnosticCodes.RECURSIVE_IMPLICIT_TYPE)
}

private enum class RefShape { CALL, READ, CALLABLE_REF }

/** How [ref] uses its name: as the callee of a call with no explicit receiver, as a plain value read, or as a
 *  `::name` reference; null for anything else (a selector, a type, an argument name, an assignment target). */
private fun referenceShape(ref: KtNameReferenceExpression): RefShape? {
    val name = ref.getReferencedName()
    if (name.isEmpty() || name == "it" || name == "field" || name == "_") return null
    return when (val p = ref.parent) {
        is KtCallExpression -> {
            if (p.calleeExpression !== ref) return null
            val q = p.parent as? KtQualifiedExpression
            if (q != null && q.selectorExpression === p) null else RefShape.CALL
        }
        is KtCallableReferenceExpression -> if (p.receiverExpression == null && p.callableReference === ref) RefShape.CALLABLE_REF else null
        is KtQualifiedExpression -> if (p.receiverExpression === ref) RefShape.READ else null
        is KtBinaryExpression -> {
            val op = p.operationReference.text
            if (op == "=" || op.length == 2 && op.endsWith("=") && op != "==" && op != "!=" && op != "<=" && op != ">=") {
                if (p.left === ref) return null
            }
            RefShape.READ
        }
        is KtValueArgument -> if (p.getArgumentName() == null) RefShape.READ else null
        is KtProperty, is KtNamedFunction, is KtPropertyAccessor, is KtBlockExpression, is KtReturnExpression,
        is KtParenthesizedExpression -> RefShape.READ
        else -> null
    }
}

/** The implicitly typed body of [d]: an expression body without a written return type, or the initializer (or
 *  getter expression) of a non-local property without a written type. Null for anything that states its type. */
private fun implicitBodyOf(d: KtDeclaration): KtExpression? = when (d) {
    is KtNamedFunction -> if (d.name != null && d.typeReference == null && !d.hasBlockBody()) d.bodyExpression else null
    is KtProperty -> if (d.typeReference != null || d.hasDelegate() || d.parent is KtBlockExpression) null
        else d.initializer ?: d.getter?.takeIf { it.returnTypeReference == null && !it.hasBlockBody() }?.bodyExpression
    else -> null
}

/** The implicitly typed declarations whose type inference resolves [ref]: walking out through lambdas, anonymous
 *  functions and local declarations (resolved in place), up to the first member or top-level declaration. */
private fun implicitHostsOf(ref: KtElement): List<KtDeclaration> {
    var hosts: ArrayList<KtDeclaration>? = null
    var child: KtElement = ref
    var node: KtElement? = ref.parent as? KtElement
    while (node != null) {
        when (node) {
            is KtClassOrObject, is KtClassBody, is KtFile -> break
            is KtNamedFunction -> {
                if (node.name != null && implicitBodyOf(node) === child) (hosts ?: ArrayList<KtDeclaration>().also { hosts = it }) += node
                if (node.parent !is KtBlockExpression && node.name != null) break
            }
            is KtProperty -> if (node.parent !is KtBlockExpression) {
                if (implicitBodyOf(node) === child) (hosts ?: ArrayList<KtDeclaration>().also { hosts = it }) += node
                break
            }
            is KtPropertyAccessor -> {
                val prop = node.property
                if (prop != null && prop.parent !is KtBlockExpression) {
                    if (implicitBodyOf(prop) === child) (hosts ?: ArrayList<KtDeclaration>().also { hosts = it }) += prop
                    break
                }
            }
            else -> {}
        }
        child = node
        node = node.parent as? KtElement
    }
    return hosts ?: emptyList()
}

/**
 * The one declaration of this file [ref] means, when it is implicitly typed; null when it is anything else or when
 * the scope gives more than one answer. Scopes are searched innermost first: local functions, the innermost
 * enclosing class's members, then the file's top level; a name declared in an outer class, a local variable or
 * parameter of the name, a receiver that could bring its own member, or an import of the name backs off.
 */
private fun KotlinSemanticChecks.implicitTargetOf(ref: KtNameReferenceExpression, shape: RefShape, resolver: KotlinResolver): KtDeclaration? {
    val name = ref.getReferencedName()
    val offset = ref.textRange.startOffset
    val call = if (shape == RefShape.CALL) ref.parent as KtCallExpression else null
    // A receiver between the reference and the scope that declares the name could take the name first.
    var innermostClass: KtClassOrObject? = null
    var node: KtElement? = ref.parent as? KtElement
    while (node != null && node !is KtFile) {
        when (node) {
            is KtClassOrObject -> { innermostClass = node; break }
            is KtNamedFunction -> if (node.receiverTypeReference != null) return null
            is KtProperty -> if (node.receiverTypeReference != null) return null
            is KtLambdaExpression -> if (!lambdaHasNoReceiver(node, name)) return null
            else -> {}
        }
        node = node.parent as? KtElement
    }
    // Cheap first: with no declaration of the name in any scope this rule searches, nothing to type a local for.
    val localFns = resolver.localFunctionsInScope(offset, name)
    val file = resolver.ktFile
    if (localFns.isEmpty() && (innermostClass?.let { classMembersNamed(it, name) }.isNullOrEmpty()) && topLevelNamed(file, name).isEmpty()) return null
    // Locals first: a local variable or parameter of the name is never an implicitly typed host.
    val locals = resolver.localsAt(offset, name)
    if (locals.any { it.kind != SymbolKind.METHOD }) return null
    if (localFns.isNotEmpty()) {
        if (shape == RefShape.READ) return null
        val fn = localFns.singleOrNull() ?: return null
        if (call != null && !arityFits(fn, call)) return null
        return fn.takeIf { implicitBodyOf(it) != null }
    }
    if (locals.isNotEmpty()) return null
    val cls = innermostClass
    if (cls != null) {
        // Members of every enclosing class (an outer one, or one a companion brings in) back off: which one the
        // name reaches depends on `inner` and on the receivers, which this rule does not model.
        val own = classMembersNamed(cls, name)
        if (own.isNotEmpty()) return pick(own, shape, call)
        if (cls.superTypeListEntries.isNotEmpty() || cls.isData() || (cls is KtClass && cls.isEnum()) || name in ANY_MEMBERS) return null
        if (cls is KtObjectDeclaration && cls.isObjectLiteral()) return null
        var outer: KtElement? = cls.parent as? KtElement
        while (outer != null && outer !is KtFile) {
            if (outer is KtClassOrObject && (classMembersNamed(outer, name).isNotEmpty() || outer.superTypeListEntries.isNotEmpty())) return null
            if (outer is KtNamedFunction || outer is KtProperty || outer is KtLambdaExpression) return null
            outer = outer.parent as? KtElement
        }
        for (comp in cls.companionObjects) if (classMembersNamed(comp, name).isNotEmpty()) return null
    }
    val tops = topLevelNamed(file, name)
    if (tops.isEmpty()) return null
    if (file.importDirectives.any { imp ->
            imp.aliasName == name || (!imp.isAllUnder && imp.importedFqName?.asString()?.substringAfterLast('.') == name)
        }) return null
    // Another file of the package can declare an overload the call might prefer.
    val pkg = file.packageFqName.asString()
    if (service.topLevelByName(name).count { it.packageName == pkg && it.origin.fromSource } > tops.size) return null
    return pick(tops, shape, call)
}

/** The single declaration of [candidates] the reference takes: for a call, the one function whose arity fits (no
 *  property of the name, which an `invoke` could make callable); for a read, the one property; for `::name`, the
 *  one declaration of the name. Extensions and classifiers of the name back off. */
private fun pick(candidates: List<KtDeclaration>, shape: RefShape, call: KtCallExpression?): KtDeclaration? {
    if (candidates.any { it !is KtNamedFunction && it !is KtProperty }) return null
    if (candidates.any { (it as? dev.ide.kotlin.syntax.psi.KtCallableDeclaration)?.receiverTypeReference != null }) return null
    val fns = candidates.filterIsInstance<KtNamedFunction>()
    val props = candidates.filterIsInstance<KtProperty>()
    val chosen: KtDeclaration = when (shape) {
        RefShape.CALL -> {
            if (props.isNotEmpty() || call == null) return null
            fns.singleOrNull { arityFits(it, call) } ?: return null
        }
        RefShape.READ -> { if (fns.isNotEmpty()) return null; props.singleOrNull() ?: return null }
        RefShape.CALLABLE_REF -> candidates.singleOrNull() ?: return null
    }
    return chosen.takeIf { implicitBodyOf(it) != null }
}

private fun arityFits(fn: KtNamedFunction, call: KtCallExpression): Boolean {
    val params = fn.valueParameters
    val args = call.valueArguments
    if (args.any { it.isSpread }) return false
    val required = params.count { !it.hasDefaultValue() && !it.isVarArg }
    val vararg = params.any { it.isVarArg }
    return args.size >= required && (vararg || args.size <= params.size)
}

/** Members of `Any`, `Enum` and data classes that every class has without declaring them. */
private val ANY_MEMBERS = setOf("toString", "equals", "hashCode", "name", "ordinal", "copy", "values", "valueOf", "entries")

/** A lambda whose parameter type has no receiver: one written on its own (a property initializer, a function's
 *  body), or passed to a function of this file whose function-type parameters have no receiver, or to `run`,
 *  `let`, `also`, `lazy` called without an explicit receiver. */
private fun KotlinSemanticChecks.lambdaHasNoReceiver(lambda: KtLambdaExpression, name: String): Boolean {
    var p: KtElement? = lambda.parent as? KtElement
    while (p is KtParenthesizedExpression) p = p.parent as? KtElement
    val call = when (p) {
        is KtValueArgument -> {
            val owner = (p.parent as? KtElement)
            owner as? KtCallExpression ?: (owner?.parent as? KtCallExpression) ?: return false
        }
        is KtCallExpression -> return p.calleeExpression === lambda
        // A declaration's written function type can give the lambda a receiver; an implicit one cannot.
        is KtProperty -> return !receiverFunctionType(p.typeReference, lambda.containingFile as? KtFile)
        is KtNamedFunction -> return !receiverFunctionType(p.typeReference, lambda.containingFile as? KtFile)
        is KtQualifiedExpression -> return p.receiverExpression === lambda
        else -> return false
    }
    val q = call.parent as? KtQualifiedExpression
    val calleeName = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
    if (q != null && q.selectorExpression === call) return calleeName == "let" || calleeName == "also"
    if (calleeName == "run" || calleeName == "lazy") return true
    val file = call.containingFile as? KtFile ?: return false
    val fn = topLevelNamed(file, calleeName).singleOrNull() as? KtNamedFunction ?: return false
    return fn.valueParameters.all { !receiverFunctionType(it.typeReference, file) || receiverLacksMember(it.typeReference, name, file) }
}

/** A receiver function type (`Scope.() -> T`) whose receiver is a class of [file] with no supertypes and no member
 *  called [name]: its receiver cannot take the name. */
private fun KotlinSemanticChecks.receiverLacksMember(ref: KtTypeReference?, name: String, file: KtFile): Boolean {
    var t = ref?.typeElement ?: return false
    while (t is dev.ide.kotlin.syntax.psi.KtNullableType) t = t.innerType ?: return false
    val receiver = ((t as? KtFunctionType)?.receiverTypeReference?.typeElement as? KtUserType) ?: return false
    if (receiver.qualifier != null) return false
    val cls = topLevelNamed(file, receiver.referencedName ?: return false).singleOrNull() as? KtClassOrObject ?: return false
    if (cls.superTypeListEntries.isNotEmpty() || cls.isData() || (cls is KtClass && cls.isEnum()) || name in ANY_MEMBERS) return false
    return classMembersNamed(cls, name).isEmpty()
}

/** A written type that is (or may be, through a type alias of [file] or an unknown file) a function type with a
 *  receiver. */
private fun receiverFunctionType(ref: KtTypeReference?, file: KtFile?): Boolean {
    var t = ref?.typeElement ?: return false
    while (t is dev.ide.kotlin.syntax.psi.KtNullableType) t = t.innerType ?: return true
    return when (t) {
        is KtFunctionType -> t.receiverTypeReference != null
        is KtUserType -> file == null || file.declarations.any { it is KtTypeAlias && it.name == t.referencedName }
        else -> true
    }
}

/** Whether [target] reaches one of [hosts] through the references of implicitly typed bodies (itself included). */
private fun KotlinSemanticChecks.reachesAny(target: KtDeclaration, hosts: List<KtDeclaration>, resolver: KotlinResolver): Boolean {
    if (target in hosts) return true
    val seen = HashSet<KtDeclaration>()
    val queue = ArrayDeque<KtDeclaration>()
    queue += target; seen += target
    while (queue.isNotEmpty() && seen.size < 64) {
        for (next in implicitEdges(queue.removeFirst(), resolver)) {
            if (next in hosts) return true
            if (seen.add(next)) queue += next
        }
    }
    return false
}

/** The implicitly typed declarations [d]'s implicit body refers to, memoized for the pass. */
private fun KotlinSemanticChecks.implicitEdges(d: KtDeclaration, resolver: KotlinResolver): List<KtDeclaration> =
    round7Memo.edges.getOrPut(d) {
        val body = implicitBodyOf(d) ?: return@getOrPut emptyList()
        val out = ArrayList<KtDeclaration>()
        fun visit(e: KtElement) {
            if (e is KtClassOrObject) return
            if (e is KtNameReferenceExpression) {
                val shape = referenceShape(e)
                if (shape != null) implicitTargetOf(e, shape, resolver)?.let { if (it !in out) out += it }
            }
            var c = e.firstChild
            while (c != null) { visit(c); c = c.nextSibling }
        }
        visit(body)
        out
    }

/** [cls]'s members called [name] (constructor properties and enum entries included), memoized per class. */
private fun KotlinSemanticChecks.classMembersNamed(cls: KtClassOrObject, name: String): List<KtDeclaration> =
    round7Memo.members.getOrPut(cls) {
        val byName = HashMap<String, MutableList<KtDeclaration>>()
        for (d in cls.declarations) {
            val n = (d as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name ?: continue
            if (d is KtObjectDeclaration && d.isCompanion()) continue
            byName.getOrPut(n) { ArrayList() } += d
        }
        for (p in cls.primaryConstructorParameters) if (p.hasValOrVar()) p.name?.let { byName.getOrPut(it) { ArrayList() } += p }
        byName
    }[name].orEmpty()

/** [file]'s top-level declarations called [name], memoized for the pass. */
private fun KotlinSemanticChecks.topLevelNamed(file: KtFile, name: String): List<KtDeclaration> {
    val memo = round7Memo.topLevel?.takeIf { it.first === file }?.second ?: run {
        val byName = HashMap<String, MutableList<KtDeclaration>>()
        for (d in file.declarations) {
            val n = (d as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name ?: continue
            byName.getOrPut(n) { ArrayList() } += d
        }
        round7Memo.topLevel = file to byName
        byName
    }
    return memo[name].orEmpty()
}

/** The per-pass tables of this file's rules, held on [KotlinSemanticChecks] and dropped with its pass caches. */
internal class Round7Memo {
    val edges = HashMap<KtDeclaration, List<KtDeclaration>>()
    val members = HashMap<KtClassOrObject, Map<String, List<KtDeclaration>>>()
    var topLevel: Pair<KtFile, Map<String, List<KtDeclaration>>>? = null
    var typeAliases: Pair<KtFile, Map<String, KtTypeAlias>>? = null

    fun clear() { edges.clear(); members.clear(); topLevel = null; typeAliases = null }
}

// --- FirConflictsDeclarationChecker: the signature two overloads are compared by ---

/**
 * The written types of [fn]'s signature in the form CONFLICTING_OVERLOADS compares them: annotations dropped,
 * whitespace normalized, a type alias of the file without type parameters expanded, a type parameter written as
 * the whole type erased to its bounds (`fun <T : Number> f(x: T)` against `f(x: Number)`), and one written inside
 * another type named by its position and bounds, so `List<T1>` and `List<T2>` read alike while `Map<T, R>` and
 * `Map<R, T>` do not. A parameter's bounds are its declared one and its `where` clauses, in either order.
 */
internal class OverloadSignature private constructor(
    private val checks: KotlinSemanticChecks,
    private val file: KtFile?,
    typeParameters: List<dev.ide.kotlin.syntax.psi.KtTypeParameter>,
    typeConstraints: List<dev.ide.kotlin.syntax.psi.KtTypeConstraint>,
) {
    constructor(checks: KotlinSemanticChecks, fn: KtNamedFunction) : this(checks, fn.containingFile as? KtFile, fn.typeParameters, fn.typeConstraints)

    /** For a declaration with no type parameters of its own (a constructor), in [file]. */
    constructor(checks: KotlinSemanticChecks, file: KtFile?) : this(checks, file, emptyList(), emptyList())

    private val position = HashMap<String, Int>()
    private val bounds = HashMap<String, String>()

    init {
        typeParameters.forEachIndexed { i, tp -> tp.name?.let { position[it] = i } }
        for (tp in typeParameters) {
            val n = tp.name ?: continue
            val all = ArrayList<String>()
            tp.extendsBound?.text?.let { all += plain(it) }
            for (c in typeConstraints) {
                if (c.subjectTypeParameterName?.getReferencedName() == n) c.boundTypeReference?.text?.let { all += plain(it) }
            }
            bounds[n] = if (all.isEmpty()) "Any ?" else all.sorted().joinToString(" & ")
        }
    }

    fun key(text: String?): String {
        if (text == null) return "?"
        val tokens = expand(tokens(text), 0)
        val head = tokens.firstOrNull()
        if (head != null && head in position && (tokens.size == 1 || tokens.size == 2 && tokens[1] == "?")) {
            return bounds[head]!! + if (tokens.size == 2) "?" else ""
        }
        val out = ArrayList<String>(tokens.size)
        for ((i, t) in tokens.withIndex()) {
            val p = position[t]
            out += if (p != null && tokens.getOrNull(i - 1) != "." && tokens.getOrNull(i + 1) != ".") "#$p:${bounds[t]}" else t
        }
        return out.joinToString(" ")
    }

    /** A bound's text, normalized the same way but with type parameters left as written. */
    private fun plain(text: String): String = expand(tokens(text), 0).joinToString(" ")

    private fun tokens(text: String): List<String> {
        val stripped = ANNOTATION.replace(text, " ")
        return TOKEN.findAll(stripped).map { it.value.removeSurrounding("`") }.toList()
    }

    private fun expand(tokens: List<String>, depth: Int): List<String> {
        val aliases = aliases()
        if (aliases.isEmpty() || depth > 4) return tokens
        val out = ArrayList<String>(tokens.size)
        for ((i, t) in tokens.withIndex()) {
            val rhs = aliases[t]?.typeReference?.text
            if (rhs == null || t in position || tokens.getOrNull(i - 1) == "." || tokens.getOrNull(i + 1) == "." || tokens.getOrNull(i + 1) == "<") {
                out += t; continue
            }
            out += expand(tokens(rhs), depth + 1)
        }
        return out
    }

    /** The file's top-level type aliases without type parameters, by name, memoized for the pass. */
    private fun aliases(): Map<String, KtTypeAlias> {
        val f = file ?: return emptyMap()
        checks.round7Memo.typeAliases?.takeIf { it.first === f }?.let { return it.second }
        val byName = HashMap<String, KtTypeAlias>()
        val dup = HashSet<String>()
        for (d in f.declarations) {
            if (d is KtClassOrObject) d.name?.let { dup += it }
            if (d !is KtTypeAlias || d.typeParameters.isNotEmpty()) continue
            val n = d.name ?: continue
            if (byName.put(n, d) != null) dup += n
        }
        dup.forEach { byName.remove(it) }
        checks.round7Memo.typeAliases = f to byName
        return byName
    }

    private companion object {
        val ANNOTATION = Regex("""@(?:\[[^\]]*]|[A-Za-z_][\w.]*(?:\([^()]*\))?)""")
        val TOKEN = Regex("""`[^`]*`|[A-Za-z_][A-Za-z0-9_]*|\S""")
    }
}

// --- FirConflictsDeclarationChecker: a data class's generated members ---

private val COMPONENT_N = Regex("""component([1-9][0-9]*)""")

/**
 * CONFLICTING_OVERLOADS between a data class's generated `componentN()` or `copy(...)` and a function its body
 * declares with that signature (`fun component1()`, `fun copy(x: Int, y: String)` for `data class A(val x: Int,
 * val y: String)`). Reported on the function and on the class header. A function that clashes with another
 * declared function of the body is already reported there, and is left to that report.
 */
internal fun KotlinSemanticChecks.dataClassMemberConflicts(cls: KtClass): List<Diagnostic> {
    if (!cls.isData() || cls.hasModifier(KtTokens.EXPECT_KEYWORD)) return emptyList()
    val ctor = cls.primaryConstructor ?: return emptyList()
    val params = ctor.valueParameters
    if (params.isEmpty() || params.any { !it.hasValOrVar() || it.typeReference == null || it.isVarArg }) return emptyList()
    val name = cls.nameIdentifier ?: return emptyList()
    val fns = cls.declarations.filterIsInstance<KtNamedFunction>()
    val out = ArrayList<Diagnostic>()
    for (f in fns) {
        if (f.receiverTypeReference != null || f.typeParameters.isNotEmpty() || f.nameIdentifier == null) continue
        if (f.annotationEntries.any { it.shortName?.asString() == "Deprecated" && it.text.contains("HIDDEN") } &&
            !f.hasModifier(KtTokens.OPEN_KEYWORD) && !f.hasModifier(KtTokens.ABSTRACT_KEYWORD)) continue
        val fname = f.name ?: continue
        val clash = COMPONENT_N.matchEntire(fname)?.let { m ->
            f.valueParameters.isEmpty() && m.groupValues[1].toInt() <= params.size
        } ?: (fname == "copy" && f.valueParameters.size == params.size && run {
            val sig = OverloadSignature(this, f)
            f.valueParameters.zip(params).all { (a, b) -> !a.isVarArg && sig.key(a.typeReference?.text) == sig.key(b.typeReference?.text) }
        })
        if (!clash) continue
        val twin = fns.any { it !== f && it.name == fname && it.valueParameters.size == f.valueParameters.size && it.receiverTypeReference == null }
        if (!twin) out += error(f.nameIdentifier!!, "Conflicting overloads: '$fname' is generated for the data class", KotlinDiagnosticCodes.CONFLICTING_DECLARATION)
        out += Diagnostic(
            TextRange(name.textRange.startOffset, ctor.textRange.endOffset), Severity.ERROR,
            "Conflicting overloads: the generated '$fname' of data class '${cls.name}'", KotlinDiagnosticCodes.CONFLICTING_DECLARATION,
        )
    }
    return out
}
