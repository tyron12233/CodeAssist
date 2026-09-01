package dev.ide.android.support.icons

import dev.ide.android.support.preview.VectorGroup
import dev.ide.android.support.preview.VectorNode
import dev.ide.android.support.preview.VectorPath
import dev.ide.android.support.preview.VectorSpec

/**
 * Small edits an importer makes to a vector before writing it: repainting it a single colour, or changing its
 * intrinsic size. Both are deliberately shallow: neither touches the coordinate space, so the geometry and
 * the file's path data stay byte-identical.
 */

/** [this] with every path's fill and stroke repainted [argb] (`0xAARRGGBB`), keeping each path's alpha. */
fun VectorSpec.recolored(argb: Long): VectorSpec = copy(nodes = nodes.map { it.recolored(argb) })

private fun VectorNode.recolored(argb: Long): VectorNode = when (this) {
    is VectorPath -> copy(
        fillColor = fillColor?.let { argb },
        strokeColor = strokeColor?.let { argb },
    )

    is VectorGroup -> copy(children = children.map { it.recolored(argb) })
}

/** [this] resized to [widthDp] x [heightDp]. The viewport is untouched, so the artwork simply scales. */
fun VectorSpec.resized(widthDp: Float, heightDp: Float): VectorSpec =
    copy(widthDp = widthDp, heightDp = heightDp)

/** A single-path vector in a [viewport]-unit square, offset by a viewBox origin of ([originX], [originY]). */
internal fun singlePathVector(
    pathData: String,
    viewport: Float,
    originX: Float,
    originY: Float,
    sizeDp: Float,
    color: Long,
): VectorSpec {
    val path = VectorPath(pathData = pathData, fillColor = color)
    return VectorSpec(
        widthDp = sizeDp,
        heightDp = sizeDp,
        viewportWidth = viewport,
        viewportHeight = viewport,
        nodes = if (originX == 0f && originY == 0f) listOf(path)
        else listOf(VectorGroup(children = listOf(path), translateX = -originX, translateY = -originY)),
    )
}
