package com.tyron.code.util

import android.view.View
import android.view.ViewGroup
import com.tyron.common.util.AndroidUtilities

val Int.dp: Int
    get() = AndroidUtilities.dpToPx(this.toFloat())

fun View.setMargins(left: Int? = null, top: Int? = null, right: Int? = null, bottom: Int? = null) {
    val params = (layoutParams as? ViewGroup.MarginLayoutParams)
    params?.setMargins(
        left ?: params.leftMargin,
        top ?: params.topMargin,
        right ?: params.rightMargin,
        bottom ?: params.bottomMargin)
    layoutParams = params
}
