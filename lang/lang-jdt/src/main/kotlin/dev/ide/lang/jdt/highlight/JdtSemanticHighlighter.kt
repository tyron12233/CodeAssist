package dev.ide.lang.jdt.highlight

import dev.ide.lang.dom.TextRange
import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.highlight.SemanticToken
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.platform.EngineCancellation
import dev.ide.vfs.VirtualFile
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.IPackageBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.MarkerAnnotation
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NormalAnnotation
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SingleMemberAnnotation

/**
 * Type-aware Java coloring over JDT bindings. Mirrors [dev.ide.lang.jdt.rename.JdtRename]'s reference walk:
 * a binding-resolved parse, one `ASTVisitor` over every `SimpleName`, each resolved and classified into a
 * [SemanticToken]. Names that don't resolve (broken code) are simply skipped — the editor's lexical layer
 * keeps coloring them. The parse uses the live buffer (the editor overlay) so an unsaved edit is reflected.
 */
class JdtSemanticHighlighter(private val analyzer: JdtSourceAnalyzer) : SemanticHighlightService {

    override suspend fun highlight(file: VirtualFile): List<SemanticToken> {
        val text = analyzer.overlayProvider()[analyzer.fqcnFor(file)]?.let { String(it) }
            ?: runCatching { file.readText() }.getOrNull()
            ?: return emptyList()
        val parsed = analyzer.parse(file, text)
        val out = ArrayList<SemanticToken>(256)
        parsed.cu.accept(object : ASTVisitor() {
            private var seen = 0
            override fun visit(node: SimpleName): Boolean {
                if (seen++ % 64 == 0) EngineCancellation.checkCanceled()
                val binding = node.resolveBinding() ?: return true
                val kind = kindOf(binding, node) ?: return true
                val mods = modifiersOf(binding, node)
                out += SemanticToken(TextRange(node.startPosition, node.startPosition + node.length), kind, mods)
                return true
            }

            // Color the `@` of an annotation usage. The annotation type NAME is a SimpleName already classified
            // as ANNOTATION above; adding just the leading `@` makes the whole `@Foo` read as one unit.
            private fun emitAt(startPosition: Int) {
                out += SemanticToken(TextRange(startPosition, startPosition + 1), HighlightKind.ANNOTATION)
            }
            override fun visit(node: MarkerAnnotation): Boolean { emitAt(node.startPosition); return true }
            override fun visit(node: NormalAnnotation): Boolean { emitAt(node.startPosition); return true }
            override fun visit(node: SingleMemberAnnotation): Boolean { emitAt(node.startPosition); return true }
        })
        return out
    }

    private fun kindOf(b: IBinding, node: SimpleName): HighlightKind? = when (b) {
        is IPackageBinding -> HighlightKind.NAMESPACE
        is ITypeBinding -> when {
            b.isTypeVariable -> HighlightKind.TYPE_PARAMETER
            b.isAnnotation -> HighlightKind.ANNOTATION
            b.isEnum -> HighlightKind.ENUM
            b.isInterface -> HighlightKind.INTERFACE
            // `Foo` in `Foo.f` / `new Foo()` / `Foo x` are all the class itself; anonymous/array/wildcard skipped.
            b.isAnonymous || b.isWildcardType || b.isCapture || b.isArray -> null
            else -> HighlightKind.CLASS
        }
        is IMethodBinding -> if (b.isConstructor) HighlightKind.CONSTRUCTOR else HighlightKind.METHOD
        is IVariableBinding -> when {
            b.isEnumConstant -> HighlightKind.ENUM_CONSTANT
            // A `static final` field is a constant (`Math.PI`, `Integer.MAX_VALUE`) — colored apart from a field.
            b.isField && Modifier.isStatic(b.modifiers) && Modifier.isFinal(b.modifiers) -> HighlightKind.CONSTANT
            b.isField -> HighlightKind.FIELD
            b.isParameter -> HighlightKind.PARAMETER
            else -> HighlightKind.LOCAL_VARIABLE
        }
        else -> null
    }

    private fun modifiersOf(b: IBinding, node: SimpleName): Set<HighlightModifier> {
        val mods = LinkedHashSet<HighlightModifier>(4)
        if (node.isDeclaration) mods += HighlightModifier.DECLARATION
        if (b.isDeprecated) mods += HighlightModifier.DEPRECATED
        val m = b.modifiers
        if (Modifier.isStatic(m)) mods += HighlightModifier.STATIC
        if (Modifier.isAbstract(m)) mods += HighlightModifier.ABSTRACT
        // A `final` field/local/parameter is an immutable binding; methods/types ignore READONLY.
        if (b is IVariableBinding) {
            if (Modifier.isFinal(m) || b.isEnumConstant) mods += HighlightModifier.READONLY
            else mods += HighlightModifier.MUTABLE
        }
        return mods
    }
}
