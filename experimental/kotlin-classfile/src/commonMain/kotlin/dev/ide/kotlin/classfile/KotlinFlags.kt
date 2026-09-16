package dev.ide.kotlin.classfile

/** How visible a declaration is. Numbers are the protobuf enum's, not this enum's ordinals. */
enum class KotlinVisibility(val number: Int) {
    INTERNAL(0), PRIVATE(1), PROTECTED(2), PUBLIC(3), PRIVATE_TO_THIS(4), LOCAL(5);

    companion object {
        fun of(number: Int): KotlinVisibility? = entries.firstOrNull { it.number == number }
    }
}

/** Whether a declaration can be overridden, and whether it has a body. */
enum class KotlinModality(val number: Int) {
    FINAL(0), OPEN(1), ABSTRACT(2), SEALED(3);

    companion object {
        fun of(number: Int): KotlinModality? = entries.firstOrNull { it.number == number }
    }
}

/** What a classifier is. `object` and `companion object` are separate kinds, not modifiers on a class. */
enum class KotlinClassKind(val number: Int) {
    CLASS(0), INTERFACE(1), ENUM_CLASS(2), ENUM_ENTRY(3), ANNOTATION_CLASS(4), OBJECT(5), COMPANION_OBJECT(6);

    companion object {
        fun of(number: Int): KotlinClassKind? = entries.firstOrNull { it.number == number }
    }
}

/**
 * Where a member came from.
 *
 * The one that matters to an index is [FAKE_OVERRIDE]: an inherited member the compiler recorded on the
 * subclass. Listing those as if they were declared here duplicates every inherited member in a completion
 * list, and dropping them loses members that really are callable on the type.
 */
enum class KotlinMemberKind(val number: Int) {
    DECLARATION(0), FAKE_OVERRIDE(1), DELEGATION(2), SYNTHESIZED(3);

    companion object {
        fun of(number: Int): KotlinMemberKind? = entries.firstOrNull { it.number == number }
    }
}

/**
 * The `flags` int that every declaration in Kotlin metadata carries.
 *
 * It is a packed bit field, and the packing is not documented anywhere except as code: each field is placed
 * immediately after the previous one, so every offset below is a consequence of every offset above it. The
 * compiler builds the chain at class-init time with `FlagField.booleanFirst()` / `booleanAfter(x)` /
 * `after(x, values)`; this reproduces the resulting offsets as constants, because a chain of lazily
 * initialised objects buys nothing here and hides the numbers.
 *
 * An enum field's width is `bitWidth(values)`, which is the number of bits needed for `values.size - 1` (and
 * 1 when that is 0). So a six-value [KotlinVisibility] is three bits and a four-value [KotlinModality] is
 * two, and adding a seventh visibility to Kotlin would shift everything after it. That is exactly why the
 * widths are spelled as `bitWidth(N)` calls against the enum sizes rather than as bare numbers: if one of
 * these enums gains an entry, the offsets move with it instead of quietly decoding the wrong bits.
 *
 * Getting an offset wrong does not throw. It reports `private` for a `public` function, or `abstract` for a
 * `final` one, which is why this is checked against kotlin-metadata-jvm over real class files rather than
 * against hand-written expectations.
 */
object KotlinFlags {

    /** Bits needed to hold `size - 1` distinct values, the compiler's `EnumLiteFlagField.bitWidth`. */
    private fun bitWidth(size: Int): Int {
        val top = size - 1
        if (top <= 0) return 1
        var bits = 0
        var value = top
        while (value != 0) {
            bits++
            value = value ushr 1
        }
        return bits
    }

    // Shared by every declaration: annotations, visibility, modality.
    private const val HAS_ANNOTATIONS = 0
    private const val VISIBILITY = 1
    private val VISIBILITY_WIDTH = bitWidth(KotlinVisibility.entries.size)
    private val MODALITY = VISIBILITY + VISIBILITY_WIDTH
    private val MODALITY_WIDTH = bitWidth(KotlinModality.entries.size)
    private val AFTER_MODALITY = MODALITY + MODALITY_WIDTH

    // Class: the kind, then its modifiers.
    private val CLASS_KIND = AFTER_MODALITY
    private val CLASS_KIND_WIDTH = bitWidth(KotlinClassKind.entries.size)
    private val IS_INNER = CLASS_KIND + CLASS_KIND_WIDTH
    private val IS_DATA = IS_INNER + 1
    private val IS_EXTERNAL_CLASS = IS_DATA + 1
    private val IS_EXPECT_CLASS = IS_EXTERNAL_CLASS + 1
    private val IS_VALUE_CLASS = IS_EXPECT_CLASS + 1
    private val IS_FUN_INTERFACE = IS_VALUE_CLASS + 1
    private val HAS_ENUM_ENTRIES = IS_FUN_INTERFACE + 1

    // Function and property share the member kind, then diverge.
    private val MEMBER_KIND = AFTER_MODALITY
    private val MEMBER_KIND_WIDTH = bitWidth(KotlinMemberKind.entries.size)
    private val AFTER_MEMBER_KIND = MEMBER_KIND + MEMBER_KIND_WIDTH

    private val IS_OPERATOR = AFTER_MEMBER_KIND
    private val IS_INFIX = IS_OPERATOR + 1
    private val IS_INLINE = IS_INFIX + 1
    private val IS_TAILREC = IS_INLINE + 1
    private val IS_EXTERNAL_FUNCTION = IS_TAILREC + 1
    private val IS_SUSPEND = IS_EXTERNAL_FUNCTION + 1
    private val IS_EXPECT_FUNCTION = IS_SUSPEND + 1

    private val IS_VAR = AFTER_MEMBER_KIND
    private val HAS_GETTER = IS_VAR + 1
    private val HAS_SETTER = HAS_GETTER + 1
    private val IS_CONST = HAS_SETTER + 1
    private val IS_LATEINIT = IS_CONST + 1
    private val HAS_CONSTANT = IS_LATEINIT + 1
    private val IS_EXTERNAL_PROPERTY = HAS_CONSTANT + 1
    private val IS_DELEGATED = IS_EXTERNAL_PROPERTY + 1
    private val IS_EXPECT_PROPERTY = IS_DELEGATED + 1

    // Constructor: no modality, so its own flags start right after the visibility.
    private val IS_SECONDARY = VISIBILITY + VISIBILITY_WIDTH

    // Value parameter: no visibility either.
    private const val DECLARES_DEFAULT_VALUE = HAS_ANNOTATIONS + 1
    private const val IS_CROSSINLINE = DECLARES_DEFAULT_VALUE + 1
    private const val IS_NOINLINE = IS_CROSSINLINE + 1

    // Type: a separate chain that shares no bits with the above.
    private const val SUSPEND_TYPE = 0
    private const val DEFINITELY_NOT_NULL_TYPE = 1

    private fun bit(flags: Int, offset: Int): Boolean = (flags ushr offset) and 1 != 0

    private fun field(flags: Int, offset: Int, width: Int): Int = (flags ushr offset) and ((1 shl width) - 1)

    fun hasAnnotations(flags: Int): Boolean = bit(flags, HAS_ANNOTATIONS)

    fun visibility(flags: Int): KotlinVisibility? =
        KotlinVisibility.of(field(flags, VISIBILITY, VISIBILITY_WIDTH))

    fun modality(flags: Int): KotlinModality? =
        KotlinModality.of(field(flags, MODALITY, MODALITY_WIDTH))

    fun classKind(flags: Int): KotlinClassKind? =
        KotlinClassKind.of(field(flags, CLASS_KIND, CLASS_KIND_WIDTH))

    fun memberKind(flags: Int): KotlinMemberKind? =
        KotlinMemberKind.of(field(flags, MEMBER_KIND, MEMBER_KIND_WIDTH))

    fun isInner(flags: Int): Boolean = bit(flags, IS_INNER)
    fun isData(flags: Int): Boolean = bit(flags, IS_DATA)
    fun isExternalClass(flags: Int): Boolean = bit(flags, IS_EXTERNAL_CLASS)
    fun isExpectClass(flags: Int): Boolean = bit(flags, IS_EXPECT_CLASS)
    fun isValueClass(flags: Int): Boolean = bit(flags, IS_VALUE_CLASS)
    fun isFunInterface(flags: Int): Boolean = bit(flags, IS_FUN_INTERFACE)
    fun hasEnumEntries(flags: Int): Boolean = bit(flags, HAS_ENUM_ENTRIES)

    fun isOperator(flags: Int): Boolean = bit(flags, IS_OPERATOR)
    fun isInfix(flags: Int): Boolean = bit(flags, IS_INFIX)
    fun isInline(flags: Int): Boolean = bit(flags, IS_INLINE)
    fun isTailrec(flags: Int): Boolean = bit(flags, IS_TAILREC)
    fun isExternalFunction(flags: Int): Boolean = bit(flags, IS_EXTERNAL_FUNCTION)
    fun isSuspend(flags: Int): Boolean = bit(flags, IS_SUSPEND)
    fun isExpectFunction(flags: Int): Boolean = bit(flags, IS_EXPECT_FUNCTION)

    fun isVar(flags: Int): Boolean = bit(flags, IS_VAR)
    fun hasGetter(flags: Int): Boolean = bit(flags, HAS_GETTER)
    fun hasSetter(flags: Int): Boolean = bit(flags, HAS_SETTER)
    fun isConst(flags: Int): Boolean = bit(flags, IS_CONST)
    fun isLateinit(flags: Int): Boolean = bit(flags, IS_LATEINIT)
    fun hasConstant(flags: Int): Boolean = bit(flags, HAS_CONSTANT)
    fun isExternalProperty(flags: Int): Boolean = bit(flags, IS_EXTERNAL_PROPERTY)
    fun isDelegated(flags: Int): Boolean = bit(flags, IS_DELEGATED)
    fun isExpectProperty(flags: Int): Boolean = bit(flags, IS_EXPECT_PROPERTY)

    fun isSecondaryConstructor(flags: Int): Boolean = bit(flags, IS_SECONDARY)

    fun declaresDefaultValue(flags: Int): Boolean = bit(flags, DECLARES_DEFAULT_VALUE)
    fun isCrossinline(flags: Int): Boolean = bit(flags, IS_CROSSINLINE)
    fun isNoinline(flags: Int): Boolean = bit(flags, IS_NOINLINE)

    fun isSuspendType(flags: Int): Boolean = bit(flags, SUSPEND_TYPE)
    fun isDefinitelyNotNullType(flags: Int): Boolean = bit(flags, DEFINITELY_NOT_NULL_TYPE)
}
