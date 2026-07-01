package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.ui.backend.PreviewProgress
import dev.ide.ui.backend.PreviewService
import dev.ide.ui.backend.UiColorEntry
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiPreviewConfig
import dev.ide.ui.backend.UiPreviewResult
import dev.ide.lang.kotlin.interp.PreviewInfo
import dev.ide.platform.EngineCanceledException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Paths

private val IDLE_PROGRESS: StateFlow<PreviewProgress?> = MutableStateFlow(null)

/** [PreviewService]: Compose `@Preview` discovery/run (interpreter, preemptible lanes) + drawable/color/image
 *  resource previews. Maps the engine's preview models onto the neutral UI DTOs. */
internal class PreviewBackend(private val ctx: BackendContext) : PreviewService {

    // The real-view layout render publishes its pipeline stage here (relink → render) for the status chip;
    // the engine owns the flow so it's the live one being updated. Idle (null) when no project is open.
    override val previewProgress: StateFlow<PreviewProgress?>
        get() = ctx.servicesOrNull?.realViewProgress ?: IDLE_PROGRESS

    override suspend fun composePreviews(path: String, text: String): List<UiComposePreview> =
    // Purely syntactic (scans for @Preview @Composable) — the interpreter only runs on the Preview button,
        // never per keystroke. Preemptible so it can't block completion; re-runs on the next edit if skipped.
        try {
            ctx.background { ctx.services.composePreviews(Paths.get(path), text) }
                .map(::toUiPreview)
        } catch (e: EngineCanceledException) {
            emptyList()
        }

    override suspend fun runComposePreview(
        path: String, text: String, functionName: String
    ): UiPreviewResult =
        ctx.preview { ctx.services.runComposePreview(Paths.get(path), text, functionName) }
            .let { UiPreviewResult(it.ok, it.message) }

    override suspend fun drawablePreview(path: String, text: String): UiDrawable? =
        ctx.services.drawablePreview(Paths.get(path), text)?.let(DrawableMapping::toUi)

    override suspend fun colorResources(path: String, text: String): List<UiColorEntry> =
        ctx.services.colorResources(Paths.get(path), text)
            .map { UiColorEntry(it.name, it.rawValue, it.argb) }

    override suspend fun resourceImageBytes(path: String): ByteArray? =
        ctx.services.resourceBytes(Paths.get(path))

    private fun toUiPreview(p: PreviewInfo): UiComposePreview {
        val c = p.config
        return UiComposePreview(
            functionName = p.functionName,
            offset = p.offset,
            variantId = p.variantId,
            label = p.label,
            group = c.group,
            arity = p.arity,
            hasParameter = p.parameter != null,
            config = UiPreviewConfig(
                widthDp = c.widthDp,
                heightDp = c.heightDp,
                showBackground = c.showBackground,
                backgroundColor = c.backgroundColor,
                fontScale = c.fontScale,
                nightMode = if (c.uiMode != null) c.isNight else null,
                locale = c.locale,
                apiLevel = c.apiLevel,
                showSystemUi = c.showSystemUi,
                device = c.device,
            ),
        )
    }

}
