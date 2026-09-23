package dev.ide.ui.editor.preview

import dev.ide.preview.ResourceQualifiers

/**
 * Start the preview chrome on the configuration the previewed file belongs to: a layout opened from
 * `res/layout-land` starts rotated, one from a `-night` folder starts dark, and a size-qualified folder
 * (`-sw600dp`, `-w720dp`) starts on the smallest built-in device that satisfies it.
 *
 * The backend renders the file under a configuration folded the same way (see
 * [dev.ide.preview.ResourceQualifiers.forFile]), so seeding the chrome keeps it from advertising a rotation
 * and a device the render does not use. Axes the folder says nothing about are left untouched, and a folder
 * no built-in device satisfies keeps the default frame: the backend still widens its render configuration, so
 * the file renders either way.
 */
internal fun PreviewSurfaceState.applyResourceQualifiers(path: String) {
    val qualifiers = ResourceQualifiers.parseFile(path)
    qualifiers.landscape?.let { landscape = it }
    qualifiers.night?.let { night = it }
    PREVIEW_DEVICES.indexOfFirst { device ->
        val w = if (landscape) device.hdp else device.wdp
        val h = if (landscape) device.wdp else device.hdp
        w >= (qualifiers.screenWidthDp ?: 0) && h >= (qualifiers.screenHeightDp ?: 0) &&
            minOf(w, h) >= (qualifiers.smallestWidthDp ?: 0)
    }.takeIf { it >= 0 }?.let { deviceIndex = it }
}
