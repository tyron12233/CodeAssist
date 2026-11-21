package dev.ide.lang.kotlin.completion

import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind

/**
 * Whether a candidate's `private`/`protected` visibility lets the completion site use it, by the compiler's
 * rules as IntelliJ's completion applies them on a first invocation:
 *
 *  - private: a class member only inside the body of the class that declares it (a companion's member inside
 *    the class that holds the companion); a top-level declaration only in its own file;
 *  - protected: inside the declaring class or a subclass of it (a companion's member: of the class holding it);
 *  - a project declaration deprecated with `DeprecationLevel.HIDDEN` is not visible anywhere.
 *
 * A source declaration is judged off its PSI, and a class named directly by every class around it too, so a
 * public class nested in a private one is as hidden as its container (a member is reached through a receiver,
 * so only its own modifier counts). A binary one is judged off its modifiers and declaring class. Any
 * question that cannot be answered (a local class, an unknown supertype chain) keeps the candidate: a missing
 * visible item costs more than an extra invisible one.
 *
 * Work is bounded: a candidate's own PSI parents are walked, and the supertype walks (only for a `protected`
 * one) start from the few classes around the caret and are memoized per declaring class.
 */
internal class KotlinCompletionVisibility(
    anchor: KtElement?,
    private val service: KotlinSymbolService,
    private val filePath: String?,
    private val filePackage: String,
) {
    /** The classes around the caret, innermost first. */
    private val enclosing: List<KtClassOrObject> =
        generateSequence(anchor?.parent) { it.parent }.filterIsInstance<KtClassOrObject>().toList()
    private val enclosingFqns: Set<String> = enclosing.mapNotNullTo(HashSet()) { it.fqName?.asString() }
    private val enclosingNames: Set<String> = enclosing.mapNotNullTo(HashSet()) { it.name }
    private val subclassMemo = HashMap<String, Boolean>()

    /** Whether [s] may be used at the caret as far as its declared visibility goes. */
    fun visible(s: KotlinSymbol): Boolean {
        val decl = (s.declaration() as? KotlinDomNode)?.psi as? KtDeclaration
        if (decl != null && s.origin.fromSource) return sourceVisible(s, decl)
        // A project class found through the class-name index arrives without its declaration; the source model
        // has it under the same name.
        if (s.kind in CLASSIFIER_KINDS) {
            val fqn = (s.type as? KotlinType)?.qualifiedName
            val source = fqn?.let { runCatching { service.classDeclaration(it) }.getOrNull() as? KotlinDomNode }?.psi as? KtDeclaration
            if (source != null) return sourceVisible(s, source)
            // Without its tree, the source model still knows a file-private top-level class.
            val rc = fqn?.let { runCatching { service.sourceClass(it) }.getOrNull() }
            if (rc != null && rc.isPrivate && fqn.substringBeforeLast('.', "") == rc.ctx.packageName && filePath != null &&
                rc.ctx.path.substringAfterLast('/') != filePath.substringAfterLast('/')
            ) return false
        }
        val private = Modifier.PRIVATE in s.modifiers
        val protected = Modifier.PROTECTED in s.modifiers
        if (!private && !protected) return !javaPackagePrivateElsewhere(s)
        val owner = binaryOwner(s) ?: return if (private) s.owner == null || s.owner?.name in enclosingNames else true
        if (private) return owner in enclosingFqns || owner.substringAfterLast('.') in enclosingNames
        // A Java `protected` member is also visible to its own package.
        if (owner.substringBeforeLast('.', "") == filePackage) return true
        return insideOrSubclassOf(owner)
    }

    /** A static member of a Java class with no access modifier is package-private: invisible from another
     *  package. (Only statics: a synthetic property made from a getter carries no access modifier at all.) */
    private fun javaPackagePrivateElsewhere(s: KotlinSymbol): Boolean {
        if (s.origin.fromSource || Modifier.STATIC !in s.modifiers || Modifier.PUBLIC in s.modifiers) return false
        if (s.kind != SymbolKind.FIELD && s.kind != SymbolKind.METHOD) return false
        val owner = s.declaringClassFqn?.replace('$', '.') ?: return false
        if (owner.substringBeforeLast('.', "") == filePackage) return false
        return javaOwners.getOrPut(owner) { runCatching { service.isJavaType(owner) }.getOrDefault(false) }
    }

    private val javaOwners = HashMap<String, Boolean>()

    private fun sourceVisible(s: KotlinSymbol, decl: KtDeclaration): Boolean {
        // `@Deprecated(level = DeprecationLevel.HIDDEN)` takes a declaration out of resolution altogether.
        if (decl.annotationEntries.any { it.text.startsWith("@Deprecated") && "HIDDEN" in it.text }) return false
        var cur: KtDeclaration = decl
        while (true) {
            val container = cur.getStrictParentOfType<KtClassOrObject>()
            val private = cur.hasModifier(KtTokens.PRIVATE_KEYWORD)
            val protected = cur.hasModifier(KtTokens.PROTECTED_KEYWORD)
            if (container == null) {
                // A private top-level declaration belongs to its file.
                if (private && filePath != null) {
                    val file = s.origin.file?.path ?: cur.containingKtFile.name
                    if (file.substringAfterLast('/') != filePath.substringAfterLast('/')) return false
                }
                return true
            }
            if (private || protected) {
                val owner = effectiveOwner(container)
                val fqn = owner.fqName?.asString() ?: return true
                val inside = fqn in enclosingFqns
                if (private && !inside) return false
                if (protected && !inside && !insideOrSubclassOf(fqn)) return false
            }
            // A member is reached through a receiver, so its class's own visibility does not matter (a public
            // class exposes what it inherits from a private interface); a class named directly needs every class
            // around it to be visible too.
            if (s.kind !in CLASSIFIER_KINDS) return true
            cur = container
        }
    }

    /** Whether the caret is inside [ownerFqn] or a class that inherits from it; an unknown chain answers yes. */
    private fun insideOrSubclassOf(ownerFqn: String): Boolean = subclassMemo.getOrPut(ownerFqn) {
        if (ownerFqn in enclosingFqns) return@getOrPut true
        for (c in enclosing) {
            val fqn = c.fqName?.asString() ?: return@getOrPut true
            if (inheritsFrom(fqn, ownerFqn) != false) return@getOrPut true
        }
        false
    }

    private fun inheritsFrom(sub: String, sup: String): Boolean? {
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue += sub
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (t == sup) return true
            if (!seen.add(t) || seen.size > MAX_SUPERTYPES) continue
            for (st in runCatching { service.supertypesOf(t) }.getOrDefault(emptyList())) queue += st.qualifiedName
        }
        return if (runCatching { service.supertypeClosureKnown(sub) }.getOrDefault(false)) false else null
    }

    /** The class a binary member belongs to for visibility: its declaring class, or the class holding the
     *  companion object that declares it. */
    private fun binaryOwner(s: KotlinSymbol): String? {
        val declaring = s.declaringClassFqn?.replace('$', '.')
            ?: ((s.owner as? KotlinSymbol)?.type as? KotlinType)?.qualifiedName
            ?: return null
        return declaring.removeSuffix(".Companion")
    }

    private companion object {
        /** A bound on one supertype walk; a real hierarchy is far shallower. */
        const val MAX_SUPERTYPES = 200

        val CLASSIFIER_KINDS = setOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM, SymbolKind.ANNOTATION_TYPE)

        /** A companion's members are private or protected to the class that holds the companion. */
        fun effectiveOwner(owner: KtClassOrObject): KtClassOrObject =
            if (owner is KtObjectDeclaration && owner.isCompanion()) owner.getStrictParentOfType<KtClassOrObject>() ?: owner else owner
    }
}
