package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotated
import dev.ide.kotlin.syntax.psi.KtAnonymousInitializer
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.calleeFunctionOf
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.PermissionContext
import dev.ide.lang.resolve.PermissionGuards
import dev.ide.lang.resolve.SymbolKind

/**
 * The missing-permission check (Android lint's `MissingPermission`) for Kotlin: a call, constructor call or
 * Java-accessor property (`adapter.bondedDevices` for `getBondedDevices()`) on a platform API that needs a
 * permission the manifest does not declare, or a dangerous one nothing around the call checks. The rules are
 * [PermissionGuards]', shared with the Java check; this finds the calls and what encloses them.
 *
 * Only BINARY symbols with a declaring class are looked up, so project code is never reported.
 */
internal class KotlinPermissionCheck(private val service: KotlinSymbolService) {

    fun call(call: KtCallExpression, resolver: KotlinResolver, ctx: PermissionContext): Diagnostic? {
        val written = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        if (!ctx.table.mayRequire(written)) return null
        val callee = resolver.calleeFunctionOf(call) ?: constructorOf(call, resolver) ?: return null
        if (callee.origin.fromSource || callee.isExtension) return null
        val owner = callee.declaringClassFqn ?: return null
        val constructor = callee.kind == SymbolKind.CONSTRUCTOR
        if (!constructor && callee.kind != SymbolKind.METHOD) return null
        val name = if (constructor) "<init>" else callee.name
        val req = ctx.table.requirement(owner, name, callee.paramTypes.size) ?: return null
        val anchor = call.calleeExpression ?: return null
        val simple = owner.substringAfterLast('.')
        return report(anchor, if (constructor) simple else "$simple.${callee.name}", req, ctx)
    }

    /** `recv.foo` / `recv.foo = x` where `foo` is a Java bean accessor seen as a Kotlin property. */
    fun accessorProperty(qualified: KtDotQualifiedExpression, resolver: KotlinResolver, ctx: PermissionContext): Diagnostic? {
        val selector = qualified.selectorExpression as? KtNameReferenceExpression ?: return null
        val name = selector.getReferencedName()
        val capital = name.replaceFirstChar { it.uppercaseChar() }
        if (!ctx.table.mayRequire("get$capital") && !ctx.table.mayRequire("set$capital") && !ctx.table.mayRequire(name) &&
            !ctx.table.mayRequire("set" + name.removePrefix("is"))
        ) return null
        val receiverType = resolver.inferType(qualified.receiverExpression) ?: return null
        if (receiverType.isTypeParameter) return null
        val property = service.membersNamedForCheck(receiverType.qualifiedName, receiverType.typeArguments, name)
            .firstOrNull { it.kind == SymbolKind.FIELD && !it.origin.fromSource && it.declaringClassFqn != null }
            ?: return null
        val owner = property.declaringClassFqn ?: return null
        val assigned = (qualified.parent as? KtBinaryExpression)
            ?.let { it.operationToken == KtTokens.EQ && it.left == qualified } == true
        val accessor = when {
            assigned -> "set" + name.removePrefix("is").replaceFirstChar { it.uppercaseChar() }
            name.length > 2 && name.startsWith("is") && name[2].isUpperCase() -> name
            else -> "get" + name.replaceFirstChar { it.uppercaseChar() }
        }
        val req = ctx.table.requirement(owner, accessor, if (assigned) 1 else 0) ?: return null
        return report(selector, "${owner.substringAfterLast('.')}.$accessor", req, ctx)
    }

    private fun constructorOf(call: KtCallExpression, resolver: KotlinResolver): KotlinSymbol? {
        val constructors = resolver.callTargets(call).filter { it.kind == SymbolKind.CONSTRUCTOR }
        val args = call.valueArguments.size
        return constructors.firstOrNull { it.paramTypes.size == args } ?: constructors.singleOrNull()
    }

    private fun report(anchor: KtElement, label: String, req: dev.ide.lang.resolve.PermissionRequirement, ctx: PermissionContext): Diagnostic? {
        if (exempt(anchor)) return null
        val message = PermissionGuards.check(req, ctx, label) { handled(anchor) } ?: return null
        val r = anchor.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, message, KotlinDiagnosticCodes.MISSING_PERMISSION)
    }

    companion object {
        /** `@SuppressLint("MissingPermission")` or `@RequiresPermission` on a declaration around [element]. */
        fun exempt(element: KtElement): Boolean {
            var node: KtElement? = element.parent
            while (node != null) {
                if (node is KtAnnotated) {
                    for (entry in node.annotationEntries) {
                        val name = entry.shortName?.asString() ?: continue
                        if (PermissionGuards.isRequiresPermission(name)) return true
                        val args = entry.valueArguments.joinToString(",") { it.getArgumentExpression()?.text.orEmpty() }
                        if (PermissionGuards.suppresses(name, args)) return true
                    }
                }
                node = node.parent
            }
            return false
        }

        /**
         * A permission check in the enclosing function, or the call inside a `try` that catches
         * `SecurityException` or a `runCatching { }`.
         */
        fun handled(element: KtElement): Boolean {
            val offset = element.textRange.startOffset
            var node: KtElement? = element.parent
            while (node != null) {
                when (node) {
                    is KtTryExpression -> {
                        val inTry = node.tryBlock.textRange.let { offset >= it.startOffset && offset < it.endOffset }
                        if (inTry && node.catchClauses.any { c ->
                                c.catchParameter?.typeReference?.text?.let(PermissionGuards::catchesSecurityException) == true
                            }) return true
                    }
                    is KtCallExpression ->
                        if ((node.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == "runCatching") return true
                    is KtNamedFunction, is KtPropertyAccessor, is KtAnonymousInitializer ->
                        return PermissionGuards.checksPermission(node.text)
                    is KtProperty -> if (!node.isLocal) return PermissionGuards.checksPermission(node.text)
                    is KtClassOrObject -> return false
                }
                node = node.parent
            }
            return false
        }
    }
}
