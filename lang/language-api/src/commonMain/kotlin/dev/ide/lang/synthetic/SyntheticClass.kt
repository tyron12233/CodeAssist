package dev.ide.lang.synthetic

/**
 * "Light" (synthetic) classes — types contributed to resolution **without real source or bytecode** on
 * disk. The motivating case is the Android `R` class (generated from resources at build time, but needed
 * for completion/analysis *before* a build), with the same shape serving any generated-code stand-in:
 * `BuildConfig`, ViewBinding/DataBinding classes, Dagger components, Room/Lombok output, AIDL stubs, etc.
 *
 * A provider describes a class as **structure** (this model), not raw syntax; the language backend renders
 * it however it needs (the JDT backend emits Java source into its name-environment overlay), so a synthetic
 * type resolves uniformly for **completion, analysis, and go-to-definition** — exactly like a real type.
 *
 * The model is plain data and lives in common code; the provider that contributes it ([SyntheticClassProvider])
 * is asked about a module of a workspace, so it stays on the JVM with the project model.
 */
data class SyntheticClass(
    /** Fully-qualified name of the **top-level** class (e.g. `com.example.app.R`). Nested types go in [nestedClasses]. */
    val fqName: String,
    val kind: SyntheticTypeKind = SyntheticTypeKind.CLASS,
    val modifiers: Set<SyntheticModifier> = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.FINAL),
    /** Fully-qualified superclass; null ⇒ `java.lang.Object`. */
    val superClass: String? = null,
    val interfaces: List<String> = emptyList(),
    val fields: List<SyntheticField> = emptyList(),
    val methods: List<SyntheticMethod> = emptyList(),
    val nestedClasses: List<SyntheticClass> = emptyList(),
    /** Optional Javadoc shown in completion/hover. */
    val doc: String? = null,
)

enum class SyntheticTypeKind { CLASS, INTERFACE, ENUM, ANNOTATION }

enum class SyntheticModifier { PUBLIC, PROTECTED, PRIVATE, STATIC, FINAL, ABSTRACT }

/**
 * A field. [type] is a fully-qualified (or primitive) type name. [constant] is an optional initializer
 * expression; when omitted the backend supplies a type-appropriate default so a `final` field still
 * compiles (e.g. `0` for `int`). For `R` every field is `public static final int … = 0`.
 */
data class SyntheticField(
    val name: String,
    val type: String = "int",
    val modifiers: Set<SyntheticModifier> = setOf(SyntheticModifier.PUBLIC, SyntheticModifier.STATIC, SyntheticModifier.FINAL),
    val constant: String? = null,
    val doc: String? = null,
)

data class SyntheticMethod(
    val name: String,
    val returnType: String = "void",
    val parameters: List<SyntheticParam> = emptyList(),
    val modifiers: Set<SyntheticModifier> = setOf(SyntheticModifier.PUBLIC),
    val doc: String? = null,
    /** A constructor: rendered with the enclosing type's name and no return type ([name]/[returnType] are
     *  ignored). Lets a provider express how a type is instantiated — e.g. a Kotlin class's primary constructor. */
    val isConstructor: Boolean = false,
)

data class SyntheticParam(val name: String, val type: String)

