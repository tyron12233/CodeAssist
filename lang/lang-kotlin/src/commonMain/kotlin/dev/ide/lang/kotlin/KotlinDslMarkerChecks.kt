package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enclosingCallAndParamIndex
import dev.ide.lang.kotlin.resolve.lambdaParamIndex
import dev.ide.lang.kotlin.resolve.lambdaReceiverType
import dev.ide.lang.kotlin.resolve.memberNamed
import dev.ide.lang.kotlin.resolve.resolveCalleeFunction
import dev.ide.lang.kotlin.resolve.localsAt
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind

/**
 * DSL_SCOPE_VIOLATION: inside nested DSL lambdas, a bare member call (`a()`) that binds to an OUTER implicit
 * receiver when a closer implicit receiver carries the same `@DslMarker` annotation. That is what keeps
 * `html { body { head { } } }` from reaching `html`'s `head` from inside `body`.
 *
 * A receiver's markers are the `@DslMarker` annotations on its class (or any supertype), and those written on the
 * receiver type of the function type the lambda fills (`(@L1 A).() -> Unit`). Only markers this file's project
 * declares are recognised, since a library annotation's own `@DslMarker` meta-annotation is not modelled; a
 * library DSL is left alone. Plain members only: a member EXTENSION binds two receivers at once and is skipped.
 */
internal fun KotlinSemanticChecks.dslScopeViolation(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    val parent = call.parent
    if (parent is KtQualifiedExpression && parent.selectorExpression === call) return null
    val name = callee.getReferencedName()
    if (resolver.localsAt(callee.textRange.startOffset, name).isNotEmpty()) return null
    val scopes = receiverScopes(call, resolver)
    if (scopes.size < 2) return null
    val bound = scopes.indexOfFirst { s ->
        resolver.memberNamed(s.type, name)?.let { it.kind == SymbolKind.METHOD && !it.isExtension } == true
    }
    if (bound <= 0) return null
    val boundMarkers = scopes[bound].markers
    if (boundMarkers.isEmpty()) return null
    if (scopes.subList(0, bound).none { inner -> inner.markers.any { it in boundMarkers } }) return null
    val r = callee.textRange
    return Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "'$name' can't be called in this context by implicit receiver. Use the explicit one if necessary",
        KotlinDiagnosticCodes.DSL_SCOPE_VIOLATION,
    )
}

private class ReceiverScope(val type: KotlinType, val markers: Set<String>)

private val MARKER_ANNOTATION = Regex("""@([A-Za-z_][A-Za-z0-9_.]*)""")

/** The receiver lambdas around [at], innermost first, each with its receiver type and DSL markers. Stops at a
 *  class or a named function: past one, receivers of the enclosing lambdas belong to another scope. */
private fun KotlinSemanticChecks.receiverScopes(at: KtElement, resolver: KotlinResolver): List<ReceiverScope> {
    val out = ArrayList<ReceiverScope>()
    var node: KtElement? = at.parent as? KtElement
    while (node != null) {
        when (node) {
            is KtLambdaExpression -> resolver.lambdaReceiverType(node)?.let { rt ->
                out += ReceiverScope(rt, typeUsageMarkers(node, resolver) + classMarkers(rt.qualifiedName, resolver))
            }
            // An extension function's receiver is the next implicit receiver out (`fun A.test() { bar { a() } }`),
            // with the markers its class carries and those written on the receiver type (`fun (@L1 A).test()`).
            is KtNamedFunction -> {
                val ref = node.receiverTypeReference ?: return out
                if (node.name == null) return out
                service.typeFromText(ref.text, resolver.fileContext)?.let { rt ->
                    val written = MARKER_ANNOTATION.findAll(ref.text).map { it.groupValues[1].substringAfterLast('.') }
                        .filter { isDslMarker(it, resolver) }.toSet()
                    out += ReceiverScope(rt, written + classMarkers(rt.qualifiedName, resolver))
                }
                return out
            }
            is KtClassOrObject -> return out
            else -> {}
        }
        node = node.parent as? KtElement
    }
    return out
}

/** Markers written on the receiver type of the parameter [lambda] fills: the `@L1` of `(@L1 A).() -> Unit`. */
private fun KotlinSemanticChecks.typeUsageMarkers(lambda: KtLambdaExpression, resolver: KotlinResolver): Set<String> {
    val (call, argIndex) = resolver.enclosingCallAndParamIndex(lambda) ?: return emptySet()
    val sym = resolver.resolveCalleeFunction(call) ?: return emptySet()
    val fn = (sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return emptySet()
    val param = fn.valueParameters.getOrNull(resolver.lambdaParamIndex(call, argIndex, sym)) ?: return emptySet()
    val text = param.typeReference?.text ?: return emptySet()
    val receiverPart = text.substringBefore(".(", missingDelimiterValue = "")
    if (receiverPart.isEmpty()) return emptySet()
    return Regex("""@([A-Za-z_][A-Za-z0-9_.]*)""").findAll(receiverPart)
        .map { it.groupValues[1].substringAfterLast('.') }
        .filter { isDslMarker(it, resolver) }.toSet()
}

/** Markers on the receiver's class declaration and its supertypes' (project classes only). */
private fun KotlinSemanticChecks.classMarkers(fqn: String, resolver: KotlinResolver): Set<String> {
    val out = HashSet<String>()
    for (owner in listOf(fqn) + service.supertypesOf(fqn).map { it.qualifiedName }) {
        val cls = (service.classDeclaration(owner) as? KotlinDomNode)?.psi as? KtClassOrObject ?: continue
        cls.annotationEntries.mapNotNull { it.shortName?.asString() }.filterTo(out) { isDslMarker(it, resolver) }
    }
    return out
}

/** Whether the annotation [simpleName] names a project annotation class itself annotated `@DslMarker`. */
private fun KotlinSemanticChecks.isDslMarker(simpleName: String, resolver: KotlinResolver): Boolean {
    val fqn = service.resolveTypeName(simpleName, resolver.fileContext) ?: return false
    val cls = (service.classDeclaration(fqn) as? KotlinDomNode)?.psi as? KtClassOrObject ?: return false
    return cls.annotationEntries.any { it.shortName?.asString() == "DslMarker" }
}
