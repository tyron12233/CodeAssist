package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstructorCalleeExpression
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtPackageDirective
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.constructorTypeFqn
import dev.ide.lang.kotlin.resolve.enclosingClassFqn
import dev.ide.lang.kotlin.resolve.enclosingClassMembersContain
import dev.ide.lang.kotlin.resolve.flowProvenTypes
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.isTypeReceiver
import dev.ide.lang.kotlin.resolve.localTypesInScope
import dev.ide.lang.kotlin.resolve.localsAt
import dev.ide.lang.kotlin.resolve.privateInAnotherFile
import dev.ide.lang.kotlin.resolve.receiverForMembers
import dev.ide.lang.kotlin.resolve.scopeMemberExtensions
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind

// --- visibility of project-source members -------------------------------------------------------------------
//
// The rules are the compiler's (FirVisibilityChecker), applied only where the declaration is project SOURCE,
// so its modifiers are read off the PSI exactly. Every question that cannot be answered for sure (a local class
// compared by name, a supertype chain that is not completely known, a receiver typed through a type parameter)
// answers "unknown", and an unknown verdict reports nothing.

private enum class Vis { PUBLIC, PROTECTED, PRIVATE }

/** The declared visibility of a source declaration. `internal` is visible here: the analyzer sees one module.
 *  An `override` without a modifier inherits its visibility from what it overrides, which this does not chase,
 *  so it reads as public (a missed report, never a wrong one). */
private fun visibilityOf(d: KtDeclaration): Vis = when {
    d.hasModifier(KtTokens.PRIVATE_KEYWORD) -> Vis.PRIVATE
    d.hasModifier(KtTokens.PROTECTED_KEYWORD) -> Vis.PROTECTED
    else -> Vis.PUBLIC
}

/** The classes lexically enclosing [e], innermost first. */
private fun enclosingClasses(e: KtElement): List<KtClassOrObject> {
    val out = ArrayList<KtClassOrObject>(2)
    var node: KtElement? = e.parent
    while (node != null) {
        if (node is KtClassOrObject) out += node
        node = node.parent
    }
    return out
}

/** Whether two class declarations are the same one: the same node, or the same qualified name. Null when that
 *  cannot be told (a local or anonymous class seen through two different trees). */
private fun sameClass(a: KtClassOrObject, b: KtClassOrObject): Boolean? {
    if (a === b) return true
    val fa = a.fqName ?: return if (b.fqName != null) false else null
    val fb = b.fqName ?: return false
    return fa == fb
}

/** Whether [chain] holds [owner]; null when some comparison could not be decided. */
private fun lexicallyInside(chain: List<KtClassOrObject>, owner: KtClassOrObject): Boolean? {
    var unknown = false
    for (c in chain) when (sameClass(c, owner)) {
        true -> return true
        null -> unknown = true
        false -> {}
    }
    return if (unknown) null else false
}

/** A companion's members are private or protected to the class that holds the companion (the compiler maps the
 *  owner through `ownerIfCompanion`), so `fromCompanion()` is visible all through the outer class's body. */
private fun effectiveOwner(owner: KtClassOrObject): KtClassOrObject =
    if (owner is KtObjectDeclaration && owner.isCompanion()) owner.getStrictParentOfType<KtClassOrObject>() ?: owner else owner

/** Whether the class [sub] is [sup] or inherits from it; null when [sub]'s supertype chain is not completely
 *  known, so "does not inherit" cannot be concluded. */
internal fun KotlinSemanticChecks.inheritsFrom(sub: String, sup: String): Boolean? {
    if (sub == sup) return true
    // Every class is an `Any` (a Java class's `Object` is the same type), written as a supertype or not.
    if (sup == "kotlin.Any" || sup == "java.lang.Object") return true
    val seen = HashSet<String>()
    val queue = ArrayDeque<String>()
    queue += sub
    while (queue.isNotEmpty()) {
        val t = queue.removeFirst()
        if (!seen.add(t)) continue
        for (s in service.supertypesOf(t)) {
            val q = s.qualifiedName
            if (q == sup) return true
            queue += q
        }
    }
    return if (service.supertypeClosureKnown(sub)) false else null
}

/**
 * Whether a source declaration [decl] of visibility [vis], declared in [owner], can be seen from [useSite].
 * [receiverFqn] is the class of the explicit dispatch receiver, or null when there is none to judge (a type
 * qualifier, a nested class reached through its outer one).
 *
 *  - private: only inside the owner's body (nested classes included), and through a receiver of the owner's
 *    own class: a private member is not inherited, so `b.prv()` with `b: B` (a subclass) does not see it even
 *    inside the owner;
 *  - protected: inside the owner, or inside a subclass of it whose receiver (if any) is that subclass again.
 */
private fun KotlinSemanticChecks.canSee(
    vis: Vis, owner: KtClassOrObject, useSite: KtElement, receiverFqn: String?,
): Boolean? {
    if (vis == Vis.PUBLIC) return true
    val chain = enclosingClasses(useSite)
    val eff = effectiveOwner(owner)
    val inside = lexicallyInside(chain, eff)
    if (vis == Vis.PRIVATE) {
        if (inside != true) return inside
        if (receiverFqn == null || owner is KtObjectDeclaration) return true
        val ownerFqn = owner.fqName?.asString() ?: return null
        return receiverFqn == ownerFqn
    }
    if (inside == true) return true
    val effFqn = eff.fqName?.asString() ?: return null
    var unknown = inside == null
    for (c in chain) {
        val cFqn = c.fqName?.asString()
        if (cFqn == null) { unknown = true; continue }
        when (inheritsFrom(cFqn, effFqn)) {
            null -> unknown = true
            false -> {}
            true -> {
                if (receiverFqn == null || owner is KtObjectDeclaration) return true
                when (inheritsFrom(receiverFqn, cFqn)) {
                    true -> return true
                    null -> unknown = true
                    false -> {}
                }
            }
        }
    }
    return if (unknown) null else false
}

private val VISIBILITY_SUPPRESSIONS = Regex("\"(INVISIBLE_REFERENCE|INVISIBLE_MEMBER)\"")

/**
 * Whether an `@Suppress("INVISIBLE_REFERENCE")` / `@Suppress("INVISIBLE_MEMBER")` on [e], an expression or
 * declaration around it, or the file, turns the compiler's visibility error off. It is how code reaches another
 * module's internals on purpose, and the compiler honours it, so a report there would be wrong.
 */
internal fun visibilitySuppressed(e: KtElement): Boolean {
    var node: KtElement? = e
    while (node != null) {
        val suppressed = if (node is KtAnnotatedExpression) {
            // An annotated expression holds its entries ahead of the expression itself, not in a modifier list.
            val base = node.baseExpression?.textRange?.startOffset ?: node.textRange.endOffset
            val head = node.text.substring(0, (base - node.textRange.startOffset).coerceIn(0, node.text.length))
            "Suppress" in head && VISIBILITY_SUPPRESSIONS.containsMatchIn(head)
        } else {
            node.annotationEntries.any { it.shortName?.asString() == "Suppress" && VISIBILITY_SUPPRESSIONS.containsMatchIn(it.text) }
        }
        if (suppressed) return true
        node = node.parent
    }
    return false
}

/** The outermost class around [c] (itself included) when that is a `private` top-level class, else null. */
private fun privateTopLevelClassOf(c: KtClassOrObject): KtClassOrObject? {
    var top = c
    while (true) top = top.getStrictParentOfType<KtClassOrObject>() ?: break
    return top.takeIf { it.parent is KtFile && it.hasModifier(KtTokens.PRIVATE_KEYWORD) }
}

/** Whether [decl] sits in a different file than [useSite]; files compared by name, as elsewhere here. */
private fun inAnotherFile(decl: KtElement, useSite: KtElement): Boolean {
    val a = (decl.containingFile as? KtFile)?.name?.substringAfterLast('/') ?: return false
    val b = (useSite.containingFile as? KtFile)?.name?.substringAfterLast('/') ?: return false
    return a != b
}

private fun KotlinSymbol.sourceDeclaration(): KtDeclaration? =
    if (origin.fromSource) (declaration() as? KotlinDomNode)?.psi as? KtDeclaration else null

private fun invisible(at: KtElement, name: String, vis: Vis, owner: KtClassOrObject): Diagnostic? {
    if (visibilitySuppressed(at)) return null
    val r = at.textRange
    val what = if (vis == Vis.PRIVATE) "private" else "protected"
    val ownerName = effectiveOwner(owner).name ?: owner.name ?: "<anonymous>"
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Cannot access '$name': it is $what in '$ownerName'", KotlinDiagnosticCodes.INVISIBLE_REFERENCE,
    )
}

/**
 * `a.p()` / `a.p` / `Outer.Nested()` / `outer.Inner()` naming a private or protected member (or nested class)
 * of a project-source class that the use site cannot see. Every declaration the name could mean must be
 * invisible: one visible overload is what the call resolves to.
 */
internal fun KotlinSemanticChecks.memberAccessVisibility(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val parent = ref.parent
    val receiver: KtExpression = when {
        parent is KtQualifiedExpression && parent.selectorExpression === ref -> parent.receiverExpression
        parent is KtCallExpression && parent.calleeExpression === ref ->
            (parent.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === parent }?.receiverExpression
        else -> null
    } ?: return null
    // `super.m()` reaches the supertype's member through the subclass's own instance; its rules are the
    // override's, not the access's.
    if (unwrapParen(receiver) is KtSuperExpression) return null
    val name = ref.getReferencedName()
    if (name.isEmpty()) return null
    val inferred = resolver.inferType(receiver) ?: return null
    val recvType = resolver.receiverForMembers(inferred, receiver.textRange.startOffset) ?: return null
    if (recvType.isTypeParameter) return null
    val typeReceiver = resolver.isTypeReceiver(receiver)
    // A nested class reached through its outer class (`Outer.Nested()`, `outer.Inner()`): judged by the nested
    // declaration's own modifier, with no receiver rule.
    service.sourceClass("${recvType.qualifiedName}.$name")?.let { rc ->
        val cls = (rc.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: return null
        val owner = cls.getStrictParentOfType<KtClassOrObject>() ?: return null
        val vis = visibilityOf(cls)
        return if (canSee(vis, owner, ref, null) == false) invisible(ref, name, vis, owner) else null
    }
    if (!service.isSourceClass(recvType.qualifiedName)) return null
    val members = service.membersNamedForCheck(recvType.qualifiedName, recvType.typeArguments, name).filter { it.name == name } +
        (if (typeReceiver) service.companionMembersFor(recvType.qualifiedName, name).filter { it.name == name } else emptyList())
    if (members.isEmpty() || members.any { it.isExtension || !it.origin.fromSource }) return null
    if (resolver.scopeMemberExtensions(ref.textRange.startOffset, recvType, name, exactName = true).any { it.name == name }) return null
    // Through a type parameter the receiver's class is only a bound; whether the private rule's "own class"
    // holds is not known.
    // A smart-cast receiver keeps the member its declared type sees (`a.prv()` inside `if (a is B)` with
    // `a: A`), which the flow's narrowed type does not tell, so the receiver rule is not applied to one.
    val smartCast = (unwrapParen(receiver) as? KtNameReferenceExpression)
        ?.let { resolver.flowProvenTypes(it, deep = true).isNotEmpty() } == true
    val receiverFqn = if (typeReceiver || smartCast) null else recvType.qualifiedName
    if (receiverFqn != null && inferred.qualifiedName != recvType.qualifiedName) return null
    var first: Pair<Vis, KtClassOrObject>? = null
    for (m in members) {
        val decl = m.sourceDeclaration() ?: return null
        // A member the compiler synthesizes (a data class's `copy`) is indexed against its class: it belongs to
        // that class, and is as visible as the primary constructor, not as the class (whose own visibility the
        // class's type reference already answers for).
        val synthesizedIn = decl as? KtClassOrObject
        val owner = synthesizedIn ?: decl.getStrictParentOfType<KtClassOrObject>() ?: return null
        var vis = if (synthesizedIn != null) {
            (synthesizedIn as? dev.ide.kotlin.syntax.psi.KtClass)?.primaryConstructor?.let(::visibilityOf) ?: Vis.PUBLIC
        } else visibilityOf(decl)
        var hiddenIn = owner
        var verdict = canSee(vis, owner, ref, receiverFqn)
        // A companion's member is no more visible than the companion (`C.baz()` with `private companion object`).
        if (verdict == true && owner is KtObjectDeclaration && owner.isCompanion()) {
            val holder = owner.getStrictParentOfType<KtClassOrObject>()
            if (holder != null && visibilityOf(owner) != Vis.PUBLIC) {
                vis = visibilityOf(owner)
                hiddenIn = holder
                verdict = canSee(vis, holder, ref, null)
            }
        }
        // Nothing inside a class that is private to ANOTHER file is reachable here, through any receiver.
        if (verdict == true) privateTopLevelClassOf(owner)?.takeIf { inAnotherFile(it, ref) }?.let {
            vis = Vis.PRIVATE
            hiddenIn = it
            verdict = false
        }
        if (verdict != false) return null
        if (first == null) first = vis to hiddenIn
    }
    val (vis, owner) = first ?: return null
    return invisible(ref, name, vis, owner)
}

/**
 * A bare name brought in by an explicit import of something NESTED in a project-source class
 * (`import test.Top.Nested` then `Nested()`), where a class on the way is private or protected to a class this
 * code is not inside of: the import compiles, the use does not. Only a name nothing nearer declares (a local, a
 * member of an enclosing class, a declaration of this file) is judged.
 */
internal fun KotlinSemanticChecks.importedNestedVisibility(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val parent = ref.parent
    if (parent is KtQualifiedExpression && parent.selectorExpression === ref) return null
    if (parent is KtCallExpression && parent.calleeExpression === ref &&
        (parent.parent as? KtQualifiedExpression)?.selectorExpression === parent
    ) return null
    if (ref.getStrictParentOfType<KtImportDirective>() != null || ref.getStrictParentOfType<KtPackageDirective>() != null) return null
    val name = ref.getReferencedName()
    if (name.isEmpty()) return null
    val imp = resolver.fileContext.imports.singleOrNull { !it.isStar && it.simpleName == name } ?: return null
    val off = ref.textRange.startOffset
    if (resolver.localsAt(off, name).isNotEmpty() || resolver.enclosingClassMembersContain(off, name)) return null
    if ((ref.containingFile as? KtFile)?.declarations?.any { (it as? KtNamedDeclaration)?.name == name } == true) return null
    // Every class on the import's path below the top level, the leaf included when it is one.
    val segs = imp.fqn.split('.')
    for (i in 2..segs.size) {
        val fqn = segs.take(i).joinToString(".")
        val cls = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: continue
        val owner = cls.getStrictParentOfType<KtClassOrObject>() ?: continue
        val vis = visibilityOf(cls)
        if (canSee(vis, owner, ref, null) == false) return invisible(ref, cls.name ?: name, vis, owner)
    }
    return null
}

/**
 * Whether a bare [name] that resolves to nothing visible names, through this file's package, an explicit
 * import or a star import, a top-level declaration that is `private` in another file: the compiler then says
 * "Cannot access" rather than "Unresolved".
 */
internal fun namesPrivateInAnotherFile(name: String, resolver: KotlinResolver): Boolean {
    val ctx = resolver.fileContext
    val candidates = ArrayList<String>(2)
    ctx.imports.forEach { imp ->
        if (imp.isStar) candidates += "${imp.packageName}.$name"
        else if (imp.simpleName == name) candidates += imp.fqn
    }
    candidates += if (ctx.packageName.isEmpty()) name else "${ctx.packageName}.$name"
    return candidates.any { resolver.privateInAnotherFile(it) }
}

/** The "Cannot access" for a [namesPrivateInAnotherFile] use, or null where an `@Suppress` turns it off (the
 *  compiler then resolves the name). */
internal fun privateInAnotherFileDiagnostic(at: KtElement, name: String): Diagnostic? = privateInFile(at, name)

private fun privateInFile(at: KtElement, name: String): Diagnostic? {
    if (visibilitySuppressed(at)) return null
    val r = at.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Cannot access '$name': it is private in file", KotlinDiagnosticCodes.INVISIBLE_REFERENCE,
    )
}

/**
 * A segment of a qualified type that the use site cannot see: the head of `Private.Public` when `Private` is
 * private to another file, or the `Nested` of `Outer.Nested` when it is a private or protected nested class of
 * a project-source `Outer` that this code is not inside of (or, for protected, a subclass of).
 */
internal fun KotlinSemanticChecks.qualifiedTypeVisibility(userType: KtUserType, resolver: KotlinResolver): Diagnostic? {
    val ref = userType.referenceExpression ?: return null
    val name = ref.getReferencedName()
    if (name.isEmpty()) return null
    val off = userType.textRange.startOffset
    val qualifier = userType.qualifier
    if (qualifier == null) {
        // The head of a qualified type; a simple type on its own is [unresolvedTypeReference]'s.
        if (userType.parent !is KtUserType) return null
        if (resolver.isTypeParameterInScope(name, off) || resolver.localTypesInScope(off).containsKey(name)) return null
        val resolved = service.resolveTypeName(name, resolver.fileContext, resolver.enclosingClassFqn(off)) ?: return null
        if (!resolver.privateInAnotherFile(resolved)) return null
        return privateInAnotherFileDiagnostic(ref, name)
    }
    val qualifierFqn = qualifiedTypeFqn(qualifier, resolver)
    if (qualifierFqn == null) {
        // A package-qualified name (`pkg.Private`): only a private-in-another-file target is judged.
        val pkg = packagePath(qualifier) ?: return null
        return if (resolver.privateInAnotherFile("$pkg.$name")) privateInFile(ref, name) else null
    }
    val rc = service.sourceClass("$qualifierFqn.$name") ?: return null
    val cls = (rc.node as? KotlinDomNode)?.psi as? KtClassOrObject ?: return null
    val owner = cls.getStrictParentOfType<KtClassOrObject>() ?: return null
    val vis = visibilityOf(cls)
    return if (canSee(vis, owner, userType, null) == false) invisible(ref, name, vis, owner) else null
}

/** One constructor of a source class: its visibility and its parameters (null for the implicit one). */
private class Ctor(val vis: Vis, val params: List<KtParameter>)

/**
 * A source class's constructors. A class that writes none has the one its kind gives it: an enum's is
 * private, a sealed class's protected, any other class's public. A written constructor without a modifier
 * takes the same default.
 */
private fun constructorsOf(cls: KtClass): List<Ctor> {
    val default = when {
        cls.isEnum() -> Vis.PRIVATE
        cls.isSealed() -> Vis.PROTECTED
        else -> Vis.PUBLIC
    }
    fun of(d: KtDeclaration) = if (d.hasModifier(KtTokens.PRIVATE_KEYWORD) || d.hasModifier(KtTokens.PROTECTED_KEYWORD) ||
        d.hasModifier(KtTokens.PUBLIC_KEYWORD) || d.hasModifier(KtTokens.INTERNAL_KEYWORD)
    ) visibilityOf(d) else default
    val out = ArrayList<Ctor>()
    cls.primaryConstructor?.let { out += Ctor(of(it), it.valueParameters) }
    cls.secondaryConstructors.forEach { out += Ctor(of(it), it.valueParameters) }
    if (out.isEmpty()) out += Ctor(default, emptyList())
    return out
}

/**
 * Whether [ctor] could take [args]: their count fits its parameters (defaults and a vararg allowed for) and no
 * argument's type is a definite mismatch for its parameter. Named or spread arguments are not matched up, and
 * count as fitting.
 */
private fun KotlinSemanticChecks.mayTake(ctor: Ctor, args: List<KtValueArgument>, cls: KtClass, resolver: KotlinResolver): Boolean {
    if (args.any { it.isNamed() || it.getSpreadElement() != null }) return true
    if (ctor.params.any { it.isVarArg }) return true
    val required = ctor.params.count { !it.hasDefaultValue() }
    if (args.size < required || args.size > ctor.params.size) return false
    val owner = cls.fqName?.asString()
    for ((i, arg) in args.withIndex()) {
        val expr = arg.getArgumentExpression() ?: continue
        val typeText = ctor.params[i].typeReference?.text ?: continue
        val declared = service.typeFromText(typeText, resolver.fileContext, owner) ?: continue
        if (declared.isTypeParameter || cls.typeParameters.any { it.name == declared.qualifiedName }) continue
        // An integer literal never becomes a floating-point value (`MyClass(1)` does not call `(x: Double)`).
        val literal = unwrapParen(expr).text
        if (literal.isNotEmpty() && literal.all { it.isDigit() } &&
            declared.qualifiedName in setOf("kotlin.Double", "kotlin.Float")
        ) return false
        val actual = resolver.inferType(expr) ?: continue
        if (isMismatch(declared, actual)) return false
    }
    return true
}

/**
 * The constructor-visibility verdict for [cls] at [useSite] called with [args]: the diagnostic when every
 * constructor that could take the arguments is hidden. When none could, the call is wrong in another way,
 * which is not this check's to report.
 */
private fun KotlinSemanticChecks.hiddenConstructor(
    cls: KtClass, args: List<KtValueArgument>, useSite: KtElement, at: KtElement, resolver: KotlinResolver,
): Diagnostic? {
    // A companion object may declare (or be given) an `invoke` that `C()` reaches instead of a constructor.
    if (cls.companionObjects.isNotEmpty()) return null
    val all = constructorsOf(cls)
    val ctors = if (all.size == 1) all else all.filter { mayTake(it, args, cls, resolver) }
    if (ctors.isEmpty() || ctors.any { canSee(it.vis, cls, useSite, null) != false }) return null
    if (visibilitySuppressed(at)) return null
    val vis = if (ctors.any { it.vis == Vis.PROTECTED }) Vis.PROTECTED else Vis.PRIVATE
    val r = at.textRange
    val what = if (vis == Vis.PRIVATE) "private" else "protected"
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Cannot access the constructor of '${cls.name}': it is $what in '${cls.name}'", KotlinDiagnosticCodes.INVISIBLE_REFERENCE,
    )
}

/**
 * `Foo(…)` / `Outer.Nested(…)` constructing a project-source class none of whose constructors the call site can
 * see (`class Foo private constructor()`, an enum's implicit private one). Only a call every target of which is a
 * constructor is judged, so a factory function named like the class is never taken for it. Abstract and sealed
 * classes are left out: constructing one is an error of its own, which the compiler reports instead.
 */
internal fun KotlinSemanticChecks.constructorCallVisibility(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val call = ref.parent as? KtCallExpression ?: return null
    if (call.calleeExpression !== ref) return null
    val name = ref.getReferencedName()
    if (name.firstOrNull()?.isUpperCase() != true) return null
    val q = call.parent as? KtQualifiedExpression
    val spelled = if (q != null && q.selectorExpression === call) q.receiverExpression.text + "." + name else name
    val targets = resolver.callTargets(call)
    if (targets.isEmpty() || targets.any { it.kind != SymbolKind.CONSTRUCTOR }) return null
    val fqn = resolver.constructorTypeFqn(spelled, ref.textRange.startOffset) ?: return null
    val cls = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClass ?: return null
    if (cls.isSealed() || cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) || cls.isInterface() || cls.isAnnotation()) return null
    return hiddenConstructor(cls, call.valueArguments, ref, ref, resolver)
}

/** `class Sub : Base(…)` where no constructor of the project-source `Base` is visible to `Sub` (a private one,
 *  seen from outside `Base`'s body). */
internal fun KotlinSemanticChecks.superConstructorVisibility(userType: KtUserType, resolver: KotlinResolver): Diagnostic? {
    val typeRef = userType.parent as? KtTypeReference ?: return null
    val callee = typeRef.parent as? KtConstructorCalleeExpression ?: return null
    val entry = callee.parent as? KtSuperTypeCallEntry ?: return null
    val ref = userType.referenceExpression ?: return null
    val fqn = qualifiedTypeFqn(userType, resolver) ?: return null
    val cls = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClass ?: return null
    if (cls.isInterface() || cls.isEnum()) return null
    return hiddenConstructor(cls, entry.valueArguments, entry, ref, resolver)
}

/** The class a (possibly qualified) user type names, walking out from its head; null when the head is not a
 *  type (a package segment) or a segment names nothing known. */
private fun KotlinSemanticChecks.qualifiedTypeFqn(t: KtUserType, resolver: KotlinResolver): String? {
    val name = t.referenceExpression?.getReferencedName()?.takeIf { it.isNotEmpty() } ?: return null
    val q = t.qualifier
    if (q == null) {
        val off = t.textRange.startOffset
        if (resolver.isTypeParameterInScope(name, off)) return null
        resolver.localTypesInScope(off)[name]?.let { return it }
        return service.resolveTypeName(name, resolver.fileContext, resolver.enclosingClassFqn(off))?.takeIf { service.isKnownType(it) }
    }
    val outer = qualifiedTypeFqn(q, resolver) ?: return null
    return "$outer.$name".takeIf { service.isKnownType(it) }
}

/** `a.b.c` for a qualifier made only of lower-case segments without type arguments, else null. */
private fun packagePath(t: KtUserType): String? {
    val segs = ArrayList<String>()
    var cur: KtUserType? = t
    while (cur != null) {
        if (cur.typeArgumentList != null) return null
        val n = cur.referenceExpression?.getReferencedName() ?: return null
        if (n.isEmpty() || !n[0].isLowerCase()) return null
        segs += n
        cur = cur.qualifier
    }
    return segs.asReversed().joinToString(".")
}

/**
 * INVISIBLE_SETTER: a write to a property whose getter the use site sees but whose setter it does not
 * (`var x = 0; private set` assigned from outside the class, or from a subclass). A write is `p.x = v`, a
 * compound assignment of a built-in value type (`p.x += 1`, which is `p.x = p.x + 1`) and `++`/`--`.
 * The property is a member of a project-source class, through an explicit receiver or, inside a subclass, bare.
 */
internal fun KotlinSemanticChecks.invisibleSetter(ref: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    val parent = ref.parent
    val qualified = (parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === ref }
    val target: KtExpression = qualified ?: ref
    val write = writeKind(target) ?: return null
    val name = ref.getReferencedName()
    val recvType: KotlinType
    val receiverFqn: String?
    if (qualified != null) {
        val receiver = qualified.receiverExpression
        if (unwrapParen(receiver) is KtSuperExpression) return null
        val inferred = resolver.inferType(receiver) ?: return null
        recvType = resolver.receiverForMembers(inferred, receiver.textRange.startOffset) ?: return null
        if (recvType.isTypeParameter || resolver.isTypeReceiver(receiver)) return null
        val smartCast = (unwrapParen(receiver) as? KtNameReferenceExpression)
            ?.let { resolver.flowProvenTypes(it, deep = true).isNotEmpty() } == true
        receiverFqn = if (smartCast || inferred.qualifiedName != recvType.qualifiedName) null else recvType.qualifiedName
    } else {
        // Bare: a member of the innermost enclosing class, read through its `this`, when nothing nearer is named so.
        if (nearestLocalDecl(name, ref.textRange.startOffset, ref) != null) return null
        val cls = ref.getStrictParentOfType<KtClassOrObject>() ?: return null
        if (cls.getStrictParentOfType<KtClassOrObject>() != null) return null
        val fqn = cls.fqName?.asString() ?: return null
        recvType = KotlinType(fqn)
        receiverFqn = fqn
    }
    if (!service.isSourceClass(recvType.qualifiedName)) return null
    val members = service.membersNamedForCheck(recvType.qualifiedName, recvType.typeArguments, name).filter { it.name == name }
    // An override in the receiver's own class is the property there (`private set` narrowing an inherited one).
    val member = members.singleOrNull() ?: members.singleOrNull { m ->
        m.sourceDeclaration()?.getStrictParentOfType<KtClassOrObject>()?.fqName?.asString() == recvType.qualifiedName
    } ?: return null
    if (member.isExtension) return null
    val prop = member.sourceDeclaration() as? KtProperty ?: return null
    if (!prop.isVar) return null
    // A compound assignment writes back through the setter only for a value with no `plusAssign` of its own.
    if (write == WriteKind.COMPOUND && resolver.inferType(target)?.qualifiedName !in VALUE_TYPES_WITHOUT_ASSIGN_OPERATORS) return null
    val owner = prop.getStrictParentOfType<KtClassOrObject>() ?: return null
    // The property itself must be visible: an invisible one is INVISIBLE_REFERENCE's.
    if (canSee(visibilityOf(prop), owner, ref, receiverFqn) != true) return null
    val setter = prop.setter ?: return null
    val vis = visibilityOf(setter)
    if (vis == Vis.PUBLIC || canSee(vis, owner, ref, receiverFqn) != false) return null
    if (visibilitySuppressed(ref)) return null
    val r = ref.textRange
    val what = if (vis == Vis.PRIVATE) "private" else "protected"
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Cannot assign to '$name': its setter is $what in '${effectiveOwner(owner).name ?: owner.name ?: "<anonymous>"}'",
        KotlinDiagnosticCodes.INVISIBLE_REFERENCE,
    )
}

private enum class WriteKind { PLAIN, COMPOUND }

/** How [e] is written, if it is: the left side of `=` or `++`/`--` (plain), or of a compound assignment. */
private fun writeKind(e: KtExpression): WriteKind? {
    val p = e.parent
    if (p is dev.ide.kotlin.syntax.psi.KtUnaryExpression && p.baseExpression === e)
        return WriteKind.PLAIN.takeIf { p.operationToken == KtTokens.PLUSPLUS || p.operationToken == KtTokens.MINUSMINUS }
    val b = p as? dev.ide.kotlin.syntax.psi.KtBinaryExpression ?: return null
    if (b.left !== e) return null
    return when (b.operationToken) {
        KtTokens.EQ -> WriteKind.PLAIN
        in COMPOUND_ASSIGNMENTS -> WriteKind.COMPOUND
        else -> null
    }
}

private val VALUE_TYPES_WITHOUT_ASSIGN_OPERATORS = setOf(
    "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float", "kotlin.Char", "kotlin.String",
)

private val COMPOUND_ASSIGNMENTS = setOf(KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)
