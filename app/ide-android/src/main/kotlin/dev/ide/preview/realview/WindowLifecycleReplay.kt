package dev.ide.preview.realview

import android.view.View
import android.view.ViewGroup
import dev.ide.jvm.interpretedClassNameOf
import dev.ide.platform.log.Log
import java.lang.reflect.Method

/**
 * Runs the window lifecycle a rendered tree would get from a real window, without one. The preview draws its decor
 * but never adds it to a window (a window costs every render a display round trip), so on its own no view ever
 * hears `onAttachedToWindow`, and no pre-draw listener ever fires. Views do real layout work there:
 * ConstraintLayout's `Grid` builds its constraints and `Group` applies its visibility on attach, and
 * CoordinatorLayout moves dependent children from a pre-draw listener.
 *
 * The order mirrors `ViewRootImpl`'s first traversal: [attach] before measure (a parent hears it before its
 * children, as `dispatchAttachedToWindow` does), [preDraw] between layout and draw, and [detach] once the frame
 * is captured (children before their parent) so attach-time registrations (a RecyclerView's prefetch worker, a
 * ProgressBar's animation) are released with the tree. Only public/protected SDK methods are called. A view that
 * throws from a hook is logged and skipped; the render continues.
 */
internal object WindowLifecycleReplay {

    private val log = Log.logger("ide.preview")

    private val onAttached: Method? = hook("onAttachedToWindow")
    private val onDetached: Method? = hook("onDetachedFromWindow")

    private fun hook(name: String): Method? =
        runCatching { View::class.java.getDeclaredMethod(name).apply { isAccessible = true } }
            .onFailure { log.warn("window lifecycle replay unavailable: View.$name: ${it.message}") }
            .getOrNull()

    /**
     * Tells every view in [root] it was attached, parents first. Returns the views told, for [detach]. When
     * [root] is a window decor it is skipped itself: its hook only registers with the window's `ViewRootImpl`,
     * which does not exist here.
     */
    fun attach(root: View, rootIsWindowDecor: Boolean): List<View> {
        val method = onAttached ?: return emptyList()
        val views = ArrayList<View>()
        preOrder(root, views)
        if (rootIsWindowDecor) views.removeAt(0)
        for (v in views) invoke(method, v)
        return views
    }

    /**
     * Fires the pre-draw listeners registered anywhere in [root]. Detached, each view keeps its own observer
     * (a window would have merged them into one), so every view's is dispatched. Returns whether a listener
     * asked for another layout pass, as `ViewRootImpl` would run before drawing.
     */
    fun preDraw(root: View): Boolean {
        val views = ArrayList<View>()
        preOrder(root, views)
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        for (v in views) {
            val observer = runCatching { v.viewTreeObserver }.getOrNull() ?: continue
            if (!seen.add(observer) || !observer.isAlive) continue
            runCatching { observer.dispatchOnPreDraw() }
                .onFailure { log.warn("pre-draw listener of ${nameOf(v)} failed (skipped): ${it.message}") }
        }
        return root.isLayoutRequested
    }

    /** Tells the views [attach] attached that they were detached, children before their parent. */
    fun detach(attached: List<View>) {
        val method = onDetached ?: return
        for (i in attached.indices.reversed()) invoke(method, attached[i])
    }

    private fun invoke(method: Method, v: View) {
        try {
            method.invoke(v)
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            log.warn("${method.name} of ${nameOf(v)} failed (skipped): ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    /** An interpreted view's own class name, not its generated peer's. */
    private fun nameOf(v: View): String = interpretedClassNameOf(v) ?: v.javaClass.name

    private fun preOrder(v: View, out: MutableList<View>) {
        out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) preOrder(v.getChildAt(i), out)
    }
}
