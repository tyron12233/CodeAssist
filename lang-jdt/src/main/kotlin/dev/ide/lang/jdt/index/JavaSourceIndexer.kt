package dev.ide.lang.jdt.index

import dev.ide.index.IndexInput
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
 * Java indexes. Accurate names/kinds/offsets/visibility/nesting from the real AST — no regex.
 *
 * The parse is shared two ways: within a pass, [sharedCu]/[sharedParsed] memoize the [CompilationUnit] (and
 * the derived [Parsed]) on the per-file [IndexInput] (the FileContent model), so all Java source indexes over
 * a file — class names, source symbols, members, entry points — parse it **once**. Across passes, [parseCu]
 * keeps a small content-keyed LRU, so a re-index of unchanged content (a no-op save, a flush-on-run, the run
 * service's cold-start scan feeding the index moments later) reuses the tree instead of re-parsing it.
 */
object JavaSourceIndexer {

    enum class DeclKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, FIELD }

    data class Decl(val name: String, val kind: DeclKind, val offset: Int, val container: String?, val public: Boolean)

    data class Parsed(val packageName: String?, val decls: List<Decl>)

    /** One member's annotation use, for the annotated-by index: `owner#member` derives from [member]. */
    data class MemberAnnotation(val member: String, val kind: DeclKind, val annotation: String)

    /**
     * A type's structural relations for the subtype + annotation indexes: its `extends`/`implements`
     * references and annotation uses AS WRITTEN (a binding-free parse sees `List` / `@Override`, not FQNs;
     * the indexes key by short name and resolve best-effort via [imports]).
     */
    data class TypeInfo(
        val fqn: String,
        val kind: DeclKind,
        val supertypes: List<String>,
        val annotations: List<String>,
        val memberAnnotations: List<MemberAnnotation>,
    )

    /** The file's type relations + its import map (simple name → FQN), shared across the indexes. */
    data class Relations(val packageName: String?, val types: List<TypeInfo>, val imports: Map<String, String>)

    /** Cross-pass content cache: source text → its parsed [CompilationUnit] (detached, binding-free → safe to
     *  hold). Bounded LRU; access-ordered so the hot working set survives. Keyed by content, so a changed file
     *  gets a fresh parse (no stale reuse) and identical content re-uses one parse. */
    private const val CU_CACHE_MAX = 16
    private val cuCache = object : LinkedHashMap<String, CompilationUnit?>(CU_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompilationUnit?>?) = size > CU_CACHE_MAX
    }

    /** Standalone (input-less) parse of [text] to the distilled model. Prefer [sharedParsed] inside an index. */
    fun parse(text: String): Parsed = declsOf(parseCu(text))

    /** The raw JDT [CompilationUnit] for this file, parsed once and shared across every index for the file. */
    fun sharedCu(input: IndexInput): CompilationUnit? =
        input.shared("jdt.cu") { input.text()?.let { parseCu(it) } }

    /** The distilled declaration model for this file, derived once from [sharedCu] and shared across indexes. */
    fun sharedParsed(input: IndexInput): Parsed =
        input.shared("jdt.parsed") { declsOf(sharedCu(input)) }

    /** The file's type relations (supertypes + annotations), derived once and shared across indexes. */
    fun sharedRelations(input: IndexInput): Relations =
        input.shared("jdt.relations") { relationsOf(sharedCu(input)) }

    /** A binding-free JDT parse of [text] to a [CompilationUnit] (statement recovery on), or null on failure.
     *  Content-cached across passes ([cuCache]). */
    @Synchronized
    fun parseCu(text: String): CompilationUnit? {
        if (cuCache.containsKey(text)) return cuCache[text]
        val cu = doParseCu(text)
        cuCache[text] = cu
        return cu
    }

    private fun doParseCu(text: String): CompilationUnit? {
        val parser = ASTParser.newParser(AST.getJLSLatest())
        parser.setKind(ASTParser.K_COMPILATION_UNIT)
        parser.setCompilerOptions(jdtStandaloneCompilerOptions(JavaCore.latestSupportedJavaVersion()))
        parser.setStatementsRecovery(true)
        parser.setSource(text.toCharArray())
        return runCatching { parser.createAST(null) as CompilationUnit }.getOrNull()
    }

    /** Walk [cu] into its package + flat declaration list (types, methods, fields). */
    fun declsOf(cu: CompilationUnit?): Parsed {
        if (cu == null) return Parsed(null, emptyList())
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

    /** Walk [cu] into per-type supertype references + annotation uses (as written) and the import map. */
    fun relationsOf(cu: CompilationUnit?): Relations {
        if (cu == null) return Relations(null, emptyList(), emptyMap())
        val pkg = cu.`package`?.name?.fullyQualifiedName
        val imports = HashMap<String, String>()
        for (imp in cu.imports()) {
            val i = imp as? org.eclipse.jdt.core.dom.ImportDeclaration ?: continue
            if (i.isOnDemand || i.isStatic) continue
            val fqn = i.name.fullyQualifiedName
            imports[fqn.substringAfterLast('.')] = fqn
        }
        val types = ArrayList<TypeInfo>()
        val path = ArrayDeque<String>()

        fun annotationsOf(modifiers: List<*>): List<String> = modifiers.mapNotNull {
            (it as? org.eclipse.jdt.core.dom.Annotation)?.typeName?.fullyQualifiedName
        }

        fun enter(
            name: String, kind: DeclKind, supertypes: List<String>, mods: List<*>, body: List<*>,
        ) {
            path.addLast(name)
            val fqn = (pkg?.plus(".") ?: "") + path.joinToString(".")
            val memberAnns = ArrayList<MemberAnnotation>()
            for (d in body) {
                when (d) {
                    is MethodDeclaration -> annotationsOf(d.modifiers()).forEach {
                        memberAnns += MemberAnnotation(d.name.identifier, DeclKind.METHOD, it)
                    }
                    is FieldDeclaration -> {
                        val anns = annotationsOf(d.modifiers())
                        if (anns.isNotEmpty()) for (frag in d.fragments()) {
                            val f = frag as? VariableDeclarationFragment ?: continue
                            anns.forEach { memberAnns += MemberAnnotation(f.name.identifier, DeclKind.FIELD, it) }
                        }
                    }
                }
            }
            types += TypeInfo(fqn, kind, supertypes, annotationsOf(mods), memberAnns)
        }

        cu.accept(object : ASTVisitor() {
            override fun visit(n: TypeDeclaration): Boolean {
                val supers = ArrayList<String>()
                n.superclassType?.let { supers += it.toString() }
                n.superInterfaceTypes().forEach { supers += it.toString() }
                enter(
                    n.name.identifier,
                    if (n.isInterface) DeclKind.INTERFACE else DeclKind.CLASS,
                    supers, n.modifiers(), n.bodyDeclarations(),
                )
                return true
            }
            override fun endVisit(n: TypeDeclaration) { path.removeLastOrNull() }
            override fun visit(n: EnumDeclaration): Boolean {
                enter(n.name.identifier, DeclKind.ENUM, n.superInterfaceTypes().map { it.toString() }, n.modifiers(), n.bodyDeclarations())
                return true
            }
            override fun endVisit(n: EnumDeclaration) { path.removeLastOrNull() }
            override fun visit(n: RecordDeclaration): Boolean {
                enter(n.name.identifier, DeclKind.RECORD, n.superInterfaceTypes().map { it.toString() }, n.modifiers(), n.bodyDeclarations())
                return true
            }
            override fun endVisit(n: RecordDeclaration) { path.removeLastOrNull() }
            override fun visit(n: AnnotationTypeDeclaration): Boolean {
                enter(n.name.identifier, DeclKind.ANNOTATION, emptyList(), n.modifiers(), n.bodyDeclarations())
                return true
            }
            override fun endVisit(n: AnnotationTypeDeclaration) { path.removeLastOrNull() }
        })
        return Relations(pkg, types, imports)
    }
}
