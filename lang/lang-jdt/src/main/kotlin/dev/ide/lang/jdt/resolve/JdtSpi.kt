package dev.ide.lang.jdt.resolve

import dev.ide.lang.dom.DomNode
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.SymbolFilter
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.Modifier as JdtModifier

/** Neutral [TypeRef] over a JDT [ITypeBinding]. */
class JdtTypeRef(val binding: ITypeBinding) : TypeRef {
    override val qualifiedName: String get() = binding.qualifiedName.ifEmpty { binding.name }
    override val typeArguments: List<TypeRef> get() = binding.typeArguments.map { JdtTypeRef(it) }
    override fun isAssignableFrom(other: TypeRef): Boolean =
        other is JdtTypeRef && runCatching { other.binding.isAssignmentCompatible(binding) }.getOrDefault(false)
    override fun supertypes(): List<TypeRef> =
        (listOfNotNull(binding.superclass) + binding.interfaces).map { JdtTypeRef(it) }
    override fun members(accessibleFrom: Symbol?): List<Symbol> =
        collectMethods(binding).map { JdtSymbol(it) } + collectFields(binding).map { JdtSymbol(it) }
}

/** Neutral [Symbol] over any JDT [IBinding]. */
class JdtSymbol(private val binding: IBinding, private val decl: DomNode? = null) : Symbol {
    override val name: String get() = binding.name
    override val kind: SymbolKind get() = symbolKindOf(binding)
    override val type: TypeRef? get() = when (binding) {
        is IMethodBinding -> binding.returnType?.let { JdtTypeRef(it) }
        is IVariableBinding -> binding.type?.let { JdtTypeRef(it) }
        else -> null
    }
    override val owner: Symbol? = null
    override val modifiers: Set<Modifier> get() = modifiersOf(binding.modifiers)
    override val origin: SymbolOrigin get() = SymbolOrigin(fromSource = !binding.isSynthetic && decl != null, file = null)
    override fun declaration(): DomNode? = decl
    override fun documentation(): String? = null
}

class JdtScope(private val symbols: List<Symbol>) : Scope {
    override val enclosing: Scope? = null
    override fun symbols(filter: SymbolFilter): List<Symbol> = symbols.filter { matches(it, filter) }
    override fun resolve(name: String): ResolveResult {
        val hits = symbols.filter { it.name == name }
        return when (hits.size) {
            0 -> ResolveResult.Unresolved
            1 -> ResolveResult.Resolved(hits[0])
            else -> ResolveResult.Ambiguous(hits)
        }
    }
    private fun matches(s: Symbol, f: SymbolFilter): Boolean {
        if (f.kinds != null && s.kind !in f.kinds!!) return false
        val static = Modifier.STATIC in s.modifiers
        if (f.staticOnly && !static) return false
        if (f.instanceOnly && static) return false
        return true
    }
}

fun modifiersOf(m: Int): Set<Modifier> = buildSet {
    if (JdtModifier.isPublic(m)) add(Modifier.PUBLIC)
    if (JdtModifier.isProtected(m)) add(Modifier.PROTECTED)
    if (JdtModifier.isPrivate(m)) add(Modifier.PRIVATE)
    if (JdtModifier.isStatic(m)) add(Modifier.STATIC)
    if (JdtModifier.isFinal(m)) add(Modifier.FINAL)
    if (JdtModifier.isAbstract(m)) add(Modifier.ABSTRACT)
    if (JdtModifier.isDefault(m)) add(Modifier.DEFAULT)
    if (JdtModifier.isSynchronized(m)) add(Modifier.SYNCHRONIZED)
}

fun symbolKindOf(binding: IBinding): SymbolKind = when (binding) {
    is IMethodBinding -> if (binding.isConstructor) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD
    is IVariableBinding -> when {
        binding.isEnumConstant -> SymbolKind.ENUM_CONSTANT
        binding.isField -> SymbolKind.FIELD
        binding.isParameter -> SymbolKind.PARAMETER
        else -> SymbolKind.LOCAL_VARIABLE
    }
    is ITypeBinding -> when {
        binding.isAnnotation -> SymbolKind.ANNOTATION_TYPE
        binding.isEnum -> SymbolKind.ENUM
        binding.isInterface -> SymbolKind.INTERFACE
        binding.isRecord -> SymbolKind.RECORD
        binding.isTypeVariable -> SymbolKind.TYPE_PARAMETER
        else -> SymbolKind.CLASS
    }
    else -> SymbolKind.LOCAL_VARIABLE
}
