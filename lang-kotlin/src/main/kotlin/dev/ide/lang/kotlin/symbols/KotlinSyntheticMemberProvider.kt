package dev.ide.lang.kotlin.symbols

import dev.ide.platform.ExtensionPoint

/**
 * A source-level view onto members a Kotlin COMPILER PLUGIN generates but that never appear in the parse-only
 * source model. The editor's symbol model ([KotlinSymbolService]) parses `.kt` to PSI and builds its OWN
 * symbols — it never runs the compiler plugins — so a plugin's generated members (kotlinx.serialization's
 * `Foo.Companion.serializer()`, Parcelize's `CREATOR`/`describeContents()`, …) are invisible: a reference to
 * one false-flags `kt.unresolved` and it is missing from completion.
 *
 * A provider bridges that gap. Given a [RawClass] from the source model it contributes the members the plugin
 * WOULD generate, expressed as ordinary [RawCallable]s, and the service folds them into member enumeration
 * exactly like real members — so they complete, resolve (a chain off them types), and satisfy the
 * unresolved-reference check, all through the one seam that already surfaces companion/instance members.
 *
 * Providers are contributed on [KOTLIN_SYNTHETIC_MEMBER_EP] (the host falls back to
 * [BUILTIN_KOTLIN_SYNTHETIC_MEMBER_PROVIDERS] when nothing is registered), so adding editor support for a new
 * plugin is a registration, not a host edit. Each provider is consulted per SOURCE class that is enumerated, so
 * it MUST be cheap and gate itself hard: its marker annotation on the class ([RawClass.annotationNames]) AND its
 * runtime actually present on the classpath ([Context.hasType]). A provider that isn't targeting [cls] returns
 * an empty list.
 */
interface KotlinSyntheticMemberProvider {
    /**
     * Members reachable through the TYPE itself — static / companion-object members surfaced at a `Foo.`
     * reference (kotlinx.serialization's `Foo.serializer()`, Parcelize's `Foo.CREATOR`). Offered alongside the
     * class's real companion members. Empty when [cls] is not a target of this plugin.
     */
    fun staticMembers(cls: RawClass, ctx: Context): List<RawCallable> = emptyList()

    /**
     * Members reachable through an INSTANCE — added to the class's own members (Parcelize's
     * `writeToParcel(...)`/`describeContents()`). Empty when [cls] is not a target of this plugin.
     */
    fun instanceMembers(cls: RawClass, ctx: Context): List<RawCallable> = emptyList()

    /** The gate a provider uses to confirm its runtime is present before synthesizing anything. */
    interface Context {
        /** True when [fqn] names a real type available to this module (classpath binary, project source, or a
         *  built-in) — used to require a plugin's runtime (`kotlinx.serialization.KSerializer`) before it fires. */
        fun hasType(fqn: String): Boolean
    }
}

/** Editor synthetic-member providers surface a compiler plugin's generated members. See [KotlinSyntheticMemberProvider]. */
val KOTLIN_SYNTHETIC_MEMBER_EP = ExtensionPoint<KotlinSyntheticMemberProvider>("platform.kotlinSyntheticMember")

/** The built-in providers, used unless a host contributes to [KOTLIN_SYNTHETIC_MEMBER_EP] (direct/test wiring). */
val BUILTIN_KOTLIN_SYNTHETIC_MEMBER_PROVIDERS: List<KotlinSyntheticMemberProvider> =
    listOf(SerializationSyntheticMembers)
