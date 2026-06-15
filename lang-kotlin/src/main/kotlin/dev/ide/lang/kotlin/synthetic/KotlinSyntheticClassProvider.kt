package dev.ide.lang.kotlin.synthetic

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
import dev.ide.model.ContentRole

/**
 * Makes a module's Kotlin source visible to its Java code (and to JDT-backed completion/analysis) before
 * a build, the same way android-support's R/BuildConfig providers expose generated code. Kotlin compiles each
 * file's top-level functions/properties into a file-facade class (`Foo.kt` → `FooKt`) and its `class`/
 * `object` declarations into normal classes, but none of that bytecode is on the editor classpath until a
 * build, so a Java file referencing `FooKt.helloWorld()` or a Kotlin class can't resolve it. This provider
 * reconstructs those Java-visible shapes from the parsed Kotlin source (no resolution) so they resolve like
 * any other type.
 *
 * Shapes are approximate: types that can't be mapped confidently become `java.lang.Object` (so the emitted
 * Java always compiles), `private` declarations are dropped, and inferred return types fall back to `Object`
 * (or `void` for a Unit-returning block body). Enough for recognition, completion, and go-to-definition.
 */
class KotlinSyntheticClassProvider : SyntheticClassProvider {

    override fun classesFor(context: SyntheticClassContext): List<SyntheticClass> {
        val roots = context.module.sourceSets
            .flatMap { it.contentRoots }
            .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
            .map { it.dir }
        if (roots.isEmpty()) return emptyList()
        val model = runCatching { SourceIndexBuilder.build(roots) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SyntheticClass>()
        for (file in model.files) {
            facadeClass(file)?.let { out += it }
            for (cls in file.classes) out += classFor(cls)
        }
        return out
    }

    /** The `<File>Kt` file facade: top-level functions/properties as `public static` members. Null if empty. */
    private fun facadeClass(file: SourceFile): SyntheticClass? {
        val members = (file.topLevel + file.extensions).filter { it.visibility != "private" }
        if (members.isEmpty()) return null
        val base = file.ctx.path.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".kts").removeSuffix(".kt")
        if (base.isEmpty()) return null
        val simple = base.replaceFirstChar { it.uppercaseChar() } + "Kt"
        val fqn = if (file.ctx.packageName.isEmpty()) simple else "${file.ctx.packageName}.$simple"
        val methods = members.map { staticMember(it) }
        return SyntheticClass(
            fqName = fqn,
            modifiers = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.FINAL),
            methods = methods,
            doc = "Kotlin file facade (synthetic)",
        )
    }

    /** A top-level `class`/`object` as a normal class; an `object` also gets the `INSTANCE` field Java sees. */
    private fun classFor(cls: RawClass): SyntheticClass {
        val members = cls.members.filter { it.visibility != "private" }
        val methods = members.filter { it.isFunction }.map { instanceMember(it) } +
            members.filterNot { it.isFunction }.map { getter(it, static = false) }
        val fields = if (cls.isObject)
            listOf(SyntheticField("INSTANCE", cls.fqn, setOf(SyntheticModifier.PUBLIC, SyntheticModifier.STATIC, SyntheticModifier.FINAL)))
        else emptyList()
        return SyntheticClass(
            fqName = cls.fqn,
            modifiers = setOf(SyntheticModifier.PUBLIC),
            fields = fields,
            methods = methods,
            doc = if (cls.isObject) "Kotlin object (synthetic)" else "Kotlin class (synthetic)",
        )
    }

    private fun staticMember(c: RawCallable): SyntheticMethod =
        if (c.isFunction) staticMethod(c) else getter(c, static = true)

    private fun staticMethod(c: RawCallable): SyntheticMethod = SyntheticMethod(
        name = c.name,
        returnType = functionReturn(c),
        parameters = params(c),
        modifiers = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.STATIC),
    )

    private fun instanceMember(c: RawCallable): SyntheticMethod = SyntheticMethod(
        name = c.name,
        returnType = functionReturn(c),
        parameters = params(c),
        modifiers = setOf(SyntheticModifier.PUBLIC),
    )

    /** A property as its JVM getter `get<Name>()` (the shape Java sees for a Kotlin property). */
    private fun getter(c: RawCallable, static: Boolean): SyntheticMethod {
        val mods = if (static) setOf(SyntheticModifier.PUBLIC, SyntheticModifier.STATIC) else setOf(SyntheticModifier.PUBLIC)
        return SyntheticMethod(
            name = "get" + c.name.replaceFirstChar { it.uppercaseChar() },
            returnType = javaType(c.returnText) ?: "java.lang.Object",
            parameters = receiverParam(c),
            modifiers = mods,
        )
    }

    private fun params(c: RawCallable): List<SyntheticParam> =
        receiverParam(c) + c.paramTexts.mapIndexed { i, (n, t) ->
            SyntheticParam(n.ifEmpty { "p$i" }, javaType(t) ?: "java.lang.Object")
        }

    /** An extension's receiver becomes a leading Java parameter (how Kotlin compiles `T.foo()`). */
    private fun receiverParam(c: RawCallable): List<SyntheticParam> =
        if (c.receiverText != null) listOf(SyntheticParam("\$receiver", javaType(c.receiverText) ?: "java.lang.Object")) else emptyList()

    /** A function's Java return type: declared → mapped; expression body → `Object` (inferred); else `void`. */
    private fun functionReturn(c: RawCallable): String = when {
        c.returnText != null -> javaType(c.returnText) ?: "java.lang.Object"
        c.initializerText != null -> "java.lang.Object"
        else -> "void"
    }

    /**
     * Map a Kotlin type text to a Java type the synthetic source can compile against. Primitives map directly;
     * anything uncertain (generics, library/source types, nullables) becomes `java.lang.Object` so the emitted
     * unit always compiles. Returns null for a null input (caller picks the default).
     */
    private fun javaType(kt: String?): String? {
        if (kt == null) return null
        val t = kt.trim().removeSuffix("?").trim()
        return when (t) {
            "Unit", "" -> "void"
            "Int" -> "int"
            "Long" -> "long"
            "Short" -> "short"
            "Byte" -> "byte"
            "Boolean" -> "boolean"
            "Char" -> "char"
            "Float" -> "float"
            "Double" -> "double"
            "String" -> "java.lang.String"
            else -> "java.lang.Object"
        }
    }
}
