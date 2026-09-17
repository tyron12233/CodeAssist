package dev.ide.lang.resolve

import dev.ide.lang.dom.DomNode
import dev.ide.vfs.VirtualFile

/**
 * Resolution: turn references into resolved [Symbol]s, enumerate visible names ([Scope]) and type
 * members ([TypeRef]). This is the input to code completion ("enumerate everything reachable here").
 *
 * Source symbols come from other ParsedFiles; binary symbols come from the module's
 * ClasspathSnapshot (the same hashed classpath the build uses, so resolution and compilation agree).
 */

interface Symbol {
    val name: String
    val kind: SymbolKind
    val type: TypeRef?                      // declared type (field/var/param) or return type (method)
    val owner: Symbol?                      // enclosing type/method/package
    val modifiers: Set<Modifier>
    val origin: SymbolOrigin
    /** Declaration site, if this symbol came from source we have parsed. */
    fun declaration(): DomNode?
    fun documentation(): String?
}

enum class SymbolKind {
    PACKAGE, CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, RECORD,
    METHOD, CONSTRUCTOR, FIELD, ENUM_CONSTANT,
    LOCAL_VARIABLE, PARAMETER, TYPE_PARAMETER,
}

enum class Modifier { PUBLIC, PROTECTED, PRIVATE, STATIC, FINAL, ABSTRACT, DEFAULT, SYNCHRONIZED }

/**
 * One declaration in a file's structure view / outline (also drives sticky scroll headers). [name] is the
 * display name; [detail] an optional suffix (a method's parameter list, a field's type); [kind] its symbol
 * kind; [nameOffset] where to put the caret on navigation (and the line pinned as a sticky header);
 * [endOffset] the declaration's end (so a scroll position can be tested for containment); [depth] the
 * nesting level (0 = top-level).
 */
data class StructureItem(
    val name: String,
    val detail: String?,
    val kind: SymbolKind,
    val nameOffset: Int,
    val endOffset: Int,
    val depth: Int,
)

/** The markup dialect of a [QuickDocInfo.doc] body, so the renderer knows how to parse it. */
enum class DocFormat { JAVADOC, KDOC, PLAIN }

/**
 * Quick documentation for the symbol under the caret: its [signature] (a display string), [name] + [kind],
 * the [container] it's declared in (owning type / package, for a subheader), and its [doc] comment tagged with
 * its [docFormat]. [doc] is RAW markup for a source-backed symbol (rendered rich) or cleaned/plain text
 * otherwise (or null when the symbol has no doc).
 */
data class QuickDocInfo(
    val signature: String,
    val name: String,
    val kind: SymbolKind,
    val container: String?,
    val doc: String?,
    val docFormat: DocFormat,
)

data class SymbolOrigin(val fromSource: Boolean, val file: VirtualFile?)

/**
 * A resolved type. Member enumeration walks the supertype graph and honors access relative to a
 * call site — exactly what `expr.` completion needs.
 */
interface TypeRef {
    val qualifiedName: String
    val typeArguments: List<TypeRef>
    fun isAssignableFrom(other: TypeRef): Boolean
    fun supertypes(): List<TypeRef>
    /** Fields + methods + nested types visible from [accessibleFrom], inherited members included. */
    fun members(accessibleFrom: Symbol?): List<Symbol>
}

/**
 * The lexical scope visible at a position: locals/params in range, fields/methods of the enclosing
 * type and supertypes, single-type and on-demand imports, same-package types, then java.lang.
 * [symbols] is the candidate set for a bare name-reference completion.
 */
interface Scope {
    val enclosing: Scope?
    fun symbols(filter: SymbolFilter = SymbolFilter.ALL): List<Symbol>
    fun resolve(name: String): ResolveResult
}

data class SymbolFilter(
    val kinds: Set<SymbolKind>? = null,     // null == any
    val staticOnly: Boolean = false,
    val instanceOnly: Boolean = false,
) {
    companion object { val ALL = SymbolFilter() }
}

sealed interface ResolveResult {
    data class Resolved(val symbol: Symbol) : ResolveResult
    data class Ambiguous(val candidates: List<Symbol>) : ResolveResult
    object Unresolved : ResolveResult
}
