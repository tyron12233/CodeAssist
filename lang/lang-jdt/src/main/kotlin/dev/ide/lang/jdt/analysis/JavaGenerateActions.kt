package dev.ide.lang.jdt.analysis

import dev.ide.analysis.ActionProvider
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.EditorActionContext
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.LanguageId
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.jdt.dom.JdtDomNode
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.Block
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment

/**
 * The "generate member" intentions for Java: a constructor, `equals`/`hashCode`, `toString`, and accessors,
 * each written from the enclosing class's own instance fields.
 *
 * All four are offered with the caret in a class body but outside any method, which is where a member is
 * about to be written. Reading the fields needs real declarations (a type, a name, and the modifiers that
 * say whether it is an instance field), so these walk the JDT AST under [JdtDomNode.node] rather than
 * pattern-matching declaration text. The generated code is plain Java 8, since the on-device toolchain
 * targets a language level where `var`, records and `Objects.requireNonNull` overloads cannot be assumed.
 */
private val JAVA = LanguageId("java")

/** One instance field: what every generator needs to know about it. */
private class Field(val name: String, val type: String, val isArray: Boolean, val isPrimitive: Boolean)

/** `public <Class>(<type> <name>, ...)` assigning every instance field, inserted at the end of the body. */
class GenerateConstructorActionProvider : ActionProvider {
    override val languages = setOf(JAVA)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val site = generationSite(ctx) ?: return emptyList()
        if (site.fields.isEmpty()) return emptyList()
        // A constructor taking exactly these fields already exists; offering a duplicate would not compile.
        if (site.type.methods.any { it.isConstructor && it.parameters().size == site.fields.size }) {
            return emptyList()
        }
        return listOf(
            generated("Generate constructor", site) { member, body ->
                val params = site.fields.joinToString(", ") { "${it.type} ${it.name}" }
                buildString {
                    append("${member}public ${site.name}($params) {\n")
                    for (f in site.fields) append("${body}this.${f.name} = ${f.name};\n")
                    append("$member}\n")
                }
            },
        )
    }
}

/** `equals` and `hashCode` over every instance field, generated as a pair so they cannot disagree. */
class GenerateEqualsHashCodeActionProvider : ActionProvider {
    override val languages = setOf(JAVA)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val site = generationSite(ctx) ?: return emptyList()
        if (site.fields.isEmpty()) return emptyList()
        if (site.type.methods.any { it.name.identifier == "equals" || it.name.identifier == "hashCode" }) {
            return emptyList()
        }
        return listOf(
            generated("Generate 'equals()' and 'hashCode()'", site) { member, body ->
                buildString {
                    append("${member}@Override\n")
                    append("${member}public boolean equals(Object o) {\n")
                    append("${body}if (this == o) return true;\n")
                    append("${body}if (o == null || getClass() != o.getClass()) return false;\n")
                    append("$body${site.name} that = (${site.name}) o;\n")
                    for (f in site.fields) append("${body}if (${comparison(f)}) return false;\n")
                    append("${body}return true;\n")
                    append("$member}\n")
                    append('\n')
                    append("${member}@Override\n")
                    append("${member}public int hashCode() {\n")
                    append("${body}int result = 1;\n")
                    for (f in site.fields) append("${body}result = 31 * result + ${hashOf(f)};\n")
                    append("${body}return result;\n")
                    append("$member}\n")
                }
            },
        )
    }

    /** The "not equal" test for one field: `==` for primitives, `Arrays.equals` for arrays, else null-safe. */
    private fun comparison(f: Field): String = when {
        f.isArray -> "!java.util.Arrays.equals(${f.name}, that.${f.name})"
        f.isPrimitive -> "${f.name} != that.${f.name}"
        else -> "${f.name} != null ? !${f.name}.equals(that.${f.name}) : that.${f.name} != null"
    }

    private fun hashOf(f: Field): String = when {
        f.isArray -> "java.util.Arrays.hashCode(${f.name})"
        f.type == "boolean" -> "(${f.name} ? 1 : 0)"
        f.type == "long" -> "(int) (${f.name} ^ (${f.name} >>> 32))"
        f.type == "float" -> "Float.floatToIntBits(${f.name})"
        f.type == "double" -> "(int) (Double.doubleToLongBits(${f.name}) ^ (Double.doubleToLongBits(${f.name}) >>> 32))"
        f.isPrimitive -> "(int) ${f.name}"
        else -> "(${f.name} != null ? ${f.name}.hashCode() : 0)"
    }
}

/** `toString` listing every instance field, in the `Class{field=value}` shape. */
class GenerateToStringActionProvider : ActionProvider {
    override val languages = setOf(JAVA)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val site = generationSite(ctx) ?: return emptyList()
        if (site.fields.isEmpty()) return emptyList()
        if (site.type.methods.any { it.name.identifier == "toString" }) return emptyList()
        return listOf(
            generated("Generate 'toString()'", site) { member, body ->
                val parts = site.fields.mapIndexed { i, f ->
                    val lead = if (i == 0) "" else ", "
                    val value = if (f.isArray) "java.util.Arrays.toString(${f.name})" else f.name
                    "\"$lead${f.name}=\" + $value"
                }
                buildString {
                    append("${member}@Override\n")
                    append("${member}public String toString() {\n")
                    append("${body}return \"${site.name}{\" + ${parts.joinToString(" + ")} + \"}\";\n")
                    append("$member}\n")
                }
            },
        )
    }
}

/** A getter and, for a non-final field, a setter for each instance field that has neither. */
class GenerateAccessorsActionProvider : ActionProvider {
    override val languages = setOf(JAVA)

    override fun actions(ctx: EditorActionContext): List<QuickFix> {
        val site = generationSite(ctx) ?: return emptyList()
        val existing = site.type.methods.mapTo(HashSet()) { it.name.identifier }
        val missing = site.fields.filter { accessorName("get", it) !in existing && accessorName("is", it) !in existing }
        if (missing.isEmpty()) return emptyList()
        return listOf(
            generated("Generate getters and setters", site) { member, body ->
                buildString {
                    for (f in missing) {
                        val suffix = f.name.replaceFirstChar { it.uppercaseChar() }
                        val prefix = if (f.type == "boolean") "is" else "get"
                        append("${member}public ${f.type} $prefix$suffix() {\n")
                        append("${body}return ${f.name};\n")
                        append("$member}\n")
                        append('\n')
                        if (!site.finalFields.contains(f.name)) {
                            append("${member}public void set$suffix(${f.type} ${f.name}) {\n")
                            append("${body}this.${f.name} = ${f.name};\n")
                            append("$member}\n")
                            append('\n')
                        }
                    }
                }.trimEnd('\n') + "\n"
            },
        )
    }

    private fun accessorName(prefix: String, f: Field) =
        prefix + f.name.replaceFirstChar { it.uppercaseChar() }
}

// ---------------------------------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------------------------------

/** Where a member is about to be generated: the class, its name, its instance fields, and the insert point. */
private class Site(
    val type: TypeDeclaration,
    val name: String,
    val fields: List<Field>,
    val finalFields: Set<String>,
    val insertAt: Int,
    val indent: String,
)

/**
 * The class to generate into, or null when the caret is not at a member position: outside any class, or
 * inside a method body (where the user is writing statements, not members).
 */
private fun generationSite(ctx: EditorActionContext): Site? {
    var n: ASTNode? = (ctx.node as? JdtDomNode)?.node ?: return null
    var type: TypeDeclaration? = null
    while (n != null) {
        if (n is Block && n.parent is MethodDeclaration) return null
        if (n is TypeDeclaration) { type = n; break }
        if (n is AbstractTypeDeclaration) return null // an enum or annotation: different member shapes
        n = n.parent
    }
    val decl = type ?: return null
    val name = decl.name.identifier

    val fields = ArrayList<Field>()
    val finals = HashSet<String>()
    for (field in decl.fields) {
        @Suppress("UNCHECKED_CAST")
        val modifiers = field.modifiers() as List<Any>
        if (modifiers.any { it is Modifier && it.isStatic }) continue
        val isFinal = modifiers.any { it is Modifier && it.isFinal }
        val typeText = field.type.toString()
        val isArray = field.type.isArrayType
        val isPrimitive = field.type.isPrimitiveType
        @Suppress("UNCHECKED_CAST")
        for (fragment in field.fragments() as List<VariableDeclarationFragment>) {
            val fieldName = fragment.name.identifier
            // `int[] a` and `int a[]` both declare an array; the extra dimensions live on the fragment.
            val extraDims = fragment.extraDimensions
            fields.add(
                Field(
                    name = fieldName,
                    type = typeText + "[]".repeat(extraDims),
                    isArray = isArray || extraDims > 0,
                    isPrimitive = isPrimitive && extraDims == 0,
                ),
            )
            if (isFinal) finals.add(fieldName)
        }
    }

    // Insert just before the closing brace of the class body.
    val end = decl.startPosition + decl.length
    val text = ctx.target.parsed.text()
    var close = (end - 1).coerceIn(0, text.length - 1)
    while (close > 0 && text[close] != '}') close--
    val indent = lineIndent(text, decl.startPosition)
    return Site(decl, name, fields, finals, close, indent)
}

/**
 * A [QuickFix] inserting a generated member at [Site.insertAt], with a blank line before it when the class
 * body is not empty.
 *
 * [render] is handed the two indents it needs: `member` for a member's own lines and `body` for the
 * statements inside it, both derived from the class's indentation so a nested class generates correctly.
 */
private fun generated(
    title: String,
    site: Site,
    render: (member: String, body: String) -> String,
): QuickFix = object : QuickFix {
    override val title = title
    override val kind = CodeActionKind.REFACTOR
    override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
        val text = ctx.target.parsed.text()
        val at = site.insertAt.coerceIn(0, text.length)
        val needsBlank = precedingIsMember(text, at)
        val member = site.indent + INDENT
        val generated = render(member, member + INDENT)
        return WorkspaceEdit.of(
            ctx.target.file,
            DocumentEdit(at, 0, (if (needsBlank) "\n" else "") + generated + site.indent),
        )
    }
}

/** One indentation level in generated code. */
private const val INDENT = "    "

/** True when the text before [at] holds a member, i.e. the class body is not empty. */
private fun precedingIsMember(text: CharSequence, at: Int): Boolean {
    var i = at - 1
    while (i >= 0 && text[i].isWhitespace()) i--
    return i >= 0 && text[i] != '{'
}
