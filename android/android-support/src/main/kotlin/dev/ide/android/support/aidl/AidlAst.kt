package dev.ide.android.support.aidl

/**
 * The AIDL syntax tree: the parsed shape of one `.aidl` file, produced by [AidlParser] and consumed by
 * [AidlJavaGenerator].
 *
 * AIDL is a small IDL: a file declares a package, a list of imports, and exactly one type (an interface, a
 * parcelable, an enum or a union). The one exception is a preprocessed file, the SDK's
 * `platforms/android-NN/framework.aidl`, which is a flat list of `parcelable a.b.C;` / `interface a.b.IC;`
 * lines with no package statement. [AidlFile.declarations] is therefore a list, not a single value.
 *
 * The tree keeps source positions ([line]/[column]) on everything a diagnostic can point at, and keeps
 * constant/default expressions as their **raw source text**: AIDL expression syntax is a subset of Java's,
 * so the generator can emit them through unchanged instead of building an expression evaluator.
 */
data class AidlFile(
    /** The file this was parsed from, for diagnostics. Empty for in-memory sources. */
    val path: String,
    /** The `package` statement, or empty for a preprocessed file. */
    val packageName: String,
    /** Fully-qualified names from `import` statements, in source order. */
    val imports: List<String>,
    val declarations: List<AidlDecl>,
)

/** Position of a construct in its source file: 1-based [line] and [column]. */
data class AidlPos(val line: Int, val column: Int) {
    companion object { val NONE = AidlPos(0, 0) }
}

/** An `@Annotation` or `@Annotation(k = v, …)`. Argument values keep their raw source text. */
data class AidlAnnotation(val name: String, val args: Map<String, String> = emptyMap()) {
    /** `@nullable` is spelled lowercase in AIDL; accept either casing the way the reference compiler does. */
    val isNullable: Boolean get() = name.equals("nullable", ignoreCase = true)
}

/**
 * A type as written: a (possibly qualified) [name], optional generic [typeArgs], and a number of `[]`
 * suffixes in [arrayDims]. Resolution of [name] to a builtin / parcelable / interface is [AidlTypeTable]'s
 * job, not the parser's; the parser records only what the source said.
 */
data class AidlTypeRef(
    val name: String,
    val typeArgs: List<AidlTypeRef> = emptyList(),
    val arrayDims: Int = 0,
    val annotations: List<AidlAnnotation> = emptyList(),
    val pos: AidlPos = AidlPos.NONE,
) {
    val isArray: Boolean get() = arrayDims > 0
    val isNullable: Boolean get() = annotations.any { it.isNullable }

    /** The type as written, for diagnostics (`java.util.List<Foo>[]`). */
    override fun toString(): String = buildString {
        append(name)
        if (typeArgs.isNotEmpty()) append(typeArgs.joinToString(", ", "<", ">"))
        repeat(arrayDims) { append("[]") }
    }
}

/** A top-level declaration. [name] is the simple name, except in a preprocessed file where it is qualified. */
sealed interface AidlDecl {
    val name: String
    val annotations: List<AidlAnnotation>
    val pos: AidlPos
    /** The leading doc comment, if any, copied onto the generated Java so hover/docs survive. */
    val doc: String?
}

/** `interface IFoo { … }`: the only declaration that generates a Binder stub/proxy. */
data class AidlInterface(
    override val name: String,
    /** `oneway interface IFoo`: every method is implicitly oneway. */
    val oneway: Boolean = false,
    val methods: List<AidlMethod> = emptyList(),
    val constants: List<AidlConstant> = emptyList(),
    /** `interface a.b.IFoo;` with no body: a name assertion (as in `framework.aidl`), so nothing is generated. */
    val forwardDeclaration: Boolean = false,
    override val annotations: List<AidlAnnotation> = emptyList(),
    override val pos: AidlPos = AidlPos.NONE,
    override val doc: String? = null,
) : AidlDecl

/**
 * `parcelable Foo;` is a forward declaration: it tells AIDL that a hand-written Java class named `Foo`
 * implements `android.os.Parcelable`, so other `.aidl` files may reference it. Generates no code.
 */
data class AidlParcelableDecl(
    override val name: String,
    /** `parcelable Foo cpp_header "foo.h";`: recorded and ignored; the Java backend has no use for it. */
    val cppHeader: String? = null,
    override val annotations: List<AidlAnnotation> = emptyList(),
    override val pos: AidlPos = AidlPos.NONE,
    override val doc: String? = null,
) : AidlDecl

/** `parcelable Foo { int a; @nullable String b = "x"; }`: a structured parcelable; generates a Java class. */
data class AidlStructuredParcelable(
    override val name: String,
    val fields: List<AidlField> = emptyList(),
    val constants: List<AidlConstant> = emptyList(),
    override val annotations: List<AidlAnnotation> = emptyList(),
    override val pos: AidlPos = AidlPos.NONE,
    override val doc: String? = null,
) : AidlDecl

/** `enum Foo { A, B = 3 }`: generates a Java class of `static final` constants over a backing integral type. */
data class AidlEnum(
    override val name: String,
    /** `@Backing(type="byte")`; defaults to `byte`, as the reference compiler does. */
    val backingType: String = "byte",
    val enumerators: List<AidlEnumerator> = emptyList(),
    override val annotations: List<AidlAnnotation> = emptyList(),
    override val pos: AidlPos = AidlPos.NONE,
    override val doc: String? = null,
) : AidlDecl

/** `union Foo { int a; String b; }`: parsed so the error is "not supported", not a syntax error. */
data class AidlUnion(
    override val name: String,
    val fields: List<AidlField> = emptyList(),
    override val annotations: List<AidlAnnotation> = emptyList(),
    override val pos: AidlPos = AidlPos.NONE,
    override val doc: String? = null,
) : AidlDecl

/** One method of an interface. */
data class AidlMethod(
    val name: String,
    val returnType: AidlTypeRef,
    val params: List<AidlParam> = emptyList(),
    /** True when the method (or its interface) is `oneway`: no reply parcel, `FLAG_ONEWAY` transaction. */
    val oneway: Boolean = false,
    /** An explicit `= N` transaction id; null means "position in the interface". */
    val transactionId: Int? = null,
    val annotations: List<AidlAnnotation> = emptyList(),
    val pos: AidlPos = AidlPos.NONE,
    val doc: String? = null,
)

/** One parameter. [direction] is null when the source omitted it; the generator then applies the per-type default. */
data class AidlParam(
    val name: String,
    val type: AidlTypeRef,
    val direction: AidlDirection? = null,
    val annotations: List<AidlAnnotation> = emptyList(),
    val pos: AidlPos = AidlPos.NONE,
)

enum class AidlDirection { IN, OUT, INOUT }

/** `const int FOO = 1;`. [value] is raw source text. */
data class AidlConstant(
    val name: String,
    val type: AidlTypeRef,
    val value: String,
    val pos: AidlPos = AidlPos.NONE,
    val doc: String? = null,
)

/** A field of a structured parcelable / union. [defaultValue] is raw source text when present. */
data class AidlField(
    val name: String,
    val type: AidlTypeRef,
    val defaultValue: String? = null,
    val annotations: List<AidlAnnotation> = emptyList(),
    val pos: AidlPos = AidlPos.NONE,
    val doc: String? = null,
)

/** One `enum` member; [value] is raw source text when the source assigned one. */
data class AidlEnumerator(val name: String, val value: String? = null, val pos: AidlPos = AidlPos.NONE, val doc: String? = null)

/** A parse/resolve/generate problem, positioned in the source. */
data class AidlDiagnostic(
    val severity: AidlSeverity,
    val message: String,
    val file: String = "",
    val pos: AidlPos = AidlPos.NONE,
) {
    override fun toString(): String =
        "$file:${pos.line}:${pos.column}: ${severity.name.lowercase()}: $message"
}

enum class AidlSeverity { ERROR, WARNING }
