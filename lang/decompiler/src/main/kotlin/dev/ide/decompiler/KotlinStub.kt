package dev.ide.decompiler

import dev.ide.lang.kotlin.symbols.KotlinMetadata
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind

/**
 * Renders a top-level Kotlin STUB from a decoded `@kotlin.Metadata` shape — the declarations (class/interface/
 * object header, members with signatures, visibility/modality/suspend/inline) with bodies elided as
 * `{ /* compiled code */ }`, IntelliJ's "decompiled Kotlin" style. There is no bytecode→Kotlin-source
 * decompiler, so this reconstructs from the metadata the shared reader already decodes; it's declaration-only
 * by nature. A file facade (`classFqn == null`) renders its top-level functions/properties.
 */
object KotlinStub {

    /** Render a top-level FACADE stub from [members] already gathered across a multi-file facade's parts
     *  (`CollectionsKt` → `listOf`, `map`, …) — package + the top-level declarations, bodies elided. */
    fun renderFacade(fqn: String, members: List<KotlinSymbol>): String {
        val pkg = fqn.substringBeforeLast('.', "")
        val sb = StringBuilder()
        sb.appendLine("// Decompiled from bytecode (Kotlin @Metadata) — declarations only, read-only.")
        if (pkg.isNotEmpty()) {
            sb.appendLine("package $pkg")
            sb.appendLine()
        }
        members.filter { !isSynthetic(it) }.forEach { sb.appendLine(memberLine(it, hasBody = true)) }
        return sb.toString()
    }

    fun render(fqn: String, d: KotlinMetadata.Decoded): String {
        val pkg = (d.classFqn ?: fqn).substringBeforeLast('.', "")
        val sb = StringBuilder()
        sb.appendLine("// Decompiled from bytecode (Kotlin @Metadata) — declarations only, read-only.")
        if (pkg.isNotEmpty()) {
            sb.appendLine("package $pkg")
            sb.appendLine()
        }
        if (d.classFqn == null) {
            (d.topLevel + d.extensions).filter { !isSynthetic(it) }.forEach { sb.appendLine(memberLine(it, hasBody = true)) }
        } else {
            renderClass(sb, d)
        }
        return sb.toString()
    }

    private fun renderClass(sb: StringBuilder, d: KotlinMetadata.Decoded) {
        val simple = d.classFqn!!.substringAfterLast('.')
        val keyword = when {
            d.isObject -> "object"
            d.isInterface -> "interface"
            else -> "class"
        }
        val prefix = buildString {
            if (d.sealedSubclasses.isNotEmpty()) append("sealed ")
            else if (d.isAbstractClass && !d.isInterface) append("abstract ")
        }
        val tp = if (d.typeParameters.isEmpty()) "" else "<${d.typeParameters.joinToString(", ")}>"
        val supers = d.supertypeFqns.filter { it != "kotlin.Any" }.map { it.substringAfterLast('.') }
        val superClause = if (supers.isEmpty()) "" else " : ${supers.joinToString(", ")}"
        sb.append(prefix).append(keyword).append(' ').append(simple).append(tp).append(superClause)

        val members = d.ownMembers.filter { !isSynthetic(it) }
        if (members.isEmpty() && d.companionObjectName == null) {
            sb.appendLine()
            return
        }
        sb.appendLine(" {")
        d.companionObjectName?.let {
            sb.appendLine("    companion object${if (it != "Companion") " $it" else ""} { /* compiled code */ }")
        }
        members.forEach { sb.append("    ").appendLine(memberLine(it, hasBody = !d.isInterface && Modifier.ABSTRACT !in it.modifiers)) }
        sb.appendLine("}")
    }

    private fun memberLine(s: KotlinSymbol, hasBody: Boolean): String {
        val mods = memberModifiers(s)
        val recv = s.receiverTypeFqn?.substringAfterLast('.')?.let { "$it." } ?: ""
        return when (s.kind) {
            SymbolKind.CONSTRUCTOR -> "${mods}constructor${s.signature ?: "()"}"
            SymbolKind.METHOD -> "${mods}fun $recv${s.name}${s.signature ?: "()"}" + if (hasBody) " { /* compiled code */ }" else ""
            SymbolKind.FIELD -> "${mods}val $recv${s.name}${s.signature ?: ""}"
            SymbolKind.ENUM_CONSTANT -> "${s.name},"
            else -> "${mods}${s.name}${s.signature ?: ""}"
        }
    }

    private fun memberModifiers(s: KotlinSymbol): String = buildString {
        when {
            Modifier.PRIVATE in s.modifiers -> append("private ")
            Modifier.PROTECTED in s.modifiers -> append("protected ")
            s.isInternal -> append("internal ")
        }
        if (Modifier.ABSTRACT in s.modifiers) append("abstract ")
        if (s.isInline) append("inline ")
        if (s.isInfix) append("infix ")
        if (s.isSuspend) append("suspend ")
    }

    /** Skip compiler-synthetic / mangled members (`$`, `<clinit>`, etc.) from the stub. */
    private fun isSynthetic(s: KotlinSymbol): Boolean =
        s.name.isEmpty() || '$' in s.name || s.name.startsWith("<")
}
