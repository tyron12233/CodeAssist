package dev.ide.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.ide.ui.theme.CodeAssistTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The drawer opens with a swipe away from the edge it hangs off: from the left, rightward, and under RTL, where it
 * hangs off the right edge, leftward. Pointer deltas are physical, so the direction has to be mirrored by hand.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PushDrawerDirectionTest {

    @Test
    fun aRightwardSwipeOpensItLeftToRight() {
        assertEquals(true, swipe(LayoutDirection.Ltr, rightward = true))
        assertEquals(null, swipe(LayoutDirection.Ltr, rightward = false))
    }

    @Test
    fun aLeftwardSwipeOpensItRightToLeft() {
        assertEquals(true, swipe(LayoutDirection.Rtl, rightward = false))
        assertEquals(null, swipe(LayoutDirection.Rtl, rightward = true))
    }

    /** What the drawer reported after one horizontal swipe across content that cannot scroll: null for nothing. */
    private fun swipe(direction: LayoutDirection, rightward: Boolean): Boolean? {
        var reported: Boolean? = null
        val scene = ImageComposeScene(width = 800, height = 400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    PushDrawer(open = false, onOpenChange = { reported = it }, drawerContent = {}) {
                        // A scrollable with nothing to scroll: every delta is left over for the drawer.
                        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()))
                    }
                }
            }
        }
        try {
            var frame = 0L
            scene.render(frame)
            var x = if (rightward) 200f else 600f
            var stamp = 0L
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, 200f), type = PointerType.Touch, timeMillis = stamp)
            repeat(12) {
                stamp += 16
                x += if (rightward) 30f else -30f
                scene.sendPointerEvent(PointerEventType.Move, Offset(x, 200f), type = PointerType.Touch, timeMillis = stamp)
                frame += FRAME_NS; scene.render(frame)
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, 200f), type = PointerType.Touch, timeMillis = stamp + 16)
            // Let the settle animation finish, which is when the drawer reports.
            repeat(60) { frame += FRAME_NS; scene.render(frame) }
        } finally {
            scene.close()
        }
        return reported
    }

    private companion object {
        const val FRAME_NS = 16_000_000L
    }
}
