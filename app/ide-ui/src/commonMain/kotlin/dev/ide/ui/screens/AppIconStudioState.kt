package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAppIconPlan
import dev.ide.ui.backend.UiAppIconPreview
import dev.ide.ui.backend.UiAppIconSpec
import dev.ide.ui.backend.UiAppIconState
import dev.ide.ui.backend.UiIconLayer
import dev.ide.ui.backend.UiIconVariant
import dev.ide.ui.backend.UiRasterFile
import dev.ide.ui.backend.UiResourceIcon
import dev.ide.ui.editor.preview.AppIconRaster
import dev.ide.ui.editor.preview.IconMask
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
    seedRepoId: String? = null,
    seedIconName: String? = null,
) {

    var spec: UiAppIconSpec by mutableStateOf(
        UiAppIconSpec(
            moduleName = "",
            foreground = if (seedRepoId != null && seedIconName != null) {
                UiIconLayer.RepoIcon(seedRepoId, seedIconName)
            } else {
                UiIconLayer.None
            },
        ),
    )
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

    /** Project drawables offered as a layer source. */
    var projectIcons: List<UiResourceIcon> by mutableStateOf(emptyList())
        private set

    var loading: Boolean by mutableStateOf(true)
        private set

    var applying: Boolean by mutableStateOf(false)
        private set

    var message: String? by mutableStateOf(null)
        private set

    var applied: Boolean by mutableStateOf(false)
        private set

    private var refreshJob: Job? = null

    init {
        scope.launch {
            val state = backend.icons.launcherIcon()
            current = state
            spec = spec.copy(moduleName = state?.moduleName.orEmpty())
            projectIcons = runCatching { backend.icons.projectIcons(state?.moduleName) }.getOrDefault(emptyList())
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
        update(spec.copy(background = UiIconLayer.Resource(path)))

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
                )
                bytes?.let { UiRasterFile(raster.relativePath, it) }
            }
            val result = backend.icons.applyAppIcon(spec, rendered)
            applying = false
            if (result.ok) {
                applied = true
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
        refresh()
    }

    private fun refresh(immediate: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (!immediate) kotlinx.coroutines.delay(REFRESH_DEBOUNCE_MS)
            val snapshot = spec
            preview = runCatching { backend.icons.previewAppIcon(snapshot) }.getOrNull()
            plan = runCatching { backend.icons.planAppIcon(snapshot) }.getOrNull()
        }
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 120L

        /** A themed icon is a stencil, so the layer is authored as flat black and re-tinted by the system. */
        const val MONOCHROME_TINT = 0xFF000000L
    }
}

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
    seedRepoId: String? = null,
    seedIconName: String? = null,
    scope: CoroutineScope = rememberCoroutineScope(),
): AppIconStudioState = remember(backend, seedRepoId, seedIconName, scope) {
    AppIconStudioState(backend, scope, seedRepoId, seedIconName)
}
