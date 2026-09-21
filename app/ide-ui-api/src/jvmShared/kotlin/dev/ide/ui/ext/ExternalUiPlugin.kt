package dev.ide.ui.ext

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.theme.colors.AttributeStyle
import dev.ide.ui.theme.colors.ColorAttribute
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceLookup
import dev.ide.plugin.ui.ColorAttribute as ExternalColorAttribute
import dev.ide.plugin.ui.Overlay
import dev.ide.plugin.ui.EditorAnchor as ExternalAnchorPoint
import dev.ide.plugin.ui.EditorLanguage as ExternalEditorLanguage
import dev.ide.plugin.ui.FileIcon as ExternalFileIcon
import dev.ide.plugin.ui.EditorLayer as ExternalEditorLayer
import dev.ide.plugin.ui.EditorLayerContext as ExternalLayerContext
import dev.ide.plugin.ui.EditorPaintContext as ExternalPaintContext
import dev.ide.plugin.ui.EditorPaintLayer as ExternalPaintLayer
import dev.ide.plugin.ui.EditorPainter as ExternalEditorPainter
import dev.ide.plugin.ui.EditorPreview as ExternalEditorPreview
import dev.ide.plugin.ui.EditorViewMode as ExternalViewMode
import dev.ide.plugin.ui.EditorViewModeContext as ExternalViewModeContext
import dev.ide.plugin.ui.EditorPreviewContext as ExternalPreviewContext
import dev.ide.plugin.ui.Screen
import dev.ide.plugin.ui.ScreenUiContext
import dev.ide.plugin.ui.SyntaxStyle as ExternalSyntax
import dev.ide.plugin.ui.ToolWindow
import dev.ide.plugin.ui.ToolWindowAnchor as ExternalAnchor
import dev.ide.plugin.ui.UiContext
import dev.ide.plugin.ui.UiHandle
import dev.ide.plugin.ui.UiPlugin as ExternalUiPlugin
import dev.ide.plugin.ui.UiRegistration

/**
 * Adapts a plugin's UI facet ([ExternalUiPlugin], from the published `plugin-ui-api`) onto the internal
 * contribution model, so an installed plugin's tool windows, screens and overlays land in the same registries
 * the built-in UI plugins use and the host renders them without knowing where they came from.
 *
 * The adaptation exists because the two models are deliberately different. Internally a body is handed the
 * whole `IdeBackend`; publishing that would freeze every concern service and DTO in it as plugin API. The
 * published surface is `UiContext` instead: where the user is, plus the few operations a panel cannot perform
 * itself. Everything else a plugin needs it reaches through its **engine** facet, which shares its
 * classloader, so the two halves of one plugin are ordinary Kotlin to each other.
 *
 * Ids pass through verbatim rather than being namespaced by plugin id, because an engine-side action's
 * `ActionEffect.Navigate(id)` has to name the same screen the UI facet registered. Two plugins claiming one
 * id is the same collision two built-ins would have.
 *
 * Lives in `jvmShared` (the desktop + android targets, not `commonMain`) because `plugin-ui-api` is a plain
 * JVM artifact: a plugin ships as a JVM/Android APK, so there is nothing for the other targets to load.
 */
fun ExternalUiPlugin.asUiPlugin(
    pluginId: String,
    services: ServiceLookup = ServiceLookup.Empty,
): UiPlugin = BridgedUiPlugin(pluginId, this, services)

private class BridgedUiPlugin(
    override val id: String,
    private val delegate: ExternalUiPlugin,
    private val services: ServiceLookup,
) : UiPlugin {

    override fun contributeUi(scope: UiContributionScope) {
        delegate.contribute(BridgedRegistration(id, scope, services))
    }
}

private class BridgedRegistration(
    override val pluginId: String,
    private val scope: UiContributionScope,
    private val services: ServiceLookup,
) : UiRegistration {

    override fun toolWindow(toolWindow: ToolWindow): UiHandle {
        val registration = scope.toolWindow(
            ToolWindowContribution(
                id = toolWindow.id,
                title = toolWindow.title,
                iconId = toolWindow.iconId,
                anchor = toolWindow.anchor.internal(),
                order = toolWindow.order,
            ) { ctx ->
                // Remembered per context instance: the host re-creates one when the file or the navigation
                // handles change, and the body should not see a new object on every recomposition.
                toolWindow.content(remember(ctx) { ToolWindowUiContext(ctx, services) })
            },
        )
        return UiHandle { registration.dispose() }
    }

    override fun screen(screen: Screen): UiHandle {
        val registration = scope.screen(
            ScreenContribution(id = screen.id, title = screen.title) { ctx ->
                screen.content(remember(ctx) { HostScreenUiContext(ctx, services) })
            },
        )
        return UiHandle { registration.dispose() }
    }

    override fun overlay(overlay: Overlay): UiHandle {
        val registration = scope.overlay(
            OverlayContribution(id = overlay.id) { ctx ->
                overlay.content(remember(ctx) { OverlayUiContext(ctx, services) })
            },
        )
        return UiHandle { registration.dispose() }
    }

    override fun editorLayer(layer: ExternalEditorLayer): UiHandle {
        val registration = scope.editorLayer(
            EditorLayerContribution(
                id = layer.id,
                order = layer.order,
                appliesTo = layer.appliesTo,
            ) { ctx ->
                // Keyed on what a layer can observe rather than on the context instance: the context carries
                // the buffer and the viewport, so it changes on every keystroke and every scroll, and
                // remembering per instance would allocate a wrapper per frame.
                val bridged = remember(ctx.path, ctx.text, ctx.visibleLines, ctx.caretOffset, ctx) {
                    LayerUiContext(ctx, services)
                }
                layer.widgets(bridged).map { w ->
                    EditorWidget(anchor = w.anchor.internal(), key = w.key, content = w.content)
                }
            },
        )
        return UiHandle { registration.dispose() }
    }

    override fun editorPainter(painter: ExternalEditorPainter): UiHandle {
        val registration = scope.editorPainter(
            EditorPainterContribution(
                id = painter.id,
                order = painter.order,
                layer = painter.layer.internal(),
                appliesTo = painter.appliesTo,
            ) { ctx ->
                // No wrapper allocation per frame: the paint context is passed straight through, since the
                // published and internal interfaces have the same members. The adapter is one object, built
                // per frame by the caller either way.
                painter.paint(this, PaintContextView(ctx))
            },
        )
        return UiHandle { registration.dispose() }
    }

    override fun viewMode(mode: ExternalViewMode): UiHandle {
        val registration = scope.viewMode(
            EditorViewModeContribution(
                id = mode.id,
                label = mode.label,
                iconId = mode.iconId,
                order = mode.order,
                appliesTo = mode.appliesTo,
                isDefault = mode.isDefault,
            ) { ctx ->
                // Keyed on what a pane can observe: the context carries the buffer and the caret, so it
                // changes on every keystroke and remembering per instance would allocate per frame.
                mode.content(
                    remember(ctx.filePath, ctx.text, ctx.caretOffset, ctx) { ViewModeUiContext(ctx, services) }
                )
            },
        )
        return UiHandle { registration.dispose() }
    }

    override fun editorLanguage(language: ExternalEditorLanguage): UiHandle {
        val registration = scope.editorLanguage(
            EditorLanguageProfile(
                id = language.id,
                suffixes = language.suffixes,
                syntax = language.syntax.internal(),
                keywords = language.keywords,
                lineComment = language.lineComment,
                blockCommentOpen = language.blockCommentOpen,
                blockCommentClose = language.blockCommentClose,
                directivePrefix = language.directivePrefix,
                // Filtered to the token types that exist, so a typo in a published plugin costs that one
                // custom color instead of leaving a mapping the host will never consult and never mention.
                tokenColorKeys = language.tokenColorKeys.filterKeys { it in TOKEN_TYPE_NAMES },
                order = language.order,
            ),
        )
        return UiHandle { registration.dispose() }
    }

    override fun colorAttribute(attribute: ExternalColorAttribute): UiHandle {
        val registration = scope.colorAttribute(
            ColorAttribute(
                key = attribute.key,
                title = attribute.title,
                group = attribute.group,
                parent = attribute.parent,
                defaultDark = attribute.styleFor(attribute.darkColor),
                defaultLight = attribute.styleFor(attribute.lightColor),
            ),
        )
        return UiHandle { registration.dispose() }
    }

    override fun fileIcon(icon: ExternalFileIcon): UiHandle {
        val registration = scope.fileIcon(
            iconId = icon.id,
            suffixes = icon.suffixes,
            // A badge, not a glyph: a plugin cannot ship a drawable (it has no Context for its own package)
            // and the published surface carries no vector type, so the art it CAN describe is a short string
            // and a color -- which is what the IDE's own `J`, `K` and `R8` file badges already are.
            icon = TreeIcon.Badge(icon.badge, Color(icon.color.toULong().toLong())),
        )
        return UiHandle { registration.dispose() }
    }

    override fun editorPreview(preview: ExternalEditorPreview): UiHandle {
        val registration = scope.editorPreview(
            EditorPreviewContribution(
                id = preview.id,
                title = preview.title,
                appliesTo = preview.appliesTo,
            ) { ctx ->
                // Not remembered on the context alone: unlike a tool window, a preview context changes on
                // every keystroke (it carries the buffer), so remembering it per instance would allocate a
                // wrapper per frame for nothing. Keyed on what a body can actually observe instead.
                preview.content(
                    remember(ctx.path, ctx.text, ctx.dark, ctx) { PreviewUiContext(ctx, services) }
                )
            },
        )
        return UiHandle { registration.dispose() }
    }
}

/**
 * A published attribute's style for one variant.
 *
 * A font flag crosses the SPI as a plain `Boolean`, because a plugin author should not have to think in
 * three states to say "bold". So `false` is carried as "no opinion" rather than as an explicit `false`:
 * the host's model is tri-state, and writing the `false` a plugin never typed would BLOCK the flag from
 * being inherited down the attribute's own parent chain. `true` is the only thing anyone actually said.
 */
private fun ExternalColorAttribute.styleFor(color: Long?): AttributeStyle = AttributeStyle(
    foreground = color?.let { Color(it.toULong().toLong()) },
    bold = bold.orNull(),
    italic = italic.orNull(),
    underline = underline.orNull(),
    strikethrough = strikethrough.orNull(),
)

private fun Boolean.orNull(): Boolean? = if (this) true else null

/** The token-type names a profile's `tokenColorKeys` may address; see `dev.ide.ui.editor.core.TokenType`. */
private val TOKEN_TYPE_NAMES = setOf(
    "KEYWORD", "STRING", "COMMENT", "NUMBER", "ANNOTATION", "FUNC", "TYPE", "PUNCT", "PROPERTY",
)

private fun ExternalSyntax.internal(): SyntaxFamily = when (this) {
    ExternalSyntax.C_FAMILY -> SyntaxFamily.C_FAMILY
    ExternalSyntax.XML -> SyntaxFamily.XML
    ExternalSyntax.HASH_COMMENT -> SyntaxFamily.HASH_COMMENT
    ExternalSyntax.MARKDOWN -> SyntaxFamily.MARKDOWN
    ExternalSyntax.PLAIN -> SyntaxFamily.PLAIN
}

private fun ExternalAnchor.internal(): ToolWindowAnchor = when (this) {
    ExternalAnchor.LEFT -> ToolWindowAnchor.LEFT
    ExternalAnchor.RIGHT -> ToolWindowAnchor.RIGHT
    ExternalAnchor.BOTTOM -> ToolWindowAnchor.BOTTOM
}

/** The open project's root, or null when none is open (the picker reports an empty path). */
private fun projectPathOf(backend: dev.ide.ui.backend.IdeBackend): String? =
    runCatching { backend.project.rootPath }.getOrNull()?.takeIf { it.isNotEmpty() }

private fun ExternalAnchorPoint.internal(): EditorAnchor = when (this) {
    is ExternalAnchorPoint.AtOffset -> EditorAnchor.AtOffset(offset)
    is ExternalAnchorPoint.AfterLine -> EditorAnchor.AfterLine(line)
    is ExternalAnchorPoint.AboveLine -> EditorAnchor.AboveLine(line)
}

private fun ExternalPaintLayer.internal(): EditorPaintLayer = when (this) {
    ExternalPaintLayer.BelowText -> EditorPaintLayer.BelowText
    ExternalPaintLayer.AboveText -> EditorPaintLayer.AboveText
}

private class LayerUiContext(private val ctx: EditorLayerContext, private val services: ServiceLookup) : ExternalLayerContext {
    override val projectPath: String? get() = projectPathOf(ctx.backend)

    /** The decorated file IS the focused tab, so both answer the same path; [path] is the non-null form. */
    override val activeFilePath: String get() = ctx.path
    override val path: String get() = ctx.path
    override val text: String get() = ctx.text
    override val visibleLines: IntRange get() = ctx.visibleLines
    override val caretOffset: Int get() = ctx.caretOffset
    override fun openFile(path: String, offset: Int) = ctx.openFile(path, offset)
    override fun openScreen(id: String) = ctx.openScreen(id)

    override fun <T : Any> service(key: ServiceKey<T>): T? = services.getServiceOrNull(key)
}

/**
 * The published [ExternalPaintContext] over the internal one.
 *
 * A pass-through rather than a copy: this is built inside a draw, so the members must not compute anything.
 * The two interfaces are deliberately identical in shape, which is what lets every member be a delegation.
 */
private class PaintContextView(private val ctx: EditorPaintContext) : ExternalPaintContext {
    override val path: String get() = ctx.path
    override val visibleLines: IntRange get() = ctx.visibleLines
    override val lineCount: Int get() = ctx.lineCount
    override val lineHeight: Float get() = ctx.lineHeight
    override val charWidth: Float get() = ctx.charWidth
    override val gutterWidth: Float get() = ctx.gutterWidth
    override val textLeft: Float get() = ctx.textLeft
    override fun lineTop(line: Int): Float = ctx.lineTop(line)
    override fun xOf(offset: Int): Float = ctx.xOf(offset)
    override fun lineOf(offset: Int): Int = ctx.lineOf(offset)
    override fun lineRange(line: Int): IntRange = ctx.lineRange(line)
    override fun isHidden(line: Int): Boolean = ctx.isHidden(line)
}

private class ViewModeUiContext(private val ctx: ViewModeContext, private val services: ServiceLookup) : ExternalViewModeContext {
    override val projectPath: String? get() = projectPathOf(ctx.backend)

    /** The pane's file IS the focused tab, so both answer the same path; [path] is the non-null form. */
    override val activeFilePath: String get() = ctx.filePath
    override val path: String get() = ctx.filePath
    override val text: String get() = ctx.text
    override val caretOffset: Int get() = ctx.caretOffset
    override fun replaceText(start: Int, end: Int, newText: String) = ctx.replaceText(start, end, newText)
    override fun openFile(path: String, offset: Int) = ctx.openFile(path, offset)
    override fun openScreen(id: String) {}

    override fun <T : Any> service(key: ServiceKey<T>): T? = services.getServiceOrNull(key)
}

private class ToolWindowUiContext(private val ctx: ToolWindowContext, private val services: ServiceLookup) : UiContext {
    override val projectPath: String? get() = projectPathOf(ctx.backend)
    override val activeFilePath: String? get() = ctx.activeFilePath
    override fun openFile(path: String, offset: Int) = ctx.openFile(path, offset)
    override fun openScreen(id: String) = ctx.openScreen(id)

    override fun <T : Any> service(key: ServiceKey<T>): T? = services.getServiceOrNull(key)
}

private class OverlayUiContext(private val ctx: OverlayContext, private val services: ServiceLookup) : UiContext {
    override val projectPath: String? get() = projectPathOf(ctx.backend)

    /** An overlay is app-wide, not tied to a tab; the host hands it no file. */
    override val activeFilePath: String? get() = null
    override fun openFile(path: String, offset: Int) = ctx.openFile(path, offset)
    override fun openScreen(id: String) = ctx.openScreen(id)

    override fun <T : Any> service(key: ServiceKey<T>): T? = services.getServiceOrNull(key)
}

private class PreviewUiContext(private val ctx: EditorPreviewContext, private val services: ServiceLookup) : ExternalPreviewContext {
    override val projectPath: String? get() = projectPathOf(ctx.backend)

    /** The previewed file IS the active one, so both answer the same path; [path] is the non-null form a
     *  preview body can rely on. */
    override val activeFilePath: String get() = ctx.path
    override val path: String get() = ctx.path
    override val text: String get() = ctx.text
    override val dark: Boolean get() = ctx.dark
    override fun reportProblems(problems: List<String>) = ctx.reportProblems(problems)
    override fun openFile(path: String, offset: Int) = ctx.openFile(path, offset)
    override fun openScreen(id: String) = ctx.openScreen(id)

    override fun <T : Any> service(key: ServiceKey<T>): T? = services.getServiceOrNull(key)
}

private class HostScreenUiContext(private val ctx: ScreenContext, private val services: ServiceLookup) : ScreenUiContext {
    override val projectPath: String? get() = projectPathOf(ctx.backend)

    /** A contributed screen replaces the editor rather than sitting beside it, so there is no active tab
     *  from its point of view. */
    override val activeFilePath: String? get() = null
    override fun openFile(path: String, offset: Int) = ctx.openFile(path, offset)
    override fun openScreen(id: String) = ctx.openScreen(id)
    override fun back() = ctx.back()

    override fun <T : Any> service(key: ServiceKey<T>): T? = services.getServiceOrNull(key)
}
