package dev.ide.lang.java.analysis

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import dev.ide.analysis.ACTION_PROVIDER_EP
import dev.ide.analysis.ActionProvider
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.FixContext
import dev.ide.analysis.QUICK_FIX_PROVIDER_EP
import dev.ide.analysis.QuickFix
import dev.ide.analysis.QuickFixProvider
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.lang.java.resolve.JavaExceptions
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.psi.IntellijPsiHost

/**
 * Native quick-fixes for the IntelliJ-PSI Java backend's own diagnostic codes (`JavaDiagnosticCodes`), keyed
 * on the diagnostic offset and computed against the live buffer's PSI under the parse lock — no ecj/JDT. The
 * ecj-problem-code-keyed JDT fixes (unused-local, etc.) don't fire under this backend since their codes aren't
 * produced; these replace the useful subset. Registered by [JavaPsiAnalysisSupport].
 */
object JavaPsiAnalysisSupport {
    val PLUGIN = PluginId("java-psi-analysis")

    fun register(extensions: ExtensionRegistry, plugin: PluginId = PLUGIN) {
        // "Surround with try/catch" is already offered by the position-based JDT ActionProvider (it walks the
        // neutral DOM, so it works under this backend too) — so only the fixes it doesn't cover are added here.
        extensions.register(QUICK_FIX_PROVIDER_EP, AddExceptionToThrowsFixProvider(), plugin)
        extensions.register(QUICK_FIX_PROVIDER_EP, ChangeVariableTypeFixProvider(), plugin)
        extensions.register(QUICK_FIX_PROVIDER_EP, CreateMethodFromUsageFixProvider(), plugin)
        extensions.register(ACTION_PROVIDER_EP, ImplementMembersActionProvider(), plugin)
    }
}

private val JAVA = LanguageId("java")

/** "Add exception to method signature": append the unhandled checked exception(s) to the enclosing method's
 *  `throws` clause (creating one if absent), with imports. */
internal class AddExceptionToThrowsFixProvider : QuickFixProvider {
    override val forCodes = setOf(JavaDiagnosticCodes.UNHANDLED_EXCEPTION)
    override val languages = setOf(JAVA)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val offset = diagnostic.range.start
        val names = IntellijPsiHost.withParseLock {
            val el = leafAt(target, offset)?.second ?: return@withParseLock emptyList()
            if (PsiTreeUtil.getParentOfType(el, PsiMethod::class.java) == null) return@withParseLock emptyList()
            JavaExceptions.unhandledFor(el).map { it.presentableText }
        }
        if (names.isEmpty()) return emptyList()
        return listOf(object : QuickFix {
            override val title = "Add '${names.joinToString(", ")}' to method 'throws'"
            override val kind = CodeActionKind.QUICK_FIX
            override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit = IntellijPsiHost.withParseLock {
                val (file, el) = leafAt(ctx.target, offset) ?: return@withParseLock WorkspaceEdit.EMPTY
                val method = PsiTreeUtil.getParentOfType(el, PsiMethod::class.java) ?: return@withParseLock WorkspaceEdit.EMPTY
                val exs = JavaExceptions.unhandledFor(el)
                if (exs.isEmpty()) return@withParseLock WorkspaceEdit.EMPTY
                val simple = exs.joinToString(", ") { it.presentableText }
                val edits = ArrayList<DocumentEdit>()
                val throwsRefs = method.throwsList.referenceElements
                if (throwsRefs.isNotEmpty()) {
                    edits += DocumentEdit(throwsRefs.last().textRange.endOffset, 0, ", $simple")
                } else {
                    edits += DocumentEdit(method.parameterList.textRange.endOffset, 0, " throws $simple")
                }
                for (ex in exs) importEditFor(file, ex)?.let { edits += it }
                WorkspaceEdit.of(ctx.target.file, *edits.toTypedArray())
            }
        })
    }
}

/** "Change variable type to X": for a variable/field initializer type mismatch, retype the declaration to the
 *  initializer's actual type (skipped for a `var` declaration). */
internal class ChangeVariableTypeFixProvider : QuickFixProvider {
    override val forCodes = setOf(JavaDiagnosticCodes.TYPE_MISMATCH)
    override val languages = setOf(JAVA)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val offset = diagnostic.range.start
        val newTypeName = IntellijPsiHost.withParseLock {
            val el = leafAt(target, offset)?.second ?: return@withParseLock null
            val v = PsiTreeUtil.getParentOfType(el, PsiVariable::class.java) ?: return@withParseLock null
            val te = v.typeElement ?: return@withParseLock null
            if (te.isInferredType) return@withParseLock null            // `var` — nothing to retype
            v.initializer?.type?.presentableText
        } ?: return emptyList()
        return listOf(object : QuickFix {
            override val title = "Change variable type to '$newTypeName'"
            override val kind = CodeActionKind.QUICK_FIX
            override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit = IntellijPsiHost.withParseLock {
                val (file, el) = leafAt(ctx.target, offset) ?: return@withParseLock WorkspaceEdit.EMPTY
                val v = PsiTreeUtil.getParentOfType(el, PsiVariable::class.java) ?: return@withParseLock WorkspaceEdit.EMPTY
                val te = v.typeElement ?: return@withParseLock WorkspaceEdit.EMPTY
                if (te.isInferredType) return@withParseLock WorkspaceEdit.EMPTY
                val nt = v.initializer?.type ?: return@withParseLock WorkspaceEdit.EMPTY
                val edits = ArrayList<DocumentEdit>()
                edits += DocumentEdit(te.textRange.startOffset, te.textRange.length, nt.presentableText)
                (nt as? PsiClassType)?.let { importEditFor(file, it)?.let { e -> edits += e } }
                WorkspaceEdit.of(ctx.target.file, *edits.toTypedArray())
            }
        })
    }
}

/** "Create method 'foo'": for an unresolved UNQUALIFIED (or `this.`) call, synthesize a stub in the enclosing
 *  class — parameter types inferred from the argument expressions, return type from the call's context. */
internal class CreateMethodFromUsageFixProvider : QuickFixProvider {
    override val forCodes = setOf(JavaDiagnosticCodes.UNRESOLVED)
    override val languages = setOf(JAVA)

    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> {
        val offset = diagnostic.range.start
        val name = IntellijPsiHost.withParseLock {
            val el = leafAt(target, offset)?.second ?: return@withParseLock null
            val call = enclosingUnresolvedCall(el, offset) ?: return@withParseLock null
            call.methodExpression.referenceName
        } ?: return emptyList()
        return listOf(object : QuickFix {
            override val title = "Create method '$name'"
            override val kind = CodeActionKind.QUICK_FIX
            override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit = IntellijPsiHost.withParseLock {
                val (_, el) = leafAt(ctx.target, offset) ?: return@withParseLock WorkspaceEdit.EMPTY
                val call = enclosingUnresolvedCall(el, offset) ?: return@withParseLock WorkspaceEdit.EMPTY
                val cls = PsiTreeUtil.getParentOfType(call, PsiClass::class.java) ?: return@withParseLock WorkspaceEdit.EMPTY
                val rBrace = cls.rBrace ?: return@withParseLock WorkspaceEdit.EMPTY
                val mName = call.methodExpression.referenceName ?: return@withParseLock WorkspaceEdit.EMPTY
                val static = PsiTreeUtil.getParentOfType(call, PsiMethod::class.java)?.hasModifierProperty(PsiModifier.STATIC) == true
                val ret = inferReturnType(call)
                val params = call.argumentList.expressions.mapIndexed { i, a ->
                    "${a.type?.presentableText ?: "Object"} p$i"
                }.joinToString(", ")
                val i = "    "
                val stub = "\n$i private ${if (static) "static " else ""}$ret $mName($params) {\n$i${i}throw new UnsupportedOperationException();\n$i}\n"
                WorkspaceEdit.of(ctx.target.file, DocumentEdit(rBrace.textRange.startOffset, 0, stub))
            }
        })
    }
}

/** "Implement methods": generate stubs for the abstract methods a concrete class hasn't implemented, inserted
 *  before its closing brace. A position-based intention (no diagnostic needed). */
internal class ImplementMembersActionProvider : ActionProvider {
    override val languages = setOf(JAVA)

    override fun actions(target: AnalysisTarget, range: TextRange): List<QuickFix> {
        val offset = range.start
        val offer = IntellijPsiHost.withParseLock {
            classAt(target, offset)?.let { unimplemented(it).isNotEmpty() } ?: false
        }
        if (!offer) return emptyList()
        return listOf(object : QuickFix {
            override val title = "Implement methods"
            override val kind = CodeActionKind.INTENTION
            override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit = IntellijPsiHost.withParseLock {
                val cls = classAt(ctx.target, offset) ?: return@withParseLock WorkspaceEdit.EMPTY
                val rBrace = cls.rBrace ?: return@withParseLock WorkspaceEdit.EMPTY
                val methods = unimplemented(cls)
                if (methods.isEmpty()) return@withParseLock WorkspaceEdit.EMPTY
                val stubs = methods.joinToString("") { renderStub(it) }
                WorkspaceEdit.of(ctx.target.file, DocumentEdit(rBrace.textRange.startOffset, 0, stubs))
            }
        })
    }
}

// --- shared helpers ---------------------------------------------------------------------------------------

/** The unresolved call whose method name is at [offset], when unqualified or `this.`-qualified (enclosing-class
 *  create only — a qualified call would target another type, possibly another file). Null otherwise. */
private fun enclosingUnresolvedCall(el: PsiElement, offset: Int): com.intellij.psi.PsiMethodCallExpression? {
    val call = PsiTreeUtil.getParentOfType(el, com.intellij.psi.PsiMethodCallExpression::class.java, false) ?: return null
    val nameEl = call.methodExpression.referenceNameElement ?: return null
    if (offset < nameEl.textRange.startOffset || offset > nameEl.textRange.endOffset) return null
    val qualifier = call.methodExpression.qualifierExpression
    if (qualifier != null && qualifier !is com.intellij.psi.PsiThisExpression) return null
    if (call.resolveMethod() != null) return null
    if (PsiTreeUtil.getParentOfType(call, PsiClass::class.java) == null) return null
    return call
}

/** The context return type for a created method: `void` in statement position, else the expected type
 *  (initializer / return / assignment), falling back to `Object`. */
private fun inferReturnType(call: com.intellij.psi.PsiMethodCallExpression): String {
    if (call.parent is com.intellij.psi.PsiExpressionStatement) return "void"
    PsiTreeUtil.getParentOfType(call, PsiVariable::class.java, false)?.let { v ->
        val init = v.initializer
        if (init != null && PsiTreeUtil.isAncestor(init, call, false)) return v.type.presentableText
    }
    PsiTreeUtil.getParentOfType(call, com.intellij.psi.PsiReturnStatement::class.java, false)?.let { ret ->
        val rv = ret.returnValue
        if (rv != null && PsiTreeUtil.isAncestor(rv, call, false)) {
            (PsiTreeUtil.getParentOfType(ret, PsiMethod::class.java, com.intellij.psi.PsiLambdaExpression::class.java) as? PsiMethod)
                ?.returnType?.let { return it.presentableText }
        }
    }
    PsiTreeUtil.getParentOfType(call, com.intellij.psi.PsiAssignmentExpression::class.java, false)?.let { asg ->
        val rhs = asg.rExpression
        if (rhs != null && PsiTreeUtil.isAncestor(rhs, call, false)) asg.lExpression.type?.let { return it.presentableText }
    }
    return "Object"
}

private fun classAt(target: AnalysisTarget, offset: Int): PsiClass? {
    val file = (target.parsed as? JavaParsedFile)?.javaFile ?: return null
    val len = file.textLength
    val el = file.findElementAt(offset.coerceIn(0, (len - 1).coerceAtLeast(0))) ?: return null
    return PsiTreeUtil.getParentOfType(el, PsiClass::class.java, false)
}

private fun unimplemented(cls: PsiClass): List<PsiMethod> = dev.ide.lang.java.resolve.JavaOverrides.unimplemented(cls)

private fun renderStub(m: PsiMethod): String {
    val i = "    "
    val ret = m.returnType?.presentableText ?: "void"
    val params = m.parameterList.parameters.mapIndexed { idx, p -> "${p.type.presentableText} ${p.name ?: "p$idx"}" }.joinToString(", ")
    val vis = if (m.hasModifierProperty(PsiModifier.PROTECTED)) "protected" else "public"
    return "\n$i@Override\n$i$vis $ret ${m.name}($params) {\n$i${i}throw new UnsupportedOperationException();\n$i}\n"
}

private fun leafAt(target: AnalysisTarget, offset: Int): Pair<PsiJavaFile, PsiElement>? {
    val file = (target.parsed as? JavaParsedFile)?.javaFile ?: return null
    val len = file.textLength
    val el = file.findElementAt(offset.coerceIn(0, (len - 1).coerceAtLeast(0))) ?: return null
    return file to el
}

/** An `import <fqn>;` edit for [type], or null when none is needed (primitive, java.lang, same package, or
 *  already imported). */
private fun importEditFor(file: PsiJavaFile, type: PsiClassType): DocumentEdit? {
    val fqn = type.resolve()?.qualifiedName ?: return null
    val pkg = fqn.substringBeforeLast('.', "")
    if (pkg.isEmpty() || pkg == "java.lang" || pkg == file.packageName) return null
    file.importList?.importStatements?.forEach { imp ->
        val q = imp.qualifiedName ?: return@forEach
        if (q == fqn || (imp.isOnDemand && q == pkg)) return null
    }
    val anchor = file.importList?.importStatements?.lastOrNull()?.textRange?.endOffset
        ?: file.packageStatement?.textRange?.endOffset
        ?: 0
    return DocumentEdit(anchor, 0, "\nimport $fqn;")
}
