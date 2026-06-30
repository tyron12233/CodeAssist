package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionEffect
import dev.ide.plugin.action.ActionPlace
import dev.ide.plugin.impl.ResolvedMenuItem
import dev.ide.ui.backend.ActionService
import dev.ide.ui.backend.UiActionContext
import dev.ide.ui.backend.UiActionEffect
import dev.ide.ui.backend.UiActionItem
import dev.ide.ui.backend.UiActionResult
import dev.ide.ui.backend.UiMenuGroup
import dev.ide.ui.backend.UiMenuNode
import kotlinx.coroutines.withContext

/** [ActionService] over the engine's [dev.ide.plugin.impl.ActionManager]: resolve/invoke the contributed
 *  toolbar/menu/palette actions, mapping the plugin-action model to/from the neutral UI DTOs. */
internal class ActionBackend(private val ctx: BackendContext) : ActionService {

    override fun actionsFor(uiCtx: UiActionContext): List<UiActionItem> {
        val c = uiCtx.toActionContext()
        return ctx.services.actions.actionsFor(c).map {
            UiActionItem(it.id, it.text, it.iconId, enabled = it.isEnabled(c))
        }
    }

    override fun menuFor(uiCtx: UiActionContext): UiMenuGroup =
        UiMenuGroup(ctx.services.actions.menuFor(uiCtx.toActionContext()).map { it.toUiMenuNode() })

    override suspend fun invokeAction(id: String, uiCtx: UiActionContext): UiActionResult {
        val c = uiCtx.toActionContext()
        val result = withContext(ctx.engineDispatcher) { ctx.services.actions.invoke(id, c) }
        return UiActionResult(result.message, result.effects.map { it.toUiEffect() })
    }

    private fun UiActionContext.toActionContext(): ActionContext {
        val snapshot = this
        return object : ActionContext {
            override val place = ActionPlace(snapshot.place)
            override val projectRoot: String? = ctx.services.workspaceRoot.toString()
            override val activeFilePath = snapshot.activeFilePath
            override val selectionStart = snapshot.selectionStart
            override val selectionEnd = snapshot.selectionEnd
            override val contextPath = snapshot.contextPath
        }
    }

    private fun ResolvedMenuItem.toUiMenuNode(): UiMenuNode = when (this) {
        is ResolvedMenuItem.Action -> UiMenuNode.Item(UiActionItem(action.id, action.text, action.iconId, enabled))
        is ResolvedMenuItem.Submenu -> UiMenuNode.Submenu(text, iconId, items.map { it.toUiMenuNode() })
        ResolvedMenuItem.Separator -> UiMenuNode.Separator
    }

    private fun ActionEffect.toUiEffect(): UiActionEffect = when (this) {
        is ActionEffect.OpenFile -> UiActionEffect.OpenFile(path, offset)
        is ActionEffect.Navigate -> UiActionEffect.Navigate(target)
        ActionEffect.RefreshTree -> UiActionEffect.RefreshTree
        is ActionEffect.ReloadFile -> UiActionEffect.ReloadFile(path)
    }
}
