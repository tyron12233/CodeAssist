package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassLiteralExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtCollectionLiteralExpression
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunction
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtStringTemplateEntryWithExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange

/**
 * Values that must be known at compile time and are not: an annotation argument, or a `const val`'s
 * initializer, that reads a plain `val`/`var` or calls a function of this file
 * (ANNOTATION_ARGUMENT_MUST_BE_CONST / NON_CONST_VAL_USED_IN_CONSTANT_EXPRESSION).
 *
 * The name is looked up the way the compiler would look it up, through locals, then enclosing classes, then
 * the file, and only as far as the tree settles it: a class with supertypes or a companion may inherit the
 * name from somewhere unseen, an explicit import may bring one in, and another file of the package may declare
 * it. Any of those, and anything that is not a plain name, a literal, a template or an operator over those,
 * leaves the argument alone.
 */
internal object KotlinConstChecks {

    /** Each non-constant part of [entry]'s arguments. */
    fun annotationArguments(entry: KtAnnotationEntry): List<Diagnostic> {
        if (entry.containingFile.name.endsWith(".kts")) return emptyList()
        val out = ArrayList<Diagnostic>()
        for (arg in entry.valueArguments) {
            val e = arg.getArgumentExpression() ?: continue
            walk(e, inAnnotation = true, out)
        }
        return out
    }

    /** Each name in a `const val`'s initializer that reads a property which is not `const`. */
    fun constInitializer(prop: KtProperty): List<Diagnostic> {
        if (!prop.hasModifier(KtTokens.CONST_KEYWORD) || prop.isVar || prop.parent is KtBlockExpression) return emptyList()
        if (prop.containingFile.name.endsWith(".kts")) return emptyList()
        val init = prop.initializer ?: return emptyList()
        val out = ArrayList<Diagnostic>()
        walk(init, inAnnotation = false, out, self = prop)
        return out
    }

    private fun walk(e: KtExpression, inAnnotation: Boolean, out: MutableList<Diagnostic>, self: KtProperty? = null) {
        when (e) {
            is KtConstantExpression, is KtClassLiteralExpression -> {}
            is KtParenthesizedExpression -> e.expression?.let { walk(it, inAnnotation, out, self) }
            is KtPrefixExpression -> e.baseExpression?.let { walk(it, inAnnotation, out, self) }
            is KtBinaryExpression -> {
                // K2's evaluator folds neither an integer division by zero nor an equality of class literals.
                if (inAnnotation && (KotlinRound8Const.integerDivisionByZero(e) ||
                        (e.operationToken == KtTokens.EQEQ || e.operationToken == KtTokens.EXCLEQ) &&
                        (e.left is KtClassLiteralExpression || e.right is KtClassLiteralExpression))
                ) {
                    out += diag(e, "An annotation argument must be a compile-time constant")
                    return
                }
                if (e.operationToken !in CONSTANT_OPERATORS) return
                e.left?.let { walk(it, inAnnotation, out, self) }
                e.right?.let { walk(it, inAnnotation, out, self) }
            }
            is KtStringTemplateExpression -> for (entry in e.entries) {
                if (entry is KtStringTemplateEntryWithExpression) entry.expression?.let { walk(it, inAnnotation, out, self) }
            }
            is KtCollectionLiteralExpression -> if (inAnnotation) e.innerExpressions.forEach { walk(it, true, out, self) }
            is KtCallExpression -> if (inAnnotation) call(e, out)
            is KtNameReferenceExpression -> name(e, inAnnotation, out, self)
            else -> {}
        }
    }

    /** An array-of call is a constant when its elements are; a call to a function this file declares never is. */
    private fun call(e: KtCallExpression, out: MutableList<Diagnostic>) {
        val callee = (e.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return
        if (callee in ARRAY_OF) {
            e.valueArguments.forEach { a -> a.getArgumentExpression()?.let { walk(it, true, out) } }
            return
        }
        when (val target = lookup(callee, e, wantFunction = true)) {
            is KtNamedFunction -> out += diag(e, "An annotation argument must be a compile-time constant")
            else -> if (target != null) return
        }
    }

    private fun name(ref: KtNameReferenceExpression, inAnnotation: Boolean, out: MutableList<Diagnostic>, self: KtProperty?) {
        val name = ref.getReferencedName()
        if (name == "it" || name == "field") return
        val target = lookup(name, ref, wantFunction = false) ?: return
        if (target === self) return // a const initializer naming itself is a cycle, reported differently
        val nonConst = when (target) {
            is KtProperty -> !target.hasModifier(KtTokens.CONST_KEYWORD)
            is KtParameter -> true
            else -> false
        }
        if (!nonConst) return
        // The compiler words a `val` whose own value would have been constant one way, anything else another.
        val couldBeConst = target is KtProperty && !target.isVar && target.initializer?.let { simpleConstant(it) } == true
        val msg = when {
            couldBeConst -> "Only 'const val' can be used in constant expressions"
            inAnnotation -> "An annotation argument must be a compile-time constant"
            else -> "Const 'val' initializer should be a constant value"
        }
        out += diag(ref, msg)
    }

    private fun simpleConstant(e: KtExpression): Boolean = when (e) {
        is KtConstantExpression -> true
        is KtStringTemplateExpression -> e.entries.none { it is KtStringTemplateEntryWithExpression }
        is KtParenthesizedExpression -> e.expression?.let(::simpleConstant) == true
        is KtPrefixExpression -> e.baseExpression?.let(::simpleConstant) == true
        is KtBinaryExpression -> e.operationToken in CONSTANT_OPERATORS &&
            e.left?.let(::simpleConstant) == true && e.right?.let(::simpleConstant) == true
        else -> false
    }

    /**
     * The declaration a bare [name] at [at] reaches, as far as the tree settles it: a local, a parameter, a
     * member of an enclosing class, or a top-level declaration of this file. Null whenever something unseen
     * could be what it means. [wantFunction] looks for a function instead of a value.
     */
    private fun lookup(name: String, at: KtElement, wantFunction: Boolean): KtElement? {
        val offset = at.textRange.startOffset
        var node: KtElement? = at.parent
        var prev: KtElement = at
        while (node != null) {
            when (node) {
                is KtBlockExpression -> for (s in node.statements) {
                    if (s.textRange.endOffset > offset) break
                    if (declares(s, name, wantFunction)) return s
                    if (s is KtNamedDeclaration && s.name == name) return null // a local class or function of that name
                    if (s is KtDestructuringDeclaration && !wantFunction && s.entries.any { it.name == name }) return null
                }
                // A lambda may have a receiver whose members the tree cannot see.
                is KtFunctionLiteral -> return null
                // A parameter's annotations see the declarations around its function: a call there reaches them
                // (`fun f(@A(f(1)) x: Int)`), and a setter parameter's annotation sees the class's members. Which
                // other names a function parameter's annotation sees is not settled here.
                is KtParameter -> {
                    val accessor = node.parent?.parent is dev.ide.kotlin.syntax.psi.KtPropertyAccessor
                    if (node.name == name || (!wantFunction && !accessor)) return null
                }
                // A function's parameters are in scope in its body, not in the annotations written on it.
                is KtFunction -> if (!wantFunction && prev is KtExpression) node.valueParameters.firstOrNull { it.name == name }?.let { return it }
                is dev.ide.kotlin.syntax.psi.KtPropertyAccessor -> if (node.valueParameters.any { it.name == name }) return null
                is KtForExpression -> if (node.loopParameter?.let { it.name == name || it.destructuringDeclaration != null } == true) return null
                is KtCatchClause -> if (!wantFunction && node.catchParameter?.name == name) return node.catchParameter
                is KtClassOrObject -> {
                    // A companion's members, or anything named after this, may be what an annotation on the
                    // class itself reaches; from the header, only the body is not in scope.
                    if (node.companionObjects.isNotEmpty() || node.declarations.any { (it as? KtNamedDeclaration)?.name == name }) {
                        if (prev !is dev.ide.kotlin.syntax.psi.KtClassBody) return null
                    }
                    if (prev is dev.ide.kotlin.syntax.psi.KtClassBody) {
                        val ctorParam = node.primaryConstructorParameters.firstOrNull { it.name == name }
                        if (ctorParam != null) return if (ctorParam.hasValOrVar() && !wantFunction) ctorParam else null
                        for (d in node.declarations) if ((d as? KtNamedDeclaration)?.name == name) {
                            return if (declares(d, name, wantFunction)) d else null
                        }
                        // Anything else may come from a supertype, a companion, or an enum's entries.
                        if (node.superTypeListEntries.isNotEmpty() || node.companionObjects.isNotEmpty()) return null
                        if (node is KtClass && node.isEnum()) return null
                    } else if (prev !is dev.ide.kotlin.syntax.psi.KtModifierList) {
                        return null // a supertype, type parameter or constructor in the header
                    }
                    if (node.parent !is KtFile && node.parent !is dev.ide.kotlin.syntax.psi.KtClassBody) return null
                }
                is KtFile -> {
                    if (node.name.endsWith(".kts")) return null
                    if (node.importDirectives.any { (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == name }) return null
                    val matches = node.declarations.filter { (it as? KtNamedDeclaration)?.name == name }
                    if (matches.any { it is KtClassOrObject || it is KtTypeAlias }) return null
                    return matches.firstOrNull { declares(it, name, wantFunction) }
                }
                else -> {}
            }
            prev = node
            node = node.parent
        }
        return null
    }

    private fun declares(d: KtElement, name: String, wantFunction: Boolean): Boolean =
        if (wantFunction) d is KtNamedFunction && d.name == name && d.receiverTypeReference == null
        // A delegated property is never a constant either: `@get:Anno(foo)` on `var foo by D()` reads it too.
        else d is KtProperty && d.name == name && d.receiverTypeReference == null

    private fun diag(at: KtElement, msg: String): Diagnostic {
        val r = at.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.NOT_CONSTANT)
    }

    private val ARRAY_OF = setOf(
        "arrayOf", "intArrayOf", "longArrayOf", "shortArrayOf", "byteArrayOf", "charArrayOf", "booleanArrayOf",
        "floatArrayOf", "doubleArrayOf", "emptyArray",
    )

    private val CONSTANT_OPERATORS = setOf(
        KtTokens.PLUS, KtTokens.MINUS, KtTokens.MUL, KtTokens.DIV, KtTokens.PERC, KtTokens.ANDAND, KtTokens.OROR,
        KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ,
    )
}
