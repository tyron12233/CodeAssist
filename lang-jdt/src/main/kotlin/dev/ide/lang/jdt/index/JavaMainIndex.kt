package dev.ide.lang.jdt.index

import dev.ide.index.EntryPointExternalizer
import dev.ide.index.EntryPointIndex
import dev.ide.index.EntryPointValue
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import org.eclipse.jdt.core.dom.ArrayType
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.PrimitiveType
import org.eclipse.jdt.core.dom.SimpleType
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import org.eclipse.jdt.core.dom.Type
import org.eclipse.jdt.core.dom.TypeDeclaration

/**
 * Detects runnable `main` entry points in a `.java` file from a real (binding-free) JDT parse — no regex.
 * Recognizes the JVM-standard `public static void main(String[])` (in any top-level or nested type, including
 * an interface's static method), plus — as a convenience beyond the JVM launcher — an *instance* `void main()`
 * / `void main(String[])` on a concrete top-level class with a no-arg constructor (the runner constructs it
 * and calls the method; this mirrors Java's newer instance-`main` entry points). Public so the run service can
 * scan on demand while the index is still building.
 */
object JavaMainScan {

    /** Standalone scan of [text] (input-less callers, e.g. the run service's cold-start fallback). Inside an
     *  index, prefer [mainsOf] over the shared [CompilationUnit] so the file is parsed once. */
    fun scan(text: String): List<Pair<String, Boolean>> = mainsOf(JavaSourceIndexer.parseCu(text))

    /** Each hit: the class FQN to launch (a `.`-package with `$`-nested binary name) paired with whether it
     *  must be invoked on an instance (no static `main`). */
    fun mainsOf(cu: CompilationUnit?): List<Pair<String, Boolean>> {
        if (cu == null) return emptyList()
        val pkg = cu.`package`?.name?.fullyQualifiedName
        val out = LinkedHashSet<Pair<String, Boolean>>()
        for (t in cu.types()) if (t is TypeDeclaration) visit(t, pkg, null, topLevel = true, out)
        return out.toList()
    }

    private fun visit(
        td: TypeDeclaration, pkg: String?, enclosingBinary: String?, topLevel: Boolean,
        out: MutableSet<Pair<String, Boolean>>,
    ) {
        val binary = when {
            enclosingBinary != null -> "$enclosingBinary\$${td.name.identifier}"
            pkg.isNullOrEmpty() -> td.name.identifier
            else -> "$pkg.${td.name.identifier}"
        }
        val methods = td.methods
        var staticFound = false
        for (m in methods) {
            if (m.isConstructor || m.name.identifier != "main" || !isVoid(m.returnType2)) continue
            if (Modifier.isStatic(m.modifiers) && Modifier.isPublic(m.modifiers) && isStringArrayParams(m)) {
                out.add(binary to false); staticFound = true
            }
        }
        // Instance main: only a concrete top-level class we can construct with a no-arg constructor, and only
        // when there's no static main to prefer.
        if (topLevel && !staticFound && !td.isInterface && !Modifier.isAbstract(td.modifiers) && hasNoArgCtor(td)) {
            for (m in methods) {
                if (m.isConstructor || m.name.identifier != "main" || !isVoid(m.returnType2)) continue
                if (Modifier.isStatic(m.modifiers) || Modifier.isAbstract(m.modifiers) || Modifier.isPrivate(m.modifiers)) continue
                if (isNoParams(m) || isStringArrayParams(m)) { out.add(binary to true); break }
            }
        }
        for (bd in td.bodyDeclarations()) if (bd is TypeDeclaration) visit(bd, pkg, binary, topLevel = false, out)
    }

    private fun hasNoArgCtor(td: TypeDeclaration): Boolean {
        val ctors = td.methods.filter { it.isConstructor }
        return ctors.isEmpty() || ctors.any { (it.parameters() as List<*>).isEmpty() }
    }

    private fun isVoid(t: Type?): Boolean = t is PrimitiveType && t.primitiveTypeCode == PrimitiveType.VOID

    private fun isNoParams(m: MethodDeclaration): Boolean = (m.parameters() as List<*>).isEmpty()

    /** True when [m] takes exactly one `String[]` / `String...` parameter (its only valid `main` shape). */
    @Suppress("UNCHECKED_CAST")
    private fun isStringArrayParams(m: MethodDeclaration): Boolean {
        val params = m.parameters() as List<SingleVariableDeclaration>
        if (params.size != 1) return false
        val p = params[0]
        if (p.isVarargs) return isString(p.type) && arrayDepth(p.type) + p.extraDimensions == 0
        return isStringArrayOfDepthOne(p.type, p.extraDimensions)
    }

    private fun isStringArrayOfDepthOne(type: Type, extra: Int): Boolean {
        val base = if (type is ArrayType) type.elementType else type
        return isString(base) && arrayDepth(type) + extra == 1
    }

    private fun arrayDepth(type: Type): Int = if (type is ArrayType) type.dimensions else 0

    private fun isString(type: Type): Boolean {
        val name = when (type) {
            is SimpleType -> type.name.fullyQualifiedName
            is ArrayType -> (type.elementType as? SimpleType)?.name?.fullyQualifiedName
            else -> null
        }
        return name == "String" || name == "java.lang.String"
    }
}

/** `java.mains` — runnable entry points in project `.java` source, keyed by [EntryPointIndex.KEY]. In-memory,
 *  source-side, rebuilt incrementally on edit (like the other source indexes). */
object JavaMainIndex : IndexExtension<String, EntryPointValue> {
    override val id = IndexId("java.mains")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = EntryPointExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter =
        InputFilter { it.origin == IndexOrigin.SOURCE && it.unitName?.endsWith(".java") == true }

    override fun index(input: IndexInput): Map<String, Collection<EntryPointValue>> {
        val fileId = input.fileId
        if (fileId < 0) return emptyMap()
        // Reuse the CompilationUnit the other Java source indexes parsed for this file (parsed once per pass).
        val hits = JavaMainScan.mainsOf(JavaSourceIndexer.sharedCu(input))
        if (hits.isEmpty()) return emptyMap()
        return mapOf(EntryPointIndex.KEY to hits.map { (fqn, instance) -> EntryPointValue(fqn, fileId, instance) })
    }
}
