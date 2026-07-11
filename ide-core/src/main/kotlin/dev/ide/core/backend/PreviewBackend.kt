package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.core.LayoutAttrInfo
import dev.ide.ui.backend.PreviewProgress
import dev.ide.ui.backend.PreviewService
import dev.ide.ui.backend.UiAttrKind
import dev.ide.ui.backend.UiColorEntry
import dev.ide.ui.backend.UiComposePreview
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiLayoutAttribute
import dev.ide.ui.backend.UiLayoutElement
import dev.ide.ui.backend.UiPreviewConfig
import dev.ide.ui.backend.UiPreviewResult
import dev.ide.ui.backend.UiTextEdit
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
            timedPass("previews", path, { it.size }) {
                ctx.background { ctx.services.composePreviews(Paths.get(path), text) }
            }.map(::toUiPreview)
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

    // ---- Real-view layout attribute editor ----

    override suspend fun layoutElementAt(path: String, text: String, sourceOffset: Int, id: String?): UiLayoutElement? =
        ctx.background { ctx.services.layoutElement(Paths.get(path), text, sourceOffset, id) }?.let { e ->
            UiLayoutElement(
                tag = e.tag, id = e.id, sourceOffset = e.sourceOffset,
                setAttributes = e.setAttributes.map(::toUiAttr),
                addable = e.addable.map(::toUiAttr),
            )
        }

    override suspend fun completeLayoutAttributeValue(
        path: String, text: String, sourceOffset: Int, id: String?, attrName: String, fieldText: String, caret: Int
    ): UiCompletionResult = try {
        ctx.interactive {
            ctx.services.completeLayoutAttributeValue(Paths.get(path), text, sourceOffset, id, attrName, fieldText, caret)
        }.toUi()
    } catch (_: EngineCanceledException) {
        UiCompletionResult(emptyList(), 0, fieldText.length)
    }

    override suspend fun setLayoutAttribute(
        path: String, text: String, sourceOffset: Int, id: String?, attrName: String, value: String
    ): List<UiTextEdit> =
        ctx.background { ctx.services.setLayoutAttributeEdits(Paths.get(path), text, sourceOffset, id, attrName, value) }
            .map { UiTextEdit(it.range.start, it.range.end, it.newText) }

    override suspend fun removeLayoutAttribute(
        path: String, text: String, sourceOffset: Int, id: String?, attrName: String
    ): List<UiTextEdit> =
        ctx.background { ctx.services.removeLayoutAttributeEdits(Paths.get(path), text, sourceOffset, id, attrName) }
            .map { UiTextEdit(it.range.start, it.range.end, it.newText) }

    private fun toUiAttr(a: LayoutAttrInfo): UiLayoutAttribute =
        UiLayoutAttribute(
            name = a.name, value = a.value, kind = attrKind(a),
            enumValues = a.enumValues, flagValues = a.flagValues, resourceRClasses = a.resourceRClasses,
        )

    /** Pick the value control for an attribute from its schema shape. Dimension wins over enum so `layout_width`
     *  shows the `wrap_content`/`match_parent` chips + a dp field (its enum keywords ride in [enumValues]). */
    private fun attrKind(a: LayoutAttrInfo): UiAttrKind = when {
        a.boolean -> UiAttrKind.BOOLEAN
        "dimen" in a.resourceRClasses -> UiAttrKind.DIMENSION
        a.flagValues.isNotEmpty() -> UiAttrKind.FLAGS
        a.enumValues.isNotEmpty() -> UiAttrKind.ENUM
        a.resourceRClasses.isNotEmpty() && a.resourceRClasses.all { it == "color" } -> UiAttrKind.COLOR
        a.resourceRClasses == listOf("integer") -> UiAttrKind.INTEGER
        a.resourceRClasses.isNotEmpty() -> UiAttrKind.REFERENCE
        else -> UiAttrKind.PLAIN
    }

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
