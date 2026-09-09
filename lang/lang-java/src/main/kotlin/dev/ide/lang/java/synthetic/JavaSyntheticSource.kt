package dev.ide.lang.java.synthetic

import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticField
import dev.ide.lang.synthetic.SyntheticMethod
import dev.ide.lang.synthetic.SyntheticModifier
import dev.ide.lang.synthetic.SyntheticTypeKind

/**
 * Renders a structured [SyntheticClass] (Android `R`/`BuildConfig`, ViewBinding, Kotlin light classes) to
 * compilable Java source, which the IntelliJ-PSI backend parses + serves through [JavaInjectedElementFinder]
 * so the facade resolves it like any real type. Mirrors `dev.ide.lang.jdt.synthetic.SyntheticJavaSource`
 * (kept as its own copy so `:lang-java` doesn't depend on `:lang-jdt`).
 */
internal object JavaSyntheticSource {

    fun emit(c: SyntheticClass): String {
        val pkg = c.fqName.substringBeforeLast('.', "")
        val sb = StringBuilder()
        if (pkg.isNotEmpty()) sb.append("package ").append(pkg).append(";\n\n")
        emitType(sb, c, 0)
        return sb.toString()
    }

    private fun emitType(sb: StringBuilder, c: SyntheticClass, indent: Int) {
        val pad = "  ".repeat(indent)
        sb.append(pad).append(mods(c.modifiers)).append(keyword(c.kind)).append(' ').append(simple(c.fqName))
        if (c.kind == SyntheticTypeKind.CLASS && c.superClass != null) sb.append(" extends ").append(c.superClass)
        if (c.interfaces.isNotEmpty()) {
            sb.append(if (c.kind == SyntheticTypeKind.INTERFACE) " extends " else " implements ")
                .append(c.interfaces.joinToString(", "))
        }
        sb.append(" {\n")
        val inner = "  ".repeat(indent + 1)
        for (f in c.fields) emitField(sb, f, inner)
        for (m in c.methods) emitMethod(sb, m, inner, c.kind, simple(c.fqName))
        for (n in c.nestedClasses) emitType(sb, n, indent + 1)
        sb.append(pad).append("}\n")
    }

    private fun emitField(sb: StringBuilder, f: SyntheticField, pad: String) {
        sb.append(pad).append(mods(f.modifiers)).append(f.type).append(' ').append(f.name)
            .append(" = ").append(f.constant ?: default(f.type)).append(";\n")
    }

    private fun emitMethod(sb: StringBuilder, m: SyntheticMethod, pad: String, owner: SyntheticTypeKind, ownerName: String) {
        sb.append(pad).append(mods(m.modifiers))
        if (m.isConstructor) sb.append(ownerName) else sb.append(m.returnType).append(' ').append(m.name)
        sb.append('(').append(m.parameters.joinToString(", ") { "${it.type} ${it.name}" }).append(')')
        val abstract = !m.isConstructor &&
            (owner == SyntheticTypeKind.INTERFACE || owner == SyntheticTypeKind.ANNOTATION || SyntheticModifier.ABSTRACT in m.modifiers)
        if (abstract) sb.append(";\n")
        else {
            sb.append(" {")
            if (!m.isConstructor && m.returnType != "void") sb.append(" return ").append(default(m.returnType)).append(';')
            sb.append(" }\n")
        }
    }

    private fun keyword(kind: SyntheticTypeKind): String = when (kind) {
        SyntheticTypeKind.CLASS -> "class"
        SyntheticTypeKind.INTERFACE -> "interface"
        SyntheticTypeKind.ENUM -> "enum"
        SyntheticTypeKind.ANNOTATION -> "@interface"
    }

    private fun mods(m: Set<SyntheticModifier>): String {
        val order = listOf(
            SyntheticModifier.PUBLIC, SyntheticModifier.PROTECTED, SyntheticModifier.PRIVATE,
            SyntheticModifier.ABSTRACT, SyntheticModifier.STATIC, SyntheticModifier.FINAL,
        )
        return order.filter { it in m }.joinToString("") { it.name.lowercase() + " " }
    }

    private fun simple(fqn: String): String = fqn.substringAfterLast('.')

    private fun default(type: String): String = when (type) {
        "boolean" -> "false"
        "byte", "short", "int", "long", "char" -> "0"
        "float" -> "0f"
        "double" -> "0d"
        "void" -> ""
        else -> "null"
    }
}
