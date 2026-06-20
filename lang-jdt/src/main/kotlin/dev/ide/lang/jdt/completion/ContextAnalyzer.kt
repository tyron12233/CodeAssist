package dev.ide.lang.jdt.completion

import org.eclipse.jdt.internal.compiler.ASTVisitor
import org.eclipse.jdt.internal.compiler.ast.Argument
import org.eclipse.jdt.internal.compiler.ast.Assignment
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration
import org.eclipse.jdt.internal.compiler.ast.FieldReference
import org.eclipse.jdt.internal.compiler.ast.ImportReference
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration
import org.eclipse.jdt.internal.compiler.ast.MessageSend
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration
import org.eclipse.jdt.internal.compiler.lookup.BlockScope
import org.eclipse.jdt.internal.compiler.lookup.Binding
import org.eclipse.jdt.internal.compiler.lookup.ClassScope
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding
import org.eclipse.jdt.internal.compiler.lookup.Scope
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding
import org.eclipse.jdt.internal.compiler.lookup.VariableBinding

/**
 * Single-pass traversal of the resolved internal AST that locates the completion marker and derives an
 * [AnalyzedContext] from real bindings (no regex): the qualifier's resolved type/package, the enclosing
 * type, the method's static-ness/return type, in-scope locals, the expected type, plus unit-level facts
 * (package, imports) for index-backed completion + auto-import.
 */
internal object ContextAnalyzer {

    /** Stackless control-flow signal: thrown once the marker context is captured to stop the AST traversal. */
    private object MarkerFound : RuntimeException() {
        private fun readResolve(): Any = MarkerFound
        override fun fillInStackTrace(): Throwable = this
    }

    fun analyze(cud: CompilationUnitDeclaration, markerStart: Int, prefix: String): AnalyzedContext {
        val unit = unitInfo(cud)
        importContext(cud, prefix, unit)?.let { return it } // marker inside an `import …;`
        val visitor = Visitor(prefix, markerStart, unit)
        // The visitor captures the context the moment it reaches the marker and then throws [MarkerFound]
        // to unwind the traversal early — without it, ecj walks the whole AST past the marker for nothing.
        try { cud.traverse(visitor, cud.scope) } catch (_: MarkerFound) {} catch (_: Throwable) {}
        return visitor.result ?: AnalyzedContext.none(prefix, unit)
    }

    /** If the marker landed inside an import statement, complete packages/types there (no auto-import). */
    private fun importContext(cud: CompilationUnitDeclaration, prefix: String, unit: UnitInfo): AnalyzedContext? {
        val imp = cud.imports?.firstOrNull { ir -> ir.tokens.any { String(it) == COMPLETION_MARKER } } ?: return null
        val idx = imp.tokens.indexOfFirst { String(it) == COMPLETION_MARKER }
        val path = imp.tokens.take(idx).joinToString(".") { String(it) }
        return AnalyzedContext(CompletionKind.IMPORT_REFERENCE, prefix, null, false, path, null, false, null, emptyList(), unit)
    }

    private fun unitInfo(cud: CompilationUnitDeclaration): UnitInfo {
        val pkg = cud.currentPackage?.let { joinTokens(it.tokens) } ?: ""
        val fqns = HashSet<String>()
        val pkgs = HashSet<String>()
        var anchor = cud.currentPackage?.declarationSourceEnd ?: -1
        cud.imports?.forEach { imp: ImportReference ->
            if (imp.isStatic) return@forEach
            val name = joinTokens(imp.tokens)
            if ((imp.bits and org.eclipse.jdt.internal.compiler.ast.ASTNode.OnDemand) != 0) pkgs.add(name) else fqns.add(name)
            if (imp.declarationSourceEnd > anchor) anchor = imp.declarationSourceEnd
        }
        return UnitInfo(pkg, fqns, pkgs, anchor)
    }

    private fun joinTokens(tokens: Array<CharArray>?): String =
        tokens?.joinToString(".") { String(it) } ?: ""

    private class Visitor(private val prefix: String, private val markerStart: Int, private val unit: UnitInfo) : ASTVisitor() {
        var result: AnalyzedContext? = null

        private val typeStack = ArrayDeque<ReferenceBinding?>()
        private val methodStatic = ArrayDeque<Boolean>()
        private val methodReturn = ArrayDeque<TypeBinding?>()
        private val expected = ArrayDeque<TypeBinding?>()
        private val locals = ArrayList<ScopeVar>()

        // --- enclosing type ---
        override fun visit(td: TypeDeclaration, scope: CompilationUnitScope): Boolean { typeStack.addLast(td.binding); return true }
        override fun visit(td: TypeDeclaration, scope: ClassScope): Boolean { typeStack.addLast(td.binding); return true }
        override fun visit(td: TypeDeclaration, scope: BlockScope): Boolean { typeStack.addLast(td.binding); return true }
        override fun endVisit(td: TypeDeclaration, scope: CompilationUnitScope) { typeStack.removeLastOrNull() }
        override fun endVisit(td: TypeDeclaration, scope: ClassScope) { typeStack.removeLastOrNull() }
        override fun endVisit(td: TypeDeclaration, scope: BlockScope) { typeStack.removeLastOrNull() }

        // --- enclosing method ---
        override fun visit(md: MethodDeclaration, scope: ClassScope): Boolean {
            enterMethod(static = md.binding?.isStatic ?: false, ret = md.binding?.returnType, args = md.arguments)
            return true
        }
        override fun endVisit(md: MethodDeclaration, scope: ClassScope) = exitMethod()
        override fun visit(cd: ConstructorDeclaration, scope: ClassScope): Boolean {
            enterMethod(static = false, ret = null, args = cd.arguments); return true
        }
        override fun endVisit(cd: ConstructorDeclaration, scope: ClassScope) = exitMethod()

        // --- locals + expected type ---
        override fun visit(ld: LocalDeclaration, scope: BlockScope): Boolean {
            if (ld.sourceStart < markerStart) locals.add(ScopeVar(String(ld.name), ld.binding?.type, parameter = false))
            expected.addLast(ld.binding?.type)
            return true
        }
        override fun endVisit(ld: LocalDeclaration, scope: BlockScope) { expected.removeLastOrNull() }

        override fun visit(a: Assignment, scope: BlockScope): Boolean { expected.addLast(runCatching { a.lhs.resolvedType }.getOrNull()); return true }
        override fun endVisit(a: Assignment, scope: BlockScope) { expected.removeLastOrNull() }

        override fun visit(r: ReturnStatement, scope: BlockScope): Boolean { expected.addLast(methodReturn.lastOrNull()); return true }
        override fun endVisit(r: ReturnStatement, scope: BlockScope) { expected.removeLastOrNull() }

        // --- marker references ---
        override fun visit(ref: QualifiedNameReference, scope: BlockScope): Boolean { onQualifiedName(ref, scope); return true }
        override fun visit(ref: QualifiedNameReference, scope: ClassScope): Boolean { onQualifiedName(ref, scope); return true }
        override fun visit(ref: FieldReference, scope: BlockScope): Boolean { onFieldRef(ref); return true }
        override fun visit(ref: FieldReference, scope: ClassScope): Boolean { onFieldRef(ref); return true }
        override fun visit(ms: MessageSend, scope: BlockScope): Boolean { onMessageSend(ms); return true }
        override fun visit(ref: SingleNameReference, scope: BlockScope): Boolean { onSingleName(ref); return true }
        override fun visit(ref: SingleNameReference, scope: ClassScope): Boolean { onSingleName(ref); return true }
        // type positions — `new Foo|`, `Foo| x;`, `(Foo|)`, `extends Foo|`, … (ecj parses these as type refs)
        override fun visit(ref: SingleTypeReference, scope: BlockScope): Boolean { onTypeRef(String(ref.token), scope); return true }
        override fun visit(ref: SingleTypeReference, scope: ClassScope): Boolean { onTypeRef(String(ref.token), scope); return true }
        override fun visit(ref: QualifiedTypeReference, scope: BlockScope): Boolean { onQualifiedType(ref); return true }
        override fun visit(ref: QualifiedTypeReference, scope: ClassScope): Boolean { onQualifiedType(ref); return true }

        private fun enterMethod(static: Boolean, ret: TypeBinding?, args: Array<Argument>?) {
            methodStatic.addLast(static)
            methodReturn.addLast(ret)
            args?.forEach { locals.add(ScopeVar(String(it.name), it.binding?.type, parameter = true)) }
        }

        private fun exitMethod() {
            methodStatic.removeLastOrNull()
            methodReturn.removeLastOrNull()
        }

        private fun onQualifiedName(ref: QualifiedNameReference, scope: Scope) {
            if (result != null || ref.tokens.size < 2 || String(ref.tokens.last()) != COMPLETION_MARKER) return
            val path = ref.tokens.dropLast(1).joinToString(".") { String(it) }
            val (type, static) = resolveQualifier(ref, scope)
            // A resolved type/value qualifier → member access; otherwise treat the dotted path as a
            // package reference (the index decides what sub-packages/types live under it).
            if (type != null) capture(CompletionKind.MEMBER_ACCESS, type, static, path)
            else capture(CompletionKind.PACKAGE_REFERENCE, null, false, path)
        }

        /**
         * Resolve the qualifier (everything before the marker) using JDT's own resolution: first as a
         * (possibly package-qualified) TYPE via [Scope.getType] — which fixes `java.util.List.` static
         * member access — then fall back to a variable + field/member-type walk for instance chains.
         */
        private fun resolveQualifier(ref: QualifiedNameReference, scope: Scope): Pair<TypeBinding?, Boolean> {
            val toks = ref.tokens
            val qualifier = toks.copyOfRange(0, toks.size - 1)
            val asType = runCatching { scope.getType(qualifier, qualifier.size) }.getOrNull()
                ?.takeIf { it.isValidBinding } as? ReferenceBinding
            if (asType != null) return asType to true

            val first = runCatching { scope.getBinding(toks[0], Binding.VARIABLE or Binding.TYPE, ref, true) }
                .getOrNull()?.takeIf { it.isValidBinding }
            var (type, static) = typeAndStaticOf(first)
            for (i in 1 until toks.size - 1) {
                val recv = type as? ReferenceBinding ?: return null to false
                val field = runCatching { scope.getField(recv, toks[i], ref) }.getOrNull()?.takeIf { it.isValidBinding }
                if (field != null) { type = field.type; static = false; continue }
                val member = runCatching { recv.memberTypes() }.getOrNull()
                    ?.firstOrNull { String(it.sourceName()) == String(toks[i]) }
                    ?: return null to false
                type = member; static = true
            }
            return type to static
        }

        private fun onFieldRef(ref: FieldReference) {
            if (result != null || String(ref.token) != COMPLETION_MARKER) return
            capture(CompletionKind.MEMBER_ACCESS, runCatching { ref.receiver.resolvedType }.getOrNull(), staticQualifier = false, qualifierPath = null)
        }

        /**
         * The marker is the selector of a call — `recv.MARKER(…)`, i.e. completion triggered with an
         * argument list already present. Same as field access: members of the receiver's type. A bare
         * type-name receiver (`Foo.MARKER()`) is a static call, so we restrict to statics there.
         */
        private fun onMessageSend(ms: MessageSend) {
            if (result != null || String(ms.selector) != COMPLETION_MARKER) return
            val static = (ms.receiver as? SingleNameReference)?.binding is ReferenceBinding
            capture(CompletionKind.MEMBER_ACCESS, runCatching { ms.receiver.resolvedType }.getOrNull(), staticQualifier = static, qualifierPath = null)
        }

        private fun onSingleName(ref: SingleNameReference) {
            if (result != null || String(ref.token) != COMPLETION_MARKER) return
            capture(CompletionKind.NAME_REFERENCE, null, staticQualifier = false, qualifierPath = null)
        }

        /** A bare type name at the marker (`new Foo|`, `Foo| x`) → type completion, ranked by the expected
         *  type at this position so `List x = new |` floats its subtypes up. Keeps the scope to resolve them. */
        private fun onTypeRef(name: String, scope: Scope) {
            if (result != null || name != COMPLETION_MARKER) return
            capture(CompletionKind.TYPE_REFERENCE, null, staticQualifier = false, qualifierPath = null, typeScope = scope)
        }

        /** A dotted type name (`java.util.Foo|`) → packages/types under that path, same as a qualified value.
         *  Skips when the head is an in-scope local/parameter: that's a value member-access ecj's recovery
         *  mis-parsed as a type (`formatter.x` → `Type var`), handled by the member-access path instead. */
        private fun onQualifiedType(ref: QualifiedTypeReference) {
            if (result != null || ref.tokens.size < 2 || String(ref.tokens.last()) != COMPLETION_MARKER) return
            val head = String(ref.tokens.first())
            if (locals.any { it.name == head }) return
            val path = ref.tokens.dropLast(1).joinToString(".") { String(it) }
            capture(CompletionKind.PACKAGE_REFERENCE, null, staticQualifier = false, qualifierPath = path)
        }

        private fun typeAndStaticOf(binding: Binding?): Pair<TypeBinding?, Boolean> = when (binding) {
            is VariableBinding -> binding.type to false
            is ReferenceBinding -> binding as TypeBinding to true
            else -> null to false
        }

        private fun capture(kind: CompletionKind, qualifierType: TypeBinding?, staticQualifier: Boolean, qualifierPath: String?, typeScope: Scope? = null) {
            result = AnalyzedContext(
                kind = kind,
                prefix = prefix,
                qualifierType = qualifierType,
                staticQualifier = staticQualifier,
                qualifierPath = qualifierPath,
                enclosingType = typeStack.lastOrNull(),
                inStaticContext = methodStatic.lastOrNull() ?: false,
                expectedType = expected.lastOrNull(),
                locals = locals.toList(),
                unit = unit,
                typeScope = typeScope,
            )
            throw MarkerFound
        }
    }
}
