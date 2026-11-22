package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.enclosingClassMembersContain
import dev.ide.lang.kotlin.resolve.enclosingCompanionMember
import dev.ide.lang.kotlin.resolve.implicitReceiversAt
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.kotlin.resolve.localFunctionsInScope
import dev.ide.lang.kotlin.resolve.localTypesInScope
import dev.ide.lang.kotlin.resolve.localsAt

/*
 * `::ext` with no left-hand side, where every top-level callable of that name is an extension: a callable reference
 * to an extension needs its receiver written (`String::ext`), so K2's callable-reference resolution finds no
 * candidate and reports UNRESOLVED_REFERENCE. Not when anything else of that name is in scope (a local, a member of
 * an enclosing class or of an implicit receiver, a companion member, a class whose constructor it names).
 */

internal fun KotlinSemanticChecks.callableReferenceCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtCallableReferenceExpression::class) { psi ->
        KotlinPerf.span("sem.ported.callableRef") {
            if (resolveReady) report(extensionReferenceWithoutReceiver(psi as KtCallableReferenceExpression, resolver))
        }
    },
)

internal fun KotlinSemanticChecks.extensionReferenceWithoutReceiver(ref: KtCallableReferenceExpression, resolver: KotlinResolver): Diagnostic? {
    if (ref.receiverExpression != null) return null
    val callee = ref.callableReference as? KtNameReferenceExpression ?: return null
    val name = callee.getReferencedName()
    if (name.isEmpty()) return null
    val offset = callee.textRange.startOffset
    if (resolver.localsAt(offset, name).isNotEmpty() || resolver.localFunctionsInScope(offset, name).isNotEmpty()) return null
    if (resolver.isTypeParameterInScope(name, offset) || resolver.localTypesInScope(offset).containsKey(name)) return null
    if (resolver.enclosingClassMembersContain(offset, name) || resolver.enclosingCompanionMember(name, offset) != null) return null
    if (resolver.implicitReceiversAt(offset).any { service.membersNamed(it.qualifiedName, it.typeArguments, name).isNotEmpty() }) return null
    if (service.resolveTypeName(name, resolver.fileContext, enclosingClassFqnOf(ref))?.let { service.isKnownType(it) } == true) return null
    val candidates = service.topLevelByName(name)
    fun extension(s: dev.ide.lang.kotlin.symbols.KotlinSymbol): Boolean =
        s.isExtension || s.receiverTypeParam != null || s.receiverTypeFqn != null ||
            ((s.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? dev.ide.kotlin.syntax.psi.KtCallableDeclaration)
                ?.receiverTypeReference != null
    // The file being edited is not in the project model yet: its own top-level declarations are read here.
    val sameFile = resolver.ktFile.declarations.filter { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }
    if (sameFile.any { (it as? dev.ide.kotlin.syntax.psi.KtCallableDeclaration)?.receiverTypeReference == null }) return null
    if ((candidates.isEmpty() && sameFile.isEmpty()) || candidates.any { !extension(it) }) return null
    // An import alias or a same-named declaration the model does not list keeps it out of reach of this check.
    if (resolver.ktFile.importDirectives.any { it.aliasName == name }) return null
    val r = callee.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Unresolved reference: '$name' is an extension, and a reference to it needs its receiver type (`Type::$name`)",
        KotlinDiagnosticCodes.UNRESOLVED,
    )
}
