package dev.ide.ui.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

@Composable
actual fun rememberWindowFold(): WindowFold? {
    val context = LocalContext.current
    val folds = remember(context) {
        val activity = context.findActivity() ?: return@remember emptyFlow()
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()?.let { fold ->
                WindowFold(
                    left = fold.bounds.left,
                    top = fold.bounds.top,
                    right = fold.bounds.right,
                    bottom = fold.bounds.bottom,
                    vertical = fold.orientation == FoldingFeature.Orientation.VERTICAL,
                    halfOpened = fold.state == FoldingFeature.State.HALF_OPENED,
                    separating = fold.isSeparating,
                )
            }
        }
    }
    val fold by folds.collectAsState(initial = null)
    return fold
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
