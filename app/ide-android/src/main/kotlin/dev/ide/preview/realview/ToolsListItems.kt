package dev.ide.preview.realview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import dev.ide.platform.log.Log

/**
 * Fills lists that declare `tools:listitem` with that item layout, as Android Studio's preview does. The preview's
 * layout rewriter carries the attribute to the device in the view's `android:tag`
 * (`dev.ide.android.support.PreviewLayoutRewriter`); this reads it back, restores the original tag, and gives a
 * framework `AdapterView` (ListView, GridView, Spinner) an adapter of its own, or a RecyclerView the interpreted
 * one from the project's own RecyclerView ([VmViewFactory.installListItems]). Runs after inflation, before the
 * tree is attached and measured.
 */
internal object ToolsListItems {

    private const val PREFIX = "codeassist.tools:"
    /** Android Studio's item count when `tools:itemCount` is not set. */
    private const val DEFAULT_COUNT = 10

    private val log = Log.logger("ide.preview")

    fun apply(root: View, packageName: String, vm: VmViewFactory) {
        val lists = ArrayList<View>()
        collect(root, lists)
        for (v in lists) {
            val spec = (v.tag as String).removePrefix(PREFIX)
            val ref = spec.removePrefix("listitem=").substringBefore(";itemCount=")
            val rest = spec.substringAfter(";itemCount=", "")
            val count = rest.substringBefore(";tag=").toIntOrNull()?.coerceIn(0, 200) ?: DEFAULT_COUNT
            v.tag = if (";tag=" in rest) rest.substringAfter(";tag=") else null
            val layout = v.resources.getIdentifier(ref.removePrefix("@"), null, packageName)
            if (layout == 0) { log.info("tools:listitem $ref not found"); continue }
            runCatching {
                when {
                    v is AdapterView<*> -> setAdapter(v, layout, count)
                    !vm.installListItems(v, layout, count) -> log.info("tools:listitem on ${v.javaClass.name} is not supported")
                }
            }.onFailure { log.warn("tools:listitem $ref failed (skipped): ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun collect(v: View, out: MutableList<View>) {
        if ((v.tag as? String)?.startsWith(PREFIX) == true) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setAdapter(list: AdapterView<*>, layout: Int, count: Int) {
        (list as AdapterView<BaseAdapter>).adapter = object : BaseAdapter() {
            override fun getCount() = count
            override fun getItem(position: Int): Any? = null
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                convertView ?: LayoutInflater.from(parent.context).inflate(layout, parent, false)
        }
    }
}
