package dev.ide.ui.platform

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.onClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton

/** Right-click opens the context menu on desktop. */
@OptIn(ExperimentalFoundationApi::class)
actual fun Modifier.secondaryClickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.onClick(enabled = enabled, matcher = PointerMatcher.mouse(PointerButton.Secondary)) { onClick() }
