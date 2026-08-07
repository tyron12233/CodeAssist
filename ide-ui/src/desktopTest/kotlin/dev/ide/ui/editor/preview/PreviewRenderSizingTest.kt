package dev.ide.ui.editor.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PreviewDevices.renderSurfaceDp] decides the dp size the off-screen preview surface renders at. The bug it
 * fixes: `@Preview(showSystemUi = true)` (and named-device previews) rendered into a fixed 411×731 default while
 * the card was sized to a taller phone, so the FillWidth frame letterboxed — white bands top and bottom.
 */
class PreviewRenderSizingTest {

    @Test fun wrapPreviewHasNoFixedSurface() {
        // No device, no size, no system UI → wrap-to-content (null = "render into the default viewport, crop").
        assertNull(PreviewDevices.renderSurfaceDp(device = null, widthDp = null, heightDp = null, showSystemUi = false))
    }

    @Test fun showSystemUiWithoutDeviceFramesTheDefaultPhoneBody() {
        // Studio parity: a sizeless showSystemUi preview uses DEFAULT_PHONE, and the surface is the BODY between
        // the mock status + nav bars — so the streamed frame fills SystemUiChrome's content slot with no letterbox.
        val (w, h) = PreviewDevices.renderSurfaceDp(null, null, null, showSystemUi = true)!!
        assertEquals(PreviewDevices.DEFAULT_PHONE.wdp, w, "width = the default phone width")
        assertEquals(
            PreviewDevices.DEFAULT_PHONE.hdp - PreviewDevices.STATUS_BAR_DP - PreviewDevices.NAV_BAR_DP, h,
            "height = phone height minus the mock system bars (the chrome's content slot)",
        )
    }

    @Test fun showSystemUiBodyAspectMatchesTheChromeContentSlot() {
        // The whole point: render body aspect == card content-slot aspect (deviceW : deviceH - bars) → fills.
        val (w, h) = PreviewDevices.renderSurfaceDp(null, null, null, showSystemUi = true)!!
        val phone = PreviewDevices.DEFAULT_PHONE
        val slotH = phone.hdp - PreviewDevices.STATUS_BAR_DP - PreviewDevices.NAV_BAR_DP
        assertEquals(phone.wdp.toFloat() / slotH, w.toFloat() / h, 0.0001f)
    }

    @Test fun namedDeviceRendersAtThatDeviceSize() {
        // A named device now sizes the surface to the device (was the fixed default → letterbox on tall phones).
        val (w, h) = PreviewDevices.renderSurfaceDp("id:pixel_6", null, null, showSystemUi = false)!!
        assertEquals(411, w); assertEquals(914, h)
    }

    @Test fun namedDeviceWithSystemUiSubtractsBars() {
        val (w, h) = PreviewDevices.renderSurfaceDp("id:pixel_6", null, null, showSystemUi = true)!!
        assertEquals(411, w)
        assertEquals(914 - PreviewDevices.STATUS_BAR_DP - PreviewDevices.NAV_BAR_DP, h)
    }

    @Test fun explicitWidthHeightIsUnchanged() {
        val (w, h) = PreviewDevices.renderSurfaceDp(null, widthDp = 320, heightDp = 480, showSystemUi = false)!!
        assertEquals(320, w); assertEquals(480, h)
    }

    @Test fun unknownDeviceFallsBackToDefaultViewport() {
        // An unrecognized device id with no size isn't wrap (a device WAS named) — fall back to the default viewport.
        val (w, h) = PreviewDevices.renderSurfaceDp("id:does_not_exist", null, null, showSystemUi = false)!!
        assertEquals(PreviewDevices.DEFAULT_VIEWPORT_WIDTH_DP, w)
        assertEquals(PreviewDevices.DEFAULT_VIEWPORT_HEIGHT_DP, h)
    }

    @Test fun bodyHeightNeverGoesNonPositive() {
        // Defensive: even a device shorter than the bars keeps a valid (≥1) surface height.
        val (_, h) = PreviewDevices.renderSurfaceDp("spec:width=200dp,height=40dp,dpi=320", null, null, showSystemUi = true)!!
        assertTrue(h >= 1, "surface height stays positive")
    }
}
