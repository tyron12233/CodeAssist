package dev.ide.android.layouteditor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import org.jetbrains.compose.resources.StringResource
import dev.ide.android.layouteditor.generated.resources.Res
import dev.ide.android.layouteditor.generated.resources.le_device_foldable
import dev.ide.android.layouteditor.generated.resources.le_device_phone
import dev.ide.android.layouteditor.generated.resources.le_device_small_phone
import dev.ide.android.layouteditor.generated.resources.le_device_tablet

/** A screen to design for: its size in dp and the density it renders at (pixels per dp). */
internal class DesignDevice(val label: StringResource, val wdp: Int, val hdp: Int, val density: Float)

/**
 * The screens offered. Densities are kept modest on purpose: the render is a bitmap the size of the screen in
 * pixels, re-made on every edit, so a 3.5x tablet would be most of a frame's memory for no visible gain.
 */
internal val DESIGN_DEVICES = listOf(
    DesignDevice(Res.string.le_device_phone, 412, 915, 2f),
    DesignDevice(Res.string.le_device_small_phone, 360, 640, 2f),
    DesignDevice(Res.string.le_device_foldable, 673, 841, 2f),
    DesignDevice(Res.string.le_device_tablet, 800, 1280, 1.5f),
)

/** The side panels. On a phone one shows at a time as a bottom sheet; on a wide pane they dock. */
internal enum class DesignPanel { Tree, Attributes }

/** The editor's per-file UI state. Plain Compose state: it lives as long as the pane does. */
@Stable
internal class DesignerState(initialLandscape: Boolean, initialNight: Boolean) {
    var deviceIndex by mutableStateOf(0)
    var landscape by mutableStateOf(initialLandscape)
    var night by mutableStateOf(initialNight)

    /** Blueprint: every view as an outline on a flat ground, so overlapping and empty views are visible. */
    var blueprint by mutableStateOf(false)

    /** User zoom; 0 means fit. */
    var zoom by mutableStateOf(0f)
    var pan by mutableStateOf(Offset.Zero)

    var selection by mutableStateOf<SelectionKey?>(null)

    /** The open bottom sheet on a phone, and how much of the height it takes. */
    var sheet by mutableStateOf<DesignPanel?>(null)
    var sheetFraction by mutableStateOf(0.55f)

    /** The pan the canvas added to keep the selection above the sheet, undone when the sheet closes. */
    var sheetPan by mutableStateOf(0f)

    /** The docked panes on a wide surface. */
    var treeDocked by mutableStateOf(true)
    var attributesDocked by mutableStateOf(true)

    val device: DesignDevice get() = DESIGN_DEVICES[deviceIndex]
    val wdp: Int get() = if (landscape) device.hdp else device.wdp
    val hdp: Int get() = if (landscape) device.wdp else device.hdp
    val widthPx: Int get() = (wdp * device.density).toInt()
    val heightPx: Int get() = (hdp * device.density).toInt()

    fun resetView() {
        zoom = 0f
        pan = Offset.Zero
    }

    companion object {
        /** Seed from the file's folder: `layout-land` opens rotated, `layout-night` opens dark. */
        fun forPath(path: String): DesignerState {
            val folder = path.replace('\\', '/').substringBeforeLast('/').substringAfterLast('/')
            val qualifiers = folder.split('-').drop(1).toSet()
            return DesignerState(initialLandscape = "land" in qualifiers, initialNight = "night" in qualifiers)
        }
    }
}
