package dev.ide.lang.kotlin

import dev.ide.index.IndexService
import dev.ide.lang.AnalysisResult
import dev.ide.lang.CompilationContext
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.CompletionService
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.kotlin.completion.KotlinCompletionService
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.TypeRef
import dev.ide.platform.Disposable
import dev.ide.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import dev.ide.lang.kotlin.symbols.KotlinType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The editor-time engine for Kotlin. Tolerant PSI parse to neutral DOM, plus resolution, the inference
 * subset, and member/name/type completion, all on an independent symbol model (the compiler is used only to
 * parse). Mirrors [dev.ide.lang.jdt.JdtSourceAnalyzer]: built from a model-derived [CompilationContext],
 * with [indexService] injected by the host after construction (it powers type-NAME completion via
 * `java.classNames`; members come from bytecode).
 */
class KotlinSourceAnalyzer(ctx: CompilationContext) : SourceAnalyzer, Disposable {

    /** Injected by the host (ide-core's `analyzerFor`). */
    @Volatile
    var indexService: IndexService? = null

    /** Injected by the host: where to persist the classpath extension scan across launches. */
    @Volatile
    var extensionCacheDir: Path? = null

    private val sourceRoots: List<VirtualFile> = ctx.sourceRoots
    private val classpathJars: List<Path> =
        (ctx.classpath.entries + ctx.bootClasspath.entries)
            .mapNotNull { runCatching { Path.of(it.root.path) }.getOrNull() }
            .filter { Files.exists(it) }

    private val serviceLazy = lazy { KotlinSymbolService(sourceRoots, classpathJars, indexService, extensionCacheDir) }
    private val service: KotlinSymbolService get() = serviceLazy.value

    private val backing = KotlinIncrementalParser()
    private val lastByFile = ConcurrentHashMap<String, KotlinParsedFile>()

    override val incrementalParser: IncrementalParser = object : IncrementalParser {
        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile =
            (backing.parseFull(snapshot) as KotlinParsedFile).also { lastByFile[snapshot.file.path] = it }

        override fun reparse(previous: ParsedFile, newSnapshot: DocumentSnapshot, edits: List<DocumentEdit>): ReparseResult =
            backing.reparse(previous, newSnapshot, edits).also { lastByFile[newSnapshot.file.path] = it.tree as KotlinParsedFile }
    }

    override val completion: CompletionService by lazy { KotlinCompletionService(service) }

    override suspend fun parsedFile(file: VirtualFile): ParsedFile =
        lastByFile[file.path] ?: incrementalParser.parseFull(EmptyDocument(file))

    override suspend fun analyze(file: VirtualFile): AnalysisResult {
        val parsed = lastByFile[file.path] ?: return AnalysisResult(file, emptyList())
        return AnalysisResult(file, parsed.diagnostics + semanticDiagnostics(parsed))
    }

    /**
     * Semantic diagnostics. Conservative to avoid false positives over an incomplete (parse-only) symbol
     * model. It flags:
     *  - an unresolved member on an explicit receiver whose type was inferred (`"".bogus()`);
     *  - an unresolved bare reference: a lower-case name in value position that resolves to nothing;
     *  - a type mismatch for an initializer (`val a: Int = ""`) or a `return` value (`fun f(): Int { return "" }`);
     *  - a `val` reassignment (`val x = 1; x = 2`);
     *  - a missing `return` in a block-body function with a non-Unit declared return type;
     *  - conflicting declarations in one scope (duplicate names / same-signature functions);
     *  - an argument-count mismatch for a call to a same-file function (where arity is readable from PSI);
     *  - a missing initializer on a top-level / concrete-class property;
     *  - an unsafe nullable access (`s.length` where `s: String?`, no guard);
     *  - unused imports, `private` declarations, and locals, and a `var` that could be `val` (warnings/hints).
     * Capitalized names (types/constructors), type-position references (generic params), qualified receivers,
     * numeric-literal adaptation, `Nothing` terminals, smart-cast guards, and implicit-companion bodies are left alone.
     */
    private fun semanticDiagnostics(parsed: KotlinParsedFile): List<Diagnostic> {
        val ktFile = parsed.ktFile
        val resolver = KotlinResolver(ktFile, parsed, service)
        val refNames = referencedNames(ktFile) // for unused-import / unused-private (names used in the body)
        val out = ArrayList<Diagnostic>()
        fun walk(psi: PsiElement) {
            when (psi) {
                is KtNameReferenceExpression ->
                    (unresolvedMember(psi, resolver) ?: unresolvedBareReference(psi, resolver))?.let { out += it }
                is KtProperty -> {
                    typeMismatch(psi.typeReference?.text, psi.initializer, resolver)?.let { out += it }
                    unusedLocal(psi)?.let { out += it }
                    missingInitializer(psi)?.let { out += it }
                    varCouldBeVal(psi)?.let { out += it }
                    if (isPrivateDeclaration(psi)) unusedPrivate(psi, refNames)?.let { out += it }
                }
                is KtParameter -> typeMismatch(psi.typeReference?.text, psi.defaultValue, resolver)?.let { out += it }
                is KtNamedFunction -> {
                    // a block-body function must return a value (missing-return); an expression body is type-checked.
                    if (psi.hasBlockBody()) missingReturn(psi, resolver)?.let { out += it }
                    else typeMismatch(psi.typeReference?.text, psi.bodyExpression, resolver)?.let { out += it }
                    if (isPrivateDeclaration(psi)) unusedPrivate(psi, refNames)?.let { out += it }
                }
                is KtReturnExpression -> returnTypeMismatch(psi, resolver)?.let { out += it }
                is KtBinaryExpression -> valReassignment(psi)?.let { out += it }
                is KtCallExpression -> argumentCountMismatch(psi)?.let { out += it }
                is KtDotQualifiedExpression -> unsafeNullableAccess(psi, resolver)?.let { out += it }
                // Conflicting declarations: same-name (and, for functions, same-signature) declarations in
                // one scope — a parameter list, a block (locals), a class body, or the file (top level).
                is KtParameterList -> out += duplicateParams(psi)
                is KtBlockExpression -> {
                    out += duplicateDeclarations(psi.statements.filterIsInstance<KtDeclaration>())
                    out += unreachableCode(psi, resolver)
                }
                is KtClassBody -> out += duplicateDeclarations(psi.declarations)
                is KtFile -> {
                    out += duplicateDeclarations(psi.declarations)
                    out += unusedImports(psi, refNames)
                }
                else -> {}
            }
            var c = psi.firstChild
            while (c != null) { walk(c); c = c.nextSibling }
        }
        walk(ktFile)
        return out
    }

    /** Every identifier referenced in the file body (outside import/package directives), for the
     *  unused-import and unused-private checks. A declaration's own name identifier is NOT a reference. */
    private fun referencedNames(file: KtFile): Set<String> {
        val names = HashSet<String>()
        fun rec(p: PsiElement) {
            if (p is KtImportDirective || p is KtPackageDirective) return
            // KtSimpleNameExpression covers plain references AND operation references (`a shl b` → `shl`),
            // so an infix function imported and used as an operator still counts as referenced.
            if (p is org.jetbrains.kotlin.psi.KtSimpleNameExpression) names += p.getReferencedName()
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(file)
        return names
    }

    /**
     * Same-scope redeclarations among [decls] — `val x = 1; val x = 2`, two properties of the same name, or
     * two functions with the same name AND signature (receiver + parameter types; overloads that differ in
     * parameter types are fine). Conservative: keys are textual (`Int` ≠ `kotlin.Int` won't merge), classes/
     * objects/enum entries and property-vs-function clashes are left alone. Every member of a clashing group
     * is flagged (matching the editor underlining each).
     */
    private fun duplicateDeclarations(decls: List<KtDeclaration>): List<Diagnostic> {
        if (decls.size < 2) return emptyList()
        val byKey = LinkedHashMap<String, MutableList<KtNamedDeclaration>>()
        for (d in decls) {
            if (d !is KtNamedDeclaration || d.nameIdentifier == null) continue
            val name = d.name ?: continue
            val key = when (d) {
                is KtProperty -> "P:${d.receiverTypeReference?.text ?: ""}:$name"
                is KtNamedFunction ->
                    "F:${d.receiverTypeReference?.text ?: ""}:$name(${d.valueParameters.joinToString(",") { it.typeReference?.text ?: "?" }})"
                else -> continue // classes/objects/enum entries: not handled here
            }
            byKey.getOrPut(key) { ArrayList() }.add(d)
        }
        return conflicts(byKey.values)
    }

    /** Duplicate parameter names within one parameter list (`fun f(x: Int, x: String)`, `{ a, a -> }`). */
    private fun duplicateParams(list: KtParameterList): List<Diagnostic> {
        if (list.parameters.size < 2) return emptyList()
        val byName = LinkedHashMap<String, MutableList<KtNamedDeclaration>>()
        for (p in list.parameters) {
            if (p.nameIdentifier == null) continue
            byName.getOrPut(p.name ?: continue) { ArrayList() }.add(p)
        }
        return conflicts(byName.values)
    }

    private fun conflicts(groups: Collection<List<KtNamedDeclaration>>): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        for (group in groups) {
            if (group.size < 2) continue
            for (d in group) {
                val r = (d.nameIdentifier ?: d).textRange
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                    "Conflicting declarations: '${d.name}'", "kt.conflictingDeclaration",
                )
            }
        }
        return out
    }

    /**
     * `val x = …; x = …` — assigning to an immutable binding. Conservative: only a plain `=` to a SIMPLE name
     * (not `this.x =`, not `x += …` which may desugar to a legal `plusAssign` on a `val`) that resolves to a
     * nearby local `val` or a parameter (function/lambda/for/catch, all immutable). Resolution stops
     * at the enclosing class so a (possibly `var`) member never trips it.
     */
    private fun valReassignment(expr: KtBinaryExpression): Diagnostic? {
        if (expr.operationToken != KtTokens.EQ) return null
        val lhs = expr.left as? KtNameReferenceExpression ?: return null
        val decl = nearestLocalDecl(lhs.getReferencedName(), lhs.textRange.startOffset, lhs) ?: return null
        val immutable = when (decl) {
            // A `val` WITHOUT an initializer permits one deferred assignment (`val x: Int; x = 1`), so only a
            // `val` that already has a value is truly un-reassignable. (Double deferred-assignment isn't caught.)
            is KtProperty -> !decl.isVar && (decl.hasInitializer() || decl.hasDelegate())
            is KtParameter -> true
            else -> false
        }
        if (!immutable) return null
        val r = lhs.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Val cannot be reassigned", "kt.valReassign")
    }

    /** The nearest in-scope local property / parameter named [name] declared before [offset], or null if the
     *  binding is a class member / top-level (resolution backs off at the class boundary). */
    private fun nearestLocalDecl(name: String, offset: Int, from: PsiElement): PsiElement? {
        var node: PsiElement? = from.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression ->
                    node.statements.firstOrNull { it is KtProperty && it.name == name && it.textRange.endOffset <= offset }?.let { return it }
                is KtFunction -> node.valueParameters.firstOrNull { it.name == name }?.let { return it }
                is KtForExpression -> node.loopParameter?.takeIf { it.name == name }?.let { return it }
                is KtCatchClause -> node.catchParameter?.takeIf { it.name == name }?.let { return it }
                is KtClassOrObject -> return null
            }
            node = node.parent
        }
        return null
    }

    /**
     * A block-body function whose declared return type is non-Unit but whose body never returns a value.
     * This does not do full control-flow analysis: it only flags when there is no `return` at all (a return
     * inside a lambda counts, conservatively) and the body doesn't end in a value-less terminal: a `throw`,
     * an infinite `while (true)`, or a `Nothing`-typed call (`TODO()`/`error(…)`).
     */
    private fun missingReturn(fn: KtNamedFunction, resolver: KotlinResolver): Diagnostic? {
        val declared = service.typeFromText(fn.typeReference?.text ?: return null, resolver.fileContext) ?: return null
        if (declared.qualifiedName == "kotlin.Unit" || declared.qualifiedName == "kotlin.Nothing") return null
        if (declared.isTypeParameter || !service.isKnownType(declared.qualifiedName)) return null
        val body = fn.bodyBlockExpression ?: return null
        if (containsReturn(body)) return null
        when (val last = body.statements.lastOrNull()) {
            is KtThrowExpression -> return null
            is KtWhileExpression -> if (last.condition?.text?.trim() == "true") return null
            is KtDoWhileExpression -> if (last.condition?.text?.trim() == "true") return null
            is KtExpression -> if (isNothingTerminal(last, resolver)) return null
            else -> {}
        }
        val anchor = fn.nameIdentifier ?: fn.typeReference ?: return null
        val r = anchor.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "A 'return' expression required in a function with a block body ('{...}')", "kt.missingReturn",
        )
    }

    /** A terminal that never returns normally: an inferred `Nothing`, or a call to a well-known `Nothing`
     *  stdlib function (`TODO()`/`error(…)`/`fail(…)`) which the parse-only inference may not type. */
    private fun isNothingTerminal(expr: KtExpression, resolver: KotlinResolver): Boolean {
        if (resolver.inferType(expr)?.qualifiedName == "kotlin.Nothing") return true
        val callee = (expr as? KtCallExpression)?.calleeExpression as? KtNameReferenceExpression ?: return false
        return callee.getReferencedName() in setOf("TODO", "error", "fail")
    }

    /** Whether [scope] contains a `return` that targets it (returns inside a NESTED named function don't). */
    private fun containsReturn(scope: PsiElement): Boolean {
        var found = false
        fun rec(p: PsiElement) {
            if (found || (p is KtNamedFunction)) return // a nested fn owns its own returns
            if (p is KtReturnExpression) { found = true; return }
            var c = p.firstChild
            while (c != null && !found) { rec(c); c = c.nextSibling }
        }
        var c = scope.firstChild
        while (c != null && !found) { rec(c); c = c.nextSibling }
        return found
    }

    /** A LOCAL `val`/`var` never referenced anywhere in its declaring block (a warning). Skips `_` and
     *  destructuring (modelled separately); counts any same-name reference as a use (so it never over-reports). */
    private fun unusedLocal(prop: KtProperty): Diagnostic? {
        val name = prop.name ?: return null
        if (name == "_") return null
        val block = prop.parent as? KtBlockExpression ?: return null // local declarations only
        val nameId = prop.nameIdentifier ?: return null
        var uses = 0
        fun rec(p: PsiElement) {
            if (p is KtNameReferenceExpression && p.getReferencedName() == name) uses++
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(block)
        if (uses > 0) return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "Variable '$name' is never used", "kt.unusedLocal")
    }

    /**
     * A property that Kotlin requires to be initialized but isn't — a top-level or concrete-class property with
     * no initializer, delegate, or getter (`class C { val x: Int }`). Skips locals (deferred init is legal),
     * `abstract`/`lateinit`/`expect`/`external`, and members of an interface/abstract/expect class.
     */
    private fun missingInitializer(prop: KtProperty): Diagnostic? {
        if (prop.hasInitializer() || prop.hasDelegate() || prop.getter != null || prop.setter != null) return null
        if (prop.typeReference == null) return null // no type AND no initializer won't parse as a property
        if (prop.hasModifier(KtTokens.ABSTRACT_KEYWORD) || prop.hasModifier(KtTokens.LATEINIT_KEYWORD) ||
            prop.hasModifier(KtTokens.EXPECT_KEYWORD) || prop.hasModifier(KtTokens.EXTERNAL_KEYWORD)
        ) return null
        val owner = prop.parent
        when (owner) {
            is KtFile -> {}
            is KtClassBody -> {
                val cls = owner.parent as? KtClassOrObject ?: return null
                if (cls is org.jetbrains.kotlin.psi.KtClass &&
                    (cls.isInterface() || cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
                        cls.hasModifier(KtTokens.SEALED_KEYWORD) || cls.hasModifier(KtTokens.EXPECT_KEYWORD) ||
                        cls.hasModifier(KtTokens.EXTERNAL_KEYWORD))
                ) return null
            }
            else -> return null // local / other: deferred init is legal
        }
        val nameId = prop.nameIdentifier ?: return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Property must be initialized", "kt.mustBeInitialized")
    }

    /** A LOCAL `var` never reassigned could be a `val` (a hint). Reassignment = `name = …`, an augmented
     *  assignment, or `++`/`--` anywhere in the declaring block. */
    private fun varCouldBeVal(prop: KtProperty): Diagnostic? {
        if (!prop.isVar) return null
        val name = prop.name ?: return null
        val block = prop.parent as? KtBlockExpression ?: return null // locals only
        if (isReassignedIn(block, name)) return null
        val nameId = prop.nameIdentifier ?: return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.HINT, "Variable '$name' is never reassigned and can be a 'val'", "kt.varCouldBeVal")
    }

    private fun isReassignedIn(scope: PsiElement, name: String): Boolean {
        var found = false
        fun isTarget(e: KtExpression?) = (e as? KtNameReferenceExpression)?.getReferencedName() == name
        fun rec(p: PsiElement) {
            if (found) return
            when (p) {
                is KtBinaryExpression -> if (p.operationToken in ASSIGN_OPS && isTarget(p.left)) { found = true; return }
                is KtPrefixExpression -> if (p.operationToken in INCDEC && isTarget(p.baseExpression)) { found = true; return }
                is KtPostfixExpression -> if (p.operationToken in INCDEC && isTarget(p.baseExpression)) { found = true; return }
            }
            var c = p.firstChild
            while (c != null && !found) { rec(c); c = c.nextSibling }
        }
        rec(scope)
        return found
    }

    private fun isPrivateDeclaration(d: KtNamedDeclaration): Boolean =
        d.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
            !d.hasModifier(KtTokens.OPERATOR_KEYWORD) && !d.hasModifier(KtTokens.OVERRIDE_KEYWORD)

    /** A `private` top-level / member function or property never referenced in the file (a warning). `private`
     *  is file- or class-scoped, so a whole-file reference scan is sound. */
    private fun unusedPrivate(decl: KtNamedDeclaration, refNames: Set<String>): Diagnostic? {
        // only top-level or class-body members (not a local, not a primary-constructor parameter)
        if (decl.parent !is KtFile && decl.parent !is KtClassBody) return null
        val name = decl.name ?: return null
        if (name in refNames) return null
        val kind = if (decl is KtNamedFunction) "Function" else "Property"
        val nameId = decl.nameIdentifier ?: return null
        val r = nameId.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "$kind '$name' is never used", "kt.unusedPrivate")
    }

    /** An import whose name is never referenced in the body (a warning). Skips star imports and imports of
     *  operator/convention names (used implicitly via `+`, `[]`, `in`, destructuring, which aren't visible here). */
    private fun unusedImports(file: KtFile, refNames: Set<String>): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        for (imp in file.importDirectives) {
            if (imp.isAllUnder) continue // star import: can't tell
            val fq = imp.importedFqName ?: continue
            val name = imp.aliasName ?: fq.shortName().asString()
            if (name.isEmpty() || name in OPERATOR_NAMES || name in refNames) continue
            val r = (imp.importedReference ?: imp).textRange
            out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.WARNING, "Unused import directive", "kt.unusedImport")
        }
        return out
    }

    /**
     * Statements in [block] that follow a statement which never completes normally: a bare `return`/`throw`/
     * `break`/`continue` or a `Nothing`-typed terminal (`TODO()`). Only DIRECT block statements count as
     * terminators (an `if (c) return` doesn't end the block), so this stays false-positive-free. Reported as
     * one warning spanning the dead range.
     */
    private fun unreachableCode(block: KtBlockExpression, resolver: KotlinResolver): List<Diagnostic> {
        val stmts = block.statements
        val cut = stmts.indexOfFirst { it is KtReturnExpression || it is KtThrowExpression || it is KtBreakExpression ||
            it is KtContinueExpression || (it is KtExpression && isNothingTerminal(it, resolver)) }
        if (cut < 0 || cut == stmts.lastIndex) return emptyList()
        val first = stmts[cut + 1]
        val last = stmts.last()
        return listOf(Diagnostic(TextRange(first.textRange.startOffset, last.textRange.endOffset), Severity.WARNING, "Unreachable code", "kt.unreachable"))
    }

    /**
     * An argument-count mismatch for a call to a function declared in THIS file, the only place the exact
     * arity is readable from the PSI (default values, varargs, trailing lambda), so it's safe. A unique
     * same-file candidate by name (skipped if overloaded or has a vararg) whose required/maximum arity the
     * call violates is flagged.
     */
    private fun argumentCountMismatch(call: KtCallExpression): Diagnostic? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        if (call.parent is KtQualifiedExpression && (call.parent as KtQualifiedExpression).selectorExpression === call) return null // member call: receiver unknown
        if (call.valueArguments.any { it.isNamed() || it.getSpreadElement() != null }) return null // named/spread: arity rules differ
        val name = callee.getReferencedName()
        val candidates = call.containingKtFile.declarations.filterIsInstance<KtNamedFunction>()
            .filter { it.name == name && it.receiverTypeReference == null }
        val fn = candidates.singleOrNull() ?: return null // overloaded or not same-file → don't guess
        val params = fn.valueParameters
        if (params.any { it.isVarArg }) return null
        val required = params.count { !it.hasDefaultValue() && !it.isVarArg }
        val max = params.size
        val n = call.valueArguments.size
        if (n in required..max) return null
        val r = call.valueArgumentList?.textRange ?: callee.textRange
        val msg = if (n > max) "Too many arguments for '$name' (expected ${if (required == max) "$max" else "$required..$max"})"
        else "No value passed for ${required - n} required argument(s) of '$name'"
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, "kt.argumentCount")
    }

    /**
     * `recv.member` on a nullable receiver without `?.`/`!!` (`val s: String? = …; s.length`). Conservative:
     * smart-casts are not modeled, so if the receiver is a simple name with any null-guard in the enclosing
     * function (`s != null`, `s ?:`, `s?.`, `s!!`), this backs off entirely to avoid the common false positive.
     */
    private fun unsafeNullableAccess(expr: KtDotQualifiedExpression, resolver: KotlinResolver): Diagnostic? {
        val receiver = expr.receiverExpression
        val recvType = resolver.inferType(receiver) ?: return null
        if (!recvType.nullable) return null
        // A simple-name receiver may be smart-cast after an untracked guard; skip if any guard exists.
        if (receiver is KtNameReferenceExpression && mayBeNullChecked(expr, receiver.getReferencedName())) return null
        val r = receiver.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Only safe (?.) or non-null asserted (!!) calls are allowed on a nullable receiver", "kt.unsafeNullable",
        )
    }

    /** Heuristic: does the enclosing function reference [name] alongside a null-related token, implying a
     *  unmodeled guard/smart-cast? Keeps [unsafeNullableAccess] from firing on `if (s != null) s.foo()`. */
    private fun mayBeNullChecked(from: PsiElement, name: String): Boolean {
        val fn = from.getStrictParentOfType<KtNamedFunction>() ?: from.containingFile
        val text = fn.text ?: return true
        return Regex("\\b${Regex.escape(name)}\\b\\s*(!!|\\?\\.|\\?:|[!=]=\\s*null)").containsMatchIn(text) ||
            Regex("null\\s*[!=]=\\s*\\b${Regex.escape(name)}\\b").containsMatchIn(text)
    }

    /**
     * A declared-type vs. initializer type mismatch (`val a: Int = ""`). Conservative to avoid false
     * positives over the parse-only model: flags only when BOTH the declared type and the inferred initializer
     * type are fully-known concrete types (no type parameters, no unknown names) and the value is not
     * assignable to the declaration. Numeric/numeric pairs are skipped entirely (integer literals adapt to
     * the expected numeric type, e.g. `val a: Long = 5`, which is not modeled here) and `Nothing`
     * (`TODO()`/`throw`) is assignable to everything, so it's skipped too.
     */
    private val NUMERIC = setOf(
        "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float",
    )

    private val ASSIGN_OPS = setOf(
        KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ,
    )
    private val INCDEC = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)

    // Convention/operator function names: imported for use via `+`, `[]`, `in`, `by`, destructuring, etc.,
    // which don't surface the name as a textual reference — so an unused-import check must not flag them.
    private val OPERATOR_NAMES = setOf(
        "plus", "minus", "times", "div", "rem", "mod", "plusAssign", "minusAssign", "timesAssign", "divAssign",
        "remAssign", "inc", "dec", "unaryPlus", "unaryMinus", "not", "get", "set", "invoke", "contains",
        "iterator", "next", "hasNext", "compareTo", "equals", "rangeTo", "rangeUntil", "provideDelegate",
        "getValue", "setValue", "component1", "component2", "component3", "component4", "component5",
    )

    private fun typeMismatch(declaredText: String?, init: KtExpression?, resolver: KotlinResolver): Diagnostic? {
        if (declaredText == null || init == null) return null
        val declared = service.typeFromText(declaredText, resolver.fileContext)
        val actual = resolver.inferType(init)
        if (!isMismatch(declared, actual)) return null
        val r = init.textRange
        return mismatchDiagnostic(r.startOffset, r.endOffset, actual!!, declared!!)
    }

    /** `return <expr>` whose value type isn't assignable to the enclosing block-body function's declared
     *  return type (`fun f(): Int { return "" }`). Skips labeled returns (they target a lambda). */
    private fun returnTypeMismatch(ret: KtReturnExpression, resolver: KotlinResolver): Diagnostic? {
        if (ret.getTargetLabel() != null) return null
        val value = ret.returnedExpression ?: return null
        val fn = ret.getStrictParentOfType<KtNamedFunction>() ?: return null
        if (!fn.hasBlockBody()) return null
        val declared = service.typeFromText(fn.typeReference?.text ?: return null, resolver.fileContext)
        if (declared?.qualifiedName == "kotlin.Unit") return null
        val actual = resolver.inferType(value)
        if (!isMismatch(declared, actual)) return null
        val r = value.textRange
        return mismatchDiagnostic(r.startOffset, r.endOffset, actual!!, declared!!)
    }

    /** Whether [actual] is a confidently-incompatible value for a declaration of type [declared].
     *  Both must be fully-known concrete types; numeric/numeric and `Nothing` are excused (see [typeMismatch]). */
    private fun isMismatch(declared: KotlinType?, actual: KotlinType?): Boolean {
        if (declared == null || actual == null) return false
        if (declared.isTypeParameter || actual.isTypeParameter) return false
        if (actual.qualifiedName == "kotlin.Nothing") return false
        if (!service.isKnownType(declared.qualifiedName) || !service.isKnownType(actual.qualifiedName)) return false
        if (declared.isAssignableFrom(actual)) return false
        return !(declared.qualifiedName in NUMERIC && actual.qualifiedName in NUMERIC)
    }

    private fun mismatchDiagnostic(start: Int, end: Int, actual: KotlinType, declared: KotlinType) = Diagnostic(
        TextRange(start, end), Severity.ERROR,
        "Type mismatch: inferred type is ${renderType(actual)} but ${renderType(declared)} was expected",
        "kt.typeMismatch",
    )

    private fun renderType(t: KotlinType): String {
        val simple = t.qualifiedName.substringAfterLast('.')
        val args = if (t.typeArguments.isEmpty()) ""
        else t.typeArguments.joinToString(", ", "<", ">") { it.qualifiedName.substringAfterLast('.') }
        return simple + args + if (t.nullable) "?" else ""
    }

    /**
     * A bare reference (no explicit receiver) in value position whose name resolves to nothing: a likely
     * typo, whether it's a call (`prinltn("x")`), an assignment target, or a plain read (`val x = bogus`).
     * Lower-case only (constructors/types/annotations are capitalized and handled by the type/import path),
     * and skipped for: member selectors (handled by [unresolvedMember]), the receiver of a qualified
     * expression (could be a package or a type's static access), type-position references (generic params),
     * import/package directives, named-argument labels, the implicit lambda `it`, a property accessor's
     * `field`, and any scope with a companion object (whose members are bare-accessible but not modeled).
     */
    private fun unresolvedBareReference(expr: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
        val parent = expr.parent
        // `a.b` selector, or the callee `b` of `a.b()` — a member, resolved by unresolvedMember (not here).
        if (parent is KtQualifiedExpression && parent.selectorExpression === expr) return null
        if (parent is KtCallExpression && parent.calleeExpression === expr) {
            (parent.parent as? KtQualifiedExpression)?.let { if (it.selectorExpression === parent) return null }
        }
        // `x.foo` / `x.foo()` — the receiver `x` could be a package or a type (static access); back off.
        if (parent is KtQualifiedExpression && parent.receiverExpression === expr) return null
        if (parent is KtValueArgumentName) return null // a named-argument label, not a reference
        if (inImportOrPackage(expr) || inTypeReference(expr)) return null
        val name = expr.getReferencedName()
        if (name.isEmpty() || name.first().isUpperCase()) return null
        if (name == "it" && hasAncestor(expr) { it is org.jetbrains.kotlin.psi.KtLambdaExpression }) return null
        if (name == "field" && hasAncestor(expr) { it is KtPropertyAccessor }) return null
        val off = expr.textRange.startOffset
        if (resolver.companionInScope(off) || resolver.bareNameResolves(name, off)) return null
        val r = expr.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Unresolved reference: $name", "kt.unresolved")
    }

    /** True if [expr] sits inside a type reference (so a lower-case generic type param isn't flagged). */
    private fun inTypeReference(expr: PsiElement): Boolean {
        var p: PsiElement? = expr.parent
        while (p != null && p !is KtDeclaration) { if (p is KtTypeReference) return true; p = p.parent }
        return false
    }

    private fun inImportOrPackage(expr: PsiElement): Boolean =
        hasAncestor(expr) { it is KtImportDirective || it is KtPackageDirective }

    private inline fun hasAncestor(expr: PsiElement, predicate: (PsiElement) -> Boolean): Boolean {
        var p: PsiElement? = expr.parent
        while (p != null) { if (predicate(p)) return true; p = p.parent }
        return false
    }

    private fun unresolvedMember(expr: KtNameReferenceExpression, resolver: KotlinResolver): Diagnostic? {
        val parent = expr.parent
        // `recv.name` (property) or `recv.name(...)` (call — expr is the callee under the selector call).
        val receiver = when {
            parent is KtQualifiedExpression && parent.selectorExpression === expr -> parent.receiverExpression
            parent is KtCallExpression && parent.calleeExpression === expr ->
                (parent.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === parent }?.receiverExpression
            else -> null
        } ?: return null
        val recvType = resolver.inferType(receiver) ?: return null // unknown receiver → don't flag
        val members = service.membersOf(recvType.qualifiedName, recvType.typeArguments, null)
        if (members.isEmpty()) return null // couldn't enumerate the type → don't flag
        val name = expr.getReferencedName()
        if (members.any { it.name == name }) return null
        val r = expr.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, "Unresolved reference: $name", "kt.unresolved")
    }

    // --- resolution / inference ---

    override fun resolve(node: DomNode): ResolveResult {
        val kdn = node as? KotlinDomNode ?: return ResolveResult.Unresolved
        val parsed = kdn.owner
        val resolver = KotlinResolver(parsed.ktFile, parsed, service)
        val psi = kdn.psi as? KtNameReferenceExpression ?: return ResolveResult.Unresolved
        val name = psi.getReferencedName()
        val q = psi.parent as? KtQualifiedExpression
        val sym: Symbol? = if (q != null && q.selectorExpression === psi) {
            resolver.inferType(q.receiverExpression)?.let { recv ->
                service.membersOf(recv.qualifiedName, recv.typeArguments, null).filterIsInstance<KotlinSymbol>().firstOrNull { it.name == name }
            }
        } else {
            resolver.scopeSymbolsAt(psi.textRange.startOffset).firstOrNull { it.name == name }
                ?: service.typeNamesByPrefix(name).firstOrNull { it.name == name }
        }
        return sym?.let { ResolveResult.Resolved(it) } ?: ResolveResult.Unresolved
    }

    override fun scopeAt(file: VirtualFile, offset: Int): Scope {
        val parsed = lastByFile[file.path] ?: return EmptyScope
        val resolver = KotlinResolver(parsed.ktFile, parsed, service)
        return KotlinScope(offset, resolver)
    }

    override fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef? = null // not yet supported

    override fun resolveType(node: DomNode): TypeRef? {
        val kdn = node as? KotlinDomNode ?: return null
        val expr = kdn.psi as? KtExpression ?: return null
        return KotlinResolver(kdn.owner.ktFile, kdn.owner, service).inferType(expr)
    }

    private inner class KotlinScope(private val offset: Int, private val resolver: KotlinResolver) : Scope {
        override val enclosing: Scope? = null
        override fun symbols(filter: SymbolFilter): List<Symbol> {
            val all = resolver.scopeSymbolsAt(offset)
            return if (filter.kinds == null) all else all.filter { it.kind in filter.kinds!! }
        }
        override fun resolve(name: String): ResolveResult =
            resolver.scopeSymbolsAt(offset).firstOrNull { it.name == name }
                ?.let { ResolveResult.Resolved(it) } ?: ResolveResult.Unresolved
    }

    /** Release the symbol service's open jar handles (mirrors JdtSourceAnalyzer's lifecycle tie-in). */
    override fun dispose() {
        if (serviceLazy.isInitialized()) service.close()
    }

    private class EmptyDocument(override val file: VirtualFile) : DocumentSnapshot {
        override val version: Long = 0
        override val text: CharSequence = ""
        override fun length(): Int = 0
    }

    private object EmptyScope : Scope {
        override val enclosing: Scope? = null
        override fun symbols(filter: SymbolFilter): List<Symbol> = emptyList()
        override fun resolve(name: String): ResolveResult = ResolveResult.Unresolved
    }
}
