package dev.ide.core.agent

import dev.ide.agent.ToolArgs
import dev.ide.ui.backend.ProjectService
import dev.ide.ui.backend.UiAgentSessionSummary
import dev.ide.ui.backend.UiProjectResult
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.backend.UiTemplateParam
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewAppToolsTest {

    private val compose = UiProjectTemplate(
        id = "compose-app", displayName = "Compose app", description = "An Android app with Jetpack Compose",
        category = "Android", iconId = "module.android",
        // Like the real templates: the project name and package are NOT parameters; they are reserved keys.
        parameters = listOf(
            UiTemplateParam.Choice("minSdk", "Minimum SDK", listOf(UiTemplateParam.Choice.Option("21", "21"), UiTemplateParam.Choice.Option("26", "26")), defaultIndex = 1),
            UiTemplateParam.Toggle("material3", "Material 3", default = true),
        ),
    )

    private class FakeProjects(private val templates: List<UiProjectTemplate>) : ProjectService {
        var created: Pair<String, Map<String, String>>? = null
        override fun projectTemplates() = templates
        override suspend fun createProject(templateId: String, args: Map<String, String>): UiProjectResult {
            created = templateId to args
            return UiProjectResult(true, "Created", "/projects/${args["name"]}")
        }
    }

    @Test
    fun `create_project fills the template from the name and defaults, then reports the new root`() {
        val projects = FakeProjects(listOf(compose))
        var createdRoot: String? = null
        val tools = NewAppTools.tools({ projects }) { createdRoot = it; "The project's libraries are resolved." }
        val listing = runBlocking { tools.first { it.spec.name == "list_project_templates" }.execute(args()) }
        assertTrue("compose-app: Compose app [Android]" in listing.content, listing.content)
        assertTrue("minSdk (Minimum SDK): one of 21, 26, default 26" in listing.content, listing.content)

        val create = tools.first { it.spec.name == "create_project" }
        val result = runBlocking { create.execute(args("template" to "compose-app", "name" to "Tip Calculator", "settings" to """{"minSdk":"21","bogus":"x"}""")) }
        assertFalse(result.isError, result.content)
        assertTrue("libraries are resolved" in result.content, result.content)
        assertEquals("/projects/Tip Calculator", createdRoot)
        val (id, values) = projects.created!!
        assertEquals("compose-app", id)
        assertEquals(
            mapOf("name" to "Tip Calculator", "packageName" to "com.example.tipcalculator", "minSdk" to "21", "material3" to "true"),
            values,
        )
    }

    @Test
    fun `an unknown template is refused without creating anything`() {
        val projects = FakeProjects(listOf(compose))
        var created = false
        val tools = NewAppTools.tools({ projects }) { created = true; null }
        val result = runBlocking { tools.first { it.spec.name == "create_project" }.execute(args("template" to "nope", "name" to "X")) }
        assertTrue(result.isError)
        assertFalse(created)
        assertNull(projects.created)
    }

    @Test
    fun `package names derive from the app name`() {
        assertEquals("com.example.tipcalculator", NewAppTools.derivePackage("Tip Calculator!", "com.example.myapplication"))
        assertEquals("com.example.app", NewAppTools.derivePackage("123", "com.example.x"))
        assertEquals("org.acme.notes", NewAppTools.derivePackage("Notes", "org.acme.sample"))
    }

    @Test
    fun `recent chats list newest first and drop conversations that are gone`() {
        val root = Files.createTempDirectory("recent")
        val index = AgentRecentChats { root.resolve("shared/recent-chats.json") }
        val a = root.resolve("a").createDirectories()
        val b = root.resolve("b").createDirectories()
        fun saved(project: java.nio.file.Path, id: String, at: Long): UiAgentSessionSummary {
            AgentSessionStore.sessionsDir(project).createDirectories().resolve("$id.json").writeText("{}")
            return UiAgentSessionSummary(id, "chat $id", at, 2)
        }
        index.record(a, saved(a, "1", 100))
        index.record(b, saved(b, "2", 300))
        index.record(a, saved(a, "1", 500))
        assertEquals(listOf("1", "2"), index.list().map { it.sessionId })

        Files.delete(AgentSessionStore.sessionsDir(b).resolve("2.json"))
        assertEquals(listOf("1"), index.list().map { it.sessionId })
        index.remove(a, "1")
        assertTrue(index.list().isEmpty())
        root.toFile().deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, String>) = object : ToolArgs {
        val map = pairs.toMap()
        override fun string(key: String) = map.getValue(key)
        override fun optString(key: String) = map[key]
        override fun int(key: String) = error("unused")
        override fun optInt(key: String): Int? = null
        override fun boolean(key: String) = error("unused")
        override fun optBoolean(key: String): Boolean? = null
        override fun stringList(key: String): List<String> = emptyList()
        override fun raw() = ""
    }
}
