package dev.ide.preview.bridge

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import dev.ide.preview.NativeCanvasHolder
import dev.ide.preview.RCanvas
import dev.ide.preview.ResolvedValue
import dev.ide.preview.ViewHost
import dev.ide.preview.impl.StyledAttrResolver

/**
 * The on-device Bridge runtime the BridgeRemapper reparents user views onto. The instrumented user class
 * `extends dev.ide.preview.bridge.widget.BridgeView` (a real `android.view.View`), and its
 * `obtainStyledAttributes(...)` call is redirected to [Bridges.obtainStyledAttributes], which returns a
 * [dev.ide.preview.bridge.res.BridgeTypedArray] of pre-resolved values. The view renders into our owned
 * canvas via [ViewHost], unwrapping the native `android.graphics.Canvas` for the user's `onDraw`.
 *
 * Package/class names here MUST match `dev.ide.preview.impl.bridge.BridgeTypeMap` exactly.
 */

/** Raw layout attributes for a custom view (KXml-free; built from the inflater's neutral attrs). */
class BridgeAttributeSet(val rawAttrs: Map<String, String>) : AttributeSet {
    private val names = rawAttrs.keys.toList()
    private val values = names.map { rawAttrs[it] ?: "" }

    override fun getAttributeCount(): Int = names.size
    override fun getAttributeName(index: Int): String = names.getOrElse(index) { "" }
    override fun getAttributeValue(index: Int): String = values.getOrElse(index) { "" }
    override fun getAttributeValue(namespace: String?, name: String?): String? = rawAttrs[name]
    override fun getPositionDescription(): String = "preview"
    override fun getAttributeNameResource(index: Int): Int = 0
    override fun getAttributeListValue(ns: String?, attr: String?, options: Array<out String>?, def: Int): Int = def
    override fun getAttributeBooleanValue(ns: String?, attr: String?, def: Boolean): Boolean = rawAttrs[attr]?.toBoolean() ?: def
    override fun getAttributeResourceValue(ns: String?, attr: String?, def: Int): Int = def
    override fun getAttributeIntValue(ns: String?, attr: String?, def: Int): Int = rawAttrs[attr]?.toIntOrNull() ?: def
    override fun getAttributeUnsignedIntValue(ns: String?, attr: String?, def: Int): Int = rawAttrs[attr]?.toIntOrNull() ?: def
    override fun getAttributeFloatValue(ns: String?, attr: String?, def: Float): Float = rawAttrs[attr]?.toFloatOrNull() ?: def
    override fun getAttributeListValue(index: Int, options: Array<out String>?, def: Int): Int = def
    override fun getAttributeBooleanValue(index: Int, def: Boolean): Boolean = values.getOrNull(index)?.toBoolean() ?: def
    override fun getAttributeResourceValue(index: Int, def: Int): Int = def
    override fun getAttributeIntValue(index: Int, def: Int): Int = values.getOrNull(index)?.toIntOrNull() ?: def
    override fun getAttributeUnsignedIntValue(index: Int, def: Int): Int = values.getOrNull(index)?.toIntOrNull() ?: def
    override fun getAttributeFloatValue(index: Int, def: Float): Float = values.getOrNull(index)?.toFloatOrNull() ?: def
    override fun getIdAttribute(): String? = rawAttrs["id"]
    override fun getClassAttribute(): String? = null
    override fun getIdAttributeResourceValue(def: Int): Int = def
    override fun getStyleAttribute(): Int = 0
}

/**
 * The static trampoline the instrumented `obtainStyledAttributes` call lands on. Resolves the styleable's
 * attr ids → values via the [StyledAttrResolver] set for the current preview (thread-local), reading the
 * view's raw attrs from its [BridgeAttributeSet].
 */
object Bridges {
    val styledResolver: ThreadLocal<StyledAttrResolver?> = ThreadLocal()

    @JvmStatic
    fun obtainStyledAttributes(context: Context?, set: AttributeSet?, attrs: IntArray, defStyleAttr: Int, defStyleRes: Int): dev.ide.preview.bridge.res.BridgeTypedArray {
        val raw = (set as? BridgeAttributeSet)?.rawAttrs ?: emptyMap()
        val values = styledResolver.get()?.resolve(raw, attrs) ?: emptyList()
        return dev.ide.preview.bridge.res.BridgeTypedArray(values)
    }
}

/** Helper: unwrap the owned [RCanvas] to the native `android.graphics.Canvas`. */
internal fun RCanvas.androidCanvas(): android.graphics.Canvas? =
    (this as? NativeCanvasHolder)?.nativeCanvas() as? android.graphics.Canvas

/**
 * Adapts a reparented [dev.ide.preview.bridge.widget.BridgeView] (a real `View`) to the engine's [ViewHost]:
 * the engine's measure/layout passes drive the `View`, and [drawContent] runs the user's `onDraw` on the
 * unwrapped native canvas. Kept as a wrapper (rather than `BridgeView : ViewHost`) so `View.getMeasuredWidth`
 * etc. don't clash with the interface's `val` members.
 */
class AndroidViewHost(private val view: dev.ide.preview.bridge.widget.BridgeView) : ViewHost {
    override fun measure(widthSpec: Int, heightSpec: Int) { view.measure(widthSpec, heightSpec) }
    override val measuredWidth: Int get() = view.measuredWidth
    override val measuredHeight: Int get() = view.measuredHeight
    override fun layout(l: Int, t: Int, r: Int, b: Int) { view.layout(l, t, r, b) }
    override fun drawContent(canvas: RCanvas) { canvas.androidCanvas()?.let { view.renderContent(it) } }
}
