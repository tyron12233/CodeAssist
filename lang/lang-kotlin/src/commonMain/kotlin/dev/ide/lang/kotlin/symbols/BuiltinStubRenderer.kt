package dev.ide.lang.kotlin.symbols

import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind

/**
 * Renders a declaration-only Kotlin STUB from a decoded built-in [TypeShape] (`kotlin.collections.List`,
 * `kotlin.Int`, `kotlin.String`, …) — the read-only "go to declaration" text for a type that has no `.class`
 * to decompile (Kotlin built-ins ship as `.kotlin_builtins` metadata, decoded by [BuiltinsReader]). Mirrors
 * IntelliJ's decompiled-built-in view: the class/interface/object header (type params + variance, supertypes)
 * and members with their already-rendered signatures, bodies elided as `{ /* compiled code */ }`.
 */
object BuiltinStubRenderer {

    fun render(fqn: String, s: TypeShape): String {
        val pkg = fqn.substringBeforeLast('.', "")
        val simple = fqn.substringAfterLast('.')
        val sb = StringBuilder()
        sb.appendLine("// Kotlin built-in (reconstructed from .kotlin_builtins) — declarations only, read-only.")
        if (pkg.isNotEmpty()) {
            sb.appendLine("package $pkg")
            sb.appendLine()
        }
        val keyword = when {
            s.isObject -> "object"
            s.isInterface -> "interface"
            else -> "class"
        }
        val prefix = when {
            s.sealedSubclasses.isNotEmpty() -> "sealed "
            s.isAbstract && !s.isInterface -> "abstract "
            else -> ""
        }
        val tps = s.typeParameters.mapIndexed { i, name ->
            val v = s.typeParameterVariances.getOrNull(i)?.takeIf { it.isNotEmpty() }
            (v?.let { "$it " } ?: "") + name
        }
        val tp = if (tps.isEmpty()) "" else "<${tps.joinToString(", ")}>"
        val supers = s.supertypes.mapNotNull { (it as? KotlinType)?.qualifiedName }
            .filter { it != "kotlin.Any" }.map { it.substringAfterLast('.') }
        val superClause = if (supers.isEmpty()) "" else " : ${supers.joinToString(", ")}"
        sb.append(prefix).append(keyword).append(' ').append(simple).append(tp).append(superClause)

        // STATIC members are the companion's, merged into the shape by BuiltinsReader — render them in a companion.
        val members = s.members.filter { renderable(it) && Modifier.STATIC !in it.modifiers }
        val statics = s.members.filter { renderable(it) && Modifier.STATIC in it.modifiers }
        if (members.isEmpty() && statics.isEmpty()) {
            sb.appendLine()
            return sb.toString()
        }
        sb.appendLine(" {")
        members.forEach { sb.append("    ").appendLine(memberLine(it, s.isInterface)) }
        if (statics.isNotEmpty()) {
            sb.appendLine("    companion object {")
            statics.forEach { sb.append("        ").appendLine(memberLine(it, iface = false)) }
            sb.appendLine("    }")
        }
        sb.appendLine("}")
        return sb.toString()
    }

    /** Skip compiler-synthetic / mangled members (`$`, `<init>`, empty). */
    private fun renderable(m: KotlinSymbol): Boolean =
        m.name.isNotEmpty() && '$' !in m.name && !m.name.startsWith("<")

    private fun memberLine(m: KotlinSymbol, iface: Boolean): String {
        val mods = buildString {
            when {
                Modifier.PRIVATE in m.modifiers -> append("private ")
                Modifier.PROTECTED in m.modifiers -> append("protected ")
                m.isInternal -> append("internal ")
            }
            val abstract = Modifier.ABSTRACT in m.modifiers
            if (abstract && !iface) append("abstract ")
            if (m.isInline) append("inline ")
            if (m.isInfix) append("infix ")
            if (m.isSuspend) append("suspend ")
        }
        val hasBody = !iface && Modifier.ABSTRACT !in m.modifiers
        return when (m.kind) {
            SymbolKind.METHOD -> "${mods}fun ${m.name}${m.signature ?: "()"}" + if (hasBody) " { /* compiled code */ }" else ""
            SymbolKind.FIELD -> "${mods}val ${m.name}${m.signature ?: ""}"
            SymbolKind.ENUM_CONSTANT -> "${m.name},"
            else -> "${mods}${m.name}${m.signature ?: ""}"
        }
    }
}
