package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtPrimaryConstructor
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange

/** The module's explicit API mode (`kotlin { explicitApi() }`, `-Xexplicit-api=strict|warning`). */
enum class KotlinExplicitApiMode { DISABLED, WARNING, STRICT }

/*
 * Explicit API mode (FirExplicitApiDeclarationChecker): a declaration that is part of the module's public API must
 * say its visibility (NO_EXPLICIT_VISIBILITY_IN_API_MODE) and a callable its type when it would be inferred
 * (NO_EXPLICIT_RETURN_TYPE_IN_API_MODE): errors in strict mode, warnings in warning mode. Public API is a
 * declaration whose effective visibility is public or protected (an `internal` one marked `@PublishedApi` too)
 * inside containers that are public API themselves. Not asked of, as K2 excludes them: primary constructors,
 * accessors, value parameters, enum entries, overrides, local declarations, properties of data, value and
 * annotation classes, and a sealed class's secondary constructors. A primary-constructor property is checked
 * for its visibility (its type is always written).
 */
internal fun explicitApiDiagnostics(d: KtDeclaration, mode: KotlinExplicitApiMode): List<Diagnostic> {
    if (mode == KotlinExplicitApiMode.DISABLED) return emptyList()
    if (d is KtEnumEntry || d is KtPrimaryConstructor) return emptyList()
    val ctorProperty = d is KtParameter && d.hasValOrVar() && d.parent?.parent is KtPrimaryConstructor
    if (d is KtParameter && !ctorProperty) return emptyList()
    if (d !is KtClassOrObject && d !is KtNamedFunction && d !is KtProperty && d !is KtTypeAlias &&
        d !is KtSecondaryConstructor && !ctorProperty) return emptyList()
    if (d is KtObjectDeclaration && d.isObjectLiteral()) return emptyList()
    if (!isPublicApi(d)) return emptyList()
    val out = ArrayList<Diagnostic>()
    val strict = mode == KotlinExplicitApiMode.STRICT
    if (!hasVisibilityModifier(d) && !visibilityNotRequired(d)) {
        val anchor = (d as? KtNamedDeclaration)?.nameIdentifier ?: d
        out += Diagnostic(
            TextRange(declarationStart(d), anchor.textRange.endOffset), if (strict) Severity.ERROR else Severity.WARNING,
            "Visibility must be specified in explicit API mode",
            if (strict) KotlinDiagnosticCodes.NO_EXPLICIT_VISIBILITY_IN_API_MODE else KotlinDiagnosticCodes.NO_EXPLICIT_VISIBILITY_IN_API_MODE_WARNING,
        )
    }
    val implicitType = when (d) {
        is KtProperty -> d.typeReference == null
        // A block body without a type is `Unit`; only an expression body infers one.
        is KtNamedFunction -> d.typeReference == null && d.hasBody() && d.bodyBlockExpression == null
        else -> false
    }
    if (implicitType) {
        val anchor = (d as KtNamedDeclaration).nameIdentifier ?: d
        out += Diagnostic(
            TextRange(anchor.textRange.startOffset, anchor.textRange.endOffset), if (strict) Severity.ERROR else Severity.WARNING,
            "Return type must be specified in explicit API mode",
            if (strict) KotlinDiagnosticCodes.NO_EXPLICIT_RETURN_TYPE_IN_API_MODE else KotlinDiagnosticCodes.NO_EXPLICIT_RETURN_TYPE_IN_API_MODE_WARNING,
        )
    }
    return out
}

/** Where K2 starts the visibility report: the declaration's first keyword, after its annotations and KDoc. */
private fun declarationStart(d: KtDeclaration): Int {
    val mods = d.modifierList
    var c = d.firstChild
    while (c != null) {
        val isTrivia = c.text.isBlank() || c.text.startsWith("/*") || c.text.startsWith("//")
        if (c === mods) {
            // Past the annotations to the first modifier keyword, if any.
            var m = mods.firstChild
            while (m != null) {
                val t = m.text
                if (t.isNotBlank() && !t.startsWith("@") && !t.startsWith("/")) return m.textRange.startOffset
                m = m.nextSibling
            }
        } else if (!isTrivia) return c.textRange.startOffset
        c = c.nextSibling
    }
    return d.textRange.startOffset
}

private fun hasVisibilityModifier(d: KtDeclaration): Boolean =
    VISIBILITY_MODIFIERS.any { d.hasModifier(it) }

private val VISIBILITY_MODIFIERS = listOf(KtTokens.PUBLIC_KEYWORD, KtTokens.PROTECTED_KEYWORD, KtTokens.INTERNAL_KEYWORD, KtTokens.PRIVATE_KEYWORD)

private fun visibilityNotRequired(d: KtDeclaration): Boolean {
    if (d.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return true
    val owner = ownerClass(d)
    if (d is KtProperty || d is KtParameter) {
        if (owner is KtClass && (owner.hasModifier(KtTokens.DATA_KEYWORD) || owner.hasModifier(KtTokens.VALUE_KEYWORD) ||
                owner.hasModifier(KtTokens.ANNOTATION_KEYWORD))) return true
    }
    if (d is KtSecondaryConstructor && owner?.hasModifier(KtTokens.SEALED_KEYWORD) == true) return true
    return false
}

/** The class a member is declared in (a primary-constructor property's class included), or null at top level. */
private fun ownerClass(d: KtDeclaration): KtClassOrObject? = when (val p = d.parent) {
    is KtClassBody -> p.parent as? KtClassOrObject
    else -> if (d is KtParameter) d.parent?.parent?.parent as? KtClassOrObject else null
}

/** Whether [d] is part of the module's public API: public or protected all the way out to the file, nothing local. */
private fun isPublicApi(d: KtDeclaration): Boolean {
    if (!publicApiItself(d, self = true)) return false
    var node: KtElement? = if (d is KtParameter) d.parent?.parent?.parent else d.parent
    while (node != null && node !is KtFile) {
        when (node) {
            is KtBlockExpression -> return false // a local declaration, or one inside a local class
            is KtClassOrObject -> {
                if (node is KtObjectDeclaration && node.isObjectLiteral()) return false
                if (node is KtEnumEntry) return false
                if (!publicApiItself(node, self = false)) return false
            }
            is KtNamedFunction, is KtProperty -> return false
        }
        node = node.parent
    }
    return true
}

/** [d]'s own visibility keeps it public API: not private, and not internal unless (for [self]) `@PublishedApi`. */
private fun publicApiItself(d: KtDeclaration, self: Boolean): Boolean {
    if (d.hasModifier(KtTokens.PRIVATE_KEYWORD)) return false
    if (d.hasModifier(KtTokens.INTERNAL_KEYWORD)) {
        return self && d.annotationEntries.any { it.shortName?.asString() == "PublishedApi" }
    }
    return true
}
