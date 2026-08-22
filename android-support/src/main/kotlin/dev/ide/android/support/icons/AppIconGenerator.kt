package dev.ide.android.support.icons

import dev.ide.android.support.preview.VectorGroup
import dev.ide.android.support.preview.VectorSpec
import kotlin.math.max

/** One layer of a launcher icon. */
sealed interface AppIconLayer {

    /** Nothing to draw (a transparent background, or no monochrome layer at all). */
    data object None : AppIconLayer

    /** A flat `0xAARRGGBB` fill, written as a colour resource so it can be themed later. */
    data class Color(val argb: Long) : AppIconLayer

    /**
     * Vector artwork placed inside the adaptive-icon box. [scale] is a multiple of the safe zone (1.0 fits the
     * artwork exactly inside it), and [offsetX]/[offsetY] nudge it as a fraction of the whole box.
     */
    data class Vector(
        val spec: VectorSpec,
        val scale: Float = 1f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val tintArgb: Long? = null,
    ) : AppIconLayer

    /** An already-encoded image (an imported PNG or WebP), written as-is into a density-independent folder. */
    data class Raster(val bytes: ByteArray, val extension: String) : AppIconLayer
}

/** What the studio should produce. */
data class AppIconSpec(
    /** Base resource name; Android's convention is `ic_launcher`. */
    val name: String = "ic_launcher",
    val background: AppIconLayer = AppIconLayer.Color(0xFFFFFFFF),
    val foreground: AppIconLayer = AppIconLayer.None,
    /** The Android 13+ themed-icon layer. [AppIconLayer.None] omits it. */
    val monochrome: AppIconLayer = AppIconLayer.None,
    /** Write the density-bucketed PNGs that pre-26 devices and many launchers actually show. */
    val generateRasters: Boolean = true,
    val generateRoundIcon: Boolean = true,
    val generatePlayStoreIcon: Boolean = true,
)

/** One file an app-icon change writes. Paths are relative to the target `res/` directory. */
sealed interface AppIconFile {
    val relativePath: String

    /** A text resource whose content the generator decided (the adaptive XML, a colour, a vector layer). */
    data class Text(override val relativePath: String, val content: String) : AppIconFile

    /** Bytes the generator already holds (an imported image used as a layer). */
    data class Bytes(override val relativePath: String, val bytes: ByteArray) : AppIconFile

    /**
     * A raster the *host* has to render, because rasterising a vector needs a canvas the engine has no access
     * to. [pixels] is the square edge length; [round] asks for the circular launcher mask (otherwise the
     * legacy rounded-square one); [opaque] forbids transparency, which the Play Store listing requires.
     */
    data class Raster(
        override val relativePath: String,
        val pixels: Int,
        val round: Boolean,
        val opaque: Boolean,
    ) : AppIconFile
}

/** The `<application>` icon references an app-icon change implies. */
data class ManifestIconEdit(val iconRef: String, val roundIconRef: String?)

/**
 * Everything an app-icon change will do, computed before anything is written. [replacing] lists the paths
 * (relative to `res/`) that already exist, so the studio can say what it is about to overwrite.
 */
data class AppIconPlan(
    val spec: AppIconSpec,
    val files: List<AppIconFile>,
    val manifest: ManifestIconEdit?,
    val replacing: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * Builds the file set for a launcher icon: the API 26+ adaptive icon, its layers, the density-bucketed
 * rasters older devices fall back to, and the opaque Play Store image.
 *
 * The geometry follows the adaptive-icon contract: the artwork lives in a 108-unit box of which only the
 * central 72 units ([SAFE_ZONE]) are guaranteed visible, because a launcher may mask the icon to a circle, a
 * squircle or a rounded square, and it animates the layers within that box. So a source icon is scaled into
 * the safe zone rather than the full box, which is why an imported 24dp icon does not come out cropped.
 *
 * Nothing here touches the filesystem: [plan] is pure, so the studio can show the exact file list (and what
 * it would replace) before the user commits, and the whole thing is unit-testable.
 */
object AppIconGenerator {

    /** The adaptive-icon box, in dp: the full canvas both layers are drawn in. */
    const val BOX = 108f

    /** The centre square/circle of [BOX] a launcher mask is guaranteed not to clip. */
    const val SAFE_ZONE = 72f

    /** The launcher-icon density buckets, as `folder qualifier to pixel size` (48dp at each density). */
    val DENSITIES: List<Pair<String, Int>> = listOf(
        "mdpi" to 48,
        "hdpi" to 72,
        "xhdpi" to 96,
        "xxhdpi" to 144,
        "xxxhdpi" to 192,
    )

    /** The Play Store listing image, which is a fixed size and must be fully opaque. */
    const val PLAY_STORE_PIXELS = 512

    /**
     * The files [spec] would write. [exists] answers whether a path (relative to `res/`) is already present,
     * which only affects [AppIconPlan.replacing]: the caller decides what to do about it.
     */
    fun plan(spec: AppIconSpec, exists: (String) -> Boolean = { false }): AppIconPlan {
        val files = ArrayList<AppIconFile>()
        val warnings = ArrayList<String>()
        val name = spec.name

        val backgroundRef = layerFiles(spec.background, "$name$BACKGROUND_SUFFIX", files, warnings, isBackground = true)
        val foregroundRef = layerFiles(spec.foreground, "$name$FOREGROUND_SUFFIX", files, warnings, isBackground = false)
        val monochromeRef = layerFiles(spec.monochrome, "$name$MONOCHROME_SUFFIX", files, warnings, isBackground = false)

        if (foregroundRef == null) warnings += "No foreground layer: the icon will be a plain background"

        // API 26+: the adaptive icon itself. The round variant is the same document; the launcher, not the
        // resource, decides the mask, and shipping both is what lets a launcher pick the round entry.
        val adaptive = adaptiveIconXml(backgroundRef, foregroundRef, monochromeRef)
        files += AppIconFile.Text("$ANYDPI_V26/$name.xml", adaptive)
        if (spec.generateRoundIcon) files += AppIconFile.Text("$ANYDPI_V26/$name$ROUND_SUFFIX.xml", adaptive)

        if (spec.generateRasters) {
            // Pre-26 has no <adaptive-icon>, so the density PNGs are the fallback (and what many launchers
            // and the recents UI read even on newer devices).
            for ((qualifier, pixels) in DENSITIES) {
                files += AppIconFile.Raster("mipmap-$qualifier/$name.png", pixels, round = false, opaque = false)
                if (spec.generateRoundIcon) {
                    files += AppIconFile.Raster(
                        "mipmap-$qualifier/$name$ROUND_SUFFIX.png", pixels, round = true, opaque = false,
                    )
                }
            }
        } else if (foregroundRef != null) {
            // No rasters were asked for, so pre-26 needs *something*: compose the same two layers as a
            // vector layer-list in the unqualified folder.
            files += AppIconFile.Text("mipmap/$name.xml", legacyLayerListXml(backgroundRef, foregroundRef))
            if (spec.generateRoundIcon) {
                files += AppIconFile.Text(
                    "mipmap/$name$ROUND_SUFFIX.xml",
                    legacyLayerListXml(backgroundRef, foregroundRef),
                )
            }
            warnings += "Without density rasters, pre-Android 8 devices show the vector fallback"
        }

        if (spec.generatePlayStoreIcon) {
            // AGP's own convention: beside `res/`, in the source set root, so it is never packaged.
            files += AppIconFile.Raster(
                "$PLAY_STORE_PATH_PREFIX$name-playstore.png",
                PLAY_STORE_PIXELS, round = false, opaque = true,
            )
        }

        return AppIconPlan(
            spec = spec,
            files = files,
            manifest = ManifestIconEdit(
                iconRef = "@mipmap/$name",
                roundIconRef = if (spec.generateRoundIcon) "@mipmap/$name$ROUND_SUFFIX" else null,
            ),
            replacing = files.map { it.relativePath }.filter(exists),
            warnings = warnings,
        )
    }

    /**
     * Emits the files for one layer and returns the resource reference the adaptive icon should point at, or
     * null for [AppIconLayer.None].
     */
    private fun layerFiles(
        layer: AppIconLayer,
        resourceName: String,
        out: MutableList<AppIconFile>,
        warnings: MutableList<String>,
        isBackground: Boolean,
    ): String? = when (layer) {
        AppIconLayer.None -> null

        is AppIconLayer.Color -> {
            // A flat layer is a colour resource, not a drawable: it is one line, and it can be re-themed.
            out += AppIconFile.Text(
                "values/$resourceName.xml",
                colorResourceXml(resourceName, layer.argb),
            )
            "@color/$resourceName"
        }

        is AppIconLayer.Vector -> {
            val composed = composeLayer(layer.spec, layer.scale, layer.offsetX, layer.offsetY)
            val tinted = layer.tintArgb?.let { composed.recolored(it) } ?: composed
            out += AppIconFile.Text("drawable/$resourceName.xml", VectorDrawableWriter.write(tinted))
            "@drawable/$resourceName"
        }

        is AppIconLayer.Raster -> {
            if (isBackground) warnings += "A bitmap background cannot be re-themed; a colour or vector is safer"
            // `nodpi` because the layer is already sized for the 108-unit box: letting Android density-scale
            // it would resample the artwork a second time.
            out += AppIconFile.Bytes("drawable-nodpi/$resourceName.${layer.extension}", layer.bytes)
            "@drawable/$resourceName"
        }
    }

    /**
     * [source] centred in the [BOX], scaled so its longest side fills [scale] of the [SAFE_ZONE].
     * [offsetX]/[offsetY] shift it by a fraction of the whole box.
     *
     * The source keeps its own coordinate space: it is wrapped in a `<group>` rather than having its path data
     * rewritten, so the artwork stays byte-identical to what was imported.
     */
    fun composeLayer(
        source: VectorSpec,
        scale: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): VectorSpec {
        val sourceW = source.viewportWidth.takeIf { it > 0f } ?: 24f
        val sourceH = source.viewportHeight.takeIf { it > 0f } ?: 24f
        val factor = (SAFE_ZONE * scale.coerceIn(MIN_SCALE, MAX_SCALE)) / max(sourceW, sourceH)
        val drawnW = sourceW * factor
        val drawnH = sourceH * factor
        val translateX = (BOX - drawnW) / 2f + offsetX * BOX
        val translateY = (BOX - drawnH) / 2f + offsetY * BOX
        return VectorSpec(
            widthDp = BOX,
            heightDp = BOX,
            viewportWidth = BOX,
            viewportHeight = BOX,
            nodes = listOf(
                VectorGroup(
                    children = source.nodes,
                    scaleX = factor,
                    scaleY = factor,
                    translateX = translateX,
                    translateY = translateY,
                ),
            ),
        )
    }

    private fun adaptiveIconXml(background: String?, foreground: String?, monochrome: String?): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<adaptive-icon xmlns:android=\"$ANDROID_NS\">\n")
            background?.let { append("    <background android:drawable=\"").append(it).append("\"/>\n") }
            foreground?.let { append("    <foreground android:drawable=\"").append(it).append("\"/>\n") }
            monochrome?.let { append("    <monochrome android:drawable=\"").append(it).append("\"/>\n") }
            append("</adaptive-icon>\n")
        }

    private fun legacyLayerListXml(background: String?, foreground: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<layer-list xmlns:android=\"$ANDROID_NS\">\n")
        background?.let { append("    <item android:drawable=\"").append(it).append("\"/>\n") }
        append("    <item android:drawable=\"").append(foreground).append("\"/>\n")
        append("</layer-list>\n")
    }

    private fun colorResourceXml(name: String, argb: Long): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<resources>\n")
        append("    <color name=\"").append(name).append("\">")
        append(VectorDrawableWriter.hex(argb))
        append("</color>\n")
        append("</resources>\n")
    }

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val ANYDPI_V26 = "mipmap-anydpi-v26"
    private const val BACKGROUND_SUFFIX = "_background"
    private const val FOREGROUND_SUFFIX = "_foreground"
    private const val MONOCHROME_SUFFIX = "_monochrome"
    private const val ROUND_SUFFIX = "_round"

    /** Up one level from `res/`, which is the source-set root: where AGP keeps the Play Store image. */
    private const val PLAY_STORE_PATH_PREFIX = "../"

    private const val MIN_SCALE = 0.2f
    private const val MAX_SCALE = 1.5f
}
