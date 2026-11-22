package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtUnaryExpression
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.constructorTypeFqn
import dev.ide.lang.kotlin.resolve.implicitReceiversAt
import dev.ide.lang.kotlin.resolve.localsAt

/*
 * A `@Deprecated(level = DeprecationLevel.HIDDEN)` function or property is invisible to resolution (K2 drops the
 * candidate in FirDeprecationChecker's resolution stage, `CheckHiddenDeclaration`), so a bare reference whose every
 * candidate is hidden is UNRESOLVED_REFERENCE. Judged only for declarations of this file, whose annotations are at
 * hand: a name that any other source (an import, another file, the classpath, a local) also declares resolves.
 */

/** Whether the bare [name] read at [offset] reaches only HIDDEN-deprecated declarations of this file. A property
 *  whose getter alone is HIDDEN is hidden to a read, one whose setter alone is HIDDEN to a plain assignment
 *  (FirDeprecationChecker judges the accessor the use calls). */
internal fun KotlinSemanticChecks.onlyHiddenCandidates(name: String, offset: Int, at: KtElement, resolver: KotlinResolver): Boolean {
    val hidden = resolver.caches.hiddenNames ?: collectHiddenNames(resolver).also { resolver.caches.hiddenNames = it }
    val use = accessorUse(at)
    fun isHidden(d: KtDeclaration) = isHiddenFor(d, use)
    if (name !in hidden) return false
    if (resolver.localsAt(offset, name).isNotEmpty()) return false
    // A class of that name in scope makes the call a constructor call, which the hidden function does not hide.
    if (resolver.constructorTypeFqn(name, offset) != null) return false
    val file = resolver.ktFile
    var any = false
    for (d in file.declarations) {
        if (d is KtClassOrObject && d.name == name) return false
        if ((d is KtNamedFunction || d is KtProperty) && (d as KtNamedDeclaration).name == name) {
            if (!isHidden(d)) return false
            any = true
        }
    }
    // The enclosing classes of the live buffer, then every member the implicit receivers offer by that name.
    var node: KtElement? = at
    while (node != null) {
        if (node is KtClassOrObject) {
            for (d in node.declarations) {
                if ((d is KtNamedFunction || d is KtProperty) && (d as KtNamedDeclaration).name == name) {
                    if (!isHidden(d)) return false
                    any = true
                }
            }
        }
        node = node.parent
    }
    for (recv in resolver.implicitReceiversAt(offset)) {
        for (m in service.membersNamed(recv.qualifiedName, recv.typeArguments, name)) {
            if (m.name != name) continue
            val psi = (m.declaration() as? KotlinDomNode)?.psi as? KtDeclaration ?: return false
            if (psi.containingFile !== file || !isHidden(psi)) return false
            any = true
        }
    }
    if (!any) return false
    // Anything this file does not declare that might carry the name.
    if (resolver.fileContext.imports.any { !it.isStar && (it.alias ?: it.simpleName) == name && it.fqn.substringBeforeLast('.', "") != resolver.fileContext.packageName }) return false
    if (service.topLevelByName(name).any { (it.declaration() as? KotlinDomNode)?.psi?.containingFile !== file }) return false
    return true
}

private fun isHidden(d: KtDeclaration): Boolean =
    d.annotationEntries.any { it.shortName?.asString() == "Deprecated" && it.text.contains("HIDDEN") }

private val COMPOUND_ASSIGN = setOf(KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)

private enum class AccessorUse { READ, WRITE, READ_WRITE }

/** Which accessors a reference calls: a plain `x = v` the setter, a compound `x += v` / `x++` both, else the getter. */
private fun accessorUse(at: KtElement): AccessorUse {
    var e: KtElement = at
    var p = e.parent
    // `recv.p = v`: the assignment's target is the qualified expression the selector ends.
    if (p is KtQualifiedExpression && p.selectorExpression === e) { e = p; p = p.parent }
    while (p is KtParenthesizedExpression) { e = p; p = p.parent }
    return when {
        p is KtBinaryExpression && p.left === e && p.operationToken == KtTokens.EQ -> AccessorUse.WRITE
        p is KtBinaryExpression && p.left === e && p.operationToken in COMPOUND_ASSIGN -> AccessorUse.READ_WRITE
        (p is KtUnaryExpression) && p.baseExpression === e &&
            (p.operationToken == KtTokens.PLUSPLUS || p.operationToken == KtTokens.MINUSMINUS) -> AccessorUse.READ_WRITE
        else -> AccessorUse.READ
    }
}

/** [isHidden], or the accessor [use] calls is HIDDEN. A compound use is judged by the property alone. */
private fun isHiddenFor(d: KtDeclaration, use: AccessorUse): Boolean {
    if (isHidden(d)) return true
    val prop = d as? KtProperty ?: return false
    return when (use) {
        AccessorUse.READ -> prop.getter?.let(::isHidden) == true
        AccessorUse.WRITE -> prop.setter?.let(::isHidden) == true
        AccessorUse.READ_WRITE -> false
    }
}

private fun collectHiddenNames(resolver: KotlinResolver): Set<String> {
    var out: HashSet<String>? = null
    fun visit(decls: List<KtDeclaration>) {
        for (d in decls) {
            if ((d is KtNamedFunction || d is KtProperty) && (isHidden(d) || (d as? KtProperty)?.accessors?.any(::isHidden) == true)) (d as KtNamedDeclaration).name?.let { (out ?: HashSet<String>().also { h -> out = h }) += it }
            if (d is KtClassOrObject) visit(d.declarations)
        }
    }
    visit(resolver.ktFile.declarations)
    return out ?: emptySet()
}

/** Whether the member or extension [sym] that the reference [at] reaches is a project declaration hidden from it
 *  (`@Deprecated(level = HIDDEN)` on it, or on the accessor the use calls). */
internal fun hiddenFromUse(sym: dev.ide.lang.kotlin.symbols.KotlinSymbol, at: KtElement): Boolean {
    val psi = (sym.declaration() as? KotlinDomNode)?.psi as? KtDeclaration ?: return false
    return isHiddenFor(psi, accessorUse(at))
}
