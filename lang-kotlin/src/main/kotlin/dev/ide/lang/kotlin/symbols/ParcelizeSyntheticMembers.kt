package dev.ide.lang.kotlin.symbols

/**
 * The editor view onto the members the kotlin-parcelize compiler plugin generates for a `@Parcelize` class.
 *
 * The plugin synthesizes a `@JvmField val CREATOR: Parcelable.Creator<T>` (a static, accessed as `Foo.CREATOR`)
 * plus the `Parcelable` implementation. The `writeToParcel`/`describeContents` methods are DECLARED on the
 * `Parcelable` supertype, so they already surface through the normal supertype path — but `CREATOR` is neither
 * declared in source nor inherited, so `Foo.CREATOR` used to false-flag `kt.unresolved` and was missing from
 * completion. This provider contributes `CREATOR` as an ordinary static (type-accessible) member, so it
 * completes on `Foo.`, resolves (a `Foo.CREATOR.createFromParcel(...)` chain sees `Parcelable.Creator`'s
 * members), and satisfies the unresolved-reference check — mirroring [SerializationSyntheticMembers].
 *
 * Gated hard so it only fires where the plugin actually runs: the class carries `@Parcelize`
 * ([RawClass.annotationNames], matched by simple name so both `kotlinx.parcelize.Parcelize` and the legacy
 * `kotlinx.android.parcel.Parcelize` count) AND `android.os.Parcelable` is on the module's classpath
 * ([KotlinSyntheticMemberProvider.Context.hasType]) — which is also `CREATOR`'s element type. A binary
 * `@Parcelize` class was already compiled WITH the plugin, so its real `CREATOR` is in bytecode and enumerates
 * through the normal classpath path — this provider is for PROJECT SOURCE classes only, which is exactly what
 * the service consults it for.
 */
object ParcelizeSyntheticMembers : KotlinSyntheticMemberProvider {

    /** The `@Parcelize` marker annotation's simple name (both the modern and legacy packages use it). */
    private const val PARCELIZE_ANNOTATION = "Parcelize"

    /** The Parcelable type — its presence on the classpath is the runtime gate, and it is `CREATOR`'s element type. */
    private const val PARCELABLE_FQN = "android.os.Parcelable"

    override fun staticMembers(cls: RawClass, ctx: KotlinSyntheticMemberProvider.Context): List<RawCallable> {
        if (PARCELIZE_ANNOTATION !in cls.annotationNames) return emptyList()
        if (!ctx.hasType(PARCELABLE_FQN)) return emptyList()
        return listOf(
            RawCallable(
                name = "CREATOR",
                isFunction = false,
                receiverText = null,
                returnText = "$PARCELABLE_FQN.Creator<${cls.fqn}>",
                initializerText = null,
                paramTexts = emptyList(),
                ctx = cls.ctx,
                node = cls.node, // navigate to the class declaration (no synthetic node exists)
                jvmField = true,
            ),
        )
    }
}
