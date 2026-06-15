package dev.ide.lang.jdt.synthetic

import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticField
import dev.ide.lang.synthetic.SyntheticMethod
import dev.ide.lang.synthetic.SyntheticModifier
import dev.ide.lang.synthetic.SyntheticTypeKind

/**
 * Renders a structured [SyntheticClass] as **compilable Java source**, which the host drops into the JDT
 * name-environment overlay (FQCN → source). Because the synthetic type then resolves through the same
 * resolver as real source, completion, analysis, and go-to-definition all treat it uniformly — the
 * structured contract a provider writes against, with full integration for free.
 *
 * Every field is initialized (so a `static final int` like `R.layout.main` compiles), and concrete method
 * bodies return a type-appropriate default, so the emitted unit always compiles cleanly.
 */
object SyntheticJavaSource {

    fun emit(c: SyntheticClass): String {
        val pkg = c.fqName.substringBeforeLast('.', "")
        val sb = StringBuilder()
        if (pkg.isNotEmpty()) sb.append("package ").append(pkg).append(";\n\n")
        emitType(sb, c, 0)
        return sb.toString()
    }

    private fun emitType(sb: StringBuilder, c: SyntheticClass, indent: Int) {
        val pad = "  ".repeat(indent)
        c.doc?.let { sb.append(pad).append("/** ").append(escapeDoc(it)).append(" */\n") }
        sb.append(pad).append(modifiers(c.modifiers)).append(keyword(c.kind)).append(' ').append(simpleName(c.fqName))
        if (c.kind == SyntheticTypeKind.CLASS && c.superClass != null) sb.append(" extends ").append(c.superClass)
        if (c.interfaces.isNotEmpty()) {
            sb.append(if (c.kind == SyntheticTypeKind.INTERFACE) " extends " else " implements ").append(c.interfaces.joinToString(", "))
        }
        sb.append(" {\n")
        val inner = "  ".repeat(indent + 1)
        for (f in c.fields) emitField(sb, f, inner)
        for (m in c.methods) emitMethod(sb, m, inner, c.kind)
        for (n in c.nestedClasses) emitType(sb, n, indent + 1)
        sb.append(pad).append("}\n")
    }

    private fun emitField(sb: StringBuilder, f: SyntheticField, pad: String) {
        f.doc?.let { sb.append(pad).append("/** ").append(escapeDoc(it)).append(" */\n") }
        sb.append(pad).append(modifiers(f.modifiers)).append(f.type).append(' ').append(f.name)
            .append(" = ").append(f.constant ?: defaultValue(f.type)).append(";\n")
    }

    private fun emitMethod(sb: StringBuilder, m: SyntheticMethod, pad: String, owner: SyntheticTypeKind) {
        m.doc?.let { sb.append(pad).append("/** ").append(escapeDoc(it)).append(" */\n") }
        sb.append(pad).append(modifiers(m.modifiers)).append(m.returnType).append(' ').append(m.name).append('(')
            .append(m.parameters.joinToString(", ") { "${it.type} ${it.name}" }).append(')')
        val abstract = owner == SyntheticTypeKind.INTERFACE || owner == SyntheticTypeKind.ANNOTATION ||
            SyntheticModifier.ABSTRACT in m.modifiers
        if (abstract) {
            sb.append(";\n")
        } else {
            sb.append(" {")
            if (m.returnType != "void") sb.append(" return ").append(defaultValue(m.returnType)).append(';')
            sb.append(" }\n")
        }
    }

    private fun keyword(kind: SyntheticTypeKind): String = when (kind) {
        SyntheticTypeKind.CLASS -> "class"
        SyntheticTypeKind.INTERFACE -> "interface"
        SyntheticTypeKind.ENUM -> "enum"
        SyntheticTypeKind.ANNOTATION -> "@interface"
    }

    /** Ordered, space-terminated modifier string (`public static final `). */
    private fun modifiers(mods: Set<SyntheticModifier>): String =
        ORDER.filter { it in mods }.joinToString("") { it.name.lowercase() + " " }

    /** A default initializer so finals compile; non-primitives are `null`. */
    private fun defaultValue(type: String): String = when (type) {
        "boolean" -> "false"
        "byte", "short", "int" -> "0"
        "long" -> "0L"
        "float" -> "0f"
        "double" -> "0d"
        "char" -> "(char) 0"
        else -> "null"
    }

    private fun simpleName(fqName: String): String = fqName.substringAfterLast('.')

    private fun escapeDoc(doc: String): String = doc.replace("*/", "* /").replace('\n', ' ')

    private val ORDER = listOf(
        SyntheticModifier.PUBLIC, SyntheticModifier.PROTECTED, SyntheticModifier.PRIVATE,
        SyntheticModifier.STATIC, SyntheticModifier.FINAL, SyntheticModifier.ABSTRACT,
    )
}
