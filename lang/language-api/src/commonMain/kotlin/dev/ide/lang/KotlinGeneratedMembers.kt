package dev.ide.lang

import dev.ide.platform.ExtensionPoint

/**
 * Teaches the Kotlin editor the members a Kotlin COMPILER PLUGIN generates, so code that uses them completes,
 * resolves and is not flagged as unresolved.
 *
 * The editor builds its symbols from the source, without running compiler plugins, so a plugin-generated member
 * (kotlinx.serialization's `Foo.serializer()`, Parcelize's `Foo.CREATOR`, a builder a plugin adds to annotated
 * classes) does not exist for it: a reference to one is a false "unresolved reference" and it never completes.
 * The IDE ships providers for kotlinx.serialization and Parcelize; a plugin that ships, or supports, another
 * compiler plugin (`dev.ide.build.KOTLIN_COMPILER_PLUGIN_EP` runs it in the build) registers one here for
 * the editor half.
 *
 * A provider is asked about every project-source class the editor enumerates, so it must be cheap and gate
 * itself hard: its marker annotation on the class ([KotlinSourceClass.annotations]) and its runtime on the
 * classpath ([KotlinGeneratedMemberContext.hasType]). A class it does not target gets an empty list. Classes
 * from libraries are not asked: they were compiled with the plugin, so their real members are in the bytecode.
 */
interface KotlinGeneratedMemberProvider {
    /** Stable, for diagnostics. */
    val id: String

    /**
     * Members reached through the TYPE (`Foo.CREATOR`, `Foo.serializer()`): offered beside the class's companion
     * members. Empty when [cls] is not a target.
     */
    fun staticMembers(cls: KotlinSourceClass, ctx: KotlinGeneratedMemberContext): List<KotlinGeneratedMember> = emptyList()

    /** Members reached through an INSTANCE, added to the class's own. Empty when [cls] is not a target. */
    fun instanceMembers(cls: KotlinSourceClass, ctx: KotlinGeneratedMemberContext): List<KotlinGeneratedMember> = emptyList()
}

/** Contributed on this point. Since SPI 3.4.0. */
val KOTLIN_GENERATED_MEMBER_EP = ExtensionPoint<KotlinGeneratedMemberProvider>("platform.kotlinGeneratedMembers")

/** What a provider can ask of the module the class is in. */
interface KotlinGeneratedMemberContext {
    /** True when [fqName] is a type this module can see (its classpath, its sources, or a built-in). */
    fun hasType(fqName: String): Boolean
}

/**
 * A class from the project's own Kotlin source, as written: what a provider decides on. Implemented by the IDE;
 * an interface so it can gain facts without a break.
 */
interface KotlinSourceClass {
    val fqName: String
    val simpleName: String

    /** The annotations on the class by the name they are written with (`Serializable`, `Parcelize`). */
    val annotations: List<String>

    /** The same annotations resolved through the file's imports, where that was possible (`kotlinx.serialization.Serializable`). */
    val annotationFqNames: List<String>

    /** Supertypes as written (`Parcelable`, `List<String>`). */
    val supertypes: List<String>
    val typeParameters: List<String>
    val isObject: Boolean
    val isCompanion: Boolean
    val isInterface: Boolean
    val isEnum: Boolean

    /** The class's own declared properties, by name and declared type as written (null when inferred). */
    val properties: List<Pair<String, String?>>

    /** The primary constructor's parameters, by name and type as written; empty without one. */
    val constructorParameters: List<Pair<String, String?>>
}

/**
 * A member a compiler plugin generates, described as source would declare it. Types are Kotlin type TEXT
 * (`kotlinx.serialization.KSerializer<com.example.User>`), fully qualified where the plugin's file would not
 * import them, since nothing resolves them against the user's imports. Built with [function] or [property].
 */
class KotlinGeneratedMember private constructor(
    val name: String,
    val isFunction: Boolean,
    /** Function parameters by name and type text; empty for a property. */
    val parameters: List<Pair<String, String>>,
    /** A function's return type, or a property's type. */
    val type: String,
    /** A `var` property. */
    val isMutable: Boolean,
) {
    companion object {
        fun function(name: String, parameters: List<Pair<String, String>>, returnType: String): KotlinGeneratedMember =
            KotlinGeneratedMember(name, isFunction = true, parameters = parameters, type = returnType, isMutable = false)

        fun property(name: String, type: String, mutable: Boolean = false): KotlinGeneratedMember =
            KotlinGeneratedMember(name, isFunction = false, parameters = emptyList(), type = type, isMutable = mutable)
    }
}
