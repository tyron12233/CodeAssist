package dev.ide.lang.synthetic

import dev.ide.model.Module
import dev.ide.model.Workspace
import dev.ide.platform.ExtensionPoint

/**
 * "Light" (synthetic) classes — types contributed to resolution **without real source or bytecode** on
 * disk. The motivating case is the Android `R` class (generated from resources at build time, but needed
 * for completion/analysis *before* a build), with the same shape serving any generated-code stand-in:
 * `BuildConfig`, ViewBinding/DataBinding classes, Dagger components, Room/Lombok output, AIDL stubs, etc.
 *
 * A provider describes a class as **structure** (this model), not raw syntax; the language backend renders
 * it however it needs (the JDT backend emits Java source into its name-environment overlay), so a synthetic
 * type resolves uniformly for **completion, analysis, and go-to-definition** — exactly like a real type.
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
)

data class SyntheticParam(val name: String, val type: String)

/** What a provider is asked about: one [module] of the [workspace]. Providers read the model themselves
 *  (facets, source/resource roots, dependencies) and return the synthetic classes that module should see. */
interface SyntheticClassContext {
    val module: Module
    val workspace: Workspace
}

/**
 * Contributes synthetic classes for a module (registered through [SYNTHETIC_CLASS_EP]). Return the classes
 * the [SyntheticClassContext.module] should resolve — empty if the provider doesn't apply (e.g. the Android
 * `R` provider returns nothing for a non-Android module). Called per module; must be cheap or cached by the
 * host (the JDT host caches the rendered result and refreshes it on file changes).
 */
fun interface SyntheticClassProvider {
    fun classesFor(context: SyntheticClassContext): List<SyntheticClass>
}

/** Plugins contribute synthetic ("light") classes here — e.g. android-support's `R`/`BuildConfig`. */
val SYNTHETIC_CLASS_EP = ExtensionPoint<SyntheticClassProvider>("platform.syntheticClass")
