package dev.ide.lang.kotlin.synthetic

import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.kotlin.symbols.RawCallable
import dev.ide.lang.kotlin.symbols.RawClass
import dev.ide.lang.kotlin.symbols.SourceFile
import dev.ide.lang.kotlin.symbols.SourceIndexBuilder
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticClassContext
import dev.ide.lang.synthetic.SyntheticClassProvider
import dev.ide.lang.synthetic.SyntheticField
import dev.ide.lang.synthetic.SyntheticMethod
import dev.ide.lang.synthetic.SyntheticModifier
import dev.ide.lang.synthetic.SyntheticParam
import dev.ide.lang.synthetic.SyntheticTypeKind
import dev.ide.model.ContentRole
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile

/**
 * Makes a module's Kotlin source visible to its Java code (and to JDT-backed completion/analysis) before a
 * build — the editor equivalent of IntelliJ's *light classes*. Kotlin compiles each file's top-level
 * functions/properties into a file-facade class (`Foo.kt` → `FooKt`) and its `class`/`object`/`interface`/
 * `enum` declarations into JVM classes, but none of that bytecode is on the editor classpath until a build,
 * so a Java file referencing `FooKt.helloWorld()` or a Kotlin class can't resolve it. This provider
 * reconstructs the Java-visible shape from the parsed Kotlin source (no resolution) as a structured
 * [SyntheticClass]; the JDT backend renders it to Java the name environment resolves against.
 *
 * Faithfulness (what Java actually sees of Kotlin):
 *  - the declaration kind is preserved (`interface`/`enum`/`@interface`; an `object` is a `final class`);
 *  - a property becomes `get<Name>()`, and a `var` also `set<Name>(…)`;
 *  - an `object` exposes its `INSTANCE`, a companion a `public static final Companion Companion` field,
 *    and nested types are embedded so `Outer.Inner` resolves;
 *  - types are mapped to Java (`String`→`java.lang.String`, non-null `Int`→`int`, `List`→`java.util.List`,
 *    a same-package/imported Kotlin or library type → its FQN), keeping type arguments that map
 *    (`List<String>` → `java.util.List<java.lang.String>`); a light class declares no type parameters of its
 *    own, so a reference to a generic class of the module stays raw;
 *  - the superclass (the supertype written with a constructor call) and interfaces are declared, and an
 *    `abstract`/`sealed` class is `abstract`.
 *
 * Approximations (these only ever *omit* or *widen*, never invent, so they can't cause false resolution):
 * an unresolved/star-imported or functional type and an inferred return fall back to `java.lang.Object`
 * (or `void` for a Unit body); `@Jvm*` customisations, default-argument overloads and `enum` constants'
 * bodies are not modelled (an enum's entries are exposed as `public static final` self-typed fields, enough
 * for `Color.RED` to resolve). `private` declarations are dropped.
 */
class KotlinSyntheticClassProvider : SyntheticClassProvider {

    /** A stateless build from disk, for any host that asks through the extension point. The IDE engine keeps
     *  an incremental [KotlinLightClasses] instead, which also sees unsaved buffers. */
    override fun classesFor(context: SyntheticClassContext): List<SyntheticClass> {
        val roots = kotlinSourceRoots(context.module)
        if (roots.isEmpty()) return emptyList()
        val model = runCatching { SourceIndexBuilder.build(roots) }.getOrNull() ?: return emptyList()
        val classNames = model.classByFqn.keys
        return model.files.flatMap { LightClassRenderer(it) { fqn -> fqn in classNames }.render() }
    }

    companion object {
        /** The roots a module's Kotlin source lives under: its source and generated content roots. */
        fun kotlinSourceRoots(module: Module): List<VirtualFile> = module.sourceSets
            .flatMap { it.contentRoots }
            .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
            .map { it.dir }
    }
}

/**
 * Renders ONE Kotlin file's Java-visible classes: its top-level classes (nested types and companions embedded)
 * and its `<File>Kt` facade. Everything it reads comes from [file], except whether a type name is a class of
 * the module, which it asks [isModuleClass]; [lookups] records every such answer, so a caller can tell whether
 * a change to the module's class set could change this file's output.
 */
internal class LightClassRenderer(private val file: SourceFile, isModuleClass: (String) -> Boolean) {

    /** Every module-class question the render asked, with the answer it got. */
    val lookups = HashMap<String, Boolean>()

    private val types = TypeMapper { fqn -> isModuleClass(fqn).also { lookups[fqn] = it } }

    private val classByFqn: Map<String, RawClass> = file.classes.associateBy { it.fqn }

    fun render(): List<SyntheticClass> {
        val out = ArrayList<SyntheticClass>()
        // Top-level classes only; nested types + companions are embedded in their owner (so `Outer.Inner`
        // resolves), and a companion's members reach Java through the owner's `Companion` field.
        for (cls in classByFqn.values) {
            if (cls.isCompanion) continue
            if (cls.fqn.substringBeforeLast('.', "") != cls.ctx.packageName) continue // nested → emitted by its parent
            out += classFor(cls)
        }
        facadeClass()?.let { out += it }
        return out
    }

    /** The `<File>Kt` file facade: top-level functions/properties (and extensions) as `public static` members. */
    private fun facadeClass(): SyntheticClass? {
        val members = (file.topLevel + file.extensions).filter { it.visibility != "private" }
        if (members.isEmpty()) return null
        val base = file.ctx.path.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".kts").removeSuffix(".kt")
        if (base.isEmpty()) return null
        val simple = file.jvmName ?: (base.replaceFirstChar { it.uppercaseChar() } + "Kt")
        val fqn = if (file.ctx.packageName.isEmpty()) simple else "${file.ctx.packageName}.$simple"
        val methods = ArrayList<SyntheticMethod>()
        val fields = ArrayList<SyntheticField>()
        members.forEach { val (ms, fs) = emitMember(it, static = true); methods += ms; fields += fs }
        return SyntheticClass(
            fqName = fqn,
            modifiers = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.FINAL),
            fields = fields,
            methods = methods,
            doc = "Kotlin file facade (synthetic)",
        )
    }

    /** A `class`/`interface`/`enum`/`object` as the matching Java type, with nested types embedded. [nested]
     *  marks an embedded type (rendered `static`); a top-level type is `public` (objects also `final`). */
    private fun classFor(cls: RawClass, nested: Boolean = false): SyntheticClass {
        val members = cls.members.filter { it.visibility != "private" && it.receiverText == null }
        // A plain class exposes its constructor(s) so `new Foo(args)` resolves from Java; an
        // object/enum/interface/annotation is never instantiated with `new`.
        val plainClass = !cls.isObject && !cls.isEnum && !cls.isInterface && !cls.isAnnotation
        val methods = ArrayList<SyntheticMethod>()
        val fields = ArrayList<SyntheticField>()
        if (plainClass) cls.constructors.filter { it.visibility != "private" }.forEach { ctor ->
            overloadVariants(ctor).forEach { ps ->
                methods += SyntheticMethod(cls.simpleName, parameters = ps, modifiers = ctorMods(ctor), isConstructor = true)
            }
        }
        for (m in members) {
            // In an `object`, a `@JvmStatic`/`@JvmField` member is a Java static; a companion's members are
            // instance members of the nested `Companion` (their statics are promoted onto the owner below).
            val static = !cls.isCompanion && cls.isObject && (m.jvmStatic || (!m.isFunction && (m.jvmField || m.isConst)))
            val (ms, fs) = emitMember(m, static); methods += ms; fields += fs
        }
        if (cls.isObject && !cls.isCompanion) fields += field("INSTANCE", cls.fqn)
        if (cls.isEnum) cls.enumEntries.forEach { fields += field(it, cls.fqn) } // `Color.RED` etc.
        cls.companionObjectName?.let { comp ->
            fields += field(comp, "${cls.fqn}.$comp")
            // `@JvmStatic`/`@JvmField` companion members are reached from Java as statics on the OWNER class.
            classByFqn["${cls.fqn}.$comp"]?.members
                ?.filter { it.visibility != "private" && it.receiverText == null && (it.jvmStatic || it.jvmField || it.isConst) }
                ?.forEach { m -> val (ms, fs) = emitMember(m, static = true); methods += ms; fields += fs }
        }
        val children = classByFqn.values
            .filter { it.fqn != cls.fqn && it.fqn.substringBeforeLast('.') == cls.fqn }
            .map { classFor(it, nested = true) }
        val kind = when {
            cls.isAnnotation -> SyntheticTypeKind.ANNOTATION
            cls.isInterface -> SyntheticTypeKind.INTERFACE
            else -> SyntheticTypeKind.CLASS // an enum is rendered as a class (entries above); an object is a final class
        }
        val mods = buildSet {
            add(SyntheticModifier.PUBLIC)
            if (nested) add(SyntheticModifier.STATIC) // referenceable as `Outer.Inner` without an instance
            if (cls.isObject) add(SyntheticModifier.FINAL)
            // `abstract`/`sealed`: Java can't instantiate it either.
            if (cls.isAbstract && kind == SyntheticTypeKind.CLASS) add(SyntheticModifier.ABSTRACT)
        }
        // What it extends, so Java sees inherited members and can pass it where a supertype is expected. The one
        // supertype written with a constructor call is the superclass; the rest are interfaces. A supertype that
        // doesn't resolve is left out rather than guessed.
        val superClass = cls.superClassText
            ?.takeIf { kind == SyntheticTypeKind.CLASS }
            ?.let { types.javaTypeOrNull(it, cls.ctx) }
            ?.takeIf { it != OBJECT }
        val interfaces = cls.superTypeTexts
            .filter { it != cls.superClassText }
            .mapNotNull { types.javaTypeOrNull(it, cls.ctx) }
            .filter { it != OBJECT }
        return SyntheticClass(
            fqName = cls.fqn,
            kind = kind,
            modifiers = mods,
            superClass = superClass,
            interfaces = interfaces,
            fields = fields,
            methods = methods,
            nestedClasses = children,
            doc = if (cls.isObject) "Kotlin object (synthetic)" else "Kotlin ${kind.name.lowercase()} (synthetic)",
        )
    }

    private fun field(name: String, type: String): SyntheticField =
        SyntheticField(name, type, setOf(SyntheticModifier.PUBLIC, SyntheticModifier.STATIC, SyntheticModifier.FINAL))

    private fun ctorMods(c: RawCallable): Set<SyntheticModifier> =
        if (c.visibility == "protected") setOf(SyntheticModifier.PROTECTED) else setOf(SyntheticModifier.PUBLIC)

    /** A callable's Java-visible member(s): a function → its method (renamed by `@JvmName`, with
     *  `@JvmOverloads` variants); a `@JvmField` property → a field; any other property → `get<Name>()` (+
     *  `set<Name>(value)` for a `var`). [static] for file-facade members and `@JvmStatic`/object members. */
    private fun emitMember(c: RawCallable, static: Boolean): Pair<List<SyntheticMethod>, List<SyntheticField>> {
        // A `@JvmField` or `const val` property is a field to Java, with no accessors; a constant keeps its literal
        // value, so it stays a constant expression (a `case` label) and two constants don't collide.
        if (!c.isFunction && (c.jvmField || c.isConst)) {
            val type = propertyType(c)
            val mods = buildSet {
                add(SyntheticModifier.PUBLIC); if (static) add(SyntheticModifier.STATIC); if (!c.isVar) add(SyntheticModifier.FINAL)
            }
            val constant = c.initializerText?.trim()?.takeIf { c.isConst && types.isJavaLiteral(it) }
            return emptyList<SyntheticMethod>() to listOf(SyntheticField(c.name, type, mods, constant))
        }
        val mods = if (static) setOf(SyntheticModifier.PUBLIC, SyntheticModifier.STATIC) else setOf(SyntheticModifier.PUBLIC)
        if (c.isFunction) {
            val ret = functionReturn(c)
            val name = c.jvmName ?: c.name
            return overloadVariants(c).map { SyntheticMethod(name, ret, it, mods) } to emptyList()
        }
        val cap = c.name.replaceFirstChar { it.uppercaseChar() }
        val type = propertyType(c)
        val accessors = ArrayList<SyntheticMethod>()
        accessors += SyntheticMethod("get$cap", type, receiverParam(c), mods)
        if (c.isVar) accessors += SyntheticMethod("set$cap", "void", receiverParam(c) + SyntheticParam("value", type), mods)
        // A `lateinit var` also exposes its backing field.
        val fields = if (c.isLateinit) listOf(SyntheticField(c.name, type, mods)) else emptyList()
        return accessors to fields
    }

    /** A function's/constructor's parameter lists: just the full list, unless `@JvmOverloads` — then also the
     *  shorter lists Kotlin generates by dropping trailing defaulted value parameters (one at a time). */
    private fun overloadVariants(c: RawCallable): List<List<SyntheticParam>> {
        val receiver = receiverParam(c)
        val value = c.paramTexts.mapIndexed { i, (n, t) ->
            val type = types.javaType(t, c.ctx)
            SyntheticParam(n.ifEmpty { "p$i" }, if (i == c.varargParamIndex) "$type[]" else type)
        }
        if (!c.jvmOverloads) return listOf(receiver + value)
        val counts = linkedSetOf(value.size)
        var i = value.size
        while (i > 0 && c.paramHasDefault.getOrElse(i - 1) { false }) { i--; counts.add(i) }
        return counts.map { receiver + value.take(it) }
    }

    /** An extension's receiver becomes a leading Java parameter (how Kotlin compiles `T.foo()`). */
    private fun receiverParam(c: RawCallable): List<SyntheticParam> =
        if (c.receiverText != null) listOf(SyntheticParam("\$receiver", types.javaType(c.receiverText, c.ctx))) else emptyList()

    /** A property's Java type: declared → mapped; else what its initializer evidently is; else `Object`. */
    private fun propertyType(c: RawCallable): String =
        c.returnText?.let { types.javaType(it, c.ctx) }
            ?: c.initializerText?.let { types.inferredJavaType(it, c.ctx) }
            ?: OBJECT

    /** A function's Java return type: declared → mapped; expression body → what it evidently is, else `Object`;
     *  block body → `void`. */
    private fun functionReturn(c: RawCallable): String = when {
        c.returnText != null -> types.javaType(c.returnText, c.ctx)
        c.initializerText != null -> types.inferredJavaType(c.initializerText, c.ctx) ?: OBJECT
        else -> "void"
    }

    /**
     * Maps a Kotlin type *text* (resolved against its declaration's [FileContext]) to a Java type the
     * synthetic source compiles against. Type arguments are kept when every one of them maps (`List<String>` →
     * `java.util.List<java.lang.String>`, `out T` → `? extends`, `*` → `?`), else the type is left raw; an
     * unresolved or functional type → `Object`. [isModuleClass] says whether an FQN is a class declared in the
     * module's Kotlin source.
     */
    private class TypeMapper(private val isModuleClass: (String) -> Boolean) {
        fun javaType(typeText: String?, ctx: FileContext): String = javaTypeOrNull(typeText, ctx) ?: OBJECT

        /** [javaType], or null when the type doesn't resolve (so a caller can tell `Object` from unknown). */
        fun javaTypeOrNull(typeText: String?, ctx: FileContext, boxed: Boolean = false): String? {
            val raw = typeText?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val nullable = raw.endsWith("?")
            val t = (if (nullable) raw.dropLast(1) else raw).trim()
            if (t.startsWith("(") || "->" in t) return functionType(t, ctx)
            val head = t.substringBefore('<').trim()
            val args = if ('<' in t) splitArgs(t.substringAfter('<').substringBeforeLast('>')) else emptyList()
            ARRAYS[head]?.let { return it }
            if (head == "Array") {
                val element = args.singleOrNull()?.let { javaTypeOrNull(it.removePrefix("out ").trim(), ctx, boxed = true) }
                return "${element ?: OBJECT}[]"
            }
            val fqn = WELL_KNOWN[head] ?: resolve(head, ctx) ?: return null
            if (fqn == "kotlin.Unit") return if (boxed) fqn else "void"
            PRIMITIVE[fqn]?.let { return if (nullable || boxed) Builtins.KOTLIN_TO_JAVA[fqn] ?: OBJECT else it }
            val java = Builtins.KOTLIN_TO_JAVA[fqn] ?: fqn
            // A light class declares no type parameters of its own, so a module class is referenced raw.
            if (args.isEmpty() || isModuleClass(fqn)) return java
            val mapped = args.map { typeArgument(it, ctx) ?: return java }
            return "$java<${mapped.joinToString(", ")}>"
        }

        /**
         * A Kotlin function type as the `kotlin.jvm.functions.FunctionN` interface it compiles to: a receiver is the
         * first type argument, then the parameters, then the return type (`Unit` as `kotlin.Unit`), so a Java
         * lambda fits it with typed parameters. A `suspend` or annotated (`@Composable`) one compiles to something
         * else, and stays `Object`, as does one whose parts don't resolve to a type.
         */
        private fun functionType(text: String, ctx: FileContext): String {
            var f = text.trim()
            while (f.startsWith("(") && closingParen(f, 0) == f.length - 1) f = f.substring(1, f.length - 1).trim()
            if (f.startsWith("@") || f.startsWith("suspend")) return OBJECT
            val arrow = topLevelArrow(f) ?: return OBJECT
            val left = f.substring(0, arrow).trim()
            if (!left.endsWith(")")) return OBJECT
            val open = openingParen(left, left.length - 1) ?: return OBJECT
            val receiver = left.substring(0, open).trim().removeSuffix(".").trim().ifEmpty { null }
            val params = splitArgs(left.substring(open + 1, left.length - 1)).map { it.substringAfter(':', it).trim() }
            val inputs = listOfNotNull(receiver) + params
            if (inputs.size > 22) return OBJECT
            val raw = "kotlin.jvm.functions.Function${inputs.size}"
            val mapped = inputs.map { javaTypeOrNull(it, ctx, boxed = true) ?: return raw }
            val ret = javaTypeOrNull(f.substring(arrow + 2).trim(), ctx, boxed = true) ?: return raw
            return "$raw<${(mapped + ret).joinToString(", ")}>"
        }

        /**
         * The Java type a declaration with no written type evidently has, from its initializer [expr]: a string,
         * number, boolean or char literal, a constructor call of a class of the module, or a standard collection
         * builder. Null for anything else (the caller falls back to `Object`): no real inference happens here.
         */
        /** Whether [text] is a literal Java accepts as written: a string with no template, a number, a boolean. */
        fun isJavaLiteral(text: String): Boolean =
            (text.startsWith("\"") && !text.startsWith("\"\"\"") && '$' !in text) || text == "true" || text == "false" ||
                INT_LITERAL.matches(text) || FLOAT_LITERAL.matches(text) || DOUBLE_LITERAL.matches(text)

        fun inferredJavaType(expr: String, ctx: FileContext): String? {
            val e = expr.trim()
            if (e.startsWith("\"")) return "java.lang.String"
            if (e == "true" || e == "false") return "boolean"
            if (e.length >= 3 && e.startsWith("'") && e.endsWith("'")) return "char"
            if (INT_LITERAL.matches(e)) return if (e.endsWith("L") || e.endsWith("l")) "long" else "int"
            if (FLOAT_LITERAL.matches(e)) return "float"
            if (DOUBLE_LITERAL.matches(e)) return "double"
            // `Head(args)`, with the call's parentheses spanning to the end (not `Head(a).more()`).
            val open = e.indexOf('(')
            if (open <= 0 || !e.endsWith(")") || closingParen(e, open) != e.length - 1) return null
            val head = e.substring(0, open).trim()
            if (!CALLEE.matches(head)) return null
            COLLECTION_BUILDERS[head]?.let { return it }
            if (!head.substringAfterLast('.').first().isUpperCase()) return null
            // Only a class of the module is known to be a constructor: an imported capitalized name can as well
            // be a factory function (`Color(0xFF000000)`).
            val fqn = resolve(head, ctx) ?: return null
            return fqn.takeIf { isModuleClass(it) }
        }

        private fun closingParen(text: String, open: Int): Int? {
            var depth = 0
            for (i in open until text.length) when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
            return null
        }

        private fun openingParen(text: String, close: Int): Int? {
            var depth = 0
            for (i in close downTo 0) when (text[i]) {
                ')' -> depth++
                '(' -> if (--depth == 0) return i
            }
            return null
        }

        /** The index of the `->` outside any parentheses or angle brackets. */
        private fun topLevelArrow(text: String): Int? {
            var depth = 0
            for (i in 0 until text.length - 1) when (text[i]) {
                '(', '<' -> depth++
                ')' -> depth--
                '>' -> if (i > 0 && text[i - 1] == '-') { if (depth == 0) return i - 1 } else depth--
                '-' -> if (depth == 0 && text[i + 1] == '>') return i
            }
            return null
        }

        /** One type argument as Java writes it: a projection becomes a wildcard, a primitive its box. */
        private fun typeArgument(text: String, ctx: FileContext): String? = when {
            text == "*" -> "?"
            text.startsWith("out ") -> javaTypeOrNull(text.removePrefix("out "), ctx, boxed = true)?.let { "? extends $it" }
            text.startsWith("in ") -> javaTypeOrNull(text.removePrefix("in "), ctx, boxed = true)?.let { "? super $it" }
            else -> javaTypeOrNull(text, ctx, boxed = true)
        }

        private fun splitArgs(text: String): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var start = 0
            for (i in text.indices) when (text[i]) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                ',' -> if (depth == 0) { out += text.substring(start, i).trim(); start = i + 1 }
            }
            text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it }
            return out
        }

        /** Resolve a simple/qualified type name to a Kotlin/Java FQN via the module's classes + the file's imports. */
        private fun resolve(head: String, ctx: FileContext): String? {
            if (isModuleClass(head)) return head
            val samePkg = if (ctx.packageName.isEmpty()) head else "${ctx.packageName}.$head"
            if (isModuleClass(samePkg)) return samePkg
            ctx.imports.firstOrNull { !it.isStar && it.simpleName == head }?.let { return it.fqn }
            return null
        }
    }

    private companion object {
        const val OBJECT = "java.lang.Object"

        val INT_LITERAL = Regex("""-?(0[xX][0-9a-fA-F_]+|0[bB][01_]+|\d[\d_]*)[lL]?""")
        val DOUBLE_LITERAL = Regex("""-?\d[\d_]*(\.\d[\d_]*([eE][+-]?\d+)?|[eE][+-]?\d+)""")
        val FLOAT_LITERAL = Regex("""-?\d[\d_]*(\.\d[\d_]*)?([eE][+-]?\d+)?[fF]""")
        val CALLEE = Regex("""[A-Za-z_][\w.]*""")

        /** The standard collection builders, as the raw Java interface each returns. */
        val COLLECTION_BUILDERS: Map<String, String> = mapOf(
            "listOf" to "java.util.List", "emptyList" to "java.util.List", "mutableListOf" to "java.util.List",
            "arrayListOf" to "java.util.ArrayList", "listOfNotNull" to "java.util.List",
            "setOf" to "java.util.Set", "emptySet" to "java.util.Set", "mutableSetOf" to "java.util.Set",
            "hashSetOf" to "java.util.HashSet", "linkedSetOf" to "java.util.LinkedHashSet",
            "mapOf" to "java.util.Map", "emptyMap" to "java.util.Map", "mutableMapOf" to "java.util.Map",
            "hashMapOf" to "java.util.HashMap", "linkedMapOf" to "java.util.LinkedHashMap",
        )

        /** Well-known Kotlin simple names → their Kotlin FQN, so the common types map without project resolution. */
        val WELL_KNOWN: Map<String, String> = mapOf(
            "Any" to "kotlin.Any", "Unit" to "kotlin.Unit", "String" to "kotlin.String",
            "CharSequence" to "kotlin.CharSequence", "Number" to "kotlin.Number", "Throwable" to "kotlin.Throwable",
            "Int" to "kotlin.Int", "Long" to "kotlin.Long", "Short" to "kotlin.Short", "Byte" to "kotlin.Byte",
            "Double" to "kotlin.Double", "Float" to "kotlin.Float", "Boolean" to "kotlin.Boolean", "Char" to "kotlin.Char",
            "List" to "kotlin.collections.List", "MutableList" to "kotlin.collections.MutableList",
            "Set" to "kotlin.collections.Set", "MutableSet" to "kotlin.collections.MutableSet",
            "Map" to "kotlin.collections.Map", "MutableMap" to "kotlin.collections.MutableMap",
            "Collection" to "kotlin.collections.Collection", "Iterable" to "kotlin.collections.Iterable",
        )

        /** Kotlin primitive FQN → the Java *unboxed* type (the nullable form uses [Builtins.KOTLIN_TO_JAVA]). */
        val PRIMITIVE: Map<String, String> = mapOf(
            "kotlin.Int" to "int", "kotlin.Long" to "long", "kotlin.Short" to "short", "kotlin.Byte" to "byte",
            "kotlin.Double" to "double", "kotlin.Float" to "float", "kotlin.Boolean" to "boolean", "kotlin.Char" to "char",
        )

        val ARRAYS: Map<String, String> = mapOf(
            "IntArray" to "int[]", "LongArray" to "long[]", "ShortArray" to "short[]", "ByteArray" to "byte[]",
            "DoubleArray" to "double[]", "FloatArray" to "float[]", "BooleanArray" to "boolean[]", "CharArray" to "char[]",
        )
    }
}
