package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAppIconPlan
import dev.ide.ui.backend.UiAppIconPreview
import dev.ide.ui.backend.UiAppIconSpec
import dev.ide.ui.backend.UiAppIconState
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.backend.UiIconVariant
import dev.ide.ui.backend.UiRasterFile
import dev.ide.ui.editor.preview.AppIconRaster
import dev.ide.ui.editor.preview.IconMask
import dev.ide.ui.editor.preview.decodeImageBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The app-icon studio's state: the layers, their placement, which outputs to write, and the live preview.
 *
 * The preview and the plan are both recomputed from the backend whenever the spec changes, so the mask
 * previews, the file list and the PNGs that get written all come from one description of the icon. Editing is
 * debounced, because dragging a scale slider would otherwise re-resolve and re-compose every layer per frame.
 */
@Stable
internal class AppIconStudioState(
    private val backend: IdeBackend,
    private val scope: CoroutineScope,
    /** The foreground to start with: the icon the user picked in the Icon Manager, style and all. */
    seed: UiIconLayer? = null,
) {

    var spec: UiAppIconSpec by mutableStateOf(UiAppIconSpec(moduleName = "", foreground = seed ?: UiIconLayer.None))
        private set

    var current: UiAppIconState? by mutableStateOf(null)
        private set

    var preview: UiAppIconPreview? by mutableStateOf(null)
        private set

    var plan: UiAppIconPlan? by mutableStateOf(null)
        private set

    /** The mask the preview is showing. Purely a preview choice: every mask ships in the same icon. */
    var mask: IconMask by mutableStateOf(IconMask.SQUIRCLE)
        private set

    /** Show the Android 13+ themed rendering instead of the full-colour one. */
    var showThemed: Boolean by mutableStateOf(false)
        private set

    var loading: Boolean by mutableStateOf(true)
        private set

    var applying: Boolean by mutableStateOf(false)
        private set

    var message: String? by mutableStateOf(null)
        private set

    var applied: Boolean by mutableStateOf(false)
        private set

    /** The decoded image layers of [preview], keyed like [UiAppIconPreview.images]. */
    var images: Map<String, ImageBitmap> by mutableStateOf(emptyMap())
        private set

    /** Every file the last Apply wrote, as absolute paths, for the "what changed" list. */
    var written: List<String> by mutableStateOf(emptyList())
        private set

    private var refreshJob: Job? = null

    init {
        scope.launch {
            val state = backend.icons.launcherIcon()
            current = state
            spec = spec.copy(moduleName = state?.moduleName.orEmpty())
            loading = false
            refresh(immediate = true)
        }
    }

    /** True when there is an Android module to write an icon into. */
    fun hasModule(): Boolean = current != null

    // --- intents ---

    fun selectMask(next: IconMask) {
        mask = next
    }

    fun toggleThemed() {
        showThemed = !showThemed
    }

    fun setName(value: String) {
        val cleaned = value.trim().lowercase().replace(' ', '_').filter { it.isLetterOrDigit() || it == '_' }
        update(spec.copy(name = cleaned.ifBlank { "ic_launcher" }))
    }

    fun setBackgroundColor(argb: Long) = update(spec.copy(background = UiIconLayer.Color(argb)))

    fun clearBackground() = update(spec.copy(background = UiIconLayer.None))

    fun setBackgroundResource(path: String) =
        update(spec.copy(background = UiIconLayer.Resource(path, scale = BACKGROUND_FILL)))

    fun setBackgroundGradient(startArgb: Long, endArgb: Long, angle: Int) =
        update(spec.copy(background = UiIconLayer.Gradient(startArgb, endArgb, angle)))

    fun setBackgroundImage(path: String) = update(spec.copy(background = UiIconLayer.ImageFile(path)))

    /**
     * Use [layer] (picked in the icon browser) as the background. Artwork is stretched over the whole box
     * rather than fitted to the safe zone, because a background that stops short of the mask shows a frame.
     */
    fun setBackgroundLayer(layer: UiIconLayer) = update(
        spec.copy(
            background = when (layer) {
                is UiIconLayer.RepoIcon -> layer.copy(scale = BACKGROUND_FILL)
                is UiIconLayer.Resource -> layer.copy(scale = BACKGROUND_FILL)
                else -> layer
            },
        ),
    )

    /** Use [layer] (picked in the icon browser) as the foreground, keeping the current placement and tint. */
    fun setForegroundLayer(layer: UiIconLayer) {
        val tint = foregroundTint()
        val placedLayer = when (val next = placed(layer)) {
            is UiIconLayer.RepoIcon -> next.copy(tintArgb = tint)
            is UiIconLayer.Resource -> next.copy(tintArgb = tint)
            else -> next
        }
        val mono = spec.monochrome != UiIconLayer.None
        update(spec.copy(foreground = placedLayer))
        // The themed layer follows the foreground it was copied from, or it would keep showing the old icon.
        if (mono) monochromeFromForeground()
    }

    fun setForegroundResource(path: String) =
        update(spec.copy(foreground = placed(UiIconLayer.Resource(path))))

    fun setForegroundImage(path: String) = update(spec.copy(foreground = UiIconLayer.ImageFile(path)))

    fun setForegroundRepoIcon(repoId: String, name: String, variant: UiIconVariant = UiIconVariant()) =
        update(spec.copy(foreground = placed(UiIconLayer.RepoIcon(repoId, name, variant))))

    fun clearForeground() = update(spec.copy(foreground = UiIconLayer.None))

    /** Reuse the foreground artwork as the themed layer, which is what most icons want. */
    fun monochromeFromForeground() {
        val source = spec.foreground
        // A themed icon is drawn as a single-colour stencil, so tinting the copy black is the honest preview.
        val mono = when (source) {
            is UiIconLayer.RepoIcon -> source.copy(tintArgb = MONOCHROME_TINT)
            is UiIconLayer.Resource -> source.copy(tintArgb = MONOCHROME_TINT)
            else -> UiIconLayer.None
        }
        update(spec.copy(monochrome = mono))
    }

    fun clearMonochrome() = update(spec.copy(monochrome = UiIconLayer.None))

    fun setScale(value: Float) = updatePlacement { scale, _, _ -> Triple(value, scale.second, scale.third) }

    fun setOffsetX(value: Float) = updatePlacement { p, _, _ -> Triple(p.first, value, p.third) }

    fun setOffsetY(value: Float) = updatePlacement { p, _, _ -> Triple(p.first, p.second, value) }

    fun setForegroundTint(argb: Long?) {
        val next = when (val fg = spec.foreground) {
            is UiIconLayer.RepoIcon -> fg.copy(tintArgb = argb)
            is UiIconLayer.Resource -> fg.copy(tintArgb = argb)
            else -> return
        }
        update(spec.copy(foreground = next))
    }

    fun toggleRasters() = update(spec.copy(generateRasters = !spec.generateRasters))

    fun toggleRoundIcon() = update(spec.copy(generateRoundIcon = !spec.generateRoundIcon))

    fun togglePlayStoreIcon() = update(spec.copy(generatePlayStoreIcon = !spec.generatePlayStoreIcon))

    fun dismissMessage() {
        message = null
    }

    /** The foreground's current placement, as (scale, offsetX, offsetY). */
    fun placement(): Triple<Float, Float, Float> = when (val fg = spec.foreground) {
        is UiIconLayer.RepoIcon -> Triple(fg.scale, fg.offsetX, fg.offsetY)
        is UiIconLayer.Resource -> Triple(fg.scale, fg.offsetX, fg.offsetY)
        else -> Triple(1f, 0f, 0f)
    }

    /** The foreground's tint, or null when it keeps its own colours. */
    fun foregroundTint(): Long? = when (val fg = spec.foreground) {
        is UiIconLayer.RepoIcon -> fg.tintArgb
        is UiIconLayer.Resource -> fg.tintArgb
        else -> null
    }

    /**
     * Render every raster the plan asks for and commit the change. Rasterising happens here, on the Compose
     * side, because that is the only place with a canvas; the engine writes the bytes it is handed.
     */
    fun apply() {
        val snapshot = preview
        if (applying || snapshot == null) return
        applying = true
        scope.launch {
            val target = backend.icons.planAppIcon(spec)
            if (target == null) {
                applying = false
                message = "There is no module to write the icon into"
                return@launch
            }
            val rendered = target.rasters.mapNotNull { raster ->
                val bytes = AppIconRaster.renderPng(
                    preview = snapshot,
                    pixels = raster.pixels,
                    mask = if (raster.round) IconMask.CIRCLE else IconMask.ROUNDED_SQUARE,
                    opaqueBackground = if (raster.opaque) AppIconRaster.opaqueGround(snapshot) else null,
                    images = images,
                )
                bytes?.let { UiRasterFile(raster.relativePath, it) }
            }
            val result = backend.icons.applyAppIcon(spec, rendered)
            applying = false
            if (result.ok) {
                applied = true
                written = target.files.map { resolveAgainst(target.resDirPath, it) }
                message = null
                plan = backend.icons.planAppIcon(spec)
            } else {
                message = result.message
            }
        }
    }

    // --- internals ---

    private fun placed(layer: UiIconLayer): UiIconLayer {
        val (scale, x, y) = placement()
        return when (layer) {
            is UiIconLayer.RepoIcon -> layer.copy(scale = scale, offsetX = x, offsetY = y)
            is UiIconLayer.Resource -> layer.copy(scale = scale, offsetX = x, offsetY = y)
            else -> layer
        }
    }

    private inline fun updatePlacement(
        transform: (Triple<Float, Float, Float>, Float, Float) -> Triple<Float, Float, Float>,
    ) {
        val (scale, x, y) = transform(placement(), 0f, 0f)
        val next = when (val fg = spec.foreground) {
            is UiIconLayer.RepoIcon -> fg.copy(scale = scale, offsetX = x, offsetY = y)
            is UiIconLayer.Resource -> fg.copy(scale = scale, offsetX = x, offsetY = y)
            else -> return
        }
        update(spec.copy(foreground = next))
    }

    private fun update(next: UiAppIconSpec) {
        spec = next
        applied = false
        written = emptyList()
        refresh()
    }

    private fun refresh(immediate: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (!immediate) kotlinx.coroutines.delay(REFRESH_DEBOUNCE_MS)
            val snapshot = spec
            val next = runCatching { backend.icons.previewAppIcon(snapshot) }.getOrNull()
            // Decoded before the preview is published, so an image layer never flashes in empty.
            images = next?.images.orEmpty().mapNotNull { (key, bytes) -> decodeImageBytes(bytes)?.let { key to it } }.toMap()
            preview = next
            plan = runCatching { backend.icons.planAppIcon(snapshot) }.getOrNull()
        }
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 120L

        /** The whole 108-unit box as a multiple of the 72-unit safe zone: a background has to reach the edges. */
        const val BACKGROUND_FILL = 1.5f

        /** A themed icon is a stencil, so the layer is authored as flat black and re-tinted by the system. */
        const val MONOCHROME_TINT = 0xFF000000L
    }
}

/**
 * [relative] (as the plan lists it, `/`-separated, possibly `../` for the Play Store image beside `res/`)
 * resolved against [dir], keeping [dir]'s own separator so a Windows path stays one.
 */
internal fun resolveAgainst(dir: String, relative: String): String {
    var base = dir.trimEnd('/', '\\')
    var rest = relative
    while (rest.startsWith("../")) {
        val cut = maxOf(base.lastIndexOf('/'), base.lastIndexOf('\\'))
        if (cut <= 0) break
        base = base.substring(0, cut)
        rest = rest.removePrefix("../")
    }
    val separator = if ('\\' in base && '/' !in base) '\\' else '/'
    return base + separator + rest.replace('/', separator)
}

/** Gradient presets for the background, as start/end pairs. */
internal val APP_ICON_GRADIENTS = listOf(
    0xFF3DDC84L to 0xFF0B8043L,
    0xFF4FC3F7L to 0xFF1A237EL,
    0xFFFF8A65L to 0xFFD81B60L,
    0xFFFFD54FL to 0xFFF4511EL,
    0xFFB388FFL to 0xFF311B92L,
    0xFF80CBC4L to 0xFF00695CL,
    0xFF90A4AEL to 0xFF263238L,
)

/** The palette the studio offers for a flat background. */
internal val APP_ICON_BACKGROUNDS = listOf(
    0xFFFFFFFFL, 0xFF000000L, 0xFF3DDC84L, 0xFF2196F3L, 0xFF6200EEL,
    0xFFE53935L, 0xFFFB8C00L, 0xFF43A047L, 0xFF546E7AL,
)

/** The tints the studio offers for the foreground artwork. */
internal val APP_ICON_TINTS = listOf(0xFFFFFFFFL, 0xFF000000L, 0xFF3DDC84L, 0xFF2196F3L, 0xFFE53935L)

@Composable
internal fun rememberAppIconStudioState(
    backend: IdeBackend,
    seed: UiIconLayer? = null,
    scope: CoroutineScope = rememberCoroutineScope(),
): AppIconStudioState = remember(backend, seed, scope) {
    AppIconStudioState(backend, scope, seed)
}
