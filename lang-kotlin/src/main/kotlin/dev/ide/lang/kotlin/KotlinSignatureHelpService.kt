package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.resolve.*
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.signature.ParameterInfo
import dev.ide.lang.signature.SignatureHelp
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.lang.signature.SignatureInfo
import dev.ide.lang.signature.SignatureScan
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Signature help for Kotlin, on the standalone PSI + the backend's own resolver (no FIR). [SignatureScan]
 * locates the enclosing call + the active argument index lexically; [KotlinResolver.callTargets] then yields
 * every function/constructor the callee could resolve to (member, top-level, in-scope, constructors of a
 * capitalized callee) — the same overload set named-argument completion uses. Each becomes a [SignatureInfo]
 * built from its real parameter names + resolved types. No resolvable target → null (the editor shows nothing).
 */
class KotlinSignatureHelpService(
    private val service: KotlinSymbolService,
    /** Run before each query — the host wires this to sync the symbol model to the live editor buffers, so a
     *  function just typed in another open file resolves here too (mirrors completion's onBeforeComplete). */
    private val onBeforeQuery: () -> Unit = {},
) : SignatureHelpService {

    override suspend fun signatureHelp(request: SignatureHelpRequest): SignatureHelp? {
        onBeforeQuery()
        val text = request.document.text.toString()
        val site = SignatureScan.enclosingCall(text, request.offset) ?: return null

        val kt = KotlinParserHost.parse(request.document.file.name, text)
        val parsed = KotlinParsedFile(kt, request.document.file, request.document.version)
        val resolver = KotlinResolver(kt, parsed, service)

        val leaf = kt.findElementAt(site.calleeNameStart) ?: return null
        val call = climbToCall(leaf) ?: return null
        val targets = resolver.callTargets(call).filter { it.paramNames.isNotEmpty() || it.paramTypes.isNotEmpty() }
        if (targets.isEmpty()) return null

        val signatures = targets.map { signatureOf(it, site.activeParameter) }
        val active = pickActiveSignature(targets, site.activeParameter)
        return SignatureHelp(signatures = signatures, activeSignature = active, activeParameter = site.activeParameter)
    }

    private fun climbToCall(leaf: PsiElement): KtCallExpression? {
        var e: PsiElement? = leaf
        while (e != null && e !is KtCallExpression) e = e.parent
        return e as? KtCallExpression
    }

    /** Prefer the smallest overload that can still hold the argument the caret is on; else the largest. */
    private fun pickActiveSignature(targets: List<KotlinSymbol>, activeParameter: Int): Int {
        fun arity(s: KotlinSymbol) = maxOf(s.paramNames.size, s.paramTypes.size)
        val covering = targets.indices.filter { arity(targets[it]) > activeParameter || targets[it].varargParamIndex >= 0 }
        if (covering.isNotEmpty()) return covering.minByOrNull { arity(targets[it]) } ?: 0
        return targets.indices.maxByOrNull { arity(targets[it]) } ?: 0
    }

    private fun signatureOf(s: KotlinSymbol, activeParameter: Int): SignatureInfo {
        val count = maxOf(s.paramNames.size, s.paramTypes.size)
        val sb = StringBuilder(s.name).append('(')
        val infos = ArrayList<ParameterInfo>(count)
        for (i in 0 until count) {
            if (i > 0) sb.append(", ")
            val nm = s.paramNames.getOrNull(i)?.takeIf { it.isNotEmpty() && !isSyntheticParamName(it) }
            val ty = renderType(s.paramTypes.getOrNull(i))
            val prefix = if (s.varargParamIndex == i) "vararg " else ""
            val partText = if (nm != null) "$prefix$nm: $ty" else "$prefix$ty"
            val start = sb.length
            sb.append(partText)
            infos.add(ParameterInfo(partText, start, sb.length))
        }
        sb.append(')')
        if (s.kind != SymbolKind.CONSTRUCTOR) {
            val ret = renderType(s.type)
            if (ret.isNotEmpty() && ret != "Unit" && ret != "?") sb.append(": ").append(ret)
        }
        // A vararg parameter absorbs all trailing arguments, so once the caret is at/after it, keep it highlighted.
        val perSig = if (s.varargParamIndex in 0..activeParameter) s.varargParamIndex else null
        return SignatureInfo(label = sb.toString(), parameters = infos, activeParameter = perSig)
    }

    private fun renderType(t: TypeRef?): String {
        if (t == null) return "?"
        val base = t.qualifiedName.substringAfterLast('.').ifEmpty { t.qualifiedName }.ifEmpty { "?" }
        val args = t.typeArguments
        val argStr = if (args.isEmpty()) "" else "<" + args.joinToString(", ") { renderType(it) } + ">"
        val q = if (t is KotlinType && t.nullable) "?" else ""
        return base + argStr + q
    }

    private fun isSyntheticParamName(n: String): Boolean =
        n.length >= 2 && n[0] == 'p' && n.drop(1).all { it.isDigit() }
}
