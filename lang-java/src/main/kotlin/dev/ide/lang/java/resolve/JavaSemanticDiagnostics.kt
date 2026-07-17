package dev.ide.lang.java.resolve

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiBreakStatement
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiContinueStatement
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPackageStatement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiSwitchExpression
import com.intellij.psi.PsiThrowStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiUnaryExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.controlFlow.ControlFlowFactory
import com.intellij.psi.controlFlow.ControlFlowUtil
import com.intellij.psi.controlFlow.LocalsOrMyInstanceFieldsControlFlowPolicy
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.psi.IntellijPsiHost

/**
 * Resolution- and control-flow-derived semantic diagnostics, hand-rolled over IntelliJ PSI (the heavy
 * `java-analysis-impl` highlight visitor is deliberately not bundled). Computed in one tree walk under one
 * parse-lock acquisition:
 *  - **unresolved reference** — undefined types / methods / fields / variables / imports;
 *  - **assignment-conversion mismatch** — an initializer / `=` / `return` value whose type can't convert to
 *    the target, and a `return` value in a `void` method / missing value in a value-returning one;
 *  - **missing return** — a value-returning method whose body can complete without returning;
 *  - **unreachable statement** — code after a `return`/`throw`/`break`/`continue`;
 *  - **definite assignment** — a local read before it is assigned (and blank-`final` fields, in
 *    [JavaDeclarationChecks]), via IntelliJ's own control-flow;
 *  - **abstract instantiation** — `new` of an abstract class / interface without an anonymous body;
 *  - **argument applicability** — a method call ([checkApplicability]) or `new`
 *    ([checkConstructorApplicability]) whose arguments fit no overload;
 *  - **illegal jump** — an unlabeled `break`/`continue` with no enclosing loop/switch;
 *  - **`var` inference** — a `var` local with no initializer / a `null` initializer;
 *  - **unhandled checked exception** — a checked throw / call / `new` neither caught nor declared.
 *
 * Control-flow checks use IntelliJ's own [ControlFlowUtil] (matching javac's reachability + definite
 * assignment, incl. the `if`-vs-`while(true)` distinction), so they are as accurate as the platform. The type,
 * applicability + exception checks are deliberately narrow to guarantee ZERO false positives: the type check
 * fires only when both types are fully resolved and non-generic ([isCheckable]) and the value is not a poly
 * expression; applicability defers the verdict to the resolver and skips generic/poly cases; the exception
 * check backs off inside lambdas / field + `static` initializers and on unresolved / generic exception types.
 *
 * ART-safety: `resolve()` / `getType()` / control-flow can lazily build decompiled (`Cls`) PSI, i.e.
 * `buildTree`, which must not race another parse on ART — so the whole pass runs under
 * [IntellijPsiHost.withParseLock] (the exclusive action). It is a debounced SEMANTIC-tier pass, not
 * per-keystroke-hot.
 */
internal object JavaSemanticDiagnostics {

    fun of(psi: PsiJavaFile): List<Diagnostic> = IntellijPsiHost.withParseLock {
        val out = ArrayList<Diagnostic>()
        psi.accept(object : JavaRecursiveElementVisitor() {
            // The platform's visitReferenceExpression delegates to visitReferenceElement, so overriding just
            // this one catches BOTH plain code references (types/imports) AND reference expressions (names/
            // member access / calls) exactly once.
            override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
                reportUnresolved(reference, out)
                super.visitReferenceElement(reference)
            }

            override fun visitLocalVariable(variable: PsiLocalVariable) {
                super.visitLocalVariable(variable)
                checkVarInference(variable, out)
                checkAssignment(variable.type, variable.initializer, out)
            }

            override fun visitField(field: PsiField) {
                super.visitField(field)
                checkAssignment(field.type, field.initializer, out)
            }

            override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                super.visitAssignmentExpression(expression)
                checkFinalReassignment(expression.lExpression, out) // any op reassigns
                if (expression.operationSign.tokenType == JavaTokenType.EQ) // type check: simple `=` only
                    checkAssignment(expression.lExpression.type, expression.rExpression, out)
            }

            override fun visitUnaryExpression(expression: PsiUnaryExpression) {
                super.visitUnaryExpression(expression)
                val op = expression.operationTokenType
                if (op == JavaTokenType.PLUSPLUS || op == JavaTokenType.MINUSMINUS)
                    checkFinalReassignment(expression.operand, out)
            }

            override fun visitReturnStatement(statement: PsiReturnStatement) {
                super.visitReturnStatement(statement)
                checkReturn(statement, out)
            }

            override fun visitClass(aClass: com.intellij.psi.PsiClass) {
                super.visitClass(aClass)
                JavaDeclarationChecks.checkClass(aClass, out)
            }

            override fun visitMethod(method: PsiMethod) {
                super.visitMethod(method)
                checkControlFlow(method, out)
                JavaDeclarationChecks.checkMethod(method, out)
            }

            override fun visitThrowStatement(statement: PsiThrowStatement) {
                super.visitThrowStatement(statement)
                val ex = statement.exception ?: return
                reportUnhandled(ex, listOfNotNull(ex.type), out)
            }

            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val anchor = expression.methodExpression.referenceNameElement ?: return
                expression.resolveMethod()?.let { reportUnhandled(anchor, it.throwsList.referencedTypes.toList(), out) }
                checkApplicability(expression, anchor, out)
            }

            override fun visitPolyadicExpression(expression: PsiPolyadicExpression) {
                super.visitPolyadicExpression(expression)
                checkOperandTypes(expression, out)
            }

            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                val anchor = expression.classReference ?: return
                checkInstantiable(expression, anchor, out)
                checkConstructorApplicability(expression, anchor, out)
                val ctor = expression.resolveConstructor() ?: return
                reportUnhandled(anchor, ctor.throwsList.referencedTypes.toList(), out)
            }

            override fun visitBreakStatement(statement: PsiBreakStatement) {
                super.visitBreakStatement(statement)
                // Only an UNLABELED break: a labeled `break x` with no target is an unresolved-label error, not
                // "outside a loop". `findExitedStatement` is the platform's own resolution.
                if (statement.labelIdentifier != null) return
                if (PsiTreeUtil.getParentOfType(statement, PsiErrorElement::class.java) != null) return
                if (statement.findExitedStatement() == null)
                    out += diag(statement, "Break outside switch or loop", JavaDiagnosticCodes.ILLEGAL_JUMP)
            }

            override fun visitContinueStatement(statement: PsiContinueStatement) {
                super.visitContinueStatement(statement)
                if (statement.labelIdentifier != null) return
                if (PsiTreeUtil.getParentOfType(statement, PsiErrorElement::class.java) != null) return
                if (statement.findContinuedStatement() == null)
                    out += diag(statement, "Continue outside of loop", JavaDiagnosticCodes.ILLEGAL_JUMP)
            }
        })
        out
    }

    // --- instantiation checks (abstract `new`, constructor applicability) ---------------------------------

    /** `new Foo(...)` where `Foo` is an abstract class or interface (and not an anonymous-body `new Foo() {}`,
     *  which supplies the implementation) — nothing concrete to instantiate. Array creations, unresolved refs,
     *  enums and annotations (which can't appear after `new` at all) are left to other checks. */
    private fun checkInstantiable(expr: PsiNewExpression, anchor: PsiJavaCodeReferenceElement, out: MutableList<Diagnostic>) {
        if (expr.anonymousClass != null) return
        if (expr.arrayDimensions.isNotEmpty() || expr.arrayInitializer != null) return // `new Runnable[3]` is legal
        val cls = expr.classReference?.resolve() as? PsiClass ?: return                 // unresolved → ref check owns it
        if (cls is PsiTypeParameter || cls.isEnum || cls.isAnnotationType) return
        if (cls.isInterface || cls.hasModifierProperty(PsiModifier.ABSTRACT)) {
            out += diag(anchor.referenceNameElement ?: anchor, "'${cls.name}' is abstract; cannot be instantiated",
                JavaDiagnosticCodes.ABSTRACT_INSTANTIATION)
        }
    }

    /** Constructor-argument applicability — the `new`-expression mirror of [checkApplicability]. Same
     *  zero-false-positive guards: only when the class resolves, is concrete (the instantiable check owns the
     *  abstract/interface case), is non-generic (diamond/inference skipped), the constructor element resolves
     *  but is an invalid result, and no argument is poly / unresolved / the list incomplete. The implicit
     *  default constructor (null element) is deliberately not reported (under-report over risk). */
    private fun checkConstructorApplicability(expr: PsiNewExpression, anchor: PsiJavaCodeReferenceElement, out: MutableList<Diagnostic>) {
        if (expr.anonymousClass != null) return
        if (expr.arrayDimensions.isNotEmpty() || expr.arrayInitializer != null) return
        val cls = expr.classReference?.resolve() as? PsiClass ?: return
        if (cls.isInterface || cls.hasModifierProperty(PsiModifier.ABSTRACT) || cls.hasTypeParameters()) return
        val argList = expr.argumentList ?: return
        if (PsiTreeUtil.findChildOfType(argList, PsiErrorElement::class.java) != null) return
        if (argList.expressions.any { it.type == null || isPoly(it) }) return
        val result = expr.resolveMethodGenerics()
        val ctor = result.element as? PsiMethod ?: return
        if (result.isValidResult || ctor.hasTypeParameters()) return
        out += diag(anchor.referenceNameElement ?: anchor,
            "Constructor '${cls.name}' cannot be applied to the given arguments", JavaDiagnosticCodes.CANNOT_APPLY)
    }

    // --- unresolved references ----------------------------------------------------------------------------

    private fun reportUnresolved(ref: PsiJavaCodeReferenceElement, out: MutableList<Diagnostic>) {
        if (PsiTreeUtil.getParentOfType(ref, PsiErrorElement::class.java) != null) return
        if (PsiTreeUtil.getParentOfType(ref, PsiPackageStatement::class.java) != null) return
        // Report only the ROOT unresolved reference: if the qualifier itself doesn't resolve / has no type,
        // that qualifier is the real error and this one is a cascade — skip it.
        if (!qualifierResolvable(ref.qualifier)) return
        if (ref.resolve() != null) return
        val nameEl = ref.referenceNameElement ?: return
        val name = ref.referenceName ?: return
        out += diag(nameEl, "Cannot resolve symbol '$name'", JavaDiagnosticCodes.UNRESOLVED)
    }

    private fun qualifierResolvable(qualifier: PsiElement?): Boolean = when (qualifier) {
        null -> true
        is PsiReferenceExpression -> qualifier.resolve() != null || qualifier.type != null
        is PsiJavaCodeReferenceElement -> qualifier.resolve() != null
        is PsiExpression -> qualifier.type != null
        else -> true
    }

    // --- `var` inference ----------------------------------------------------------------------------------

    /** A `var` local whose type can't be inferred: no initializer (`var a;`) or a `null` one (`var a = null;`).
     *  Both are hard javac errors. Only fires when the type element is genuinely inferred (`isInferredType`), so
     *  a non-`var` declaration or a `var` with a real initializer is untouched; enhanced-for / lambda `var`
     *  parameters aren't `PsiLocalVariable`s, so they never reach here. */
    private fun checkVarInference(v: PsiLocalVariable, out: MutableList<Diagnostic>) {
        val te = v.typeElement
        if (te == null || !te.isInferredType) return
        val init = v.initializer
        val msg = when {
            init == null -> "Cannot infer type: 'var' on a variable without an initializer"
            init.type?.equalsToText("null") == true -> "Cannot infer type: variable initializer is 'null'"
            else -> return
        }
        out += diag(te, msg, JavaDiagnosticCodes.CANNOT_INFER_VAR)
    }

    // --- assignment-conversion checks ---------------------------------------------------------------------

    private fun checkAssignment(expected: PsiType?, value: PsiExpression?, out: MutableList<Diagnostic>) {
        if (value == null) return
        val valueType = value.type ?: return                        // unresolved value → ref check handles it
        if (!isCheckable(expected) || !isCheckable(valueType)) return
        if (isPoly(value)) return
        if (TypeConversionUtil.areTypesAssignmentCompatible(expected, value)) return
        out += diag(
            value,
            "Incompatible types: '${valueType.presentableText}' cannot be converted to " +
                "'${expected!!.presentableText}'",
            JavaDiagnosticCodes.TYPE_MISMATCH,
        )
    }

    /** Flag an assignment/increment to a `final` variable that is *definitely* already assigned — a final
     *  parameter, or a final local with an initializer. Blank finals and final fields (legally assigned once in
     *  a constructor/initializer) need definite-assignment flow analysis, so are NOT flagged (zero false positives). */
    private fun checkFinalReassignment(lhs: com.intellij.psi.PsiExpression?, out: MutableList<Diagnostic>) {
        val ref = lhs as? PsiReferenceExpression ?: return
        val v = ref.resolve() as? PsiVariable ?: return
        if (!v.hasModifierProperty(PsiModifier.FINAL)) return
        val illegal = when (v) {
            is PsiParameter -> true
            is PsiLocalVariable -> v.initializer != null
            else -> false // final field / other → blank-final rules, skip
        }
        if (illegal) {
            out += diag(ref.referenceNameElement ?: ref, "Cannot assign a value to final variable '${v.name}'",
                JavaDiagnosticCodes.FINAL_REASSIGNMENT)
        }
    }

    private fun checkReturn(statement: PsiReturnStatement, out: MutableList<Diagnostic>) {
        val method = PsiTreeUtil.getParentOfType(
            statement, PsiMethod::class.java, PsiLambdaExpression::class.java,
        ) as? PsiMethod ?: return
        if (method.isConstructor) return
        val returnType = method.returnType ?: return
        val value = statement.returnValue
        val isVoid = returnType.equalsToText("void")
        when {
            isVoid && value != null ->
                out += diag(value, "Cannot return a value from a method with void result type", JavaDiagnosticCodes.RETURN_VALUE)
            !isVoid && value == null ->
                out += diag(statement, "Missing return value", JavaDiagnosticCodes.RETURN_VALUE)
            !isVoid && value != null -> checkAssignment(returnType, value, out)
        }
    }

    // --- control flow (missing return + unreachable) — IntelliJ's own reachability ------------------------

    private fun checkControlFlow(method: PsiMethod, out: MutableList<Diagnostic>) {
        val body = method.body ?: return
        val policy = LocalsOrMyInstanceFieldsControlFlowPolicy.getInstance()
        // Unreachable + read-before-write — off the standard flow (JLS reachability: folds `while(false)` but
        // not `if(false)`). `getReadBeforeWriteLocals` is IntelliJ's own definite-assignment computation for
        // locals, so "might not have been initialized" is as precise as the platform (no false positives).
        runCatching { ControlFlowFactory.getInstance(method.project).getControlFlow(body, policy) }.getOrNull()
            ?.let { flow ->
                runCatching { ControlFlowUtil.getUnreachableStatement(flow) }.getOrNull()?.let { unreachable ->
                    out += diag(unreachable, "Unreachable statement", JavaDiagnosticCodes.UNREACHABLE)
                }
                runCatching { ControlFlowUtil.getReadBeforeWriteLocals(flow) }.getOrDefault(emptyList()).forEach { ref ->
                    // The flow policy also tracks `this.field`, so this list can include instance fields read in
                    // a method that doesn't assign them — but such a field is legally assigned in a constructor
                    // (checked separately in JavaDeclarationChecks). Restrict to true LOCALS to stay zero-FP.
                    if (ref.resolve() !is PsiLocalVariable) return@forEach
                    val name = ref.referenceName ?: return@forEach
                    out += diag(ref, "Variable '$name' might not have been initialized", JavaDiagnosticCodes.NOT_INITIALIZED)
                }
                // A blank-final LOCAL assigned on a path where it may already be assigned. Restricted to blank
                // finals (final-with-initializer + final params are handled by checkFinalReassignment) and to
                // locals (fields need cross-constructor analysis), so no double-report / field FP.
                runCatching { ControlFlowUtil.getInitializedTwice(flow) }.getOrDefault(emptyList()).forEach { info ->
                    val v = (info.expression as? PsiReferenceExpression)?.resolve() as? PsiVariable ?: return@forEach
                    if (v is PsiLocalVariable && v.hasModifierProperty(PsiModifier.FINAL) && v.initializer == null) {
                        out += diag(info.expression, "Variable '${v.name}' might already have been assigned",
                            JavaDiagnosticCodes.FINAL_REASSIGNMENT)
                    }
                }
            }
        // Missing return — a value-returning method whose end is reachable. Uses the no-constant-evaluate flow,
        // matching javac (`if (cond) return x;` at the end is still "missing return").
        val returnType = method.returnType
        if (!method.isConstructor && returnType != null && !returnType.equalsToText("void")) {
            val flow = runCatching { ControlFlowFactory.getControlFlowNoConstantEvaluate(body) }.getOrNull() ?: return
            val returns = runCatching { ControlFlowUtil.returnPresent(flow) }.getOrDefault(true)
            if (!returns) {
                val anchor = body.rBrace ?: method.nameIdentifier ?: return
                out += diag(anchor, "Missing return statement", JavaDiagnosticCodes.MISSING_RETURN)
            }
        }
    }

    // --- unhandled checked exceptions ---------------------------------------------------------------------

    private fun reportUnhandled(anchor: PsiElement, thrown: List<PsiType>, out: MutableList<Diagnostic>) {
        val unhandled = thrown.filterIsInstance<PsiClassType>()
            .filter { JavaExceptions.isChecked(it) && !JavaExceptions.isHandled(anchor, it) }
        if (unhandled.isEmpty()) return
        val names = unhandled.joinToString(", ") { it.presentableText }
        out += diag(anchor, "Unhandled exception: $names", JavaDiagnosticCodes.UNHANDLED_EXCEPTION)
    }

    // --- argument applicability ("cannot be applied to …") ------------------------------------------------

    /**
     * When a call's name resolves to a method but the arguments don't fit it, report it — deferring the
     * VERDICT to the resolver's own `isValidResult` (not a hand-rolled applicability judgement). Guarded to be
     * false-positive-free: only when the name resolves (so the unresolved-ref check doesn't also fire), the
     * method is non-generic and the call has no explicit type args, no argument is a poly / target-typed / not-
     * yet-resolved expression, and the argument list is syntactically complete.
     */
    private fun checkApplicability(call: PsiMethodCallExpression, anchor: PsiElement, out: MutableList<Diagnostic>) {
        if (call.methodExpression.resolve() == null) return // unresolved-ref check owns a missing name
        val result = call.resolveMethodGenerics()
        val method = result.element as? PsiMethod ?: return
        if (result.isValidResult) return
        if (method.hasTypeParameters() || call.typeArgumentList.typeArguments.isNotEmpty()) return
        val argList = call.argumentList
        if (PsiTreeUtil.findChildOfType(argList, PsiErrorElement::class.java) != null) return
        if (argList.expressions.any { it.type == null || isPoly(it) }) return
        out += diag(anchor, "'${method.name}' cannot be applied to the given arguments", JavaDiagnosticCodes.CANNOT_APPLY)
    }

    // --- operator operand types ("operator '-' cannot be applied to 'String'") ----------------------------

    private fun checkOperandTypes(expr: PsiPolyadicExpression, out: MutableList<Diagnostic>) {
        if (expr.operationTokenType !in NUMERIC_OPS) return
        val operands = expr.operands
        // Any uncertain operand (target-typed or not-yet-resolved) → skip the whole expression (no risk).
        if (operands.any { isPoly(it) || it.type == null }) return
        for (operand in operands) {
            val t = operand.type ?: continue
            if (!isNumericType(t)) {
                out += diag(operand, "Operator cannot be applied to '${t.presentableText}'", JavaDiagnosticCodes.BAD_OPERAND)
            }
        }
    }

    /** A numeric type — a numeric primitive or its boxed wrapper (the operands the arithmetic/relational
     *  operators accept, after unboxing). */
    private fun isNumericType(t: PsiType): Boolean = when {
        t is PsiPrimitiveType -> PRIMITIVE_NUMERICS.any { t.equalsToText(it) }
        else -> BOXED_NUMERICS.any { t.equalsToText(it) }
    }

    // --- shared -------------------------------------------------------------------------------------------

    private fun diag(element: PsiElement, message: String, code: String): Diagnostic {
        val r = element.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, code = code)
    }

    /** A poly / target-typed expression (see the type-mismatch note); its type depends on context. */
    private fun isPoly(expr: PsiExpression): Boolean = when (expr) {
        is PsiLambdaExpression, is PsiMethodReferenceExpression,
        is PsiConditionalExpression, is PsiSwitchExpression -> true
        is PsiParenthesizedExpression -> expr.expression?.let { isPoly(it) } ?: false
        is PsiMethodCallExpression -> {
            val m = expr.resolveMethod()
            m != null && m.hasTypeParameters() && expr.typeArgumentList.typeArguments.isEmpty()
        }
        is PsiNewExpression -> {
            val pl = expr.classReference?.parameterList
            pl != null && pl.typeArguments.isEmpty() && pl.text.trim() == "<>" // diamond
        }
        else -> false
    }

    /** Concrete enough to judge an assignment conversion (see the type-mismatch note). */
    private fun isCheckable(type: PsiType?): Boolean = when (type) {
        null -> false
        is PsiPrimitiveType -> true
        is PsiArrayType -> isCheckable(type.componentType)
        is PsiClassType -> {
            val resolved = type.resolve()
            resolved != null && resolved !is PsiTypeParameter && type.parameters.all { isCheckable(it) }
        }
        else -> false
    }

    // Arithmetic + relational operators whose operands must be numeric (`+` excluded — it is also string
    // concatenation; `== !=`, `&& || & | ^`, and shifts excluded — their operand rules aren't purely numeric).
    private val NUMERIC_OPS: Set<com.intellij.psi.tree.IElementType> = setOf(
        JavaTokenType.MINUS, JavaTokenType.ASTERISK, JavaTokenType.DIV, JavaTokenType.PERC,
        JavaTokenType.LT, JavaTokenType.GT, JavaTokenType.LE, JavaTokenType.GE,
    )
    private val PRIMITIVE_NUMERICS = listOf("byte", "short", "int", "long", "float", "double", "char")
    private val BOXED_NUMERICS = listOf(
        "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
        "java.lang.Float", "java.lang.Double", "java.lang.Character",
    )
}
