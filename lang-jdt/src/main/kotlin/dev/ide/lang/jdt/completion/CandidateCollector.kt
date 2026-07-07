package dev.ide.lang.jdt.completion

import dev.ide.index.ClassNameValue
import dev.ide.index.IndexId
import dev.ide.index.IndexService
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding
import org.eclipse.jdt.internal.compiler.lookup.Scope
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding

/**
 * The single completion candidate source. Given the resolved [AnalyzedContext], it gathers candidates
 * from the right places — there is no separate "augment" step: the index is consulted here, as one
 * source among the resolved bindings, selected by the context kind:
 *  - MEMBER_ACCESS  → the receiver type's accessible members (resolved bindings)
 *  - NAME_REFERENCE → in-scope names (locals/params/enclosing members) + unimported types (index, auto-import)
 *  - PACKAGE/IMPORT → sub-packages + the types in that package (index; inserted fully-qualified, no import)
 */
internal object CandidateCollector {

    private val CLASS_NAMES = IndexId("java.classNames")
    private val PACKAGES = IndexId("java.packages")
    private val PACKAGE_TYPES = IndexId("java.packageTypes")

    /**
     * [appendCallParens] is false when the caret is already followed by an argument list (`foo.bar|()`),
     * so a completed method inserts just its name; otherwise it inserts `()` and parks the caret inside
     * the parentheses when the method takes arguments. The flag is decided once, by the call site.
     */
    fun collect(ctx: AnalyzedContext, index: IndexService?, importOffset: Int, appendCallParens: Boolean, docs: dev.ide.lang.jdt.SourceMethodResolver? = null): List<Candidate> = when (ctx.kind) {
        CompletionKind.MEMBER_ACCESS -> members(ctx, appendCallParens, docs)
        CompletionKind.NAME_REFERENCE -> {
            val inScope = names(ctx, appendCallParens, docs)
            val taken = inScope.mapTo(HashSet()) { it.name }
            inScope + (index?.let { unimportedTypes(ctx, it, importOffset, taken) } ?: emptyList())
        }
        CompletionKind.TYPE_REFERENCE -> index?.let { typeReferences(ctx, it, importOffset) } ?: emptyList()
        CompletionKind.PACKAGE_REFERENCE, CompletionKind.IMPORT_REFERENCE ->
            index?.let { packageChildren(ctx, it) } ?: emptyList()
        CompletionKind.NONE -> emptyList()
    }

    // ---- resolved-binding sources ----

    private fun members(ctx: AnalyzedContext, appendCallParens: Boolean, docs: dev.ide.lang.jdt.SourceMethodResolver?): List<Candidate> {
        val type = ctx.qualifierType as? ReferenceBinding ?: return emptyList()
        val from = ctx.enclosingType
        val pkg = InternalMembers.samePackage(type, from)
        // An instance/value qualifier (`list.`) vs a type/static qualifier (`List.`). On an instance a
        // static member is legal but poor style → keep it but demote it; on a type only statics apply.
        val onInstance = !ctx.staticQualifier
        val out = ArrayList<Candidate>()

        for (m in InternalMembers.methods(type)) {
            if (ctx.staticQualifier && !m.isStatic) continue
            if (!InternalMembers.accessibleMethod(m, from, pkg)) continue
            if (!matches(String(m.selector), ctx.prefix)) continue
            out.add(methodCandidate(m, from, appendCallParens, discouraged = onInstance && m.isStatic, docs = docs))
        }
        for (f in InternalMembers.fields(type)) {
            if (ctx.staticQualifier && !f.isStatic) continue
            if (!InternalMembers.accessibleField(f, from, pkg)) continue
            if (!matches(String(f.name), ctx.prefix)) continue
            out.add(fieldCandidate(f, from, discouraged = onInstance && f.isStatic))
        }
        // Nested types are referenced through the enclosing *type* (`Map.Entry`), never through an instance,
        // so only offer them for a type/static qualifier — an instance member-select must not suggest them.
        if (ctx.staticQualifier) {
            for (t in InternalMembers.memberTypes(type)) {
                if (!InternalMembers.accessibleType(t, from, pkg)) continue // hides private nested types/interfaces
                if (!matches(String(t.sourceName()), ctx.prefix)) continue
                out.add(typeCandidate(t))
            }
        }
        return out
    }

    private fun names(ctx: AnalyzedContext, appendCallParens: Boolean, docs: dev.ide.lang.jdt.SourceMethodResolver?): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        for (v in ctx.locals) {
            if (matches(v.name, ctx.prefix)) out.putIfAbsent("v:${v.name}", variableCandidate(v))
        }
        val from = ctx.enclosingType
        if (from != null) {
            for (m in InternalMembers.methods(from)) {
                if (ctx.inStaticContext && !m.isStatic) continue
                if (matches(String(m.selector), ctx.prefix)) out.putIfAbsent("m:${String(m.selector)}/${m.parameters.size}", methodCandidate(m, from, appendCallParens, docs = docs))
            }
            for (f in InternalMembers.fields(from)) {
                if (ctx.inStaticContext && !f.isStatic) continue
                if (matches(String(f.name), ctx.prefix)) out.putIfAbsent("f:${String(f.name)}", fieldCandidate(f, from))
            }
        }
        return out.values.toList()
    }

    // ---- index sources ----

    /** Unimported types matching the prefix, each with an auto-`import` edit (skipped if already visible). */
    private fun unimportedTypes(ctx: AnalyzedContext, index: IndexService, importOffset: Int, taken: Set<String>): List<Candidate> {
        if (ctx.prefix.isEmpty()) return emptyList()
        val out = ArrayList<Candidate>()
        for (hit in index.fuzzy<ClassNameValue>(CLASS_NAMES, ctx.prefix, 60)) {
            if (hit.key in taken) continue
            out.add(unimportedTypeCandidate(hit.value, ctx, importOffset))
        }
        return out
    }

    /**
     * A type used unqualified normally needs an auto-`import`. But if its simple name is already bound to a
     * *different* type by an explicit single-type import (e.g. `java.awt.List` imported while completing
     * `java.util.List`), an unqualified insert would resolve to that other type, and adding our own import
     * would be a duplicate-name clash that won't compile. In that case insert the fully-qualified name and
     * add no import; otherwise insert the simple name with the auto-`import` (itself skipped if visible).
     */
    private fun unimportedTypeCandidate(v: ClassNameValue, ctx: AnalyzedContext, importOffset: Int, resolved: TypeBinding? = null): Candidate {
        val simple = v.fqn.substringAfterLast('.')
        val collides = ctx.unit.importedFqns.any { it != v.fqn && it.substringAfterLast('.') == simple }
        return if (collides) indexTypeCandidate(v, Proximity.UNIMPORTED_TYPE, emptyList(), insertFqn = true, resolved = resolved)
        else indexTypeCandidate(v, Proximity.UNIMPORTED_TYPE, importEdit(v.fqn, ctx, importOffset), resolved = resolved)
    }

    /**
     * Type-position completion (`new Foo|`, `Foo| x`, casts, `extends`/`throws`): types matching the prefix
     * from the index, each with an auto-`import` (or fully-qualified on a name clash). When the position has
     * an expected type (`List x = new |…`), candidates are resolved against the marker's scope so the ranker
     * can float assignable ones (ArrayList, LinkedList) above the rest.
     */
    private fun typeReferences(ctx: AnalyzedContext, index: IndexService, importOffset: Int): List<Candidate> {
        if (ctx.prefix.isEmpty()) return emptyList() // need a prefix to query the index (can't enumerate all types)
        val rankByType = ctx.expectedType != null && ctx.typeScope != null
        val out = ArrayList<Candidate>()
        for (hit in index.fuzzy<ClassNameValue>(CLASS_NAMES, ctx.prefix, 60)) {
            val resolved = if (rankByType) resolveType(ctx.typeScope!!, hit.value.fqn) else null
            out.add(unimportedTypeCandidate(hit.value, ctx, importOffset, resolved))
        }
        return out
    }

    /** Resolve a fully-qualified type name against the marker's scope (for expected-type ranking). */
    private fun resolveType(scope: Scope, fqn: String): TypeBinding? = runCatching {
        val parts = fqn.split('.').map { it.toCharArray() }.toTypedArray()
        scope.getType(parts, parts.size)?.takeIf { it.isValidBinding }
    }.getOrNull()

    /** Sub-packages and the types directly under a package path (inserted fully-qualified — no import). */
    private fun packageChildren(ctx: AnalyzedContext, index: IndexService): List<Candidate> {
        val q = ctx.qualifierPath ?: ""
        val prefix = ctx.prefix
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        // Query the index for the *children* of `q`: under a known package that means the `q.` prefix (with
        // the trailing dot), so an empty typed prefix (`import java.|`) still lists java's sub-packages —
        // querying bare `q` matches only `q` itself, which is then filtered out (→ nothing). With a typed
        // prefix it's `q.prefix`; at the top level (no `q`) it's just the prefix.
        val full = if (q.isEmpty()) prefix else "$q.$prefix"
        for (hit in index.prefix<String>(PACKAGES, full, 200)) {
            val pkg = hit.value
            val rest = when {
                q.isEmpty() -> pkg
                pkg.startsWith("$q.") -> pkg.removePrefix("$q.")
                else -> continue
            }
            val seg = rest.substringBefore('.')
            if (seg.isEmpty() || !matches(seg, prefix) || !seen.add(seg)) continue
            out.add(packageCandidate(seg))
        }
        if (q.isNotEmpty()) {
            for (v in index.exact<ClassNameValue>(PACKAGE_TYPES, q)) {
                val simple = v.fqn.substringAfterLast('.')
                if (!matches(simple, prefix)) continue
                out.add(indexTypeCandidate(v, Proximity.NESTED_TYPE, emptyList()))
            }
        }
        return out
    }

    private fun importEdit(fqn: String, ctx: AnalyzedContext, importOffset: Int): List<TextEdit> {
        val pkg = fqn.substringBeforeLast('.', "")
        val u = ctx.unit
        if (pkg.isEmpty() || pkg == "java.lang" || pkg == u.enclosingPackage) return emptyList()
        if (fqn in u.importedFqns || pkg in u.importedPackages) return emptyList()
        return listOf(TextEdit(TextRange(importOffset, importOffset), "import $fqn;\n"))
    }

    // ---- candidate builders ----

    /**
     * A callable: unless the caret is already followed by an argument list, the inserted text appends
     * `()`, and the caret parks between the parentheses when the method takes arguments (so the user types
     * them straight away) or after them when it doesn't (no-arg call is complete). The parens-insertion
     * policy is Java's; the neutral [CaretAction] is what carries it out in the editor.
     */
    private fun methodCandidate(m: MethodBinding, from: ReferenceBinding?, appendCallParens: Boolean, discouraged: Boolean = false, docs: dev.ide.lang.jdt.SourceMethodResolver? = null): Candidate {
        val name = String(m.selector)
        val takesArgs = m.parameters?.isNotEmpty() == true
        val insertText = if (appendCallParens) "$name()" else name
        val caret = when {
            !appendCallParens -> CaretAction.AtEnd
            takesArgs -> CaretAction.At(name.length + 1) // between '(' and ')'
            else -> CaretAction.AtEnd                     // after '()', the call is complete
        }
        val params = m.parameters ?: emptyArray()
        // Recover real parameter names + javadoc from source when available; fall back to types only.
        val info = if (docs != null && params.isNotEmpty()) {
            m.declaringClass?.let { String(it.constantPoolName()).replace('/', '.') }?.let { docs.lookup(it, name, params.size) }
        } else null
        val presentation = if (info != null && info.params.size == params.size)
            "$name(${params.indices.joinToString(", ") { i -> InternalMembers.display(params[i]) + " " + info.params[i] }})"
        else "$name(${params.joinToString(", ") { InternalMembers.display(it) }})"
        return Candidate(
            name = name,
            insertText = insertText,
            presentation = presentation,
            tail = InternalMembers.display(m.returnType),
            container = m.declaringClass?.let { InternalMembers.name(it) },
            kind = if (m.isConstructor) CompletionItemKind.CONSTRUCTOR else CompletionItemKind.METHOD,
            type = m.returnType,
            proximity = proximity(m.declaringClass, from),
            deprecated = m.isDeprecated,
            caret = caret,
            discouraged = discouraged,
            documentation = info?.javadocRaw?.let { dev.ide.lang.jdt.JavadocText.clean(it) }?.takeIf { it.isNotEmpty() },
        )
    }

    private fun fieldCandidate(f: FieldBinding, from: ReferenceBinding?, discouraged: Boolean = false) = Candidate(
        name = String(f.name),
        insertText = String(f.name),
        presentation = String(f.name),
        tail = InternalMembers.display(f.type),
        container = f.declaringClass?.let { InternalMembers.name(it) },
        kind = CompletionItemKind.FIELD,
        type = f.type,
        proximity = proximity(f.declaringClass, from),
        deprecated = f.isDeprecated,
        discouraged = discouraged,
    )

    private fun variableCandidate(v: ScopeVar) = Candidate(
        name = v.name,
        insertText = v.name,
        presentation = v.name,
        tail = v.type?.let { InternalMembers.display(it) },
        kind = if (v.parameter) CompletionItemKind.PARAMETER else CompletionItemKind.VARIABLE,
        type = v.type,
        proximity = if (v.parameter) Proximity.PARAMETER else Proximity.LOCAL,
        deprecated = false,
    )

    private fun typeCandidate(t: ReferenceBinding) = Candidate(
        name = String(t.sourceName()),
        insertText = String(t.sourceName()),
        presentation = String(t.sourceName()),
        tail = null,
        container = InternalMembers.packageName(t),
        kind = InternalMembers.kindOfType(t),
        type = t,
        proximity = Proximity.NESTED_TYPE,
        deprecated = t.isDeprecated,
    )

    private fun indexTypeCandidate(v: ClassNameValue, proximity: Proximity, edits: List<TextEdit>, insertFqn: Boolean = false, resolved: TypeBinding? = null): Candidate {
        val simple = v.fqn.substringAfterLast('.')
        return Candidate(
            name = simple,
            insertText = if (insertFqn) v.fqn else simple,
            presentation = if (insertFqn) v.fqn else simple,
            tail = null,
            container = v.fqn.substringBeforeLast('.', "").ifEmpty { null },
            kind = kindOf(v.kind),
            type = resolved,
            proximity = proximity,
            deprecated = false,
            importEdits = edits,
        )
    }

    private fun packageCandidate(seg: String) = Candidate(
        name = seg,
        insertText = seg,
        presentation = seg,
        tail = "package",
        kind = CompletionItemKind.PACKAGE,
        type = null,
        proximity = Proximity.PACKAGE,
        deprecated = false,
    )

    private fun kindOf(kind: String): CompletionItemKind = when (kind) {
        "interface" -> CompletionItemKind.INTERFACE
        "enum" -> CompletionItemKind.ENUM
        "record" -> CompletionItemKind.RECORD
        "annotation" -> CompletionItemKind.ANNOTATION_TYPE
        else -> CompletionItemKind.CLASS
    }

    private fun proximity(declaring: ReferenceBinding?, from: ReferenceBinding?): Proximity = when {
        declaring == null -> Proximity.INHERITED
        InternalMembers.isObjectMember(declaring) -> Proximity.OBJECT_MEMBER
        from != null && InternalMembers.name(declaring) == InternalMembers.name(from) -> Proximity.OWN_MEMBER
        else -> Proximity.INHERITED
    }

    // The shared graded matcher (prefix/camel-hump/substring) so `mDL` reaches `myDynamicList` on the
    // Java path too. Single-slot memo: one completion filters hundreds of bindings with the same prefix
    // (a stale race just recomputes — the matcher is a pure value of its prefix).
    private var lastMatcher: dev.ide.lang.completion.PrefixMatcher? = null

    private fun matches(name: String, prefix: String): Boolean {
        if (prefix.isEmpty()) return true
        val m = lastMatcher?.takeIf { it.prefix == prefix }
            ?: dev.ide.lang.completion.PrefixMatcher(prefix).also { lastMatcher = it }
        return m.matches(name)
    }
}
