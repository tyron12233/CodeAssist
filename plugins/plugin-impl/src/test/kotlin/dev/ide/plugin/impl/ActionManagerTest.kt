package dev.ide.plugin.impl

import dev.ide.platform.PluginId
import dev.ide.platform.impl.ExtensionRegistryImpl
import dev.ide.plugin.action.ACTION_GROUP_EP
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionGroup
import dev.ide.plugin.action.ActionPlace
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import dev.ide.plugin.action.SimpleGroup
import dev.ide.plugin.action.UI_ACTION_EP
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PLUGIN = PluginId("test")

private fun ctx(
    place: ActionPlace,
    activeFilePath: String? = null,
    contextPath: String? = null,
): ActionContext = object : ActionContext {
    override val place = place
    override val projectRoot: String? = "/proj"
    override val activeFilePath = activeFilePath
    override val selectionStart: Int? = null
    override val selectionEnd: Int? = null
    override val contextPath = contextPath
}

class ActionManagerTest {

    @Test
    fun `actionsFor filters by place and orders by order then text`() {
        val reg = ExtensionRegistryImpl()
        // Registered out of order; resolution must sort by (order, text).
        reg.register(UI_ACTION_EP, action("z", "Zebra", ActionPlaces.MAIN_TOOLBAR, order = 10), PLUGIN)
        reg.register(UI_ACTION_EP, action("a", "Apple", ActionPlaces.MAIN_TOOLBAR, order = 10), PLUGIN)
        reg.register(UI_ACTION_EP, action("first", "First", ActionPlaces.MAIN_TOOLBAR, order = 5), PLUGIN)
        reg.register(UI_ACTION_EP, action("elsewhere", "Other", ActionPlaces.MORE_MENU, order = 1), PLUGIN)

        val mgr = ActionManager(reg)
        val ids = mgr.actionsFor(ctx(ActionPlaces.MAIN_TOOLBAR)).map { it.id }

        assertEquals(listOf("first", "a", "z"), ids)
    }

    @Test
    fun `invisible actions are excluded`() {
        val reg = ExtensionRegistryImpl()
        reg.register(UI_ACTION_EP, action("shown", "Shown", ActionPlaces.MORE_MENU), PLUGIN)
        reg.register(
            UI_ACTION_EP,
            SimpleAction("hidden", "Hidden", setOf(ActionPlaces.MORE_MENU), visible = { false }) { ActionResult.NONE },
            PLUGIN,
        )

        val ids = ActionManager(reg).actionsFor(ctx(ActionPlaces.MORE_MENU)).map { it.id }
        assertEquals(listOf("shown"), ids)
    }

    @Test
    fun `invoke runs perform and returns its result`() = runBlocking {
        val reg = ExtensionRegistryImpl()
        reg.register(
            UI_ACTION_EP,
            SimpleAction("greet", "Greet", setOf(ActionPlaces.COMMAND_PALETTE)) { ActionResult.message("hi") },
            PLUGIN,
        )
        val result = ActionManager(reg).invoke("greet", ctx(ActionPlaces.COMMAND_PALETTE))
        assertEquals("hi", result.message)
    }

    @Test
    fun `invoke of unknown id returns a message rather than throwing`() = runBlocking {
        val result = ActionManager(ExtensionRegistryImpl()).invoke("nope", ctx(ActionPlaces.COMMAND_PALETTE))
        assertTrue(result.message!!.contains("Unknown action"))
    }

    @Test
    fun `invoke of a disabled action is a no-op`() = runBlocking {
        val reg = ExtensionRegistryImpl()
        var ran = false
        reg.register(
            UI_ACTION_EP,
            SimpleAction("x", "X", setOf(ActionPlaces.COMMAND_PALETTE), enabled = { false }) {
                ran = true; ActionResult.message("ran")
            },
            PLUGIN,
        )
        val result = ActionManager(reg).invoke("x", ctx(ActionPlaces.COMMAND_PALETTE))
        assertEquals(ActionResult.NONE, result)
        assertTrue(!ran)
    }

    @Test
    fun `menuFor expands a group and excludes its children from the top level`() {
        val reg = ExtensionRegistryImpl()
        reg.register(UI_ACTION_EP, action("rename", "Rename", ActionPlaces.FILE_CONTEXT, order = 1), PLUGIN)
        reg.register(UI_ACTION_EP, action("javaClass", "Java Class", ActionPlaces.FILE_CONTEXT, order = 50), PLUGIN)
        reg.register(UI_ACTION_EP, action("kotlinFile", "Kotlin File", ActionPlaces.FILE_CONTEXT, order = 50), PLUGIN)
        // A "New" group placed in FILE_CONTEXT whose children are the two create actions above.
        reg.register(
            ACTION_GROUP_EP,
            SimpleGroup(
                "new", "New", setOf(ActionPlaces.FILE_CONTEXT), order = 0,
                children = listOf("javaClass", ActionGroup.SEPARATOR, "kotlinFile"),
            ),
            PLUGIN,
        )

        val menu = ActionManager(reg).menuFor(ctx(ActionPlaces.FILE_CONTEXT, contextPath = "/proj/A.java"))

        // Top level: the "New" submenu (order 0) then the "rename" action (order 1). The create actions are
        // nested, so they don't also appear at top level.
        assertEquals(2, menu.size)
        val submenu = menu[0] as ResolvedMenuItem.Submenu
        assertEquals("New", submenu.text)
        assertEquals(
            listOf("javaClass", "kotlinFile"),
            submenu.items.filterIsInstance<ResolvedMenuItem.Action>().map { it.action.id },
        )
        // The separator between them survives.
        assertTrue(submenu.items.any { it is ResolvedMenuItem.Separator })
        assertTrue(menu[1] is ResolvedMenuItem.Action)
        assertEquals("rename", (menu[1] as ResolvedMenuItem.Action).action.id)
    }

    @Test
    fun `menuFor drops a group that resolves to nothing`() {
        val reg = ExtensionRegistryImpl()
        reg.register(
            ACTION_GROUP_EP,
            SimpleGroup("empty", "Empty", setOf(ActionPlaces.MORE_MENU), children = listOf("missing", ActionGroup.SEPARATOR)),
            PLUGIN,
        )
        assertTrue(ActionManager(reg).menuFor(ctx(ActionPlaces.MORE_MENU)).isEmpty())
    }

    @Test
    fun `menuFor tolerates mutually-referential groups without looping`() {
        val reg = ExtensionRegistryImpl()
        reg.register(UI_ACTION_EP, action("leaf", "Leaf", ActionPlaces.MORE_MENU), PLUGIN)
        // a (top-level) -> b -> a (cycle) + leaf. The cycle back into `a` must be cut, not followed.
        reg.register(
            ACTION_GROUP_EP,
            SimpleGroup("a", "A", setOf(ActionPlaces.MORE_MENU), children = listOf("b")),
            PLUGIN,
        )
        reg.register(
            ACTION_GROUP_EP,
            SimpleGroup("b", "B", emptySet(), children = listOf("a", "leaf")),
            PLUGIN,
        )

        val menu = ActionManager(reg).menuFor(ctx(ActionPlaces.MORE_MENU))

        val a = menu.filterIsInstance<ResolvedMenuItem.Submenu>().single { it.id == "a" }
        val b = a.items.filterIsInstance<ResolvedMenuItem.Submenu>().single { it.id == "b" }
        // `b` keeps `leaf`; the recursion back into `a` was cut by the cycle guard (reaching here at all is
        // the real assertion — an unguarded resolver would not return).
        assertEquals(listOf("leaf"), b.items.filterIsInstance<ResolvedMenuItem.Action>().map { it.action.id })
        assertNull(b.items.firstOrNull { it is ResolvedMenuItem.Submenu })
    }

    private fun action(id: String, text: String, place: ActionPlace, order: Int = 1000) =
        SimpleAction(id, text, setOf(place), order = order) { ActionResult.NONE }
}
