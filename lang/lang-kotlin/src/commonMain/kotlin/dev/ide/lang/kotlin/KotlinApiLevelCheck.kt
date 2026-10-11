package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotated
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtContinueExpression
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.calleeFunctionOf
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.ApiLevelContext
import dev.ide.lang.resolve.ApiGuards
import dev.ide.lang.resolve.ApiLevel
import dev.ide.lang.resolve.ApiPlatform
import dev.ide.lang.resolve.PlatformApiLevels
import dev.ide.lang.resolve.SymbolKind

/**
 * The minSdk check (Android lint's `NewApi`) for Kotlin: a call, constructor call or Java-accessor property
 * (`view.foo` for `getFoo()`) on a platform API added after the module's `minSdk`, unless an enclosing
 * `if`/`when`/`&&` on `Build.VERSION.SDK_INT`, an earlier `if (SDK_INT < N) return`, or `@RequiresApi(N)` /
 * `@TargetApi(N)` on an enclosing declaration guarantees the level. Same rules as the Java check, through the
 * shared [ApiGuards].
 *
 * Only BINARY symbols with a declaring class are looked up, so project code is never reported, and an API
 * the table does not know is never reported. On a plain JVM module the same lookups compare JDK APIs with the
 * module's Java release (`-Xjdk-release`), with no guards.
 */
internal class KotlinApiLevelCheck(private val service: KotlinSymbolService) {

    fun call(call: KtCallExpression, resolver: KotlinResolver, api: ApiLevelContext): Diagnostic? {
        // `calleeFunctionOf` answers functions only; a constructor call is matched among the call's targets.
        val callee = resolver.calleeFunctionOf(call) ?: constructorOf(call, resolver) ?: return null
        val anchor = call.calleeExpression ?: return null
        val level = levelOfCallable(callee, api.levels) ?: return null
        val owner = callee.declaringClassFqn ?: return null
        val label = if (callee.kind == SymbolKind.CONSTRUCTOR) "new $owner" else "$owner#${callee.name}"
        return reportIfTooNew(anchor, "Call", label, level, api)
    }

    private fun constructorOf(call: KtCallExpression, resolver: KotlinResolver): KotlinSymbol? {
        val constructors = resolver.callTargets(call).filter { it.kind == SymbolKind.CONSTRUCTOR }
        val args = call.valueArguments.size
        return constructors.firstOrNull { it.paramTypes.size == args } ?: constructors.singleOrNull()
    }

    /** `recv.foo` / `recv.foo = x` where `foo` is a Java bean accessor seen as a Kotlin property. */
    fun accessorProperty(qualified: KtDotQualifiedExpression, resolver: KotlinResolver, api: ApiLevelContext): Diagnostic? {
        val selector = qualified.selectorExpression as? KtNameReferenceExpression ?: return null
        val name = selector.getReferencedName()
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
        val level = api.levels.methodLevel(owner, accessor, null, arity = if (assigned) 1 else 0) ?: return null
        return reportIfTooNew(selector, "Call", "$owner#$accessor", level, api)
    }

    private fun levelOfCallable(callee: KotlinSymbol, levels: PlatformApiLevels): ApiLevel? {
        if (callee.origin.fromSource || callee.isExtension) return null
        val owner = callee.declaringClassFqn ?: return null
        return when (callee.kind) {
            SymbolKind.CONSTRUCTOR -> levels.methodLevel(owner, "<init>", null, callee.paramTypes.size)
                ?: levels.classLevel(owner)
            SymbolKind.METHOD -> levels.methodLevel(owner, callee.name, null, callee.paramTypes.size)
            else -> null
        }
    }

    private fun reportIfTooNew(anchor: KtElement, what: String, label: String, level: ApiLevel, api: ApiLevelContext): Diagnostic? {
        if (level.since <= api.minLevel) return null
        if (api.platform == ApiPlatform.ANDROID && (level.desugared || guaranteedLevel(anchor) >= level.since)) return null
        val r = anchor.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), if (api.enforced) Severity.ERROR else Severity.WARNING,
            ApiGuards.message(what, label, level.since, api),
            ApiGuards.codeFor(api.platform),
        )
    }

    companion object {
        /** The highest API level the code around [element] guarantees through guards and annotations. */
        fun guaranteedLevel(element: KtElement): Int {
            val offset = element.textRange.startOffset
            var level = 0
            var node: KtElement? = element.parent
            while (node != null) {
                level = maxOf(level, guardFrom(node, offset))
                node = node.parent
            }
            return level
        }

        private fun KtElement?.covers(offset: Int): Boolean =
            this != null && offset >= textRange.startOffset && offset < textRange.endOffset

        private fun guardFrom(node: KtElement, offset: Int): Int {
            var level = 0
            when (node) {
                is KtIfExpression -> {
                    val cond = node.condition?.text
                    if (cond != null) {
                        if (node.then.covers(offset)) level = ApiGuards.levelGuaranteed(cond, true)
                        else if (node.`else`.covers(offset)) level = ApiGuards.levelGuaranteed(cond, false)
                    }
                }
                is KtWhenExpression -> if (node.subjectExpression == null) level = whenLevel(node, offset)
                is KtBinaryExpression -> {
                    val left = node.left?.text
                    if (left != null && node.right.covers(offset)) {
                        level = when (node.operationToken) {
                            KtTokens.ANDAND -> ApiGuards.levelGuaranteed(left, true)
                            KtTokens.OROR -> ApiGuards.levelGuaranteed(left, false)
                            else -> 0
                        }
                    }
                }
                is KtBlockExpression -> level = earlyExitLevel(node, offset)
            }
            if (node is KtAnnotated) {
                for (entry in node.annotationEntries) {
                    val name = entry.shortName?.asString() ?: continue
                    val argument = entry.valueArguments.firstOrNull()?.getArgumentExpression()?.text
                    level = maxOf(level, ApiGuards.levelFromAnnotation(name, argument))
                }
            }
            return level
        }

        /** A subject-less `when`: the entry holding [offset] has its condition true and every earlier one false. */
        private fun whenLevel(node: KtWhenExpression, offset: Int): Int {
            var level = 0
            for (entry in node.entries) {
                val conditions = entry.conditions.map { (it as? KtWhenConditionWithExpression)?.expression?.text }
                if (entry.expression.covers(offset)) {
                    if (!entry.isElse && conditions.size == 1) conditions[0]?.let { level = maxOf(level, ApiGuards.levelGuaranteed(it, true)) }
                    return level
                }
                if (!entry.isElse) for (c in conditions) if (c != null) level = maxOf(level, ApiGuards.levelGuaranteed(c, false))
            }
            return 0
        }

        /** `if (SDK_INT < 26) return` earlier in [block] guarantees 26 below it. */
        private fun earlyExitLevel(block: KtBlockExpression, offset: Int): Int {
            var level = 0
            for (statement in block.statements) {
                if (statement.textRange.endOffset > offset) break
                val ifExpression = statement as? KtIfExpression ?: continue
                val cond = ifExpression.condition?.text ?: continue
                if (ifExpression.`else` == null && exits(ifExpression.then)) level = maxOf(level, ApiGuards.levelGuaranteed(cond, false))
            }
            return level
        }

        private fun exits(expression: KtExpression?): Boolean = when (expression) {
            is KtReturnExpression, is KtThrowExpression, is KtBreakExpression, is KtContinueExpression -> true
            is KtBlockExpression -> exits(expression.statements.lastOrNull())
            else -> false
        }
    }
}
