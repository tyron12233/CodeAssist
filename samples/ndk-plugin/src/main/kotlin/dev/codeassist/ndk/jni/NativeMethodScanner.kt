package dev.codeassist.ndk.jni

/**
 * Finds the `native` methods of a Java file and the `external` functions of a Kotlin file, from the text.
 *
 * Text rather than a syntax tree because the trees the host hands a plugin do not carry modifiers, and the
 * question is narrow: which declarations say `native`/`external`, in which class, with which parameter
 * types. Comments and string literals are blanked first, keeping every offset, so `"native"` in a string or
 * a commented-out declaration is not one.
 *
 * Types are resolved the way a reader would without a classpath: primitives and arrays exactly, then the
 * file's imports, then the types every file sees (`java.lang`, Kotlin's built-ins), then the file's own
 * package. That decides the C type of each parameter (`jstring`, `jintArray`, `jobject`) exactly; a class
 * that resolves wrongly can only affect the long, overloaded symbol name.
 */
object NativeMethodScanner {

    fun scanJava(text: String): List<NativeMethod> = JavaScan(SourceText.clean(text, kotlin = false)).run()

    /** [fileName] is the `.kt` file's name, which names the class top-level functions compile into. */
    fun scanKotlin(text: String, fileName: String): List<NativeMethod> =
        KotlinScan(text, SourceText.clean(text, kotlin = true), fileName).run()
}

/** A token of cleaned source: an identifier, or one punctuation character. */
internal class Tok(val text: String, val start: Int) {
    override fun toString() = text
}

internal object SourceText {

    /** [text] with comments and string/char literals replaced by spaces; newlines kept, so offsets hold. */
    fun clean(text: String, kotlin: Boolean): String {
        val out = StringBuilder(text)
        var i = 0
        fun blank(from: Int, to: Int) {
            for (k in from until minOf(to, out.length)) if (out[k] != '\n') out[k] = ' '
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    blank(i, end); i = end
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    // Kotlin block comments nest; Java's do not.
                    var depth = 1
                    var j = i + 2
                    while (j < text.length && depth > 0) {
                        if (kotlin && text.startsWith("/*", j)) { depth++; j += 2 }
                        else if (text.startsWith("*/", j)) { depth--; j += 2 }
                        else j++
                    }
                    blank(i, j); i = j
                }
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    blank(i, end); i = end
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != c && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    blank(i, minOf(j + 1, text.length)); i = j + 1
                }
                else -> i++
            }
        }
        return out.toString()
    }

    fun tokens(clean: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                c.isWhitespace() -> i++
                c.isLetter() || c == '_' || c == '$' -> {
                    val s = i
                    while (i < clean.length && (clean[i].isLetterOrDigit() || clean[i] == '_' || clean[i] == '$')) i++
                    out.add(Tok(clean.substring(s, i), s))
                }
                c.isDigit() -> { while (i < clean.length && (clean[i].isLetterOrDigit() || clean[i] == '.')) i++ }
                clean.startsWith("...", i) -> { out.add(Tok("...", i)); i += 3 }
                else -> { out.add(Tok(c.toString(), i)); i++ }
            }
        }
        return out
    }

    fun packageOf(clean: String): String =
        Regex("""\bpackage\s+([\w.]+)""").find(clean)?.groupValues?.get(1).orEmpty()

    /** Simple name (or alias) to fully-qualified name, from `import a.b.C` and `import a.b.C as D`. */
    fun imports(clean: String): Map<String, String> =
        Regex("""\bimport\s+(?:static\s+)?([\w.]+)(?:\s+as\s+(\w+))?""").findAll(clean)
            .filter { !it.groupValues[1].endsWith(".") }
            .associate { m -> (m.groupValues[2].ifEmpty { m.groupValues[1].substringAfterLast('.') }) to m.groupValues[1] }
}

/** Index of the token matching the opener at [open] (`(`→`)`, `<`→`>`, `{`→`}`), or the last index. */
internal fun List<Tok>.matching(open: Int): Int {
    val o = this[open].text
    val c = when (o) { "(" -> ")"; "<" -> ">"; "{" -> "}"; "[" -> "]"; else -> return open }
    var depth = 0
    for (k in open until size) {
        if (this[k].text == o) depth++
        else if (this[k].text == c) { depth--; if (depth == 0) return k }
    }
    return size - 1
}

/** Split [from, to) at the commas not nested in brackets. */
internal fun List<Tok>.splitTopLevel(from: Int, to: Int): List<List<Tok>> {
    if (from >= to) return emptyList()
    val parts = ArrayList<List<Tok>>()
    var depth = 0
    var start = from
    for (k in from until to) {
        when (this[k].text) {
            "(", "<", "[", "{" -> depth++
            ")", ">", "]", "}" -> depth--
            "," -> if (depth == 0) { parts.add(subList(start, k)); start = k + 1 }
        }
    }
    parts.add(subList(start, to))
    return parts.filter { it.isNotEmpty() }
}

/** Resolves a type written in a file to a descriptor, as described on [NativeMethodScanner]. */
internal class TypeResolver(private val pkg: String, private val imports: Map<String, String>, private val kotlin: Boolean) {

    fun objectDescriptor(name: String): String {
        val fqn = when {
            '.' in name && name.first().isLowerCase() -> name
            '.' in name -> qualify(name.substringBefore('.')) + "$" + name.substringAfter('.').replace('.', '$')
            else -> qualify(name)
        }
        return "L${fqn.replace('.', '/')};"
    }

    private fun qualify(simple: String): String =
        imports[simple] ?: (if (kotlin) KOTLIN_TYPES[simple] else null) ?: JAVA_LANG[simple]
            ?: if (pkg.isEmpty()) simple else "$pkg.$simple"

    /** A Java type: `int`, `String[]`, `java.util.List<String>`, `byte...`. */
    fun java(tokens: List<Tok>): JvmType {
        var dims = 0
        val name = StringBuilder()
        var k = 0
        while (k < tokens.size) {
            val t = tokens[k].text
            when {
                t == "<" -> { k = tokens.matching(k) + 1; continue }
                t == "[" -> dims++
                t == "..." -> dims++
                t == "]" || t == "@" -> {}
                t == "." -> name.append('.')
                t.first().isLetter() || t.first() == '_' || t.first() == '$' -> if (name.isEmpty() || name.endsWith('.')) name.append(t)
            }
            k++
        }
        val base = JAVA_PRIMITIVES[name.toString()] ?: objectDescriptor(name.toString())
        return JvmType("[".repeat(dims) + base)
    }

    /** A Kotlin type: `Int`, `String?`, `IntArray`, `Array<String>`, `List<Int>`. */
    fun kotlin(tokens: List<Tok>): JvmType {
        val ts = tokens.filter { it.text != "?" }
        if (ts.isEmpty()) return JvmType.VOID
        val name = buildString {
            for (t in ts) { if (t.text == "<") break; append(t.text) }
        }
        KOTLIN_PRIMITIVES[name]?.let { return JvmType(it) }
        if (name == "Array") {
            val open = ts.indexOfFirst { it.text == "<" }
            if (open >= 0) {
                val inner = ts.subList(open + 1, ts.matching(open)).filter { it.text != "out" && it.text != "in" }
                // An element type is always boxed in an Array<T>: Array<Int> is Integer[], not int[].
                val element = kotlin(inner).descriptor
                return JvmType("[" + (BOXED[element] ?: element))
            }
            return JvmType("[Ljava/lang/Object;")
        }
        return JvmType(objectDescriptor(name))
    }

    companion object {
        val JAVA_PRIMITIVES = mapOf(
            "boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S",
            "int" to "I", "long" to "J", "float" to "F", "double" to "D", "void" to "V",
        )
        val KOTLIN_PRIMITIVES = mapOf(
            "Boolean" to "Z", "Byte" to "B", "Char" to "C", "Short" to "S", "Int" to "I", "Long" to "J",
            "Float" to "F", "Double" to "D", "Unit" to "V",
            "BooleanArray" to "[Z", "ByteArray" to "[B", "CharArray" to "[C", "ShortArray" to "[S",
            "IntArray" to "[I", "LongArray" to "[J", "FloatArray" to "[F", "DoubleArray" to "[D",
        )
        val BOXED = mapOf(
            "Z" to "Ljava/lang/Boolean;", "B" to "Ljava/lang/Byte;", "C" to "Ljava/lang/Character;",
            "S" to "Ljava/lang/Short;", "I" to "Ljava/lang/Integer;", "J" to "Ljava/lang/Long;",
            "F" to "Ljava/lang/Float;", "D" to "Ljava/lang/Double;",
        )
        val JAVA_LANG = listOf(
            "String", "Object", "Class", "Throwable", "Exception", "RuntimeException", "Error", "Integer",
            "Long", "Short", "Byte", "Character", "Boolean", "Float", "Double", "Number", "Void", "CharSequence",
            "StringBuilder", "Thread", "Runnable", "Iterable", "Comparable", "Enum", "Record", "Math", "System",
        ).associateWith { "java.lang.$it" }
        val KOTLIN_TYPES = mapOf(
            "Any" to "java.lang.Object", "String" to "java.lang.String", "CharSequence" to "java.lang.CharSequence",
            "Throwable" to "java.lang.Throwable", "Number" to "java.lang.Number", "Comparable" to "java.lang.Comparable",
            "List" to "java.util.List", "MutableList" to "java.util.List", "Set" to "java.util.Set",
            "MutableSet" to "java.util.Set", "Map" to "java.util.Map", "MutableMap" to "java.util.Map",
            "Collection" to "java.util.Collection", "MutableCollection" to "java.util.Collection",
            "Iterable" to "java.lang.Iterable", "Nothing" to "java.lang.Void",
        )
    }
}

private val JAVA_MODIFIERS = setOf(
    "public", "protected", "private", "static", "final", "synchronized", "native", "abstract", "strictfp", "default",
)

private class Scope(val className: String?, val kind: String)

private class JavaScan(private val clean: String) {
    private val toks = SourceText.tokens(clean)
    private val types = TypeResolver(SourceText.packageOf(clean), SourceText.imports(clean), kotlin = false)

    fun run(): List<NativeMethod> {
        val out = ArrayList<NativeMethod>()
        val pkg = SourceText.packageOf(clean)
        val scopes = ArrayList<Scope>()
        var pendingClass: String? = null
        var k = 0
        while (k < toks.size) {
            val t = toks[k].text
            when {
                (t == "class" || t == "interface" || t == "enum" || t == "record") &&
                    toks.getOrNull(k - 1)?.text != "." && toks.getOrNull(k + 1)?.text?.first()?.isLetter() == true -> {
                    val name = toks[k + 1].text
                    val outer = scopes.lastOrNull { it.className != null }?.className
                    pendingClass = when {
                        scopes.any { it.kind == "block" } -> null // a local class: its binary name is compiler-chosen
                        outer != null -> "$outer\$$name"
                        pkg.isEmpty() -> name
                        else -> "$pkg.$name"
                    }
                    if (pendingClass == null) pendingClass = "<local>"
                    k += 2; continue
                }
                t == "{" -> {
                    val cls = pendingClass
                    scopes.add(if (cls != null && cls != "<local>") Scope(cls, "class") else Scope(null, "block"))
                    pendingClass = null
                }
                t == "}" -> if (scopes.isNotEmpty()) scopes.removeAt(scopes.size - 1)
                t == "native" && scopes.lastOrNull()?.kind == "class" -> {
                    parseNative(k, scopes.last().className!!)?.let { out.add(it) }
                }
            }
            k++
        }
        return out
    }

    /** The declaration around the `native` at [at], from the previous statement boundary to its `;`. */
    private fun parseNative(at: Int, className: String): NativeMethod? {
        var start = at
        while (start > 0 && toks[start - 1].text !in setOf(";", "{", "}")) start--
        val open = (at until toks.size).firstOrNull { toks[it].text == "(" || toks[it].text == ";" } ?: return null
        if (toks[open].text != "(") return null
        val nameTok = toks.getOrNull(open - 1) ?: return null
        val close = toks.matching(open)
        // Modifiers, annotations and type parameters come before the return type.
        var k = start
        var isStatic = false
        while (k < open - 1) {
            val t = toks[k].text
            when {
                t == "@" -> { k += 2; if (toks.getOrNull(k)?.text == "(") k = toks.matching(k) + 1; continue }
                t == "<" -> { k = toks.matching(k) + 1; continue }
                t in JAVA_MODIFIERS -> { if (t == "static") isStatic = true; k++ }
                else -> break
            }
        }
        val returnType = types.java(toks.subList(k, open - 1))
        val params = toks.splitTopLevel(open + 1, close).map { param ->
            // `final @NonNull String... names`: drop modifiers and annotations, the last identifier is the name.
            val cleaned = ArrayList<Tok>()
            var p = 0
            while (p < param.size) {
                when {
                    param[p].text == "@" -> { p += 2; if (param.getOrNull(p)?.text == "(") p = param.matching(p) + 1 }
                    param[p].text == "final" -> p++
                    else -> { cleaned.add(param[p]); p++ }
                }
            }
            types.java(cleaned.dropLast(1))
        }
        return NativeMethod(className, nameTok.text, params, returnType, isStatic, nameTok.start)
    }
}

private class KotlinScan(raw: String, private val clean: String, fileName: String) {
    private val toks = SourceText.tokens(clean)
    private val pkg = SourceText.packageOf(clean)
    private val types = TypeResolver(pkg, SourceText.imports(clean), kotlin = true)

    /** The class top-level declarations compile into: `@file:JvmName("X")`, else `<FileName>Kt`. */
    private val facade: String = run {
        // The name is a string literal, which cleaning blanks, so it is read from the raw text; the match is
        // only trusted where the cleaned text still has the annotation, so one inside a comment is ignored.
        val jvmName = Regex("""@file\s*:\s*JvmName\s*\(\s*"([^"]+)"""").find(raw)
            ?.takeIf { clean.startsWith("@file", it.range.first) }?.groupValues?.get(1)
        val base = jvmName ?: (fileName.removeSuffix(".kt").replaceFirstChar { it.uppercase() } + "Kt")
        if (pkg.isEmpty()) base else "$pkg.$base"
    }

    fun run(): List<NativeMethod> {
        val out = ArrayList<NativeMethod>()
        val scopes = ArrayList<Scope>()
        var pending: String? = null
        var pendingDepth = 0
        var parenDepth = 0
        var k = 0
        while (k < toks.size) {
            val t = toks[k].text
            when {
                t == "(" -> parenDepth++
                t == ")" -> parenDepth--
                (t == "class" || t == "interface" || t == "object") && toks.getOrNull(k - 1)?.text != "." &&
                    toks.getOrNull(k - 1)?.text != ":" && parenDepth == 0 -> {
                    val next = toks.getOrNull(k + 1)?.text
                    val isCompanion = t == "object" && toks.getOrNull(k - 1)?.text == "companion"
                    val name = when {
                        next != null && next.first().isLetter() && next !in KOTLIN_HEADER_STOPS -> next
                        isCompanion -> "Companion"
                        else -> null // an object expression: anonymous, nothing native lives on it by name
                    }
                    val outer = scopes.lastOrNull { it.className != null }?.className
                    pending = when {
                        name == null || scopes.any { it.kind == "block" } -> "<anon>"
                        outer != null -> "$outer\$$name"
                        pkg.isEmpty() -> name
                        else -> "$pkg.$name"
                    }
                    pendingDepth = scopes.size
                    if (isCompanion && pending != "<anon>") pending = "companion:$pending"
                    else if (t == "object" && pending != "<anon>") pending = "object:$pending"
                }
                t == "{" -> {
                    val cls = pending
                    scopes.add(
                        when {
                            cls == null || cls == "<anon>" -> Scope(null, "block")
                            cls.startsWith("companion:") -> Scope(cls.removePrefix("companion:"), "companion")
                            cls.startsWith("object:") -> Scope(cls.removePrefix("object:"), "object")
                            else -> Scope(cls, "class")
                        },
                    )
                    pending = null
                }
                t == "}" -> if (scopes.isNotEmpty()) scopes.removeAt(scopes.size - 1)
                // A class with no body ends its header at the next declaration: its pending name is not the
                // owner of whatever brace comes after that.
                t in setOf("fun", "val", "var") && pending != null && scopes.size == pendingDepth && parenDepth == 0 ->
                    pending = null
                t == "external" -> {
                    val scope = scopes.lastOrNull()
                    if (scope == null || scope.kind != "block") parseExternal(k, scope)?.let { out.add(it) }
                }
            }
            k++
        }
        return out
    }

    private fun parseExternal(at: Int, scope: Scope?): NativeMethod? {
        var start = at
        while (start > 0 && toks[start - 1].text !in setOf(";", "{", "}", ")") && !isDeclarationEnd(start - 1)) start--
        val funAt = (at until minOf(toks.size, at + 12)).firstOrNull { toks[it].text == "fun" } ?: return null
        var k = funAt + 1
        if (toks.getOrNull(k)?.text == "<") k = toks.matching(k) + 1
        val open = (k until toks.size).firstOrNull { toks[it].text == "(" } ?: return null
        // `external fun Foo.bar()` (an extension) is not something JNI can bind by name; skip it.
        if (open - k != 1) return null
        val nameTok = toks[open - 1]
        val close = toks.matching(open)
        val params = toks.splitTopLevel(open + 1, close).map { param ->
            val colon = param.indexOfFirst { it.text == ":" }
            if (colon < 0) return@map JvmType.OBJECT
            val end = param.indexOfFirst { it.text == "=" }.let { if (it < 0) param.size else it }
            val type = types.kotlin(param.subList(colon + 1, end))
            if (param.any { it.text == "vararg" }) JvmType("[" + type.descriptor) else type
        }
        val returnType = if (toks.getOrNull(close + 1)?.text == ":") {
            var e = close + 2
            while (e < toks.size) {
                val t = toks[e].text
                if (t == "<") { e = toks.matching(e) + 1; continue }
                if (t == "." || t == "?" || (t.first().isLetter() && (e == close + 2 || toks[e - 1].text == "."))) e++ else break
            }
            types.kotlin(toks.subList(close + 2, e))
        } else JvmType.VOID

        val jvmStatic = (start until funAt).any { toks[it].text == "JvmStatic" && toks.getOrNull(it - 1)?.text == "@" }
        val (owner, isStatic) = when (scope?.kind) {
            null -> facade to true
            // `@JvmStatic` in a companion puts the native method on the enclosing class, as a static one.
            "companion" -> if (jvmStatic) scope.className!!.substringBeforeLast('$') to true else scope.className!! to false
            "object" -> scope.className!! to jvmStatic
            else -> scope.className!! to false
        }
        return NativeMethod(owner, nameTok.text, params, returnType, isStatic, nameTok.start)
    }

    /** A token that ends the previous declaration in a statement list without a `;`: a newline is invisible
     *  to the tokenizer, so the start of a modifier run is found by walking back over modifiers instead. */
    private fun isDeclarationEnd(index: Int): Boolean {
        val t = toks[index].text
        return t !in KOTLIN_MODIFIERS && t != "@" && !(toks.getOrNull(index - 1)?.text == "@")
    }

    companion object {
        val KOTLIN_HEADER_STOPS = setOf("fun", "val", "var", "class", "object", "interface")
        val KOTLIN_MODIFIERS = setOf(
            "public", "private", "internal", "protected", "external", "override", "open", "final", "abstract",
            "suspend", "inline", "operator", "infix", "tailrec", "actual", "expect",
        )
    }
}
