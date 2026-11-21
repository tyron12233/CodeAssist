package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateEntry
import dev.ide.kotlin.syntax.psi.KtSafeQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtArrayAccessExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtContainerNodeForControlStructureBody
import dev.ide.kotlin.syntax.psi.KtDeclaration
import dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtFunctionType
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtModifierListOwner
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentName
import dev.ide.kotlin.syntax.psi.KtWhenEntry
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange

/**
 * The rules an inline function's body is held to (FirInlineDeclarationChecker and
 * FirInlineBodyResolvableExpressionChecker): an inlinable functional parameter may only be called or handed
 * on to another inline function (USAGE_IS_NOT_INLINABLE), and a public-API inline function may not touch a
 * declaration outside the public API, since its body is copied into callers that cannot see it
 * (NON_PUBLIC_CALL_FROM_PUBLIC_INLINE). Both are judged against declarations of this file only.
 */
internal object KotlinInlineChecks {

    // --- USAGE_IS_NOT_INLINABLE ----------------------------------------------------------------------------

    /**
     * Uses of [fn]'s inlinable parameters (a non-nullable function type, not `noinline`, not `vararg`) that
     * are neither a call (`p()`, `p.invoke()`) nor an argument of an inline function's inlinable parameter:
     * the bare value as a statement, an initializer, a returned or branch value, either side of `?:`, the
     * right side of `=`, the receiver of anything but `invoke`, an argument of a function or constructor of
     * this file that is not inline (or whose parameter is not inlinable), and the operands of an operator
     * function of this file. Calls to anything not declared in this file are left alone.
     */
    fun usageIsNotInlinable(fn: KtNamedFunction): List<Diagnostic> {
        if (!fn.hasModifier(KtTokens.INLINE_KEYWORD)) return emptyList()
        val body = fn.bodyExpression ?: return emptyList()
        val inlinable = fn.valueParameters.filter { isInlinableParameter(it, fn.containingKtFile) }.mapNotNull { it.name }.toHashSet()
        if (inlinable.isEmpty()) return emptyList()
        val file = fn.containingKtFile
        val out = ArrayList<Diagnostic>()
        fun visit(e: KtElement) {
            when (e) {
                is KtClassOrObject -> return
                is KtNamedFunction -> return // a local or anonymous function: its own scope and rules
                is KtCallableReferenceExpression -> { e.receiverExpression?.let { visit(it) }; return }
                is KtNameReferenceExpression -> {
                    val name = e.getReferencedName()
                    if (name in inlinable && !shadowedParameter(e, fn, name) && notInlinableUse(e, file, fn)) {
                        val r = e.textRange
                        out += Diagnostic(
                            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                            "Illegal usage of inline parameter '$name'. Add 'noinline' modifier to the parameter declaration.",
                            KotlinDiagnosticCodes.USAGE_IS_NOT_INLINABLE,
                        )
                    }
                    return
                }
                else -> {}
            }
            for (c in e.children) visit(c)
        }
        visit(body)
        return out
    }

    /** A parameter K2 inlines: not `noinline` or `vararg`, of a non-nullable function type, written as one
     *  (`(Int) -> Unit`, `T.() -> R`) or as `FunctionN<…>` (when this file declares no class of that name). */
    private fun isInlinableParameter(p: KtParameter, file: KtFile): Boolean {
        if (p.hasModifier(KtTokens.NOINLINE_KEYWORD) || p.isVarArg) return false
        return when (val t = p.typeReference?.typeElement) {
            is KtFunctionType -> true
            is KtUserType -> {
                val name = t.referencedName ?: return false
                val qualifier = t.qualifier?.text
                (qualifier == null || qualifier == "kotlin") && FUNCTION_N.matches(name) &&
                    file.declarations.none { it is KtClassOrObject && it.name == name }
            }
            else -> false
        }
    }

    private val FUNCTION_N = Regex("""Function([0-9]|1[0-9]|2[0-2])""")

    /** Whether a nearer declaration (a local, a lambda's or a local function's parameter, a local class's
     *  member) hides [fn]'s parameter [name] at [ref]. */
    private fun shadowedParameter(ref: KtElement, fn: KtNamedFunction, name: String): Boolean {
        var node: KtElement? = ref.parent
        while (node != null && node !== fn) {
            when (node) {
                is KtBlockExpression -> if (node.statements.any { declares(it, name) }) return true
                is KtFunctionLiteral -> if (node.valueParameters.any { it.name == name || it.destructuringDeclaration?.entries?.any { e -> e.name == name } == true }) return true
                is KtNamedFunction -> if (node.valueParameters.any { it.name == name }) return true
                is KtClassOrObject -> return true
                else -> {}
            }
            node = node.parent
        }
        return node == null
    }

    private fun declares(st: KtElement, name: String): Boolean = when (st) {
        is KtNamedDeclaration -> st.name == name
        is KtDestructuringDeclaration -> st.entries.any { it.name == name }
        else -> false
    }

    /** The judgement on one reference to an inlinable parameter: true when K2 rejects the use. */
    private fun notInlinableUse(ref: KtNameReferenceExpression, file: KtFile, fn: KtNamedFunction): Boolean {
        val parent = ref.parent
        if (parent is KtValueArgumentName || parent is KtUserType) return false
        if (parent is KtCallExpression && parent.calleeExpression === ref) return false // `p()`
        var holder: KtElement = ref
        while (holder.parent is KtParenthesizedExpression || holder.parent is KtLabeledExpression) holder = holder.parent!!
        return when (val p = holder.parent) {
            // `p?.invoke()` is a safe call on the value, not a call of it.
            is KtSafeQualifiedExpression -> p.receiverExpression === holder
            is KtQualifiedExpression -> {
                if (p.receiverExpression !== holder) return false
                val sel = p.selectorExpression
                val selName = when (sel) {
                    is KtNameReferenceExpression -> sel.getReferencedName()
                    is KtCallExpression -> (sel.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                    else -> null
                } ?: return false
                selName != "invoke"
            }
            is KtCallableReferenceExpression -> p.receiverExpression === holder
            is KtPostfixExpression -> p.operationToken == KtTokens.EXCLEXCL
            is KtPrefixExpression -> {
                val conventionName = when (p.operationToken) {
                    KtTokens.EXCL -> "not"
                    KtTokens.MINUS -> "unaryMinus"
                    KtTokens.PLUS -> "unaryPlus"
                    else -> return false
                }
                fileOperators(file, conventionName).isNotEmpty()
            }
            is KtArrayAccessExpression -> p.arrayExpression === holder && fileOperators(file, "get").isNotEmpty()
            is KtBlockExpression -> true
            is KtProperty -> p.initializer === holder
            is KtReturnExpression -> true
            is KtNamedFunction -> p === fn && p.bodyExpression === holder && !p.hasBlockBody()
            is KtWhenEntry -> p.expression === holder
            is KtWhenExpression -> p.subjectExpression === holder
            is KtContainerNodeForControlStructureBody -> true
            is KtIsExpression -> p.leftHandSide === holder
            is KtBinaryExpressionWithTypeRHS -> p.left === holder
            is KtStringTemplateEntry -> true
            is KtBinaryExpression -> binaryOperandRejected(p, holder, file)
            is KtValueArgument -> argumentRejected(p, file)
            else -> false
        }
    }

    private fun fileOperators(file: KtFile, name: String): List<KtNamedFunction> =
        file.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == name && it.hasModifier(KtTokens.OPERATOR_KEYWORD) }

    private fun binaryOperandRejected(b: KtBinaryExpression, operand: KtElement, file: KtFile): Boolean {
        val op = b.operationToken
        when (op) {
            KtTokens.ELVIS, KtTokens.ANDAND, KtTokens.OROR, KtTokens.EQEQ, KtTokens.EXCLEQ -> return true
            KtTokens.EQ -> return b.right === operand
            else -> {}
        }
        val conventionNames = when (op) {
            KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ -> listOf("compareTo")
            KtTokens.RANGE -> listOf("rangeTo")
            KtTokens.PLUS -> listOf("plus")
            KtTokens.MINUS -> listOf("minus")
            KtTokens.MUL -> listOf("times")
            KtTokens.DIV -> listOf("div")
            KtTokens.PERC -> listOf("rem")
            KtTokens.IN_KEYWORD, KtTokens.NOT_IN -> listOf("contains")
            KtTokens.PLUSEQ -> listOf("plusAssign", "plus")
            KtTokens.MINUSEQ -> listOf("minusAssign", "minus")
            KtTokens.MULTEQ -> listOf("timesAssign", "times")
            KtTokens.DIVEQ -> listOf("divAssign", "div")
            KtTokens.PERCEQ -> listOf("remAssign", "rem")
            else -> return false
        }
        // An `xAssign` operator wins over `x` when the file declares one; only operators of this file are judged.
        val operators = conventionNames.asSequence().map { fileOperators(file, it) }.firstOrNull { it.isNotEmpty() } ?: return false
        // `a in b` calls `b.contains(a)`: the operands swap roles.
        val isReceiver = if (conventionNames[0] == "contains") b.right === operand else b.left === operand
        if (isReceiver) return true
        // The argument side is fine only when the operator inlines that parameter; judged when every
        // same-named operator of this file agrees. Operators on `FunctionN` receivers are first narrowed to the
        // arity of the receiver operand when that is a function-typed parameter.
        val receiverOperand = (if (conventionNames[0] == "contains") b.right else b.left)?.let { unwrapParenOrLabel(it) }
        val arity = (receiverOperand as? KtNameReferenceExpression)?.let { functionArityOfParameter(it) }
        val candidates = arity?.let { n -> operators.filter { receiverArity(it) == n }.takeIf { it.isNotEmpty() } } ?: operators
        val verdicts = candidates.map { f ->
            val param = f.valueParameters.singleOrNull()
            !(f.hasModifier(KtTokens.INLINE_KEYWORD) && param != null && isInlinableParameter(param, file))
        }.toSet()
        return verdicts.singleOrNull() == true
    }

    private fun unwrapParenOrLabel(e: KtExpression): KtExpression = when (e) {
        is KtParenthesizedExpression -> e.expression?.let { unwrapParenOrLabel(it) } ?: e
        is KtLabeledExpression -> e.baseExpression?.let { unwrapParenOrLabel(it) } ?: e
        else -> e
    }

    /** The `FunctionN` arity of the function-typed parameter [ref] names (an extension's receiver counts). */
    private fun functionArityOfParameter(ref: KtNameReferenceExpression): Int? {
        val name = ref.getReferencedName()
        var node: KtElement? = ref.parent
        while (node != null) {
            if (node is KtNamedFunction) {
                val p = node.valueParameters.firstOrNull { it.name == name } ?: return null
                return typeArity(p.typeReference?.typeElement)
            }
            node = node.parent
        }
        return null
    }

    private fun typeArity(t: Any?): Int? = when (t) {
        is KtFunctionType -> t.parameters.size + (if (t.receiverTypeReference != null) 1 else 0)
        is KtUserType -> t.referencedName?.let { FUNCTION_N.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        else -> null
    }

    private fun receiverArity(f: KtNamedFunction): Int? = typeArity(f.receiverTypeReference?.typeElement)

    /** An argument [arg] rejected because the call goes to something that does not inline the parameter it
     *  binds to: a function or constructor of this file that is not inline (or whose parameter is not
     *  inlinable), or a local value invoked as a function. Unknown callees are left alone. */
    private fun argumentRejected(arg: KtValueArgument, file: KtFile): Boolean {
        if (arg is KtLambdaArgument || arg.isSpread) return false
        val call = arg.parent?.parent as? KtCallExpression ?: return false
        val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
        val qualified = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call } != null
        if (!qualified) localValueOrFunction(call, callee)?.let { return it }
        if (localDeclares(call, callee)) return false
        val fns = file.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == callee }
        val classes = file.declarations.filterIsInstance<KtClass>().filter { it.name == callee }
        if (fns.isNotEmpty() && classes.isNotEmpty()) return false
        if (classes.size == 1 && fns.isEmpty() && !qualified) return true // a constructor never inlines
        if (fns.size != 1) return false
        val f = fns.single()
        if (qualified != (f.receiverTypeReference != null)) return false
        if (!f.hasModifier(KtTokens.INLINE_KEYWORD)) return true
        val params = f.valueParameters
        val named = arg.getArgumentName()?.asName?.identifier
        val idx = if (named != null) params.indexOfFirst { it.name == named } else call.valueArgumentList?.arguments?.indexOf(arg) ?: -1
        val param = params.getOrNull(idx) ?: params.lastOrNull()?.takeIf { it.isVarArg && named == null && idx >= params.size } ?: return false
        if (param.isVarArg) return true
        return !isInlinableParameter(param, file)
    }

    /** For a call of [name] from [call]: true when the nearest declaration of that name is a local value or a
     *  parameter (invoked through a function type's non-inline `invoke`), false when it is a local function
     *  or member, null when nothing local declares it. */
    private fun localValueOrFunction(call: KtElement, name: String): Boolean? {
        var node: KtElement? = call.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression -> node.statements.firstOrNull { declares(it, name) }?.let { return it is KtProperty }
                is KtFunctionLiteral -> if (node.valueParameters.any { it.name == name }) return true
                is KtNamedFunction -> if (node.valueParameters.any { it.name == name }) return true
                is KtClassOrObject -> if (node.declarations.any { it is KtNamedDeclaration && it.name == name }) return false
                else -> {}
            }
            node = node.parent
        }
        return null
    }

    private fun localDeclares(from: KtElement, name: String): Boolean {
        var node: KtElement? = from.parent
        while (node != null) {
            if (node is KtBlockExpression && node.statements.any { it is KtNamedDeclaration && it.name == name }) return true
            if (node is KtClassOrObject && node.declarations.any { it is KtNamedDeclaration && it.name == name }) return true
            node = node.parent
        }
        return false
    }

    // --- NON_PUBLIC_CALL_FROM_PUBLIC_INLINE ----------------------------------------------------------------

    /**
     * A public-API inline function (or inline property accessor) whose body reads or calls a declaration that
     * is not public API: `private`, or `internal` without `@PublishedApi`. Judged for names reached without a
     * receiver (or through a plain `this`) that land on a member of an enclosing class or a top-level
     * declaration of this file; every same-named candidate there must be non-public for the call to be.
     */
    fun nonPublicCallFromPublicInline(decl: KtDeclaration): List<Diagnostic> {
        val bodies = ArrayList<KtExpression>()
        val owner: KtDeclaration
        when (decl) {
            is KtNamedFunction -> {
                if (!decl.hasModifier(KtTokens.INLINE_KEYWORD)) return emptyList()
                bodies += decl.bodyExpression ?: return emptyList()
                owner = decl
            }
            is KtProperty -> {
                val inlineAll = decl.hasModifier(KtTokens.INLINE_KEYWORD)
                decl.accessors.filter { inlineAll || it.hasModifier(KtTokens.INLINE_KEYWORD) }.mapNotNullTo(bodies) { it.bodyExpression }
                if (bodies.isEmpty()) return emptyList()
                owner = decl
            }
            else -> return emptyList()
        }
        if (!isPublicApi(owner)) return emptyList()
        val file = owner.containingKtFile
        val imported = file.importDirectives.mapNotNull { it.aliasName ?: it.importedFqName?.asString()?.substringAfterLast('.') }.toHashSet()
        val out = ArrayList<Diagnostic>()
        fun visit(e: KtElement) {
            when (e) {
                is KtClassOrObject, is KtNamedFunction, is KtLambdaExpression, is KtCallableReferenceExpression -> return
                is KtNameReferenceExpression -> {
                    nonPublicTarget(e, owner, file, imported)?.let { what ->
                        val r = e.textRange
                        out += Diagnostic(
                            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                            "Public-API inline function cannot access non-public-API $what.",
                            KotlinDiagnosticCodes.NON_PUBLIC_CALL_FROM_PUBLIC_INLINE,
                        )
                    }
                    return
                }
                else -> {}
            }
            for (c in e.children) visit(c)
        }
        bodies.forEach { visit(it) }
        return out
    }

    /** Effective visibility at least as wide as public API: no `private`, `internal` only with `@PublishedApi`,
     *  not local, through every enclosing class. */
    internal fun isPublicApi(decl: KtDeclaration): Boolean {
        var node: KtElement? = decl
        while (node != null && node !is KtFile) {
            when (node) {
                is KtBlockExpression, is KtFunctionLiteral -> return false
                is KtObjectDeclaration -> if (node.isObjectLiteral()) return false
                else -> {}
            }
            if (node is KtDeclaration && (node is KtClassOrObject || node === decl)) {
                if (!visibleAsApi(node)) return false
            }
            node = node.parent
        }
        return true
    }

    private fun visibleAsApi(d: KtDeclaration): Boolean {
        if (d.hasModifier(KtTokens.PRIVATE_KEYWORD)) return false
        if (d.hasModifier(KtTokens.INTERNAL_KEYWORD)) return d.annotationEntries.any { it.shortName?.asString() == "PublishedApi" }
        return true
    }

    /** "function 'f'" / "property 'p'" when [ref] lands on a non-public-API declaration, else null. */
    private fun nonPublicTarget(ref: KtNameReferenceExpression, owner: KtDeclaration, file: KtFile, imported: Set<String>): String? {
        val name = ref.getReferencedName()
        val parent = ref.parent
        if (parent is KtValueArgumentName || parent is KtUserType) return null
        val call = (parent as? KtCallExpression)?.takeIf { it.calleeExpression === ref }
        val node: KtExpression = call ?: ref
        val q = node.parent as? KtQualifiedExpression
        if (q != null && q.selectorExpression === node) {
            // `Z().x` on a class of this file: its members decide.
            constructedClass(q.receiverExpression, file)?.let { cls ->
                val members = cls.declarations.filter { it is KtNamedDeclaration && it.name == name && (it is KtNamedFunction || it is KtProperty) }
                    .map { it as KtCallableDeclaration }.filter { it.receiverTypeReference == null } +
                    cls.primaryConstructorParameters.filter { it.name == name && it.hasValOrVar() }
                if (members.isEmpty() || cls.superTypeListEntries.isNotEmpty()) return null
                return judge(members, call != null, functionsMayHaveOverloads = false)
            }
            // Otherwise only `this.x`, which reads the innermost class's member the same way a bare `x` does.
            val recv = q.receiverExpression as? KtThisExpression ?: return null
            if (recv.getLabelName() != null) return null
        }
        if (localOrParameter(ref, owner, name)) return null
        val isCall = call != null
        // The innermost enclosing class that declares the name decides; with supertypes, an inherited overload
        // could be the one called, so functions are only judged in a class without them.
        var cls = ref.parentClass()
        while (cls != null) {
            val members = cls.declarations.filter { it is KtNamedDeclaration && it.name == name && (it is KtNamedFunction || it is KtProperty) }
                .map { it as KtCallableDeclaration }.filter { it.receiverTypeReference == null } +
                cls.primaryConstructorParameters.filter { it.name == name && it.hasValOrVar() }
            if (members.isNotEmpty() || cls.declarations.any { it is KtNamedDeclaration && it.name == name }) {
                return judge(members, isCall, functionsMayHaveOverloads = cls.superTypeListEntries.isNotEmpty())
            }
            if (cls.superTypeListEntries.isNotEmpty() || cls.companionObjects.isNotEmpty()) return null
            cls = cls.parentClass()
        }
        if (q != null) return null
        if (name in imported) return null
        val top = file.declarations.filter { it is KtNamedDeclaration && it.name == name && (it is KtNamedFunction || it is KtProperty) }
            .map { it as KtCallableDeclaration }.filter { it.receiverTypeReference == null }
        if (top.isEmpty()) return null
        if (file.declarations.any { it is KtClassOrObject && it.name == name }) return null
        return judge(top, isCall, functionsMayHaveOverloads = false)
    }

    /**
     * The verdict over the same-named [candidates] a scope offers: a call needs every function among them to
     * be non-public, a read every property. [functionsMayHaveOverloads] (a class with supertypes) leaves
     * functions alone, since an inherited public overload could be the one called.
     */
    private fun judge(candidates: List<KtDeclaration>, isCall: Boolean, functionsMayHaveOverloads: Boolean): String? {
        val relevant = if (isCall) candidates.filterIsInstance<KtNamedFunction>() else candidates.filter { it !is KtNamedFunction }
        if (relevant.isEmpty()) return null
        if (isCall && functionsMayHaveOverloads) return null
        if (relevant.any { visibleAsApi(it) }) return null
        val target = relevant.first()
        val kind = if (target is KtNamedFunction) "function" else "property"
        return "$kind '${(target as KtNamedDeclaration).name}'"
    }

    /** The top-level class of this file that [receiver] constructs (`Z()`), when the name means nothing else. */
    private fun constructedClass(receiver: KtExpression, file: KtFile): KtClass? {
        val call = receiver as? KtCallExpression ?: return null
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        if (localDeclares(call, name)) return null
        val decls = file.declarations.filter { it is KtNamedDeclaration && it.name == name }
        val cls = decls.singleOrNull() as? KtClass ?: return null
        return cls.takeIf { !it.isInterface() && !it.isEnum() }
    }

    private fun localOrParameter(ref: KtElement, owner: KtDeclaration, name: String): Boolean {
        var node: KtElement? = ref.parent
        while (node != null && node !== owner) {
            when (node) {
                is KtBlockExpression -> if (node.statements.any { declares(it, name) }) return true
                is KtNamedFunction -> if (node.valueParameters.any { it.name == name }) return true
                is KtPropertyAccessor -> if (node.valueParameters.any { it.name == name } || (node.isSetter && name == "value" && node.valueParameters.isEmpty())) return true
                else -> {}
            }
            node = node.parent
        }
        if (owner is KtNamedFunction && owner.valueParameters.any { it.name == name }) return true
        if (name == "field") return true
        return false
    }

    private fun KtElement.parentClass(): KtClassOrObject? {
        var node = parent
        while (node != null) {
            if (node is KtClassOrObject) return node
            node = node.parent
        }
        return null
    }
}
