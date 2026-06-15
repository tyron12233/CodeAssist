package dev.ide.lang.jdt.resolve

import dev.ide.lang.completion.CompletionItemKind
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.Modifier

/**
 * Binding-level helpers behind smart completion: member enumeration over the supertype graph, plus
 * the access (private/protected/package) and static-context rules. Driven by JDT's `IBinding`
 * modifiers, so they work even on partially-recovered bindings from broken code.
 */

/** Methods visible on [type] including inherited ones; overrides de-duplicated by erased signature. */
fun collectMethods(type: ITypeBinding): List<IMethodBinding> {
    val byKey = LinkedHashMap<String, IMethodBinding>()
    fun visit(t: ITypeBinding?, seen: MutableSet<String>) {
        if (t == null || !seen.add(t.key ?: t.qualifiedName)) return
        for (m in t.declaredMethods) {
            if (m.isConstructor || m.isSynthetic) continue
            val key = m.name + "(" + m.parameterTypes.joinToString(",") { it.erasure?.qualifiedName ?: it.qualifiedName } + ")"
            byKey.putIfAbsent(key, m)
        }
        visit(t.superclass, seen)
        for (i in t.interfaces) visit(i, seen)
    }
    visit(type, HashSet())
    return byKey.values.toList()
}

/** Fields visible on [type] including inherited ones; shadowed fields de-duplicated by name. */
fun collectFields(type: ITypeBinding): List<IVariableBinding> {
    val byName = LinkedHashMap<String, IVariableBinding>()
    fun visit(t: ITypeBinding?, seen: MutableSet<String>) {
        if (t == null || !seen.add(t.key ?: t.qualifiedName)) return
        for (f in t.declaredFields) {
            if (f.isSynthetic || f.name == "this") continue
            byName.putIfAbsent(f.name, f)
        }
        visit(t.superclass, seen)
        for (i in t.interfaces) visit(i, seen)
    }
    visit(type, HashSet())
    return byName.values.toList()
}

fun collectNestedTypes(type: ITypeBinding): List<ITypeBinding> {
    val out = LinkedHashMap<String, ITypeBinding>()
    var t: ITypeBinding? = type
    val seen = HashSet<String>()
    while (t != null && seen.add(t.key ?: t.qualifiedName)) {
        for (nested in t.declaredTypes) out.putIfAbsent(nested.name, nested)
        t = t.superclass
    }
    return out.values.toList()
}

/** Same-class access sees everything; otherwise honor public/protected/package/private. */
fun isAccessible(modifiers: Int, declaring: ITypeBinding?, from: ITypeBinding?, samePackage: Boolean): Boolean {
    if (from != null && declaring != null && sameType(declaring, from)) return true
    return when {
        Modifier.isPublic(modifiers) -> true
        Modifier.isPrivate(modifiers) -> false
        Modifier.isProtected(modifiers) -> samePackage || (from != null && declaring != null && isSubtype(from, declaring))
        else -> samePackage // package-private
    }
}

fun samePackage(a: ITypeBinding?, b: ITypeBinding?): Boolean =
    a?.`package`?.name != null && a.`package`.name == b?.`package`?.name

private fun sameType(a: ITypeBinding, b: ITypeBinding): Boolean =
    (a.key != null && a.key == b.key) || a.erasure?.qualifiedName == b.erasure?.qualifiedName

private fun isSubtype(sub: ITypeBinding, sup: ITypeBinding): Boolean {
    val target = sup.erasure?.qualifiedName ?: sup.qualifiedName
    val seen = HashSet<String>()
    fun walk(t: ITypeBinding?): Boolean {
        if (t == null || !seen.add(t.key ?: t.qualifiedName)) return false
        if ((t.erasure?.qualifiedName ?: t.qualifiedName) == target) return true
        if (walk(t.superclass)) return true
        return t.interfaces.any { walk(it) }
    }
    return walk(sub)
}

fun itemKind(binding: org.eclipse.jdt.core.dom.IBinding): CompletionItemKind = when (binding) {
    is IMethodBinding -> if (binding.isConstructor) CompletionItemKind.CONSTRUCTOR else CompletionItemKind.METHOD
    is IVariableBinding -> when {
        binding.isEnumConstant -> CompletionItemKind.ENUM_CONSTANT
        binding.isField -> CompletionItemKind.FIELD
        binding.isParameter -> CompletionItemKind.PARAMETER
        else -> CompletionItemKind.VARIABLE
    }
    is ITypeBinding -> when {
        binding.isAnnotation -> CompletionItemKind.ANNOTATION_TYPE
        binding.isEnum -> CompletionItemKind.ENUM
        binding.isInterface -> CompletionItemKind.INTERFACE
        binding.isRecord -> CompletionItemKind.RECORD
        else -> CompletionItemKind.CLASS
    }
    else -> CompletionItemKind.VARIABLE
}

fun methodSignature(m: IMethodBinding): String {
    val params = m.parameterTypes.joinToString(", ") { it.name }
    val ret = m.returnType?.name ?: "void"
    return "($params) → $ret"
}

fun typeSimpleName(t: ITypeBinding?): String = t?.name ?: "?"
