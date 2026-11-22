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

/**
 * A ninth round of rules ported from the K2 compiler's checkers (`compiler/fir/checkers`, Apache 2.0). Each function
 * names the K2 code and checker it follows. Registered from [KotlinSemanticChecks.checkers], after the earlier rounds.
 */
internal fun KotlinSemanticChecks.round9PortedCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtNameReferenceExpression::class) { psi ->
        if (resolveReady) report(privateToThisAccess(psi as KtNameReferenceExpression, resolver))
    },
    KotlinChecker(KtCallExpression::class) { psi ->
        if (resolveReady) report(functionValueMissingArguments(psi as KtCallExpression, resolver))
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
