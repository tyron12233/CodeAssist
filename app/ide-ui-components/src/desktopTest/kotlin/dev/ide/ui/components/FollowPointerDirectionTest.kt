package dev.ide.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A drag chip sits beside the finger, whatever the layout direction: pointer positions are physical, so under RTL
 * it must not be mirrored to the other side of the screen (which `Modifier.offset` did, in the file tree and the
 * tool-window drag). It sits on the trailing side: right of the finger, or left of it under RTL.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FollowPointerDirectionTest {

    @Test
    fun leftToRightItSitsRightOfThePointer() {
        // 50dp at density 1 = 50px; pointer at (200, 100), gap 10.
        assertEquals(Offset(210f, 110f), chipTopLeft(LayoutDirection.Ltr, Offset(200f, 100f)))
    }

    @Test
    fun rightToLeftItSitsLeftOfThePointerNotMirrored() {
        // Off centre on purpose: at the centre the old mirrored placement lands on the same spot. Here it gave 240.
        assertEquals(Offset(40f, 110f), chipTopLeft(LayoutDirection.Rtl, Offset(100f, 100f)))
    }

    @Test
    fun itStaysOnScreenNearAnEdge() {
        assertEquals(Offset(0f, 110f), chipTopLeft(LayoutDirection.Rtl, Offset(20f, 100f)))
        assertEquals(Offset(350f, 110f), chipTopLeft(LayoutDirection.Ltr, Offset(390f, 100f)))
    }

    private fun chipTopLeft(direction: LayoutDirection, pointer: Offset): Offset {
        var topLeft = Offset.Unspecified
        val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .followPointer(gapX = 10, gapY = 10) { pointer }
                            .size(50.dp)
                            .onGloballyPositioned { topLeft = it.boundsInRoot().topLeft },
                    )
                }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        return topLeft
    }
}
