package dev.ide.lang.kotlin.symbols

/**
 * The editor view onto the members the kotlinx.serialization compiler plugin generates for a `@Serializable`
 * class. The plugin synthesizes a companion-object `serializer(): KSerializer<T>` (and a `T.$serializer`
 * backing object) that the parse-only source model never sees, so `Foo.serializer()` used to false-flag
 * `kt.unresolved` and be missing from completion. This provider contributes that `serializer()` as an ordinary
 * static (companion-accessible) member so it completes on `Foo.`, resolves (a `Foo.serializer().descriptor`
 * chain sees `KSerializer`'s members), and satisfies the unresolved-reference check.
 *
 * Gated hard so it only fires where the plugin actually runs: the class carries `@Serializable`
 * ([RawClass.annotationNames]) AND the serialization runtime is on the module's classpath
 * ([KotlinSyntheticMemberProvider.Context.hasType] on `kotlinx.serialization.KSerializer`). A binary
 * `@Serializable` class was already compiled WITH the plugin, so its real `serializer()`/`$serializer` are in
 * bytecode and enumerate through the normal classpath path — this provider is for PROJECT SOURCE classes only,
 * which is exactly what the service consults it for.
 *
 * The generic case (`@Serializable class Box<T>`, whose real `serializer` takes a `KSerializer<T>` per type
 * parameter) is contributed as the same nullary `serializer()` — enough to resolve the reference and complete
 * the name; the argument shape isn't modeled (a rarer pattern, and the unresolved check keys on the name).
 */
object SerializationSyntheticMembers : KotlinSyntheticMemberProvider {

    /** The kotlinx.serialization marker annotation's simple name. */
    private const val SERIALIZABLE_ANNOTATION = "Serializable"

    /** The serializer interface — its presence on the classpath is the runtime gate, and it is `serializer()`'s
     *  return type. */
    private const val KSERIALIZER_FQN = "kotlinx.serialization.KSerializer"

    override fun staticMembers(cls: RawClass, ctx: KotlinSyntheticMemberProvider.Context): List<RawCallable> {
        if (SERIALIZABLE_ANNOTATION !in cls.annotationNames) return emptyList()
        if (!ctx.hasType(KSERIALIZER_FQN)) return emptyList()
        return listOf(
            RawCallable(
                name = "serializer",
                isFunction = true,
                receiverText = null,
                returnText = "$KSERIALIZER_FQN<${cls.fqn}>",
                initializerText = null,
                paramTexts = emptyList(),
                ctx = cls.ctx,
                node = cls.node, // navigate to the class declaration (no synthetic node exists)
            )
        )
    }
}
