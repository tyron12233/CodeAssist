package dev.ide.agent.impl

import dev.ide.agent.AgentWorkspace
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `add_dependency` names the configuration a library goes into, and `remove_dependency` takes one out. */
class DependencyToolsTest {

    private class Recording(private val base: FakeWorkspace = FakeWorkspace()) : AgentWorkspace by base {
        val calls = ArrayList<String>()
        override suspend fun addDependency(module: String, coordinate: String, scope: String): String {
            calls += "add $module $coordinate $scope"
            return "added"
        }
        override suspend fun removeDependency(module: String, coordinate: String): String {
            calls += "remove $module $coordinate"
            return "removed"
        }
    }

    private fun run(ws: AgentWorkspace, tool: String, json: String) =
        runBlocking { builtinTools(ws).first { it.spec.name == tool }.execute(JsonToolArgs(parseArgsObject(json))) }

    @Test
    fun `a dependency goes into the scope asked for, and implementation when none is`() {
        val ws = Recording()
        assertFalse(run(ws, "add_dependency", """{"module":"app","coordinate":"junit:junit:4.13.2","scope":"testImplementation"}""").isError)
        assertFalse(run(ws, "add_dependency", """{"module":"app","coordinate":"com.squareup.okhttp3:okhttp:4.12.0"}""").isError)
        assertEquals(
            listOf("add app junit:junit:4.13.2 testImplementation", "add app com.squareup.okhttp3:okhttp:4.12.0 implementation"),
            ws.calls,
        )
    }

    @Test
    fun `an unknown scope is refused rather than read as implementation`() {
        val ws = Recording()
        val result = run(ws, "add_dependency", """{"module":"app","coordinate":"a:b:1","scope":"testCompile"}""")
        assertTrue(result.isError && "testImplementation" in result.content, result.content)
        assertTrue(ws.calls.isEmpty())
    }

    @Test
    fun `remove_dependency is a gated tool that reaches the workspace`() {
        val ws = Recording()
        val tool = builtinTools(ws).first { it.spec.name == "remove_dependency" }
        assertTrue(tool.mutating)
        assertEquals("removed", run(ws, "remove_dependency", """{"module":"app","coordinate":"junit:junit"}""").content)
        assertEquals(listOf("remove app junit:junit"), ws.calls)
    }

    @Test
    fun `a workspace that knows one configuration still adds the library`() {
        // The default of the scoped method falls back to the one every workspace has.
        val result = run(FakeWorkspace(), "add_dependency", """{"module":"app","coordinate":"a:b:1","scope":"api"}""")
        assertFalse(result.isError, result.content)
    }
}
