package dev.ide.preview.bridge.res

import dev.ide.preview.ResolvedValue

/**
 * A drop-in for `android.content.res.TypedArray` in instrumented user code (the remapper remaps the user's
 * `TypedArray` local type to this). It is NOT a real `TypedArray`; it just exposes the same getters, reading
 * the values pre-resolved by [dev.ide.preview.bridge.Bridges.obtainStyledAttributes] (indexed by styleable
 * position — the `R.styleable.X_attr` index constants).
 */
class BridgeTypedArray(private val values: List<ResolvedValue?>) {
    fun hasValue(index: Int): Boolean = values.getOrNull(index) != null
    fun getColor(index: Int, defValue: Int): Int = (values.getOrNull(index) as? ResolvedValue.Color)?.argb ?: defValue
    fun getDimension(index: Int, defValue: Float): Float = (values.getOrNull(index) as? ResolvedValue.Dimension)?.px ?: defValue
    fun getDimensionPixelSize(index: Int, defValue: Int): Int = (values.getOrNull(index) as? ResolvedValue.Dimension)?.px?.toInt() ?: defValue
    fun getDimensionPixelOffset(index: Int, defValue: Int): Int = (values.getOrNull(index) as? ResolvedValue.Dimension)?.px?.toInt() ?: defValue
    fun getInt(index: Int, defValue: Int): Int = (values.getOrNull(index) as? ResolvedValue.IntV)?.v ?: defValue
    fun getInteger(index: Int, defValue: Int): Int = getInt(index, defValue)
    fun getFloat(index: Int, defValue: Float): Float = (values.getOrNull(index) as? ResolvedValue.FloatV)?.v ?: defValue
    fun getBoolean(index: Int, defValue: Boolean): Boolean = (values.getOrNull(index) as? ResolvedValue.BoolV)?.v ?: defValue
    fun getString(index: Int): String? = (values.getOrNull(index) as? ResolvedValue.Str)?.text?.toString()
    fun getText(index: Int): CharSequence? = (values.getOrNull(index) as? ResolvedValue.Str)?.text
    fun getResourceId(index: Int, defValue: Int): Int = defValue
    fun getDrawable(index: Int): android.graphics.drawable.Drawable? = null
    fun getIndexCount(): Int = values.count { it != null }
    fun recycle() { /* no pool */ }
}
