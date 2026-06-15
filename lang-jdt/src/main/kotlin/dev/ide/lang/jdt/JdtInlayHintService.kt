package dev.ide.lang.jdt

import dev.ide.lang.dom.TextRange
import dev.ide.lang.hints.InlayHint
import dev.ide.lang.hints.InlayHintKind
import dev.ide.lang.hints.InlayHintPart
import dev.ide.lang.hints.InlayHintService
import dev.ide.vfs.VirtualFile
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.BooleanLiteral
import org.eclipse.jdt.core.dom.CharacterLiteral
import org.eclipse.jdt.core.dom.ClassInstanceCreation
import org.eclipse.jdt.core.dom.EnhancedForStatement
import org.eclipse.jdt.core.dom.Expression
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.LambdaExpression
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.NullLiteral
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.PrefixExpression
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import org.eclipse.jdt.core.dom.StringLiteral
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.eclipse.jdt.core.dom.VariableDeclarationStatement

/**
 * Inlay hints for Java, on the JDT binding DOM. Derives four kinds from the same resolved tree the editor
 * already builds:
 *  - **var types** — the inferred type after a `var` local or `for (var x : …)` (`var a` → `a: String`),
 *  - **lambda parameter types** — `list.forEach(x -> …)` → `x: String`,
 *  - **parameter names** — at a call site, the name of each parameter in front of a literal/null argument
 *    (`setMargin(/*top:*/ 0, …)`), for methods declared in the same file,
 *  - **chaining** — the intermediate type in a multi-line fluent chain.
 *
 * Hints degrade gracefully: an unresolved binding (e.g. on a partial buffer) simply yields no hint.
 */
class JdtInlayHintService(private val analyzer: JdtSourceAnalyzer) : InlayHintService {

    // Recovers parameter names from sources (project + library -sources.jars + JDK/Android) when the
    // binding can't. Shared with completion so parse caches are reused.
    private val sourceResolver get() = analyzer.sourceMethodResolver

    override suspend fun hints(file: VirtualFile, range: TextRange): List<InlayHint> {
        val text = liveText(file)
        val pf = runCatching { analyzer.parse(file, text) }.getOrNull() ?: return emptyList()
        val cu = pf.cu
        val out = ArrayList<InlayHint>()
        // Pre-index method declarations in this unit so call sites can recover parameter names (the DOM
        // binding doesn't expose them for binaries; same-file methods are the reliable case).
        val paramNames = HashMap<String, List<String>>()
        cu.accept(object : ASTVisitor() {
            override fun visit(md: MethodDeclaration): Boolean {
                val key = md.resolveBinding()?.key
                if (key != null) {
                    @Suppress("UNCHECKED_CAST")
                    paramNames[key] = (md.parameters() as List<SingleVariableDeclaration>).map { it.name.identifier }
                }
                return true
            }
        })

        cu.accept(object : ASTVisitor() {
            override fun visit(node: VariableDeclarationStatement): Boolean {
                if (node.type.isVar) {
                    @Suppress("UNCHECKED_CAST")
                    for (frag in node.fragments() as List<VariableDeclarationFragment>) {
                        val t = frag.resolveBinding()?.type ?: continue
                        out += typeHint(frag.name.startPosition + frag.name.length, t)
                    }
                }
                return true
            }

            override fun visit(node: EnhancedForStatement): Boolean {
                val p = node.parameter
                if (p.type.isVar) {
                    p.resolveBinding()?.type?.let { out += typeHint(p.name.startPosition + p.name.length, it) }
                }
                return true
            }

            override fun visit(node: LambdaExpression): Boolean {
                // Untyped lambda params are VariableDeclarationFragments; typed ones are SingleVariableDeclarations.
                for (p in node.parameters()) {
                    if (p is VariableDeclarationFragment) {
                        val t = p.resolveBinding()?.type ?: continue
                        out += typeHint(p.name.startPosition + p.name.length, t)
                    }
                }
                return true
            }

            override fun visit(node: MethodInvocation): Boolean {
                val mb = node.resolveMethodBinding()
                if (mb != null) {
                    @Suppress("UNCHECKED_CAST")
                    val args = node.arguments() as List<Expression>
                    // Same-file methods are in paramNames; otherwise parse the declaring source for names.
                    val names = paramNames[mb.key]
                        ?: declaringFqn(mb.declaringClass)?.let { sourceResolver.lookup(it, mb.name, args.size)?.params }
                    parameterNameHints(args, names, out)
                }
                chainingHint(node, out)
                return true
            }

            override fun visit(node: ClassInstanceCreation): Boolean {
                val cb = node.resolveConstructorBinding()
                if (cb != null) {
                    @Suppress("UNCHECKED_CAST")
                    val args = node.arguments() as List<Expression>
                    val declFqn = declaringFqn(cb.declaringClass)
                    val names = paramNames[cb.key]
                        ?: declFqn?.let { sourceResolver.lookup(it, simpleName(it), args.size)?.params }
                    parameterNameHints(args, names, out)
                }
                return true
            }
        })

        return out.filter { it.offset in range.start..range.end }.sortedBy { it.offset }
    }

    private fun typeHint(offset: Int, type: ITypeBinding): InlayHint =
        InlayHint(offset, listOf(InlayHintPart(": " + typeName(type))), InlayHintKind.TYPE, tooltip = type.qualifiedName)

    private fun parameterNameHints(args: List<Expression>, names: List<String>?, out: MutableList<InlayHint>) {
        if (names == null) return
        for ((i, arg) in args.withIndex()) {
            val name = names.getOrNull(i) ?: continue
            if (!isLiteralLike(arg)) continue // only annotate non-obvious args; a named variable is self-documenting
            out += InlayHint(
                arg.startPosition,
                listOf(InlayHintPart("$name:")),
                InlayHintKind.PARAMETER,
                paddingRight = true,
            )
        }
    }

    /** At a fluent chain that spans lines, show the receiver call's result type before the next `.`. */
    private fun chainingHint(node: MethodInvocation, out: MutableList<InlayHint>) {
        val recv = node.expression as? MethodInvocation ?: return
        val cu = node.root as? org.eclipse.jdt.core.dom.CompilationUnit ?: return
        val recvEnd = recv.startPosition + recv.length
        // Only when the next call is on a later line (multi-line chain) — otherwise it's just noise.
        if (cu.getLineNumber(recvEnd) == cu.getLineNumber(node.name.startPosition)) return
        val t = recv.resolveTypeBinding() ?: return
        out += InlayHint(recvEnd, listOf(InlayHintPart(typeName(t))), InlayHintKind.CHAINING, paddingLeft = true)
    }

    private fun isLiteralLike(e: Expression): Boolean = when (e) {
        is NumberLiteral, is StringLiteral, is BooleanLiteral, is CharacterLiteral, is NullLiteral -> true
        is PrefixExpression -> isLiteralLike(e.operand) // -1, !flag
        else -> false
    }

    /** Binary name (`pkg.Outer$Inner`) so the resolver maps nested types to the right top-level source file. */
    private fun declaringFqn(t: ITypeBinding?): String? = t?.let { it.binaryName ?: it.qualifiedName }

    private fun simpleName(fqn: String): String =
        fqn.substringBefore('<').substringAfterLast('.').substringAfterLast('$')

    private fun typeName(t: ITypeBinding): String {
        val n = t.name
        return if (n.isNullOrEmpty()) t.qualifiedName.ifEmpty { "?" } else n
    }

    private fun liveText(file: VirtualFile): CharSequence =
        analyzer.overlayProvider()[analyzer.fqcnFor(file)]?.let { String(it) } ?: file.readText()
}
