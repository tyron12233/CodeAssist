package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.highlight.SemanticToken
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.platform.EngineCancellation
import dev.ide.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtTypeParameter
import org.jetbrains.kotlin.psi.KtTypeParameterListOwner
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Type-aware Kotlin coloring. One PSI walk over the live parse (the same model
 * [KotlinSourceAnalyzer.semanticDiagnostics] uses), emitting a [SemanticToken] per identifier it can
 * classify CONFIDENTLY off the parse-only symbol model:
 *  - declarations (classes/objects, functions, properties, parameters, type parameters) — exact from PSI;
 *  - call sites resolved to a function — with the Kotlin distinctions the user asked for:
 *    `@Composable` (Android-Studio style), extension, and `suspend`;
 *  - local variables / parameters in use, with `var` (mutable) vs `val` (read-only);
 *  - type references, separating a type parameter (`T`) from a class.
 *
 * Anything it can't resolve cleanly (a member/top-level property read, an unresolved name) is left to the
 * lexical layer — the highlighter never guesses, so it never miscolors. Polls [EngineCancellation] between
 * nodes so completion can preempt the pass; the host retries.
 */
class KotlinSemanticHighlighter(
    private val parsedFor: (VirtualFile) -> KotlinParsedFile?,
    private val resolverFor: (KotlinParsedFile) -> KotlinResolver,
    private val refresh: () -> Unit,
) : SemanticHighlightService {

    override suspend fun highlight(file: VirtualFile): List<SemanticToken> {
        val parsed = parsedFor(file) ?: return emptyList()
        refresh() // cross-file freshness: a decl just typed in another open file resolves here
        val resolver = resolverFor(parsed)
        val out = ArrayList<SemanticToken>(256)
        var seen = 0
        fun emit(range: org.jetbrains.kotlin.com.intellij.openapi.util.TextRange?, kind: HighlightKind, mods: Set<HighlightModifier> = emptySet()) {
            if (range == null) return
            out += SemanticToken(TextRange(range.startOffset, range.endOffset), kind, mods)
        }
        fun walk(psi: PsiElement) {
            if (seen++ % 64 == 0) EngineCancellation.checkCanceled()
            when (psi) {
                is KtObjectDeclaration ->
                    emit(psi.nameIdentifier?.textRange, HighlightKind.OBJECT, setOf(HighlightModifier.DECLARATION))
                is KtClass ->
                    emit(psi.nameIdentifier?.textRange, classKind(psi), setOf(HighlightModifier.DECLARATION))
                is KtNamedFunction ->
                    emit(psi.nameIdentifier?.textRange, HighlightKind.FUNCTION,
                        declMods(HighlightModifier.DECLARATION) + functionMods(psi) + extensionIf(psi.receiverTypeReference != null))
                is KtProperty -> {
                    val local = psi.parent is KtBlockExpression
                    emit(psi.nameIdentifier?.textRange, if (local) HighlightKind.LOCAL_VARIABLE else HighlightKind.PROPERTY,
                        declMods(HighlightModifier.DECLARATION) + mutability(psi.isVar) + extensionIf(psi.receiverTypeReference != null))
                }
                is KtParameter ->
                    emit(psi.nameIdentifier?.textRange, HighlightKind.PARAMETER,
                        declMods(HighlightModifier.DECLARATION) + paramMutability(psi))
                is KtTypeParameter ->
                    emit(psi.nameIdentifier?.textRange, HighlightKind.TYPE_PARAMETER, setOf(HighlightModifier.DECLARATION))
                is KtCallExpression -> classifyCall(psi, resolver, ::emit)
                is KtUserType -> classifyTypeRef(psi, ::emit)
                is KtNameReferenceExpression -> classifyReference(psi, ::emit)
                else -> {}
            }
            var c = psi.firstChild
            while (c != null) { walk(c); c = c.nextSibling }
        }
        walk(parsed.ktFile)
        return out
    }

    private fun classKind(c: KtClass): HighlightKind = when {
        c.isInterface() -> HighlightKind.INTERFACE
        c.isEnum() -> HighlightKind.ENUM
        c.isAnnotation() -> HighlightKind.ANNOTATION
        else -> HighlightKind.CLASS
    }

    private fun functionMods(fn: KtNamedFunction): Set<HighlightModifier> {
        val m = HashSet<HighlightModifier>(2)
        if (fn.annotationEntries.any { it.shortName?.asString() == "Composable" }) m += HighlightModifier.COMPOSABLE
        if (fn.hasModifier(KtTokens.SUSPEND_KEYWORD)) m += HighlightModifier.SUSPEND
        return m
    }

    private fun declMods(vararg m: HighlightModifier): Set<HighlightModifier> = m.toSet()
    private fun extensionIf(b: Boolean): Set<HighlightModifier> = if (b) setOf(HighlightModifier.EXTENSION) else emptySet()
    private fun mutability(isVar: Boolean): Set<HighlightModifier> =
        setOf(if (isVar) HighlightModifier.MUTABLE else HighlightModifier.READONLY)

    /** A primary-constructor `val`/`var` property param is read-only/mutable; a plain param is neither. */
    private fun paramMutability(p: KtParameter): Set<HighlightModifier> = when {
        p.hasValOrVar() && p.isMutable -> setOf(HighlightModifier.MUTABLE)
        p.hasValOrVar() -> setOf(HighlightModifier.READONLY)
        else -> emptySet()
    }

    /** Classify a call's callee: a resolved function (+ composable/extension/suspend), or a capitalized
     *  constructor invocation. The callee is also a name reference, so [classifyReference] skips call callees. */
    private fun classifyCall(call: KtCallExpression, resolver: KotlinResolver, emit: (org.jetbrains.kotlin.com.intellij.openapi.util.TextRange?, HighlightKind, Set<HighlightModifier>) -> Unit) {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return
        val sym = resolver.calleeFunctionOf(call)
        if (sym != null) {
            val mods = HashSet<HighlightModifier>(3)
            if (sym.isComposable) mods += HighlightModifier.COMPOSABLE
            if (sym.isExtension) mods += HighlightModifier.EXTENSION
            if (sym.isSuspend) mods += HighlightModifier.SUSPEND
            emit(callee.textRange, HighlightKind.METHOD, mods)
            return
        }
        // No function resolved: a capitalized callee is a constructor / object invoke (`Foo(...)`, `Color(...)`).
        if (callee.getReferencedName().firstOrNull()?.isUpperCase() == true) {
            emit(callee.textRange, HighlightKind.CONSTRUCTOR, emptySet())
        }
    }

    /** A type-position name: distinguish an in-scope type parameter (`T`) from a class. Only the LEAF of a
     *  qualified type (`a.b.C` → `C`) is colored; the qualifier segments (packages/owners) are KtUserType
     *  children visited separately and skipped here, so a package name isn't miscolored as a class. */
    private fun classifyTypeRef(userType: KtUserType, emit: (org.jetbrains.kotlin.com.intellij.openapi.util.TextRange?, HighlightKind, Set<HighlightModifier>) -> Unit) {
        if (userType.parent is KtUserType) return // a qualifier segment of an enclosing type
        val ref = userType.referenceExpression ?: return
        val name = ref.getReferencedName()
        val kind = if (isTypeParameterInScope(userType, name)) HighlightKind.TYPE_PARAMETER else HighlightKind.CLASS
        emit(ref.textRange, kind, emptySet())
    }

    /** A bare reference in value position: classify only LOCALS and PARAMETERS (with var/val) — exact from the
     *  PSI scope. Members/top-level reads, type refs, call callees, named-arg labels, this/super are skipped. */
    private fun classifyReference(ref: KtNameReferenceExpression, emit: (org.jetbrains.kotlin.com.intellij.openapi.util.TextRange?, HighlightKind, Set<HighlightModifier>) -> Unit) {
        val parent = ref.parent
        if (parent is KtCallExpression && parent.calleeExpression === ref) return // a call callee (classifyCall)
        if (parent is KtQualifiedExpression && parent.selectorExpression === ref) return // a member selector
        if (parent is KtUserType || parent is KtValueArgumentName) return
        if (parent is org.jetbrains.kotlin.psi.KtInstanceExpressionWithLabel) return // this/super
        val name = ref.getReferencedName()
        if (name.isEmpty()) return
        when (val decl = localOrParamDecl(name, ref.textRange.startOffset, ref)) {
            is KtParameter -> emit(ref.textRange, HighlightKind.PARAMETER, paramMutability(decl))
            is KtProperty -> emit(ref.textRange, HighlightKind.LOCAL_VARIABLE, mutability(decl.isVar))
            else -> {} // a member / top-level / unresolved name → leave to the lexical layer
        }
    }

    /** The nearest local property / parameter named [name] declared before [offset], or null if it resolves
     *  to a class member / top level (the walk stops at the enclosing class). Mirrors the analyzer's lookup. */
    private fun localOrParamDecl(name: String, offset: Int, from: PsiElement): PsiElement? {
        var node: PsiElement? = from.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression ->
                    node.statements.firstOrNull { it is KtProperty && it.name == name && it.textRange.endOffset <= offset }?.let { return it }
                is KtFunction -> node.valueParameters.firstOrNull { it.name == name }?.let { return it }
                is KtForExpression -> node.loopParameter?.takeIf { it.name == name }?.let { return it }
                is KtCatchClause -> node.catchParameter?.takeIf { it.name == name }?.let { return it }
                is KtClassOrObject -> return null
            }
            node = node.parent
        }
        return null
    }

    /** Whether [name] is declared as a type parameter of an enclosing function/class (`fun <T> …`, `class C<T>`). */
    private fun isTypeParameterInScope(from: PsiElement, name: String): Boolean {
        var owner = from.getParentOfType<KtTypeParameterListOwner>(strict = true)
        while (owner != null) {
            if (owner.typeParameters.any { it.name == name }) return true
            owner = owner.getParentOfType<KtTypeParameterListOwner>(strict = true)
        }
        return false
    }
}
