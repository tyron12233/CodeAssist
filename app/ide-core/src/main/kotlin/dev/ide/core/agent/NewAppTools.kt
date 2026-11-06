package dev.ide.core.agent

import dev.ide.agent.AgentTool
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema
import dev.ide.model.template.TemplateArgs
import dev.ide.ui.backend.ProjectService
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.backend.UiTemplateParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The tools of a new-app conversation before its project exists ([dev.ide.ui.backend.AgentService.startNewApp]):
 * list the project templates, and create a project from one. Creating it opens it, and from the next step on the
 * conversation has the full toolset in the new project.
 */
internal object NewAppTools {

    /** The system-prompt section for a conversation that has no project yet. */
    val PROMPT = """
        No project is open: the user wants a NEW app, and you will create it and then build it.
        1. Understand what they want. If something that decides the project's shape is unclear (a game or a regular
           app, which features matter most), ask with ask_user, offering concrete options. Do not ask about things
           you can choose sensibly yourself.
        2. Call list_project_templates and pick the best fit. For an Android app, prefer a Jetpack Compose template
           unless the user asks for Views/XML; for a console program, a Java or Kotlin console template.
        3. Call create_project with a short, friendly app name.
        4. The project then opens and you get every tool for it. Build the app the user described completely: real
           screens and logic, not placeholders. Check each file with get_diagnostics, and build the module before
           you finish.
        5. Finish with a short summary of what you built and tell the user they can tap Run to try it.
    """.trimIndent()

    /**
     * [onCreated] runs once the project exists and is open; what it returns (how the project's libraries resolved) is
     * added to the tool's answer, so the agent knows before it builds.
     */
    fun tools(projects: () -> ProjectService?, onCreated: suspend (rootPath: String) -> String?): List<AgentTool> = listOf(
        object : AgentTool {
            override val spec = ToolSpec(
                name = "list_project_templates",
                description = "List the project templates a new project can be created from, with each template's id, " +
                    "what it is for, and its settings (with defaults).",
                parameters = toolSchema { },
            )

            override fun summarize(args: ToolArgs) = "list project templates"

            override suspend fun execute(args: ToolArgs): ToolExecutionResult {
                val templates = projects()?.projectTemplates().orEmpty()
                if (templates.isEmpty()) return ToolExecutionResult.error("No project templates are available in this build.")
                return ToolExecutionResult.ok(templates.joinToString("\n\n", transform = ::describe))
            }
        },
        object : AgentTool {
            override val spec = ToolSpec(
                name = "create_project",
                description = "Create the new project from a template (see list_project_templates) and open it. Settings " +
                    "not given take the template's defaults.",
                parameters = toolSchema {
                    string("template", "The template id.")
                    string("name", "The app's name, as the user will see it, e.g. \"Tip Calculator\".")
                    string("package_name", "The package / application id, e.g. com.example.tipcalculator. Derived from the name when omitted.", required = false)
                    string(
                        "settings",
                        "Other template settings as a JSON object of setting key to value, e.g. {\"minSdk\":\"24\"}. Omit for defaults.",
                        required = false,
                    )
                },
            )

            override fun summarize(args: ToolArgs) = "create project ${args.optString("name").orEmpty()}"

            override suspend fun execute(args: ToolArgs): ToolExecutionResult {
                val service = projects() ?: return ToolExecutionResult.error("Projects cannot be created in this build.")
                val templateId = args.string("template")
                val template = service.projectTemplates().firstOrNull { it.id == templateId }
                    ?: return ToolExecutionResult.error("No template \"$templateId\". Call list_project_templates for the ids.")
                val name = args.string("name").trim().ifEmpty { "My App" }
                val extra = args.optString("settings")?.let(::parseSettings) ?: emptyMap()
                val values = templateArgs(template, name, args.optString("package_name"), extra)
                val result = service.createProject(template.id, values)
                if (!result.success || result.rootPath == null) {
                    return ToolExecutionResult.error("The project could not be created: ${result.message}")
                }
                val libraries = onCreated(result.rootPath!!)
                return ToolExecutionResult.ok(
                    "Created \"$name\" from ${template.displayName} at ${result.rootPath}. It is open now, and you have " +
                        "every tool for it: look at the generated files, then build what the user asked for." +
                        (libraries?.let { "\n\n$it" } ?: ""),
                )
            }
        },
    )

    private fun describe(t: UiProjectTemplate): String = buildString {
        append(t.id).append(": ").append(t.displayName).append(" [").append(t.category).append("] - ").append(t.description.trim())
        t.parameters.forEach { p ->
            append("\n  ").append(p.key).append(" (").append(p.label).append("): ")
            when (p) {
                is UiTemplateParam.Text -> append("text, default \"").append(p.default).append('"')
                    .append(if (p.validation != "none") " [${p.validation}]" else "")
                is UiTemplateParam.Choice -> append("one of ")
                    .append(p.options.joinToString(", ") { it.value })
                    .append(", default ").append(p.options.getOrNull(p.defaultIndex)?.value.orEmpty())
                is UiTemplateParam.Toggle -> append("true/false, default ").append(p.default)
            }
        }
    }

    /**
     * The values to create [template] with: its defaults, the [name] in its project-name setting, the package in
     * its package setting (derived from the name when not given), then whatever [extra] settings the agent chose.
     */
    fun templateArgs(template: UiProjectTemplate, name: String, packageName: String?, extra: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // The project name and base package are reserved keys every template reads, NOT entries of its parameter
        // list (the Create Project screen asks for them separately). Left out, the project is "Untitled" in
        // com.example.app.
        out[TemplateArgs.NAME] = name
        out[TemplateArgs.PACKAGE] = packageName?.takeIf { it.isNotBlank() } ?: derivePackage(name, DEFAULT_PACKAGE)
        template.parameters.forEach { p ->
            out[p.key] = when (p) {
                is UiTemplateParam.Text -> when (p.validation) {
                    "project" -> name
                    "package" -> packageName?.takeIf { it.isNotBlank() } ?: derivePackage(name, p.default)
                    else -> p.default
                }
                is UiTemplateParam.Choice -> p.options.getOrNull(p.defaultIndex)?.value ?: p.options.firstOrNull()?.value.orEmpty()
                is UiTemplateParam.Toggle -> p.default.toString()
            }
        }
        val keys = template.parameters.map { it.key }.toSet()
        extra.forEach { (k, v) -> if (k in keys) out[k] = v }
        return out
    }

    private const val DEFAULT_PACKAGE = "com.example.app"

    /** `Tip Calculator` -> `com.example.tipcalculator`, keeping the template default's prefix when it has one. */
    fun derivePackage(name: String, templateDefault: String): String {
        val leaf = name.lowercase().filter { it.isLetterOrDigit() }.dropWhile { it.isDigit() }.ifEmpty { "app" }
        val prefix = templateDefault.substringBeforeLast('.', "").takeIf { it.contains('.') || it.isNotEmpty() } ?: "com.example"
        return "$prefix.$leaf"
    }

    private fun parseSettings(raw: String): Map<String, String> = runCatching {
        (Json.parseToJsonElement(raw) as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())
}
