package dev.ide.agent.impl

import dev.ide.agent.AgentTool
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A skill: a folder holding a `SKILL.md` (YAML front matter with `name` and `description`, then instructions) and
 * any files those instructions refer to. The format is the Agent Skills one Claude Code uses, so a skill written
 * for it works here.
 *
 * Only [name] and [description] go into the system prompt; the body is loaded when the model calls the `skill`
 * tool for a task that matches, so ten installed skills cost about what one does until they are used.
 */
data class AgentSkill(
    val name: String,
    val description: String,
    val dir: Path,
    /** Where it came from, for the UI ("project" or "app"). */
    val source: String,
)

object AgentSkills {
    const val FILE = "SKILL.md"
    private const val MAX_SKILLS = 100
    private const val MAX_DESCRIPTION = 400
    private const val MAX_FILE_CHARS = 40_000
    private const val MAX_LISTED_FILES = 60

    /**
     * Every skill under [roots], each a directory of skill folders. An earlier root wins a name clash, so a
     * project's own skill overrides the app-wide one of the same name.
     */
    fun scan(roots: List<Pair<Path, String>>): List<AgentSkill> {
        val out = LinkedHashMap<String, AgentSkill>()
        for ((root, source) in roots) {
            if (!root.isDirectory()) continue
            val dirs = runCatching { Files.list(root).use { s -> s.filter { it.isDirectory() }.sorted().collect(Collectors.toList()) } }.getOrDefault(emptyList())
            for (dir in dirs) {
                if (out.size >= MAX_SKILLS) break
                val file = dir.resolve(FILE)
                if (!file.isRegularFile()) continue
                val skill = runCatching { parse(file.readText(), dir, source) }.getOrNull() ?: continue
                out.putIfAbsent(skill.name, skill)
            }
        }
        return out.values.toList()
    }

    /** The skill described by [text] (a SKILL.md), or null when it has no description to match tasks against. */
    fun parse(text: String, dir: Path, source: String): AgentSkill? {
        val (meta, body) = frontMatter(text)
        val name = meta["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: dir.name
        val description = meta["description"]?.trim()?.takeIf { it.isNotEmpty() }
            // No front matter: the first paragraph of the body says what the skill is for.
            ?: body.lineSequence().map { it.trim() }.dropWhile { it.isEmpty() || it.startsWith("#") }.takeWhile { it.isNotEmpty() }
                .joinToString(" ").takeIf { it.isNotEmpty() }
            ?: return null
        return AgentSkill(name, description.take(MAX_DESCRIPTION), dir, source)
    }

    /**
     * Splits a leading `---` YAML block from the body. Only flat `key: value` pairs are read (plus `>`/`|` blocks
     * and quoted values), which is all the format uses; anything else in the block is ignored.
     */
    fun frontMatter(text: String): Pair<Map<String, String>, String> {
        val lines = text.removePrefix("﻿").lines()
        if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to text
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return emptyMap<String, String>() to text
        val block = lines.subList(1, end + 1)
        val body = lines.drop(end + 2).joinToString("\n")
        val meta = LinkedHashMap<String, String>()
        var i = 0
        while (i < block.size) {
            val line = block[i]
            val m = Regex("""^([A-Za-z_][A-Za-z0-9_-]*)\s*:\s*(.*)$""").find(line)
            if (m == null || line.startsWith(" ") || line.startsWith("\t")) {
                i++
                continue
            }
            val key = m.groupValues[1]
            var value = m.groupValues[2].trim()
            if (value == ">" || value == "|" || value == ">-" || value == "|-" || value.isEmpty()) {
                val folded = value.startsWith(">") || value.isEmpty()
                val parts = ArrayList<String>()
                while (i + 1 < block.size && (block[i + 1].startsWith(" ") || block[i + 1].startsWith("\t") || block[i + 1].isBlank())) {
                    parts += block[++i].trim()
                }
                value = parts.joinToString(if (folded) " " else "\n").trim()
            } else if (value.length >= 2 && (value.first() == '"' && value.last() == '"' || value.first() == '\'' && value.last() == '\'')) {
                value = value.substring(1, value.length - 1).replace("\\\"", "\"").replace("''", "'")
            }
            meta[key] = value
            i++
        }
        return meta to body
    }

    /** The system-prompt section listing [skills], or null when there are none. */
    fun promptSection(skills: List<AgentSkill>): String? {
        if (skills.isEmpty()) return null
        return buildString {
            append("Skills (packaged instructions for specific tasks). When a request matches a skill's description, ")
            append("call the skill tool with its name BEFORE starting, then follow what it says. Do not load a skill that ")
            append("does not match. Skills cannot run their bundled scripts here unless one of your tools can.\n")
            skills.forEach { append("- ").append(it.name).append(": ").append(it.description.replace('\n', ' ')).append('\n') }
        }.trimEnd()
    }

    /**
     * The `skill` tool: with `name`, the skill's instructions plus the files beside them; with `file` too, that
     * file's text. Paths outside the skill's folder are refused.
     */
    fun tool(skills: () -> List<AgentSkill>): AgentTool = object : AgentTool {
        override val spec = ToolSpec(
            name = TOOL_NAME,
            description = "Load an installed skill's instructions (see the Skills list in your instructions), or one of " +
                "the files in its folder. Call it with just the name first.",
            parameters = toolSchema {
                string("name", "The skill's name, exactly as listed.")
                string("file", "A file inside the skill's folder (relative path) to read instead of SKILL.md.", required = false)
            },
        )

        override fun summarize(args: ToolArgs): String {
            val file = args.optString("file")
            return "skill ${args.optString("name").orEmpty()}" + if (file != null) " / $file" else ""
        }

        override suspend fun execute(args: ToolArgs): ToolExecutionResult {
            val name = args.string("name")
            val all = skills()
            val skill = all.firstOrNull { it.name == name } ?: all.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: return ToolExecutionResult.error(
                    "No skill named \"$name\". Installed: " + all.joinToString(", ") { it.name }.ifEmpty { "none" } + ".",
                )
            val file = args.optString("file")?.takeIf { it.isNotBlank() }
            if (file != null) {
                val base = skill.dir.toAbsolutePath().normalize()
                val target = base.resolve(file).normalize()
                if (!target.startsWith(base)) return ToolExecutionResult.error("That path is outside the skill's folder.")
                if (!target.isRegularFile()) return ToolExecutionResult.error("No file \"$file\" in skill \"${skill.name}\".")
                val text = runCatching { target.readText() }.getOrElse { return ToolExecutionResult.error("Could not read $file: ${it.message}") }
                return ToolExecutionResult.ok(cap(text))
            }
            val body = frontMatter(runCatching { skill.dir.resolve(FILE).readText() }.getOrDefault("")).second.trim()
            val files = listFiles(skill.dir)
            return ToolExecutionResult.ok(buildString {
                append("Skill \"").append(skill.name).append("\" (folder: ").append(skill.dir).append(")\n\n")
                append(cap(body))
                if (files.isNotEmpty()) {
                    append("\n\nFiles in this skill (read one with the skill tool's `file` argument):\n")
                    files.forEach { append("- ").append(it).append('\n') }
                }
            }.trimEnd())
        }
    }

    const val TOOL_NAME = "skill"

    private fun listFiles(dir: Path): List<String> = runCatching {
        Files.walk(dir, 4).use { s ->
            s.filter { it.isRegularFile() && it.name != FILE }
                .map { dir.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .limit(MAX_LISTED_FILES.toLong())
                .collect(Collectors.toList())
        }
    }.getOrDefault(emptyList())

    private fun cap(text: String): String =
        if (text.length <= MAX_FILE_CHARS) text else text.take(MAX_FILE_CHARS) + "\n… (cut at $MAX_FILE_CHARS characters)"
}
