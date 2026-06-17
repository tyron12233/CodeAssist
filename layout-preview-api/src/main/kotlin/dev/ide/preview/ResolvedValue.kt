package dev.ide.preview

/**
 * A resource/attribute value after resolution — the typed payload a renderer reads instead of a raw string.
 * Colors are `0xAARRGGBB` ints; dimensions are already converted to pixels for the previewed device config.
 */
sealed interface ResolvedValue {
    data class Color(val argb: Int) : ResolvedValue
    data class Dimension(val px: Float) : ResolvedValue
    data class Str(val text: CharSequence) : ResolvedValue
    data class IntV(val v: Int) : ResolvedValue
    data class FloatV(val v: Float) : ResolvedValue
    data class BoolV(val v: Boolean) : ResolvedValue
    /** A reference that resolves to another resource rather than a scalar (e.g. `@drawable/x`, `@id/y`). */
    data class Ref(val resType: String, val name: String) : ResolvedValue
}

/**
 * The expected shape of an attribute value, so the resolver can parse a raw string correctly. The
 * preview-local mirror of an attribute's `format` (`color|dimension|reference|...`); the impl maps
 * android-support's `AttrFormat` onto this so this module stays android-free.
 */
enum class ValueFormat { ANY, COLOR, DIMENSION, STRING, INTEGER, BOOLEAN, FLOAT, REFERENCE, FLAG, ENUM }

/**
 * The neutral resource seam the renderers (and the bridge's styled-attribute path) read through. The impl is
 * backed by the project's merged resources + the SDK attr metadata; this contract carries no android types so
 * the api stays portable.
 */
interface PreviewResources {
    /** Resolve a raw attribute string ("@color/x", "#fff", "16dp", "true") under a [format] hint; null if unresolved. */
    fun resolve(raw: String, format: ValueFormat): ResolvedValue?

    /** Load the image a `@drawable/@mipmap` reference (or a file path) points at; null if unavailable. */
    fun image(ref: String): RImage?
}
