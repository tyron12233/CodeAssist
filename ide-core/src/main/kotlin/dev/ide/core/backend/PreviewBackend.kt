package dev.ide.core.backend

import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.GradientSpec
import dev.ide.android.support.preview.Layer
import dev.ide.android.support.preview.StateLayer
import dev.ide.android.support.preview.VectorPath
import dev.ide.core.BackendContext
import dev.ide.ui.backend.PreviewService
import dev.ide.ui.backend.UiColorEntry
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiGradient
import dev.ide.ui.backend.UiLayer
import dev.ide.ui.backend.UiPreviewConfig
import dev.ide.ui.backend.UiPreviewResult
import dev.ide.ui.backend.UiStateLayer
import dev.ide.ui.backend.UiVectorPath
import dev.ide.lang.kotlin.interp.PreviewInfo
import dev.ide.platform.EngineCanceledException
import java.nio.file.Paths

/** [PreviewService]: Compose `@Preview` discovery/run (interpreter, preemptible lanes) + drawable/color/image
 *  resource previews. Maps the engine's preview models onto the neutral UI DTOs. */
internal class PreviewBackend(private val ctx: BackendContext) : PreviewService {

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
        ctx.services.drawablePreview(Paths.get(path), text)?.let(::toUiDrawable)

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

    /** Map the engine's [DrawablePreview] onto the UI's neutral [UiDrawable] DTO. */
    private fun toUiDrawable(d: DrawablePreview): UiDrawable = when (d) {
        is DrawablePreview.SolidColor -> UiDrawable.SolidColor(d.color)
        is DrawablePreview.Shape -> d.spec.let { s ->
            UiDrawable.Shape(
                shape = s.shape.name.lowercase(),
                solidColor = s.solidColor,
                gradient = s.gradient?.let(::toUiGradient),
                strokeColor = s.strokeColor,
                strokeWidthDp = s.strokeWidthDp,
                dashWidthDp = s.dashWidthDp,
                dashGapDp = s.dashGapDp,
                cornerTopLeftDp = s.cornerTopLeftDp,
                cornerTopRightDp = s.cornerTopRightDp,
                cornerBottomRightDp = s.cornerBottomRightDp,
                cornerBottomLeftDp = s.cornerBottomLeftDp,
                intrinsicWidthDp = s.intrinsicWidthDp,
                intrinsicHeightDp = s.intrinsicHeightDp,
                innerRadiusFraction = s.innerRadiusFraction,
                thicknessFraction = s.thicknessFraction,
            )
        }

        is DrawablePreview.Vector -> d.spec.let { v ->
            UiDrawable.Vector(
                widthDp = v.widthDp, heightDp = v.heightDp,
                viewportWidth = v.viewportWidth, viewportHeight = v.viewportHeight,
                rootAlpha = v.rootAlpha,
                paths = v.paths.map(::toUiVectorPath),
            )
        }

        is DrawablePreview.Layers -> UiDrawable.Layers(d.layers.map(::toUiLayer))
        is DrawablePreview.States -> UiDrawable.States(
            states = d.states.map(::toUiStateLayer),
            defaultLayer = d.defaultLayer?.let(::toUiDrawable),
        )

        is DrawablePreview.BitmapRef -> UiDrawable.Bitmap(d.resType, d.resName, d.filePath)
        is DrawablePreview.Unsupported -> UiDrawable.Unsupported(d.rootTag, d.message)
    }

    private fun toUiGradient(g: GradientSpec) = UiGradient(
        kind = g.kind.name.lowercase(),
        startColor = g.startColor,
        centerColor = g.centerColor,
        endColor = g.endColor,
        angle = g.angle,
        centerX = g.centerX,
        centerY = g.centerY,
        radiusFraction = g.radiusFraction,
    )

    private fun toUiVectorPath(p: VectorPath) = UiVectorPath(
        pathData = p.pathData, fillColor = p.fillColor, strokeColor = p.strokeColor,
        strokeWidthVp = p.strokeWidthVp, fillAlpha = p.fillAlpha, strokeAlpha = p.strokeAlpha,
    )

    private fun toUiLayer(l: Layer) = UiLayer(
        drawable = toUiDrawable(l.drawable),
        insetLeftDp = l.insetLeftDp, insetTopDp = l.insetTopDp,
        insetRightDp = l.insetRightDp, insetBottomDp = l.insetBottomDp,
    )

    private fun toUiStateLayer(s: StateLayer) = UiStateLayer(s.states, toUiDrawable(s.drawable))
}
