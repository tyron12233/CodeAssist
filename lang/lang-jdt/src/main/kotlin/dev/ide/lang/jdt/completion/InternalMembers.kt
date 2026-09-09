package dev.ide.lang.jdt.completion

import dev.ide.lang.completion.CompletionItemKind
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding

/**
 * Member enumeration + visibility rules over ecj's *internal* bindings (the engine resolves against
 * [dev.ide.lang.jdt.env.JdtNameEnvironment]). Mirrors the access/static logic of the DOM path: a
 * static qualifier shows only static members; inaccessible members (private/protected/package from
 * another class, incl. private nested types) are hidden.
 */
internal object InternalMembers {

    fun methods(type: ReferenceBinding): List<MethodBinding> {
        val out = LinkedHashMap<String, MethodBinding>()
        val seen = HashSet<String>()
        fun visit(t: ReferenceBinding?) {
            if (t == null || !seen.add(name(t))) return
            for (m in t.methods()) {
                if (m.isConstructor || m.isSynthetic) continue
                val key = String(m.selector) + "(" + m.parameters.joinToString(",") { name(it) } + ")"
                out.putIfAbsent(key, m)
            }
            visit(t.superclass())
            t.superInterfaces()?.forEach { visit(it) }
        }
        visit(type)
        return out.values.toList()
    }

    fun fields(type: ReferenceBinding): List<FieldBinding> {
        val out = LinkedHashMap<String, FieldBinding>()
        val seen = HashSet<String>()
        fun visit(t: ReferenceBinding?) {
            if (t == null || !seen.add(name(t))) return
            for (f in t.fields()) {
                if (f.isSynthetic) continue
                out.putIfAbsent(String(f.name), f)
            }
            visit(t.superclass())
            t.superInterfaces()?.forEach { visit(it) }
        }
        visit(type)
        return out.values.toList()
    }

    fun memberTypes(type: ReferenceBinding): List<ReferenceBinding> {
        val out = LinkedHashMap<String, ReferenceBinding>()
        var t: ReferenceBinding? = type
        val seen = HashSet<String>()
        while (t != null && seen.add(name(t))) {
            t.memberTypes()?.forEach { out.putIfAbsent(String(it.sourceName()), it) }
            t = t.superclass()
        }
        return out.values.toList()
    }

    fun accessibleMethod(m: MethodBinding, from: ReferenceBinding?, samePackage: Boolean): Boolean =
        accessible(m.isPublic, m.isPrivate, m.isProtected, m.declaringClass, from, samePackage)

    fun accessibleField(f: FieldBinding, from: ReferenceBinding?, samePackage: Boolean): Boolean =
        accessible(f.isPublic, f.isPrivate, f.isProtected, f.declaringClass, from, samePackage)

    fun accessibleType(t: ReferenceBinding, from: ReferenceBinding?, samePackage: Boolean): Boolean =
        accessible(t.isPublic, t.isPrivate, t.isProtected, t.enclosingType(), from, samePackage)

    private fun accessible(
        isPublic: Boolean, isPrivate: Boolean, isProtected: Boolean,
        declaring: ReferenceBinding?, from: ReferenceBinding?, samePackage: Boolean,
    ): Boolean {
        if (from != null && declaring != null && name(declaring) == name(from)) return true // same class
        return when {
            isPublic -> true
            isPrivate -> false
            isProtected -> samePackage || (from != null && declaring != null && runCatching { from.isCompatibleWith(declaring) }.getOrDefault(false))
            else -> samePackage
        }
    }

    fun samePackage(a: TypeBinding?, b: TypeBinding?): Boolean {
        if (a == null || b == null) return false
        return String(a.qualifiedPackageName()) == String(b.qualifiedPackageName())
    }

    fun isObjectMember(declaring: ReferenceBinding?): Boolean =
        declaring != null && name(declaring) == "java.lang.Object"

    fun simpleName(t: TypeBinding?): String = if (t == null) "?" else String(t.sourceName())

    /**
     * Display name carrying type arguments and array brackets, from the (already type-substituted) binding —
     * so a member of a parameterized receiver shows its inferred generics: `List<Integer>.stream()` reads
     * `Stream<Integer>`. ecj substitutes the receiver's type arguments into the methods/fields it hands back,
     * so no inference is done here; we render the short (unqualified, generics-bearing) name instead of the
     * bare [simpleName], and drop the bounded-wildcard noise (`? super E`/`? extends E` → `E`) for brevity —
     * `map`'s parameter reads `Function<Integer,R>` rather than `Function<? super Integer,? extends R>`.
     */
    fun display(t: TypeBinding?): String =
        if (t == null) "?" else String(t.shortReadableName()).replace("? super ", "").replace("? extends ", "")

    fun packageName(t: TypeBinding?): String? = t?.let { String(it.qualifiedPackageName()).ifEmpty { null } }

    fun name(t: TypeBinding): String = String(t.readableName())

    fun kindOfType(t: ReferenceBinding): CompletionItemKind = when {
        t.isAnnotationType -> CompletionItemKind.ANNOTATION_TYPE
        t.isEnum -> CompletionItemKind.ENUM
        t.isInterface -> CompletionItemKind.INTERFACE
        else -> CompletionItemKind.CLASS
    }
}
