package dev.ide.lang.jdt.completion

import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding
import org.eclipse.jdt.internal.compiler.lookup.Scope
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding

internal enum class CompletionKind { MEMBER_ACCESS, NAME_REFERENCE, TYPE_REFERENCE, PACKAGE_REFERENCE, IMPORT_REFERENCE, NONE }

/** A local variable or parameter visible at the caret (for bare-name completion). */
internal class ScopeVar(val name: String, val type: TypeBinding?, val parameter: Boolean)

/** Compilation-unit facts read from the resolved AST (not regex): package, imports, import anchor. */
internal class UnitInfo(
    val enclosingPackage: String,
    val importedFqns: Set<String>,
    val importedPackages: Set<String>,
    /** AST end offset of the last import/package decl (for placing an auto-import), or -1. */
    val importAnchorEnd: Int,
) {
    companion object { val EMPTY = UnitInfo("", emptySet(), emptySet(), -1) }
}

/** The semantic situation at the caret, derived from the internal resolved AST. */
internal class AnalyzedContext(
    val kind: CompletionKind,
    val prefix: String,
    val qualifierType: TypeBinding?,
    val staticQualifier: Boolean,
    /** Dotted qualifier of a `a.b.X` reference (for package / unresolved-name completion); null otherwise. */
    val qualifierPath: String?,
    val enclosingType: ReferenceBinding?,
    val inStaticContext: Boolean,
    val expectedType: TypeBinding?,
    val locals: List<ScopeVar>,
    val unit: UnitInfo,
    /** The ecj scope at a TYPE_REFERENCE marker — lets the collector resolve candidate types to rank by
     *  assignability to [expectedType] (so `List x = new |` floats ArrayList/LinkedList up). Null otherwise. */
    val typeScope: Scope? = null,
) {
    companion object {
        fun none(prefix: String, unit: UnitInfo = UnitInfo.EMPTY) =
            AnalyzedContext(CompletionKind.NONE, prefix, null, false, null, null, false, null, emptyList(), unit)
    }
}
