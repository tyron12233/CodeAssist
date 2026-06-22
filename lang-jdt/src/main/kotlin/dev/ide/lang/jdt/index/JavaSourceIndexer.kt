package dev.ide.lang.jdt.index

import dev.ide.lang.jdt.jdtStandaloneCompilerOptions
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.EnumDeclaration
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.RecordDeclaration
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment

/**
 * Structural (binding-free) JDT parse of one source file → its declarations, for the source side of the
 * Java indexes. Accurate names/kinds/offsets/visibility/nesting from the real AST — no regex. Parses are
 * memoized per source text (a small LRU) so the four Java indexes share one parse per file per pass.
 */
object JavaSourceIndexer {

    enum class DeclKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, FIELD }

    data class Decl(val name: String, val kind: DeclKind, val offset: Int, val container: String?, val public: Boolean)

    data class Parsed(val packageName: String?, val decls: List<Decl>)

    private val cache = object : LinkedHashMap<String, Parsed>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>?) = size > 64
    }

    @Synchronized
    fun parse(text: String): Parsed = cache.getOrPut(text) { doParse(text) }

    private fun doParse(text: String): Parsed {
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setCompilerOptions(jdtStandaloneCompilerOptions(JavaCore.latestSupportedJavaVersion()))
        parser.setStatementsRecovery(true)
        parser.setSource(text.toCharArray())
        val cu = runCatching { parser.createAST(null) as CompilationUnit }.getOrNull()
            ?: return Parsed(null, emptyList())

        val pkg = cu.`package`?.name?.fullyQualifiedName
        val decls = ArrayList<Decl>()
        val typeStack = ArrayDeque<String>()

        cu.accept(object : ASTVisitor() {
            private fun enterType(name: String, kind: DeclKind, offset: Int, mods: Int): Boolean {
                decls.add(Decl(name, kind, offset, typeStack.lastOrNull(), Modifier.isPublic(mods)))
                typeStack.addLast(name)
                return true
            }
            override fun visit(n: TypeDeclaration) = enterType(n.name.identifier, if (n.isInterface) DeclKind.INTERFACE else DeclKind.CLASS, n.name.startPosition, n.modifiers)
            override fun endVisit(n: TypeDeclaration) { typeStack.removeLastOrNull() }
            override fun visit(n: EnumDeclaration) = enterType(n.name.identifier, DeclKind.ENUM, n.name.startPosition, n.modifiers)
            override fun endVisit(n: EnumDeclaration) { typeStack.removeLastOrNull() }
            override fun visit(n: RecordDeclaration) = enterType(n.name.identifier, DeclKind.RECORD, n.name.startPosition, n.modifiers)
            override fun endVisit(n: RecordDeclaration) { typeStack.removeLastOrNull() }
            override fun visit(n: AnnotationTypeDeclaration) = enterType(n.name.identifier, DeclKind.ANNOTATION, n.name.startPosition, n.modifiers)
            override fun endVisit(n: AnnotationTypeDeclaration) { typeStack.removeLastOrNull() }
            override fun visit(n: MethodDeclaration): Boolean {
                if (!n.isConstructor) {
                    decls.add(Decl(n.name.identifier, DeclKind.METHOD, n.name.startPosition, typeStack.lastOrNull(), Modifier.isPublic(n.modifiers)))
                }
                return true
            }
            override fun visit(n: FieldDeclaration): Boolean {
                val pub = Modifier.isPublic(n.modifiers)
                for (frag in n.fragments()) {
                    val f = frag as? VariableDeclarationFragment ?: continue
                    decls.add(Decl(f.name.identifier, DeclKind.FIELD, f.name.startPosition, typeStack.lastOrNull(), pub))
                }
                return true
            }
        })
        return Parsed(pkg, decls)
    }
}
