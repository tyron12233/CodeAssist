package dev.ide.preview.layoutlib

import dev.ide.preview.PreviewViewNode
import java.io.File

/**
 * One layout to render with the real Android view classes.
 *
 * Unlike the device path, nothing here is compiled: resources are read from their source folders (no aapt2,
 * no `resources.arsc`), library classes load from their jars as ordinary JVM classes, and the `R` classes
 * library code reads its ids from are made on demand for [resourcePackages].
 */
class LayoutlibRequest(
    /** The layout XML to inflate. A live editor buffer is written to a file first; layoutlib reads files. */
    val layoutFile: File,
    /** Resource folders (`res/`), lowest priority first: each library's, then the project's own. */
    val resourceDirs: List<File>,
    /** Jars and class directories holding the view classes the layout names and everything they need. */
    val classpath: List<File>,
    /** Every package that has an `R` class: the app's own and each library's (from its manifest). */
    val resourcePackages: List<String>,
    /** `Theme.Material3.Light` for a project or library theme, `android:Theme.Material.Light` for a framework one. */
    val themeName: String,
    val widthPx: Int,
    val heightPx: Int,
    /** Screen density as a scale of 160 dpi (2.0 = xhdpi). */
    val density: Float,
    val night: Boolean = false,
    val rtl: Boolean = false,
    val minSdk: Int = 21,
    /** Where resolved resource tables are kept between renders and launches. */
    val cacheDir: File,
    /**
     * How many frames to run before the picture is taken. A view draws itself in one; one that posts work to
     * the frame clock or the main looper (an animation's first step, a deferred layout) is itself a frame or
     * two later.
     */
    val frames: Int = 1,
    /** aapt2's `R.txt` from the project's last build, when there is one: see [ResourceIds]. */
    val builtResourceIds: File? = null,
)

/**
 * The picture and the tree behind it. [pixels] is `width * height` premultiplied ARGB, row by row: the form
 * layoutlib draws in, handed over without an encode, since the host on the other side of a JVM boundary can
 * wrap it as it is. [viewTree] is the same model the device preview captures, so the same inspector reads it.
 */
class LayoutlibResult(
    val pixels: IntArray?,
    val width: Int = 0,
    val height: Int = 0,
    val viewTree: PreviewViewNode? = null,
    /** What layoutlib reported while rendering, as `tag\tmessage`; the frame may still have rendered. */
    val problems: List<String> = emptyList(),
    /** Why there is no picture, when there is none. */
    val error: String? = null,
)
