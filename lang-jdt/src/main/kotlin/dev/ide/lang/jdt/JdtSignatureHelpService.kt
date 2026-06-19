package dev.ide.lang.jdt

import dev.ide.lang.signature.ParameterInfo
import dev.ide.lang.signature.SignatureHelp
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpService
import dev.ide.lang.signature.SignatureInfo
import dev.ide.lang.signature.SignatureScan
import dev.ide.vfs.VirtualFile
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.ClassInstanceCreation
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.ConstructorInvocation
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.NodeFinder
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import org.eclipse.jdt.core.dom.SuperConstructorInvocation
import org.eclipse.jdt.core.dom.SuperMethodInvocation

/**
 * Signature help for Java, on the JDT binding DOM. The shared [SignatureScan] locates the enclosing call + the
 * active argument index lexically (robust on a half-typed `foo(|`), then we resolve the callee's method/
 * constructor binding and surface every same-name overload (walking the declaring type's supertypes) as a
 * [SignatureInfo]. Parameter names come from the binding's own declaration when it is in this file, else from
 * the attached sources via [JdtSourceAnalyzer.sourceMethodResolver] (binaries strip them) — falling back to
 * just the type when neither has a name. Unresolved (a partial/broken call) → null, and the editor shows nothing.
 */
class JdtSignatureHelpService(private val analyzer: JdtSourceAnalyzer) : SignatureHelpService {

    private val sourceResolver get() = analyzer.sourceMethodResolver

    override suspend fun signatureHelp(request: SignatureHelpRequest): SignatureHelp? {
        val text = request.document.text.toString()
        val site = SignatureScan.enclosingCall(text, request.offset) ?: return null
        val pf = runCatching { analyzer.parse(request.document.file, text) }.getOrNull() ?: return null
        val cu = pf.cu

        val len = (site.calleeNameEnd - site.calleeNameStart).coerceAtLeast(0)
        val nameNode = NodeFinder.perform(cu, site.calleeNameStart, len) ?: return null
        val call = enclosingCall(nameNode) ?: return null
        val resolved = bindingOf(call) ?: return null

        // Same-file method/constructor declarations → parameter names (the binding doesn't carry them for binaries).
        val sameFileNames = HashMap<String, List<String>>()
        cu.accept(object : ASTVisitor() {
            override fun visit(md: MethodDeclaration): Boolean {
                val key = md.resolveBinding()?.key
                if (key != null) {
                    @Suppress("UNCHECKED_CAST")
                    sameFileNames[key] = (md.parameters() as List<SingleVariableDeclaration>).map { it.name.identifier }
                }
                return true
            }
        })

        val candidates = candidateMethods(resolved)
        if (candidates.isEmpty()) return null
        val signatures = candidates.map { signatureOf(it, sameFileNames) }
        val resolvedKey = paramKey(resolved)
        val active = candidates.indexOfFirst { paramKey(it) == resolvedKey }.coerceAtLeast(0)
        return SignatureHelp(signatures = signatures, activeSignature = active, activeParameter = site.activeParameter)
    }

    /** Climb from the callee-name node to its enclosing call expression. */
    private fun enclosingCall(node: ASTNode): ASTNode? {
        var n: ASTNode? = node
        while (n != null) {
            when (n) {
                is MethodInvocation, is SuperMethodInvocation, is ClassInstanceCreation,
                is ConstructorInvocation, is SuperConstructorInvocation -> return n
            }
            n = n.parent
        }
        return null
    }

    private fun bindingOf(call: ASTNode): IMethodBinding? = when (call) {
        is MethodInvocation -> call.resolveMethodBinding()
        is SuperMethodInvocation -> call.resolveMethodBinding()
        is ClassInstanceCreation -> call.resolveConstructorBinding()
        is ConstructorInvocation -> call.resolveConstructorBinding()
        is SuperConstructorInvocation -> call.resolveConstructorBinding()
        else -> null
    }

    /** The resolved callable plus its sibling overloads — same-name methods (or the declaring type's constructors)
     *  declared on the type and, for methods, its supertypes; deduped by erased parameter signature. */
    private fun candidateMethods(mb: IMethodBinding): List<IMethodBinding> {
        val decl = mb.declaringClass ?: return listOf(mb)
        val isCtor = mb.isConstructor
        val out = LinkedHashMap<String, IMethodBinding>()
        fun consider(m: IMethodBinding) {
            if (m.isConstructor != isCtor) return
            if (!isCtor && m.name != mb.name) return
            out.putIfAbsent(paramKey(m), m)
        }
        val seen = HashSet<String>()
        val stack = ArrayDeque<ITypeBinding>()
        stack.addLast(decl)
        while (stack.isNotEmpty()) {
            val t = stack.removeFirst()
            if (!seen.add(t.erasure?.qualifiedName ?: t.qualifiedName)) continue
            t.declaredMethods?.forEach { consider(it) }
            if (isCtor) break // constructors aren't inherited
            t.superclass?.let { stack.addLast(it) }
            t.interfaces?.forEach { stack.addLast(it) }
        }
        consider(mb) // the resolved binding may be inherited from beyond our walk
        return out.values.toList()
    }

    private fun paramKey(m: IMethodBinding): String =
        m.parameterTypes.joinToString(",") { it.erasure?.qualifiedName ?: it.qualifiedName } + "#" + m.parameterTypes.size

    private fun signatureOf(m: IMethodBinding, sameFileNames: Map<String, List<String>>): SignatureInfo {
        val params = m.parameterTypes
        val names = paramNamesFor(m, sameFileNames)
        val display = if (m.isConstructor) simpleName(declaringFqn(m.declaringClass) ?: m.name) else m.name
        val sb = StringBuilder(display).append('(')
        val infos = ArrayList<ParameterInfo>(params.size)
        for (i in params.indices) {
            if (i > 0) sb.append(", ")
            val varargTail = m.isVarargs && i == params.size - 1
            val typeName = paramTypeName(params[i], varargTail)
            val nm = names?.getOrNull(i)?.takeIf { it.isNotEmpty() }
            val partText = if (nm != null) "$typeName $nm" else typeName
            val start = sb.length
            sb.append(partText)
            infos.add(ParameterInfo(partText, start, sb.length))
        }
        sb.append(')')
        if (!m.isConstructor) {
            val rt = m.returnType
            if (rt != null && rt.name != "void") sb.append(": ").append(typeName(rt))
        }
        // A vararg overload absorbs all trailing arguments into its last parameter, so highlight it there.
        val active = if (m.isVarargs && params.isNotEmpty()) (params.size - 1) else null
        return SignatureInfo(label = sb.toString(), parameters = infos, activeParameter = active)
    }

    private fun paramNamesFor(m: IMethodBinding, sameFileNames: Map<String, List<String>>): List<String>? {
        sameFileNames[m.key]?.let { return it }
        val declFqn = declaringFqn(m.declaringClass) ?: return null
        val lookupName = if (m.isConstructor) simpleName(declFqn) else m.name
        return sourceResolver.lookup(declFqn, lookupName, m.parameterTypes.size)?.params
    }

    private fun paramTypeName(t: ITypeBinding, vararg_: Boolean): String =
        if (vararg_ && t.isArray) typeName(t.componentType) + "..." else typeName(t)

    private fun declaringFqn(t: ITypeBinding?): String? = t?.let { it.binaryName ?: it.qualifiedName }

    private fun simpleName(fqn: String): String =
        fqn.substringBefore('<').substringAfterLast('.').substringAfterLast('$')

    private fun typeName(t: ITypeBinding): String {
        val n = t.name
        return if (n.isNullOrEmpty()) t.qualifiedName.ifEmpty { "?" } else n
    }
}
