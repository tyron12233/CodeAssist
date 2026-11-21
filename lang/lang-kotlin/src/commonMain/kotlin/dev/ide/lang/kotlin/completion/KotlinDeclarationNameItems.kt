package dev.ide.lang.kotlin.completion

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtPackageDirective
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParameterList
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionRelevance
import dev.ide.lang.completion.PrefixMatcher
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.bareNameResolves
import dev.ide.lang.kotlin.resolve.companionMemberInScope
import dev.ide.lang.kotlin.resolve.implicitReceiversAt
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.receiverForMembers
import dev.ide.lang.kotlin.resolve.scopeMemberExtensions
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType

/**
 * The names IntelliJ offers for a declaration being named, besides the receiver types and `name: Type` pairs
 * the rest of completion offers there:
 *
 *  - the names of the references nearby that resolve to nothing (`fun <caret>` above a call of `helper()` that
 *    does not exist yet offers `helper`), the "create from usage" shortcut of K2's
 *    `K2DeclarationFromUnresolvedNameContributor`;
 *  - for a top-level class, object or interface, the file's own name when no class in the file has it yet
 *    (`K2SameAsFileClassifierNameCompletionContributor`).
 *
 * Bounded: one walk over the scope the new declaration is visible in (its block, class, function or file),
 * a syntactic filter first, and at most [MAX_NAMES] distinct names resolved.
 */
internal class KotlinDeclarationNameItems(
    private val service: KotlinSymbolService,
    private val resolver: KotlinResolver,
) {

    fun items(leaf: KtElement?, offset: Int, matcher: PrefixMatcher, fileName: String): List<CompletionItem> {
        val decl = leaf?.parent as? KtNamedDeclaration ?: return emptyList()
        if (decl.nameIdentifier !== leaf) return emptyList()
        val out = ArrayList<CompletionItem>()
        sameAsFileName(decl, matcher, fileName)?.let { out += it }
        out += fromUnresolvedNames(decl, offset, matcher)
        return out
    }

    private fun sameAsFileName(decl: KtNamedDeclaration, matcher: PrefixMatcher, fileName: String): CompletionItem? {
        if (decl !is KtClassOrObject || decl.parent !is KtFile) return null
        val name = fileName.substringAfterLast('/').substringBefore('.')
        if (name.isEmpty() || !name[0].isUpperCase() || !name.all { it.isLetterOrDigit() || it == '_' }) return null
        if (!matcher.matches(name) || !firstLetterCaseAgrees(matcher.prefix, name)) return null
        val file = decl.parent as KtFile
        if (file.declarations.any { it is KtClassOrObject && it.name == name }) return null
        return CompletionItem(name, name, CompletionItemKind.CLASS, relevance = CompletionRelevance(proximity = 0))
    }

    private fun fromUnresolvedNames(decl: KtNamedDeclaration, offset: Int, matcher: PrefixMatcher): List<CompletionItem> {
        if (decl is KtParameter && (decl.parent as? KtParameterList)?.parent !is KtNamedFunction &&
            (decl.parent as? KtParameterList)?.parent !is KtPrimaryConstructor &&
            (decl.parent as? KtParameterList)?.parent !is KtSecondaryConstructor
        ) return emptyList()
        val scope = referenceScope(decl) ?: return emptyList()
        val shape = Shape.of(decl) ?: return emptyList()
        // Names declared anywhere in the scope resolve (or at least are not the missing declaration).
        val candidates = LinkedHashMap<String, MutableList<KtNameReferenceExpression>>()
        for (ref in scope.collectReferences()) {
            if (scope is KtBlockExpression && ref.textRange.startOffset < offset) continue
            val name = ref.getReferencedName()
            if (name.isEmpty() || "IntellijIdeaRulezzz" in name) continue
            if (!matcher.matches(name) || !firstLetterCaseAgrees(matcher.prefix, name)) continue
            if (!shape.accepts(ref)) continue
            candidates.getOrPut(name) { ArrayList(1) } += ref
        }
        if (candidates.isEmpty()) return emptyList()
        val receiverType = shape.receiverType(decl)
        val out = ArrayList<CompletionItem>()
        var resolved = 0
        for ((name, refs) in candidates) {
            if (resolved++ >= MAX_NAMES) break
            if (refs.none { unresolvedFits(it, name, shape, receiverType) }) continue
            out += CompletionItem(name, name, shape.kind, relevance = CompletionRelevance(proximity = UNRESOLVED_PROXIMITY))
        }
        return out
    }

    /** Whether [ref] resolves to nothing and a declaration of [shape] would be what it means. */
    private fun unresolvedFits(ref: KtNameReferenceExpression, name: String, shape: Shape, receiverType: KotlinType?): Boolean {
        val at = ref.textRange.startOffset
        val receiver = explicitReceiver(ref)
        if (receiver != null) {
            if (shape.classifier) return false
            val recv = runCatching { resolver.inferType(receiver)?.let { resolver.receiverForMembers(it, receiver.textRange.startOffset) } }
                .getOrNull() ?: return false
            if (receiverType == null || !runCatching { receiverType.isAssignableFrom(recv) }.getOrDefault(false)) return false
            val members = runCatching { service.membersNamed(recv.qualifiedName, recv.typeArguments, name) }.getOrDefault(emptyList())
            if (members.any { it.name == name }) return false
            if (runCatching { resolver.scopeMemberExtensions(at, recv, name, exactName = true) }.getOrDefault(emptyList()).any { it.name == name }) return false
            return !runCatching { resolver.bareNameResolves(name, at) }.getOrDefault(true)
        }
        if (runCatching { resolver.bareNameResolves(name, at) || resolver.companionMemberInScope(name, at) }.getOrDefault(true)) return false
        if (ref.parent is KtUserType) {
            val fqn = runCatching { service.resolveTypeName(name, resolver.fileContext) }.getOrNull()
            if (fqn != null && runCatching { service.isKnownType(fqn) }.getOrDefault(true)) return false
        }
        // An extension is named for a call made where its receiver is the implicit `this`.
        if (!shape.classifier && shape.extension) {
            val ext = receiverType ?: return false
            return runCatching { resolver.implicitReceiversAt(at) }.getOrDefault(emptyList())
                .any { runCatching { ext.isAssignableFrom(it) }.getOrDefault(false) }
        }
        return true
    }

    /** What the declaration being named can stand for at a reference, read off its syntax. */
    private class Shape(
        val kind: CompletionItemKind,
        val classifier: Boolean,
        val function: Boolean,
        val extension: Boolean,
        /** For a class-like declaration: a call `Name()` names a class, a value `Name` an object. */
        val instantiable: Boolean,
        val singleton: Boolean,
    ) {
        fun accepts(ref: KtNameReferenceExpression): Boolean {
            val parent = ref.parent
            if (classifier) {
                if (explicitReceiver(ref) != null) return false
                return when {
                    parent is KtUserType -> true
                    parent is KtCallExpression && parent.calleeExpression === ref -> instantiable
                    parent is KtCallableReferenceExpression -> false
                    else -> singleton
                }
            }
            if (parent is KtUserType) return false
            if (!function) return true
            return parent is KtCallableReferenceExpression || parent is KtCallExpression && parent.calleeExpression === ref
        }

        companion object {
            fun of(decl: KtNamedDeclaration): Shape? = when (decl) {
                is KtClassOrObject -> {
                    val obj = decl is KtObjectDeclaration
                    val iface = (decl as? KtClass)?.isInterface() == true
                    Shape(CompletionItemKind.CLASS, classifier = true, function = false, extension = false,
                        instantiable = !obj && !iface, singleton = obj)
                }
                is KtNamedFunction -> Shape(CompletionItemKind.METHOD, false, function = true,
                    extension = decl.receiverTypeReference != null, instantiable = false, singleton = false)
                is KtCallableDeclaration -> Shape(CompletionItemKind.VARIABLE, false, function = false,
                    extension = decl.receiverTypeReference != null, instantiable = false, singleton = false)
                is KtParameter -> Shape(CompletionItemKind.PARAMETER, false, function = false,
                    extension = false, instantiable = false, singleton = false)
                else -> null
            }
        }
    }

    /** The type a reference's explicit receiver must be of: the extension receiver, else the class holding a
     *  member. */
    private fun Shape.receiverType(decl: KtNamedDeclaration): KotlinType? {
        val callable = decl as? KtCallableDeclaration ?: return null
        callable.receiverTypeReference?.let { ref ->
            return runCatching { service.typeFromText(ref.text, resolver.fileContext) }.getOrNull()
        }
        val owner = (decl.parent as? KtClassBody)?.parent as? KtClassOrObject ?: return null
        val fqn = owner.fqName?.asString() ?: return null
        return runCatching { service.typeByFqn(fqn) }.getOrNull()
    }

    private companion object {
        /** A bound on the distinct names resolved per request. */
        const val MAX_NAMES = 64

        /** Below every declared symbol: IntelliJ gives these the lowest item priority. */
        const val UNRESOLVED_PROXIMITY = 1_000

        /** The scope the new declaration is visible in, which its uses are looked for in (K2's `referenceScope`). */
        fun referenceScope(decl: KtNamedDeclaration): KtElement? = when (val parent = decl.parent) {
            is KtParameterList -> parent.parent as? KtElement
            is KtClassBody -> (parent.parent as? KtClassOrObject)?.let { c ->
                if (c is KtObjectDeclaration && c.isCompanion()) c.getStrictParentOfType<KtClassOrObject>() ?: c else c
            }
            is KtFile -> parent
            is KtBlockExpression -> parent
            else -> null
        }

        fun explicitReceiver(ref: KtNameReferenceExpression): KtExpression? {
            val selector: KtElement = (ref.parent as? KtCallExpression)?.takeIf { it.calleeExpression === ref } ?: ref
            val q = selector.parent as? KtDotQualifiedExpression ?: return null
            return if (q.selectorExpression === selector) q.receiverExpression else null
        }

        /** The name references under [this], outside `package`/`import` directives. */
        fun KtElement.collectReferences(): List<KtNameReferenceExpression> {
            val out = ArrayList<KtNameReferenceExpression>()
            fun walk(e: KtElement) {
                if (e is KtPackageDirective || e is KtImportDirective) return
                if (e is KtNameReferenceExpression) out += e
                for (c in e.children) walk(c)
            }
            walk(this)
            return out
        }
    }
}
