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
