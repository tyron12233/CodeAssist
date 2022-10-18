package com.tyron.code.ui.editor.impl.text.squircle

import android.text.Editable
import android.text.InputFilter
import android.text.SpannableStringBuilder
import com.blacksquircle.ui.editorkit.plugin.base.EditorPlugin
import com.tyron.editor.Content
import com.tyron.editor.event.ContentListener

class ContentImpl(
    private val baseContent: Content
) : EditorPlugin("content"), Editable {

    val editable = SpannableStringBuilder()

    override fun get(index: Int): Char {
        return editable[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        return editable.subSequence(startIndex, endIndex)
    }

    override fun getChars(p0: Int, p1: Int, p2: CharArray?, p3: Int) {
        return editable.getChars(p0, p1, p2, p3)
    }

    override fun <T : Any> getSpans(p0: Int, p1: Int, p2: Class<T>?): Array<T> {
        return editable.getSpans(p0, p1, p2)
    }

    override fun getSpanStart(p0: Any?): Int {
        return editable.getSpanStart(p0)
    }

    override fun getSpanEnd(p0: Any?): Int {
        return editable.getSpanEnd(p0)
    }

    override fun getSpanFlags(p0: Any?): Int {
        return editable.getSpanFlags(p0)
    }

    override fun nextSpanTransition(p0: Int, p1: Int, p2: Class<*>?): Int {
        return editable.nextSpanTransition(p0, p1, p2)
    }

    override fun setSpan(p0: Any?, p1: Int, p2: Int, p3: Int) {
        editable.setSpan(p0, p1, p2, p3)
    }

    override fun removeSpan(p0: Any?) {
        editable.removeSpan(p0)
    }

    override fun append(p0: CharSequence?): Editable {
        return editable.append(p0)
    }

    override fun append(p0: CharSequence?, p1: Int, p2: Int): Editable {
        return editable.append(p0, p1, p2)
    }

    override fun append(p0: Char): Editable {
        return editable.append(p0)
    }

    override fun replace(p0: Int, p1: Int, p2: CharSequence?, p3: Int, p4: Int): Editable {
        return this
    }

    override fun replace(p0: Int, p1: Int, p2: CharSequence?): Editable {
        TODO("Not yet implemented")
    }

    override fun insert(p0: Int, p1: CharSequence?, p2: Int, p3: Int): Editable {
        TODO("Not yet implemented")
    }

    override fun insert(p0: Int, p1: CharSequence?): Editable {
        TODO("Not yet implemented")
    }

    override fun delete(p0: Int, p1: Int): Editable {
        return editable.delete(p0, p1)
    }

    override fun clear() {
        editable.clear()
    }

    override fun clearSpans() {
        editable.clearSpans()
    }

    override fun setFilters(p0: Array<out InputFilter>?) {
        editable.filters = p0
    }

    override fun getFilters(): Array<InputFilter> {
        return editable.filters
    }

    override val length: Int
        get() = editable.length


}