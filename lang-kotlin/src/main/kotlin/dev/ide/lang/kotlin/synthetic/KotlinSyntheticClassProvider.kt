package dev.ide.lang.kotlin.synthetic

import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.kotlin.symbols.ModuleSourceModel
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
 *    a same-package/imported Kotlin or library type → its FQN); generics are erased to raw types.
 *
 * Approximations (these only ever *omit* or *widen*, never invent, so they can't cause false resolution):
 * an unresolved/star-imported or functional type and an inferred return fall back to `java.lang.Object`
 * (or `void` for a Unit body); `@Jvm*` customisations, default-argument overloads and `enum` constants'
 * bodies are not modelled (an enum's entries are exposed as `public static final` self-typed fields, enough
 * for `Color.RED` to resolve). `private` declarations are dropped.
 */
class KotlinSyntheticClassProvider : SyntheticClassProvider {

    override fun classesFor(context: SyntheticClassContext): List<SyntheticClass> {
        val roots = context.module.sourceSets
            .flatMap { it.contentRoots }
            .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
            .map { it.dir }
        if (roots.isEmpty()) return emptyList()
        val model = runCatching { SourceIndexBuilder.build(roots) }.getOrNull() ?: return emptyList()
        val types = TypeMapper(model)
        val out = ArrayList<SyntheticClass>()
        // Top-level classes only; nested types + companions are embedded in their owner (so `Outer.Inner`
        // resolves), and a companion's members reach Java through the owner's `Companion` field.
        for (cls in model.classByFqn.values) {
            if (cls.isCompanion) continue
            if (cls.fqn.substringBeforeLast('.', "") != cls.ctx.packageName) continue // nested → emitted by its parent
            out += classFor(cls, model, types)
        }
        for (file in model.files) facadeClass(file, types)?.let { out += it }
        return out
    }

    /** The `<File>Kt` file facade: top-level functions/properties (and extensions) as `public static` members. */
    private fun facadeClass(file: SourceFile, types: TypeMapper): SyntheticClass? {
        val members = (file.topLevel + file.extensions).filter { it.visibility != "private" }
        if (members.isEmpty()) return null
        val base = file.ctx.path.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".kts").removeSuffix(".kt")
        if (base.isEmpty()) return null
        val simple = base.replaceFirstChar { it.uppercaseChar() } + "Kt"
        val fqn = if (file.ctx.packageName.isEmpty()) simple else "${file.ctx.packageName}.$simple"
        val methods = ArrayList<SyntheticMethod>()
        val fields = ArrayList<SyntheticField>()
        members.forEach { val (ms, fs) = emitMember(it, types, static = true); methods += ms; fields += fs }
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
    private fun classFor(cls: RawClass, model: ModuleSourceModel, types: TypeMapper, nested: Boolean = false): SyntheticClass {
        val members = cls.members.filter { it.visibility != "private" && it.receiverText == null }
        // A plain class exposes its constructor(s) so `new Foo(args)` resolves from Java; an
        // object/enum/interface/annotation is never instantiated with `new`.
        val plainClass = !cls.isObject && !cls.isEnum && !cls.isInterface && !cls.isAnnotation
        val methods = ArrayList<SyntheticMethod>()
        val fields = ArrayList<SyntheticField>()
        if (plainClass) cls.constructors.filter { it.visibility != "private" }.forEach { ctor ->
            overloadVariants(ctor, types).forEach { ps ->
                methods += SyntheticMethod(cls.simpleName, parameters = ps, modifiers = ctorMods(ctor), isConstructor = true)
            }
        }
        for (m in members) {
            // In an `object`, a `@JvmStatic`/`@JvmField` member is a Java static; a companion's members are
            // instance members of the nested `Companion` (their statics are promoted onto the owner below).
            val static = !cls.isCompanion && cls.isObject && (m.jvmStatic || (!m.isFunction && m.jvmField))
            val (ms, fs) = emitMember(m, types, static); methods += ms; fields += fs
        }
        if (cls.isObject && !cls.isCompanion) fields += field("INSTANCE", cls.fqn)
        if (cls.isEnum) cls.enumEntries.forEach { fields += field(it, cls.fqn) } // `Color.RED` etc.
        cls.companionObjectName?.let { comp ->
            fields += field(comp, "${cls.fqn}.$comp")
            // `@JvmStatic`/`@JvmField` companion members are reached from Java as statics on the OWNER class.
            model.classByFqn["${cls.fqn}.$comp"]?.members
                ?.filter { it.visibility != "private" && it.receiverText == null && (it.jvmStatic || it.jvmField) }
                ?.forEach { m -> val (ms, fs) = emitMember(m, types, static = true); methods += ms; fields += fs }
        }
        val children = model.classByFqn.values
            .filter { it.fqn != cls.fqn && it.fqn.substringBeforeLast('.') == cls.fqn }
            .map { classFor(it, model, types, nested = true) }
        val kind = when {
            cls.isAnnotation -> SyntheticTypeKind.ANNOTATION
            cls.isInterface -> SyntheticTypeKind.INTERFACE
            else -> SyntheticTypeKind.CLASS // an enum is rendered as a class (entries above); an object is a final class
        }
        val mods = buildSet {
            add(SyntheticModifier.PUBLIC)
            if (nested) add(SyntheticModifier.STATIC) // referenceable as `Outer.Inner` without an instance
            if (cls.isObject) add(SyntheticModifier.FINAL)
        }
        return SyntheticClass(
            fqName = cls.fqn,
            kind = kind,
            modifiers = mods,
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
    private fun emitMember(c: RawCallable, types: TypeMapper, static: Boolean): Pair<List<SyntheticMethod>, List<SyntheticField>> {
        if (!c.isFunction && c.jvmField) {
            val type = c.returnText?.let { types.javaType(it, c.ctx) } ?: "java.lang.Object"
            val mods = buildSet {
                add(SyntheticModifier.PUBLIC); if (static) add(SyntheticModifier.STATIC); if (!c.isVar) add(SyntheticModifier.FINAL)
            }
            return emptyList<SyntheticMethod>() to listOf(SyntheticField(c.name, type, mods))
        }
        val mods = if (static) setOf(SyntheticModifier.PUBLIC, SyntheticModifier.STATIC) else setOf(SyntheticModifier.PUBLIC)
        if (c.isFunction) {
            val ret = functionReturn(c, types)
            val name = c.jvmName ?: c.name
            return overloadVariants(c, types).map { SyntheticMethod(name, ret, it, mods) } to emptyList()
        }
        val cap = c.name.replaceFirstChar { it.uppercaseChar() }
        val type = c.returnText?.let { types.javaType(it, c.ctx) } ?: "java.lang.Object"
        val accessors = ArrayList<SyntheticMethod>()
        accessors += SyntheticMethod("get$cap", type, receiverParam(c, types), mods)
        if (c.isVar) accessors += SyntheticMethod("set$cap", "void", receiverParam(c, types) + SyntheticParam("value", type), mods)
        return accessors to emptyList()
    }

    /** A function's/constructor's parameter lists: just the full list, unless `@JvmOverloads` — then also the
     *  shorter lists Kotlin generates by dropping trailing defaulted value parameters (one at a time). */
    private fun overloadVariants(c: RawCallable, types: TypeMapper): List<List<SyntheticParam>> {
        val receiver = receiverParam(c, types)
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
    private fun receiverParam(c: RawCallable, types: TypeMapper): List<SyntheticParam> =
        if (c.receiverText != null) listOf(SyntheticParam("\$receiver", types.javaType(c.receiverText, c.ctx))) else emptyList()

    /** A function's Java return type: declared → mapped; expression body → `Object` (inferred); else `void`. */
    private fun functionReturn(c: RawCallable, types: TypeMapper): String = when {
        c.returnText != null -> types.javaType(c.returnText, c.ctx)
        c.initializerText != null -> "java.lang.Object"
        else -> "void"
    }

    /**
     * Maps a Kotlin type *text* (resolved against its declaration's [FileContext]) to a Java type the
     * synthetic source compiles against. Generics are erased; an unresolved or functional type → `Object`.
     */
    private class TypeMapper(private val model: ModuleSourceModel) {
        fun javaType(typeText: String?, ctx: FileContext): String {
            val raw = typeText?.trim().orEmpty()
            if (raw.isEmpty()) return OBJECT
            val nullable = raw.endsWith("?")
            val t = (if (nullable) raw.dropLast(1) else raw).trim()
            if (t.startsWith("(") || "->" in t) return OBJECT // function type → opaque
            val head = t.substringBefore('<').trim()
            ARRAYS[head]?.let { return it }
            if (head == "Array") return "$OBJECT[]"
            val fqn = WELL_KNOWN[head] ?: resolve(head, ctx) ?: return OBJECT
            if (fqn == "kotlin.Unit") return "void"
            PRIMITIVE[fqn]?.let { return if (nullable) Builtins.KOTLIN_TO_JAVA[fqn] ?: OBJECT else it }
            return Builtins.KOTLIN_TO_JAVA[fqn] ?: fqn
        }

        /** Resolve a simple/qualified type name to a Kotlin/Java FQN via the module model + the file's imports. */
        private fun resolve(head: String, ctx: FileContext): String? {
            if (head in model.classByFqn) return head
            val samePkg = if (ctx.packageName.isEmpty()) head else "${ctx.packageName}.$head"
            if (samePkg in model.classByFqn) return samePkg
            ctx.imports.firstOrNull { !it.isStar && it.simpleName == head }?.let { return it.fqn }
            return null
        }
    }

    private companion object {
        const val OBJECT = "java.lang.Object"

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
