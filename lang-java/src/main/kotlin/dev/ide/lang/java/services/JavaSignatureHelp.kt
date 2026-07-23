package dev.ide.lang.java.services

import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.util.PsiTreeUtil
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.index.JavaDoc
import dev.ide.psi.IntellijPsiHost
import dev.ide.lang.signature.ParameterInfo
import dev.ide.lang.signature.SignatureHelp
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.lang.signature.SignatureInfo

/**
 * Parameter-info popup. Resolves the (possibly overloaded) call the caret sits inside — via IntelliJ's
 * multi-resolve for method calls and the class's constructors for `new` — renders each overload's signature
 * with per-parameter label ranges, and reports the active parameter from the comma count before the caret.
 */
class JavaSignatureHelp(private val env: JavaEnvironment) : SignatureHelpService {

    // Under the parse lock (exclusive): `multiResolve` / `resolve` can lazily `buildTree` classpath PSI, which
    // must not race another parse on ART (same rule as JavaSemanticHighlighter). `withParseLock` isn't inline,
    // so early exits use the labeled `return@withParseLock`.
    override suspend fun signatureHelp(request: SignatureHelpRequest): SignatureHelp? = IntellijPsiHost.withParseLock {
        val text = request.document.text
        val offset = request.offset.coerceIn(0, text.length)
        val psi = env.parse(request.document.file.name, text)
        val leaf = psi.findElementAt((offset - 1).coerceAtLeast(0)) ?: return@withParseLock null
        val call = PsiTreeUtil.getParentOfType(leaf, PsiCallExpression::class.java, false) ?: return@withParseLock null
        val argList = call.argumentList ?: return@withParseLock null
        if (offset <= argList.textRange.startOffset || offset > argList.textRange.endOffset) return@withParseLock null

        val overloads: List<PsiMethod> = when (call) {
            is PsiMethodCallExpression ->
                call.methodExpression.multiResolve(false).mapNotNull { it.element as? PsiMethod }
                    .ifEmpty { listOfNotNull(call.resolveMethod()) }
            is PsiNewExpression ->
                (call.classReference?.resolve() as? PsiClass)?.constructors?.toList() ?: emptyList()
            else -> emptyList()
        }.distinct()
        if (overloads.isEmpty()) return@withParseLock null

        val activeParam = commasBefore(argList, offset)
        val signatures = overloads.map { signatureInfo(it) }
        val activeSignature = overloads.indexOfFirst { it.parameterList.parametersCount > activeParam }
            .let { if (it >= 0) it else 0 }
        SignatureHelp(signatures, activeSignature, activeParam)
    }

    private fun commasBefore(argList: PsiExpressionList, offset: Int): Int {
        var count = 0
        var child = argList.firstChild
        while (child != null && child.textRange.startOffset < offset) {
            if (child is PsiJavaToken && child.tokenType == JavaTokenType.COMMA) count++
            child = child.nextSibling
        }
        return count
    }

    private fun signatureInfo(m: PsiMethod): SignatureInfo {
        val sb = StringBuilder(m.name).append('(')
        val params = ArrayList<ParameterInfo>()
        m.parameterList.parameters.forEachIndexed { i, p ->
            if (i > 0) sb.append(", ")
            val start = sb.length
            sb.append(p.type.presentableText).append(' ').append(p.name)
            params += ParameterInfo(label = sb.substring(start), labelStart = start, labelEnd = sb.length)
        }
        sb.append(')')
        if (!m.isConstructor) m.returnType?.let { sb.append(": ").append(it.presentableText) }
        // Clean the raw `/** … */` block to plain text (the peek/doc panel shows this verbatim).
        val doc = m.docComment?.text?.let { JavaDoc.clean(it) }?.takeIf { it.isNotEmpty() }
        return SignatureInfo(sb.toString(), params, documentation = doc)
    }
}
