package dev.ide.ui.dialog

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import dev.ide.ui.platform.WindowFold
import dev.ide.ui.platform.rememberWindowFold

/** Space kept between the dialog and both the hinge and the screen edge, in dp. */
private const val SIDE_MARGIN_DP = 16

private const val SIDE_OF_VERTICAL = Gravity.LEFT or Gravity.CENTER_VERTICAL
private const val SIDE_OF_HORIZONTAL = Gravity.TOP or Gravity.CENTER_HORIZONTAL

@Composable
actual fun DialogFoldEffect() {
    val fold = rememberWindowFold()
    val view = LocalView.current
    DisposableEffect(fold, view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window == null) {
            onDispose { }
        } else {
            val decor = window.decorView
            // The dialog's own size is only known once it has been laid out, and changes with its content.
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place(window, view, fold) }
            decor.addOnLayoutChangeListener(listener)
            place(window, view, fold)
            onDispose { decor.removeOnLayoutChangeListener(listener) }
        }
    }
}

/**
 * Positions [window] within the larger side of [fold] (the trailing side of a vertical fold or the upper side
 * of a horizontal one on a tie), or back in the centre when no fold divides the screen.
 */
private fun place(window: Window, view: View, fold: WindowFold?) {
    val attrs = window.attributes
    val screen = view.resources.displayMetrics
    if (fold == null || !fold.separating) {
        // Only a window this moved is put back: a dialog that was never moved keeps whatever layout it asked for.
        if (attrs.gravity == SIDE_OF_VERTICAL || attrs.gravity == SIDE_OF_HORIZONTAL) {
            attrs.gravity = Gravity.CENTER
            attrs.x = 0
            attrs.y = 0
            window.attributes = attrs
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return
    }
    val margin = (SIDE_MARGIN_DP * screen.density).toInt()
    val decor = window.decorView
    if (fold.vertical) {
        val before = fold.left
        val after = screen.widthPixels - fold.right
        val start = if (before > after) 0 else fold.right
        val room = (if (before > after) before else after) - 2 * margin
        if (room <= 0 || decor.width == 0) return
        val width = minOf(decor.width, room)
        val x = start + margin + (room - width) / 2
        if (decor.width > room) window.setLayout(room, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (attrs.gravity != SIDE_OF_VERTICAL || attrs.x != x) {
            attrs.gravity = SIDE_OF_VERTICAL
            attrs.x = x
            attrs.y = 0
            window.attributes = attrs
        }
    } else {
        val above = fold.top
        val below = screen.heightPixels - fold.bottom
        val start = if (below > above) fold.bottom else 0
        val room = (if (below > above) below else above) - 2 * margin
        if (room <= 0 || decor.height == 0) return
        val height = minOf(decor.height, room)
        val y = start + margin + (room - height) / 2
        if (decor.height > room) window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, room)
        if (attrs.gravity != SIDE_OF_HORIZONTAL || attrs.y != y) {
            attrs.gravity = SIDE_OF_HORIZONTAL
            attrs.x = 0
            attrs.y = y
            window.attributes = attrs
        }
    }
}
