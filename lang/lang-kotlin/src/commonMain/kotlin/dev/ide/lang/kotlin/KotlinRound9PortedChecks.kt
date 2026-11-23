package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.Variance
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.enclosingClassMembersContain
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.localTypesInScope
import dev.ide.lang.kotlin.resolve.localsAt
import dev.ide.lang.kotlin.resolve.scopeMemberExtensions
import dev.ide.lang.kotlin.resolve.extensionInScope
import dev.ide.lang.kotlin.resolve.localFunctionsInScope

/**
 * A ninth round of rules ported from the K2 compiler's checkers (`compiler/fir/checkers`, Apache 2.0). Each function
 * names the K2 code and checker it follows. Registered from [KotlinSemanticChecks.checkers], after the earlier rounds.
 */
internal fun KotlinSemanticChecks.round9PortedCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtBinaryExpression::class) { psi ->
        if (resolveReady) report(infixModifierRequired(psi as dev.ide.kotlin.syntax.psi.KtBinaryExpression, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression::class) { psi ->
        report(confusingBranchCondition((psi as dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression).expression, psi))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtWhenConditionInRange::class) { psi ->
        report(confusingBranchCondition((psi as dev.ide.kotlin.syntax.psi.KtWhenConditionInRange).rangeExpression, psi))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtDeclaration::class) { psi ->
        reportAll(explicitApiDiagnostics(psi as dev.ide.kotlin.syntax.psi.KtDeclaration, explicitApiModeThisPass()))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration::class) { psi ->
        report(nonLocalDestructuring(psi as dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtTypeConstraint::class) { psi ->
        report(nameInConstraintNotATypeParameter(psi as dev.ide.kotlin.syntax.psi.KtTypeConstraint))
    },
    KotlinChecker(KtNameReferenceExpression::class) { psi ->
        if (resolveReady) report(privateToThisAccess(psi as KtNameReferenceExpression, resolver))
        if (resolveReady) report(inaccessibleOuterReceiver(psi as KtNameReferenceExpression, resolver) ?: innerConstructorWithoutReceiver(psi, resolver))
    },
    KotlinChecker(KtThisExpression::class) { psi ->
        if (resolveReady) report(inaccessibleOuterThis(psi as KtThisExpression, resolver))
    },
    KotlinChecker(KtCallExpression::class) { psi ->
        if (resolveReady) report(functionValueMissingArguments(psi as KtCallExpression, resolver))
        if (resolveReady) reportAll(nameForAmbiguousParameter(psi as KtCallExpression, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression::class) { psi ->
        if (resolveReady) reportAll(qualifiedLhsTypeArgumentCounts(psi as dev.ide.kotlin.syntax.psi.KtDoubleColonExpression, resolver))
    },
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtClassLiteralExpression::class) { psi ->
        if (resolveReady) reportAll(qualifiedLhsTypeArgumentCounts(psi as dev.ide.kotlin.syntax.psi.KtDoubleColonExpression, resolver))
    },
)

/** The per-pass tables of this round, cleared with [KotlinSemanticChecks.resetPassCaches]. */
internal class Round9Memo {
    /** A class with `in`/`out` type parameters, and the names of its members that are private to `this`. */
    val privateToThis = HashMap<KtClass, Set<String>>()

    /** Per file: whether it declares a class with an `in`/`out` type parameter at all. */
    val variantFiles = HashMap<dev.ide.kotlin.syntax.psi.KtFile, Boolean>()

    fun clear() { privateToThis.clear(); variantFiles.clear() }
}

// --- FirPrivateToThisAccessChecker: INVISIBLE_REFERENCE for a private-to-this member ---

/**
 * INVISIBLE_REFERENCE: `t.foo()` inside `class Test<in I>` whose `private fun foo(): I` puts `I` where its variance
 * forbids. Such a member is private to `this` (K1's `Visibilities.PrivateToThis`, which K2 keeps), visible only
 * through the class's own dispatch receiver: `foo()`, `this.foo()`, `this@Test.foo()`. Any other receiver of the
 * class's type, another instance or a `with` lambda's receiver, cannot see it, inside the class body included.
 * Judged where the receiver is written; a bare `this` is left alone, since it may be a lambda's receiver.
 */
internal fun KotlinSemanticChecks.privateToThisAccess(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val parent = ref.parent
    val q = when {
        parent is KtQualifiedExpression && parent.selectorExpression === ref -> parent
        parent is KtCallExpression && parent.calleeExpression === ref ->
            (parent.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === parent }
        else -> null
    } ?: return null
    val name = ref.getReferencedName()
    if (name.isEmpty()) return null
    val file = ref.containingFile as? dev.ide.kotlin.syntax.psi.KtFile ?: return null
    if (!variantClassesIn(file)) return null
    var owner: KtClass? = null
    var node = q.parent
    while (node != null) {
        if (node is KtClass && name in privateToThisNames(node, resolver)) { owner = node; break }
        node = node.parent
    }
    if (owner == null) return null
    val receiver = unwrapParen(q.receiverExpression)
    if (receiver is KtSuperExpression) return null
    if (receiver is KtThisExpression) {
        val label = receiver.getLabelName() ?: return null
        // `this@Test` is the class's own receiver; `this@Outer` an enclosing class's, of another type.
        var n = receiver.parent
        while (n != null) {
            if (n is KtClassOrObject && n.name == label) return null
            n = n.parent
        }
    }
    val fqn = owner.fqName?.asString() ?: return null
    if (resolver.inferType(receiver)?.qualifiedName != fqn) return null
    if (visibilitySuppressed(ref)) return null
    val r = ref.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Cannot access '$name': it is private/*private to this*/ in '${owner.name}'", KotlinDiagnosticCodes.INVISIBLE_REFERENCE,
    )
}

/** Whether [file] declares a class with an `in`/`out` type parameter outside any function body (the only place a
 *  private-to-this member can live), walked once per pass over class bodies only. */
private fun KotlinSemanticChecks.variantClassesIn(file: dev.ide.kotlin.syntax.psi.KtFile): Boolean =
    round9Memo.variantFiles.getOrPut(file) {
        fun scan(decls: List<dev.ide.kotlin.syntax.psi.KtDeclaration>): Boolean = decls.any { d ->
            d is KtClassOrObject && ((d as? KtClass)?.typeParameters?.any { it.variance != Variance.INVARIANT } == true || scan(d.declarations))
        }
        scan(file.declarations)
    }

/** The names of [cls]'s members that are private to `this` and that no other member of [cls] shares (an overload
 *  of another visibility is what the call may resolve to); empty for a class with no `in`/`out` type parameter. */
private fun KotlinSemanticChecks.privateToThisNames(cls: KtClass, resolver: KotlinResolver): Set<String> {
    if (cls.typeParameters.none { it.variance != Variance.INVARIANT }) return emptySet()
    return round9Memo.privateToThis.getOrPut(cls) {
        val hidden = HashSet<String>()
        val other = HashSet<String>()
        for (d in cls.declarations) {
            if (d !is KtNamedFunction && d !is KtProperty) continue
            val n = (d as KtNamedDeclaration).name ?: continue
            if (d.hasModifier(KtTokens.PRIVATE_KEYWORD) && isPrivateToThis(cls, d, resolver)) hidden += n else other += n
        }
        cls.primaryConstructor?.valueParameters?.forEach { p -> if (p.hasValOrVar()) p.name?.let { other += it } }
        hidden - other
    }
}

// --- FirImportsChecker.checkCanBeImported: INVISIBLE_REFERENCE on an import ---

/**
 * INVISIBLE_REFERENCE on the segments of an import that this file cannot see: `import a.B` or `import a.B.Companion.bar`
 * with `private class B` in another file, `import a.C.Companion.baz` with `private companion object`, `import
 * pkg.Foo.Bar.baz` with a private `baz` in `object Bar`. As K2 checks it: every class on the imported member's
 * parent chain, then the member (a top-level target is [privateTopLevelImports]'). A `private`
 * declaration is visible only in its own file (K2 skips the containing-class rule for imports). Project source only.
 */
internal fun KotlinSemanticChecks.importVisibility(imp: KtImportDirective, filePath: String): List<Diagnostic> {
    if (imp.isAllUnder) return emptyList()
    val fqn = imp.importedFqName?.asString()?.takeIf { '.' in it } ?: return emptyList()
    val segments = ArrayList<KtNameReferenceExpression>()
    fun collect(e: KtElement) {
        if (e is KtNameReferenceExpression) { segments += e; return }
        var c = e.firstChild
        while (c != null) { collect(c); c = c.nextSibling }
    }
    collect(imp.importedReference ?: return emptyList())
    val names = fqn.split('.')
    if (segments.size != names.size) return emptyList()
    val here = filePath
    // Paths compared by file name, as [privateInAnotherFile] does: the live buffer's and the model's spelling differ.
    fun sameFile(path: String) = path.substringAfterLast('/') == here.substringAfterLast('/')
    val out = ArrayList<Diagnostic>()
    fun invisible(at: KtElement, name: String) {
        if (visibilitySuppressed(at)) return
        val r = at.textRange
        out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Cannot access '$name': it is private", KotlinDiagnosticCodes.INVISIBLE_REFERENCE)
    }
    val parentFqn = fqn.substringBeforeLast('.')
    val parent = service.sourceClass(parentFqn)
    if (parent != null) {
        // The parent class, then each class around it, by its segment counted from the end.
        var depth = 1
        var cls = parentFqn
        while (true) {
            val rc = service.sourceClass(cls) ?: break
            if (rc.isPrivate && !sameFile(rc.ctx.path)) invisible(segments[segments.size - 1 - depth], rc.simpleName)
            cls = cls.substringBeforeLast('.', "")
            depth++
            if (cls.isEmpty() || depth >= segments.size) break
        }
        // A member of an object: every one of that name private to another file.
        if (parent.isObject && !sameFile(parent.ctx.path)) {
            val leaf = names.last()
            val members = parent.members.filter { it.name == leaf }
            if (members.isNotEmpty() && members.all { it.visibility == "private" }) invisible(segments.last(), leaf)
        }
    }
    // A top-level class or callables the whole name denotes: [privateTopLevelImports].
    return out
}

// --- WRONG_NUMBER_OF_TYPE_ARGUMENTS on a qualified left side of `::` (FirTypeResolver's qualifier argument check) ---

/**
 * WRONG_NUMBER_OF_TYPE_ARGUMENTS along `Outer<Int, Int>.Inner::id` / `Outer.Inner<Int, Int>::id`: on the left of
 * `::` each class segment takes its OWN type arguments, so a segment written with arguments needs exactly as many
 * as it declares, and a generic class whose next segment is an `inner` class must be given its arguments where
 * it is written (`Outer.Inner<Int, Int>::id` reports the bare `Outer`). Project classes, reached from the head by
 * their nested names.
 */
internal fun KotlinSemanticChecks.qualifiedLhsTypeArgumentCounts(
    e: dev.ide.kotlin.syntax.psi.KtDoubleColonExpression, resolver: KotlinResolver,
): List<Diagnostic> {
    val lhs = e.receiverExpression as? dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression ?: return emptyList()
    // The segments, head first: a bare name, or an argument-less call carrying type arguments (`Outer<Int>`).
    class Seg(val name: KtNameReferenceExpression, val args: dev.ide.kotlin.syntax.psi.KtTypeArgumentList?)
    val segs = ArrayList<Seg>()
    fun collect(x: dev.ide.kotlin.syntax.psi.KtExpression?): Boolean {
        when (x) {
            is KtNameReferenceExpression -> segs += Seg(x, null)
            is KtCallExpression -> {
                if (x.valueArgumentList != null || x.lambdaArguments.isNotEmpty()) return false
                segs += Seg(x.calleeExpression as? KtNameReferenceExpression ?: return false, x.typeArgumentList)
            }
            is dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression -> return collect(x.receiverExpression) && collect(x.selectorExpression)
            else -> return false
        }
        return true
    }
    if (!collect(lhs) || segs.size < 2) return emptyList()
    val head = segs[0].name.getReferencedName()
    val offset = segs[0].name.textRange.startOffset
    if (resolver.isTypeParameterInScope(head, offset) || resolver.localsAt(offset, head).isNotEmpty()) return emptyList()
    if (resolver.enclosingClassMembersContain(offset, head) || service.isProjectTypeAlias(head)) return emptyList()
    var fqn = resolver.localTypesInScope(offset)[head]
        ?: service.resolveTypeName(head, resolver.fileContext, dev.ide.lang.kotlin.resolve.enclosingClassFqnOf(e))
            ?.takeIf { service.sourceClass(it) != null }
        ?: return emptyList()
    val classes = ArrayList<KtClassOrObject>()
    for ((i, seg) in segs.withIndex()) {
        if (i > 0) fqn = "$fqn.${seg.name.getReferencedName()}"
        val cls = (service.sourceClass(fqn)?.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject ?: return emptyList()
        if (cls.name != seg.name.getReferencedName()) return emptyList()
        classes += cls
    }
    val out = ArrayList<Diagnostic>()
    for ((i, seg) in segs.withIndex()) {
        val cls = classes[i]
        val expected = cls.typeParameters.size
        val args = seg.args
        if (args != null) {
            val actual = args.arguments.size
            if (actual != expected) {
                val msg = if (expected == 0) "Type '${cls.name}' does not take type arguments" else "$expected type argument${if (expected == 1) "" else "s"} expected for '${cls.name}', but $actual found"
                val r = args.textRange
                out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT)
            }
        } else if (expected > 0 && i < segs.lastIndex && (classes[i + 1] as? KtClass)?.isInner() == true) {
            val r = seg.name.textRange
            out += Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "$expected type argument${if (expected == 1) "" else "s"} expected for '${cls.name}'", KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT,
            )
        }
    }
    return out
}

// --- NO_VALUE_FOR_PARAMETER on the invocation of a function-typed value (the `invoke` call's argument mapping) ---

/**
 * NO_VALUE_FOR_PARAMETER: `p { 1 }` with `p: (Int, () -> Int) -> Unit`. A value of a plain function type is called
 * through its `invoke`, whose parameters have no defaults, so every one of them needs an argument; reported on the
 * callee once for the call. Judged for a local or parameter whose type is a non-extension, non-suspend function
 * type, called with positional arguments only.
 */
internal fun KotlinSemanticChecks.functionValueMissingArguments(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    if (call.parent is KtQualifiedExpression && (call.parent as KtQualifiedExpression).selectorExpression === call) return null
    val name = callee.getReferencedName()
    // A call that binds a function (the memoized call targets) is not a value's `invoke`.
    if (resolver.callTargets(call).isNotEmpty()) return null
    if (call.valueArguments.any { it.isNamed() || it.getSpreadElement() != null }) return null
    val local = resolver.localsAt(callee.textRange.startOffset, name).firstOrNull() ?: return null
    if (local.kind == dev.ide.lang.resolve.SymbolKind.METHOD) return null
    // The declared type, read off the declaration: `p: (Int, () -> Int) -> Unit`.
    val typeRef = when (val d = (local.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi) {
        is dev.ide.kotlin.syntax.psi.KtParameter -> d.typeReference
        is KtProperty -> d.typeReference
        else -> null
    } ?: return null
    if (typeRef.modifierList?.hasModifier(KtTokens.SUSPEND_KEYWORD) == true) return null
    val fn = typeRef.typeElement as? dev.ide.kotlin.syntax.psi.KtFunctionType ?: return null
    if (fn.receiverTypeReference != null) return null
    val arity = fn.parameters.size
    val given = call.valueArguments.size
    if (given >= arity) return null
    val r = callee.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "No value passed for parameter 'p${given + 1}'", KotlinDiagnosticCodes.ARGUMENT_COUNT,
    )
}

/**
 * INFIX_MODIFIER_REQUIRED (FirInfixFunctionCallChecker): `a name b` calls a `name` that is not `infix`. Every
 * function of that name that could take the one argument is gathered (members, local and member extensions,
 * extensions in scope); the call is reported only when there is one and none of them is `infix`. A value of
 * function type called this way (`f invoke 1`, `1 ext 2` with `ext: Int.(Int) -> Unit`) is an `invoke`, which
 * is never infix. An operand whose type is unknown is not judged.
 */
private fun KotlinSemanticChecks.infixModifierRequired(e: dev.ide.kotlin.syntax.psi.KtBinaryExpression, resolver: KotlinResolver): Diagnostic? {
    if (e.operationToken != KtTokens.IDENTIFIER || e.right == null) return null
    val ref = e.operationReference
    val name = ref.getReferencedName()
    val left = resolver.inferType(e.left) ?: return null
    if (left.isTypeParameter) return null
    val offset = e.textRange.startOffset
    fun takesOne(s: dev.ide.lang.kotlin.symbols.KotlinSymbol): Boolean {
        val n = s.paramTypes.size
        if (n == 0) return false
        val required = if (s.paramHasDefault.size == n) s.paramHasDefault.count { !it } else n
        return required <= 1 || s.varargParamIndex >= 0
    }
    val candidates = (service.membersNamed(left.qualifiedName, left.typeArguments, name) +
        resolver.scopeMemberExtensions(offset, left, name, exactName = true))
        .filter { it.name == name && it.kind == dev.ide.lang.resolve.SymbolKind.METHOD && takesOne(it) &&
            (!it.isExtension || it.owner != null || resolver.extensionInScope(it)) }
    val locals = resolver.localFunctionsInScope(offset, name)
        .filter { fn -> fn.receiverTypeReference != null && fn.valueParameters.isNotEmpty() }
    if (candidates.any { it.isInfix } || locals.any { it.hasModifier(KtTokens.INFIX_KEYWORD) }) return null
    // A function value's own `invoke` (`f invoke 1`): an `operator`, never `infix`.
    val functionInvoke = name == "invoke" && dev.ide.lang.kotlin.symbols.TypeRendering.isFunctionType(left.qualifiedName)
    val viaValue = functionInvoke || candidates.isEmpty() && locals.isEmpty() && resolver.localsAt(offset, name).any { v ->
        (v.type as? dev.ide.lang.kotlin.symbols.KotlinType)?.let { dev.ide.lang.kotlin.symbols.TypeRendering.isFunctionType(it.qualifiedName) } == true
    }
    if (candidates.isEmpty() && locals.isEmpty() && !viaValue) return null
    val r = ref.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "'infix' modifier is required on '$name'", KotlinDiagnosticCodes.INFIX_MODIFIER_REQUIRED,
    )
}

/**
 * NON_LOCAL_DESTRUCTURING_DECLARATION (FirDestructuringDeclarationChecker): a destructuring declaration in a class
 * body or at the top level. Only a statement of a block (a local) may destructure. Spans the parenthesized
 * entries, as K2 reports it.
 */
private fun nonLocalDestructuring(d: dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration): Diagnostic? {
    val parent = d.parent
    if (parent is dev.ide.kotlin.syntax.psi.KtBlockExpression) return null
    if (parent !is dev.ide.kotlin.syntax.psi.KtClassBody && parent !is dev.ide.kotlin.syntax.psi.KtFile) return null
    val first = d.entries.firstOrNull() ?: return null
    val last = d.entries.last()
    val text = d.text
    val base = d.textRange.startOffset
    val open = text.lastIndexOf('(', first.textRange.startOffset - base).takeIf { it >= 0 } ?: return null
    val close = text.indexOf(')', last.textRange.endOffset - base).takeIf { it >= 0 } ?: return null
    return Diagnostic(
        TextRange(base + open, base + close + 1), Severity.ERROR,
        "Destructuring declarations are only allowed for local variables/values",
        KotlinDiagnosticCodes.NON_LOCAL_DESTRUCTURING_DECLARATION,
    )
}

/** NAME_IN_CONSTRAINT_IS_NOT_A_TYPE_PARAMETER: a `where X : A` whose `X` is not a type parameter of the declaration. */
internal fun KotlinSemanticChecks.nameInConstraintNotATypeParameter(c: dev.ide.kotlin.syntax.psi.KtTypeConstraint): Diagnostic? {
    val subject = c.subjectTypeParameterName ?: return null
    val name = subject.getReferencedName()
    var owner: dev.ide.kotlin.syntax.psi.KtElement? = c.parent as? dev.ide.kotlin.syntax.psi.KtElement
    while (owner != null && owner !is dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner) owner = owner.parent as? dev.ide.kotlin.syntax.psi.KtElement
    val tps = (owner as? dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner)?.typeParameters ?: return null
    // With no type parameters at all the `where` is a syntax error instead.
    if (tps.any { it.name == name }) return null
    if (tps.isEmpty() && (owner as dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner).typeParameterList == null) return null
    // Reported once per name, on its first constraint.
    val list = c.parent as? dev.ide.kotlin.syntax.psi.KtTypeConstraintList
    if (list != null && list.children.filterIsInstance<dev.ide.kotlin.syntax.psi.KtTypeConstraint>()
            .firstOrNull { it.subjectTypeParameterName?.getReferencedName() == name } !== c) return null
    val r = subject.textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "$name does not refer to a type parameter of ${(owner as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name ?: "the declaration"}",
        KotlinDiagnosticCodes.NAME_IN_CONSTRAINT_IS_NOT_A_TYPE_PARAMETER)
}

/**
 * NAME_FOR_AMBIGUOUS_PARAMETER (FirNamedArgumentsChecker): a member the receiver's class does not declare but
 * inherits from two or more supertypes at once (an intersection override) has no parameter names of its own where
 * those supertypes name a parameter differently, so a named argument for that parameter is an error:
 * `c.foo(x = 0)` on `interface C : A, B` with `A.foo(x: Int)` and `B.foo(y: Int)`.
 *
 * Read from source: the receiver's written class must not declare the function, and each of its direct supertypes
 * must reach one declaration of it (its own or the nearest inherited) with the same parameter types, written alike.
 * A generic declaration is left to the compiler.
 */
private fun KotlinSemanticChecks.nameForAmbiguousParameter(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
    val named = call.valueArguments.filter { it.getArgumentName() != null }
    if (named.isEmpty()) return emptyList()
    val dot = call.parent as? KtQualifiedExpression ?: return emptyList()
    if (dot.selectorExpression !== call) return emptyList()
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return emptyList()
    val receiver = resolver.inferType(dot.receiverExpression) ?: return emptyList()
    if (receiver.isTypeParameter) return emptyList()
    val cls = sourceClassPsi(receiver.qualifiedName) ?: return emptyList()
    if (declaresFunction(cls, name)) return emptyList()
    // Each direct supertype's declaration, with the type arguments that supertype is written with.
    val found = LinkedHashMap<KtNamedFunction, Map<String, dev.ide.lang.kotlin.symbols.KotlinType>>()
    for (ref in service.sourceSupertypeRefs(receiver.qualifiedName)) {
        val st = ref as? dev.ide.lang.kotlin.symbols.KotlinType ?: return emptyList()
        val (fn, owner) = nearestDeclaration(st.qualifiedName, name, HashSet()) ?: continue
        val ownerTps = owner.typeParameters.mapNotNull { it.name }
        val bindings = when {
            ownerTps.isEmpty() -> emptyMap()
            // Arguments are only substituted one level down: the declaration must be the supertype's own.
            owner === sourceClassPsi(st.qualifiedName) && st.typeArguments.size == ownerTps.size ->
                ownerTps.zip(st.typeArguments).associate { (n, a) -> n to (a as? dev.ide.lang.kotlin.symbols.KotlinType ?: return emptyList()) }
            else -> return emptyList()
        }
        found[fn] = bindings
    }
    if (found.size < 2) return emptyList()
    val fns = found.keys.toList()
    // Parameter types are compared as this file reads them, so every declaration must be in it.
    if (fns.any { it.containingFile !== call.containingFile }) return emptyList()
    if (fns.any { it.receiverTypeReference != null || it.valueParameters.any { p -> p.isVarArg } }) return emptyList()
    val arity = fns.first().valueParameters.size
    val typeArity = fns.first().typeParameters.size
    if (fns.any { it.valueParameters.size != arity || it.typeParameters.size != typeArity }) return emptyList()
    // One signature: every parameter's type the same once the supertypes' arguments are in and the functions' own
    // type parameters are matched by position, so the declarations are one member, not overloads.
    for (i in 0 until arity) {
        val texts = fns.map { f ->
            val owner = f.parent?.parent as? KtClassOrObject ?: return emptyList()
            val own = f.typeParameters.mapNotNull { it.name }
            val names = owner.typeParameters.mapNotNull { it.name }.toSet() + own
            val text = f.valueParameters[i].typeReference?.text ?: return emptyList()
            val t = service.markTypeParameters(service.typeFromText(text, resolver.fileContext), names) ?: return emptyList()
            val bindings = found.getValue(f) + own.withIndex().associate { (k, n) -> n to dev.ide.lang.kotlin.symbols.KotlinType("#$k", isTypeParameter = true) }
            renderType(substituteKeepingNullability(t, bindings))
        }
        if (texts.toSet().size != 1) return emptyList()
    }
    val out = ArrayList<Diagnostic>()
    for (arg in named) {
        val n = arg.getArgumentName()?.asName?.identifier ?: continue
        val index = fns.firstNotNullOfOrNull { f -> f.valueParameters.indexOfFirst { it.name == n }.takeIf { it >= 0 } } ?: continue
        val names = fns.map { it.valueParameters[index].name }
        if (names.toSet().size < 2) continue
        val r = arg.getArgumentName()!!.textRange
        out += Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Named argument '$n' is ambiguous: the supertypes of '${cls.name}' name this parameter differently (${names.joinToString(", ")})",
            KotlinDiagnosticCodes.NAME_FOR_AMBIGUOUS_PARAMETER,
        )
    }
    return out
}

private fun KotlinSemanticChecks.sourceClassPsi(fqn: String): KtClassOrObject? =
    (service.sourceClass(fqn)?.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject

/** The direct supertypes of the source class [fqn] as resolved where it is declared, or null when one is generic. */
private fun KotlinSemanticChecks.directSupertypes(fqn: String): List<String>? =
    service.sourceSupertypeRefs(fqn).map { t ->
        val k = t as? dev.ide.lang.kotlin.symbols.KotlinType ?: return null
        if (k.typeArguments.isNotEmpty()) return null
        k.qualifiedName
    }

private fun declaresFunction(cls: KtClassOrObject, name: String): Boolean =
    cls.declarations.any { it is KtNamedFunction && it.name == name } ||
        cls.primaryConstructor?.valueParameters?.any { it.hasValOrVar() && it.name == name } == true

/** The declaration of [name] that a value of class [fqn] gets: its own, else the one of its single supertype path. */
private fun KotlinSemanticChecks.nearestDeclaration(fqn: String, name: String, seen: HashSet<String>): Pair<KtNamedFunction, KtClassOrObject>? {
    if (!seen.add(fqn)) return null
    val cls = sourceClassPsi(fqn) ?: return null
    cls.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == name }.let { own ->
        if (own.size > 1) return null
        own.singleOrNull()?.let { return it to cls }
    }
    val inherited = (directSupertypes(fqn) ?: return null).mapNotNull { s -> nearestDeclaration(s, name, seen) }.distinctBy { it.first }
    return inherited.singleOrNull()
}

/**
 * CONFUSING_BRANCH_CONDITION_ERROR (FirConfusingWhenBranchSyntaxChecker): in a `when` with a subject, a branch
 * condition written as an unparenthesized `in`, `is`, comparison, equality or `&&`/`||` expression reads as if it
 * tested the subject (`when (x) { x in r -> }`) but is a value compared with it. The same for the range of an
 * `in` condition. Parenthesized, it is what it says and allowed.
 */
private fun confusingBranchCondition(e: dev.ide.kotlin.syntax.psi.KtExpression?, condition: KtElement): Diagnostic? {
    val w = condition.parent?.parent as? dev.ide.kotlin.syntax.psi.KtWhenExpression ?: return null
    if (w.subjectExpression == null && w.subjectVariable == null) return null
    val confusing = when (e) {
        is dev.ide.kotlin.syntax.psi.KtIsExpression -> true
        is dev.ide.kotlin.syntax.psi.KtBinaryExpression -> e.operationToken in CONFUSING_CONDITION_OPERATORS
        else -> false
    }
    if (!confusing) return null
    val r = e!!.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "This condition is compared with the 'when' subject, not tested on it: wrap it in parentheses if that is meant",
        KotlinDiagnosticCodes.CONFUSING_BRANCH_CONDITION,
    )
}

private val CONFUSING_CONDITION_OPERATORS = setOf(
    KtTokens.IN_KEYWORD, KtTokens.NOT_IN, KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ,
    KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ, KtTokens.ANDAND, KtTokens.OROR,
)
