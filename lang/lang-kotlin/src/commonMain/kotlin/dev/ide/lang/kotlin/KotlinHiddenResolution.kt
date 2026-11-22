package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
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

/** Whether the bare [name] read at [offset] reaches only HIDDEN-deprecated declarations of this file. */
internal fun KotlinSemanticChecks.onlyHiddenCandidates(name: String, offset: Int, at: KtElement, resolver: KotlinResolver): Boolean {
    val hidden = resolver.caches.hiddenNames ?: collectHiddenNames(resolver).also { resolver.caches.hiddenNames = it }
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

private fun collectHiddenNames(resolver: KotlinResolver): Set<String> {
    var out: HashSet<String>? = null
    fun visit(decls: List<KtDeclaration>) {
        for (d in decls) {
            if ((d is KtNamedFunction || d is KtProperty) && isHidden(d)) (d as KtNamedDeclaration).name?.let { (out ?: HashSet<String>().also { h -> out = h }) += it }
            if (d is KtClassOrObject) visit(d.declarations)
        }
    }
    visit(resolver.ktFile.declarations)
    return out ?: emptySet()
}
