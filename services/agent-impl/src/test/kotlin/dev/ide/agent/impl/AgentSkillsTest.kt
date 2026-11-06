package dev.ide.agent.impl

import dev.ide.agent.ToolArgs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentSkillsTest {

    @Test
    fun `front matter is read, including folded descriptions and quotes`() {
        val (meta, body) = AgentSkills.frontMatter(
            """
            ---
            name: release-notes
            description: >
              Writes release notes
              from the git log.
            license: "MIT"
            ---
            # Release notes
            Steps here.
            """.trimIndent(),
        )
        assertEquals("release-notes", meta["name"])
        assertEquals("Writes release notes from the git log.", meta["description"])
        assertEquals("MIT", meta["license"])
        assertTrue(body.startsWith("# Release notes"))
    }

    @Test
    fun `project skills override app-wide ones and the tool loads instructions and files`() {
        val root = Files.createTempDirectory("skills")
        val project = root.resolve("project")
        val app = root.resolve("app")
        skill(project, "pdf", "---\nname: pdf\ndescription: Fill PDF forms\n---\nUse the form tool.")
        project.resolve("pdf/reference.md").writeText("Field list")
        skill(app, "pdf", "---\nname: pdf\ndescription: App copy\n---\nOld.")
        skill(app, "notes", "# Notes\n\nKeeps meeting notes tidy.\n\nMore.")
        skill(app, "empty", "---\nname: empty\n---\n")

        val skills = AgentSkills.scan(listOf(project to "project", app to "app"))
        assertEquals(listOf("pdf", "notes"), skills.map { it.name })
        assertEquals("Fill PDF forms", skills[0].description)
        assertEquals("Keeps meeting notes tidy.", skills[1].description)

        val tool = AgentSkills.tool { skills }
        val loaded = runBlocking { tool.execute(args("name" to "pdf")) }
        assertFalse(loaded.isError)
        assertTrue("Use the form tool." in loaded.content)
        assertTrue("reference.md" in loaded.content)
        assertFalse("description:" in loaded.content)

        val file = runBlocking { tool.execute(args("name" to "pdf", "file" to "reference.md")) }
        assertEquals("Field list", file.content)
        val escape = runBlocking { tool.execute(args("name" to "pdf", "file" to "../../app/notes/SKILL.md")) }
        assertTrue(escape.isError)
        val missing = runBlocking { tool.execute(args("name" to "nope")) }
        assertTrue(missing.isError && "pdf" in missing.content)

        val section = AgentSkills.promptSection(skills)
        assertNotNull(section)
        assertTrue("- pdf: Fill PDF forms" in section)
    }

    @Test
    fun `MCP tools are explained in the prompt but not listed one by one`() {
        val prompt = SystemPrompt.grounding(listOf("read_file", "mcp__linear__create_issue"), null, listOf("Skills: x"))
        assertTrue("Available tools: read_file." in prompt)
        assertTrue("mcp__<server>__<tool>" in prompt)
        assertFalse("mcp__linear__create_issue" in prompt)
        assertTrue(prompt.endsWith("Skills: x"))
    }

    private fun skill(root: Path, name: String, text: String) {
        root.resolve(name).createDirectories().resolve("SKILL.md").writeText(text)
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
