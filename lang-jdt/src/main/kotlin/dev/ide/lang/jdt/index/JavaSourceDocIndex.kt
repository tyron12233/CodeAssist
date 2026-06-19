package dev.ide.lang.jdt.index

import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.SourceDocExternalizer
import dev.ide.index.SourceDocValue
import dev.ide.index.StringKeyDescriptor
import dev.ide.lang.jdt.JavadocText
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.SingleVariableDeclaration

/**
 * sourceDoc (Java): owner type FQN -> per-method real parameter NAMES + cleaned javadoc, recovered from an
 * attached `-sources.jar`/JDK `src.zip`/Android `sources/` (a compiled class carries neither). The Kotlin
 * editor queries this so completing a Java/Android API from a `.kt` file shows real names + docs. A type's
 * own javadoc is the entry with an empty name. `.java` sources only; binding-free structural parse.
 */
object JavaSourceDocIndex : IndexExtension<String, SourceDocValue> {
    override val id = IndexId("java.sourceDoc")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = SourceDocExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = InputFilter { it.origin == IndexOrigin.LIBRARY_SOURCE && it.unitName?.endsWith(".java") == true }

    override fun index(input: IndexInput): Map<String, Collection<SourceDocValue>> {
        val text = input.text() ?: return emptyMap()
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setResolveBindings(false)
        parser.setSource(text.toCharArray())
        val cu = runCatching { parser.createAST(null) as CompilationUnit }.getOrNull() ?: return emptyMap()
        val pkg = cu.`package`?.name?.fullyQualifiedName
        val out = HashMap<String, MutableList<SourceDocValue>>()
        val typeStack = ArrayDeque<String>()
        fun fqn(): String {
            val nested = typeStack.joinToString(".")
            return if (pkg.isNullOrEmpty()) nested else "$pkg.$nested"
        }
        cu.accept(object : ASTVisitor() {
            private fun enter(td: AbstractTypeDeclaration): Boolean {
                typeStack.addLast(td.name.identifier)
                td.javadoc?.let { JavadocText.clean(it.toString()) }?.takeIf { it.isNotEmpty() }?.let {
                    out.getOrPut(fqn()) { ArrayList() }.add(SourceDocValue("", -1, emptyList(), it))
                }
                return true
            }
            override fun visit(n: org.eclipse.jdt.core.dom.TypeDeclaration) = enter(n)
            override fun endVisit(n: org.eclipse.jdt.core.dom.TypeDeclaration) { typeStack.removeLastOrNull() }
            override fun visit(n: org.eclipse.jdt.core.dom.EnumDeclaration) = enter(n)
            override fun endVisit(n: org.eclipse.jdt.core.dom.EnumDeclaration) { typeStack.removeLastOrNull() }
            override fun visit(n: org.eclipse.jdt.core.dom.RecordDeclaration) = enter(n)
            override fun endVisit(n: org.eclipse.jdt.core.dom.RecordDeclaration) { typeStack.removeLastOrNull() }
            override fun visit(n: org.eclipse.jdt.core.dom.AnnotationTypeDeclaration) = enter(n)
            override fun endVisit(n: org.eclipse.jdt.core.dom.AnnotationTypeDeclaration) { typeStack.removeLastOrNull() }
            override fun visit(md: MethodDeclaration): Boolean {
                if (typeStack.isEmpty()) return true
                @Suppress("UNCHECKED_CAST")
                val params = (md.parameters() as List<SingleVariableDeclaration>).map { it.name.identifier }
                val doc = md.javadoc?.let { JavadocText.clean(it.toString()) }?.takeIf { it.isNotEmpty() }
                // A constructor is keyed by the simple class name (matching the bytecode symbol's name).
                val name = if (md.isConstructor) typeStack.last() else md.name.identifier
                out.getOrPut(fqn()) { ArrayList() }.add(SourceDocValue(name, params.size, params, doc))
                return true
            }
        })
        return out
    }
}
