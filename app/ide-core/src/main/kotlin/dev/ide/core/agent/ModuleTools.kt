package dev.ide.core.agent

import dev.ide.agent.AgentTool
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema
import dev.ide.core.IdeServices
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiModuleTypeOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The agent's tools over the project's MODULES: what the Modules screen does by hand. List the module types,
 * create a module, remove one, make one depend on another, and bring the model in line with the disk.
 *
 * Without these the agent's only route to a new module was to write a `module.toml` as a plain file, which the
 * open project never read: the model is loaded once, at open, so the module did not exist for the editor, the
 * build or the Modules screen until the project was reopened. `create_module` goes through the same service the
 * screen uses, and `reload_project` covers the manifest that was written or edited as a file anyway.
 */
internal object ModuleTools {

    /** The system-prompt section that tells the agent these exist and when a reload is needed. */
    val PROMPT = """
        Modules: a project is made of modules, each a directory with a module.toml that CodeAssist owns.
        - To add a module, call list_module_types and then create_module. Do not hand-write a module.toml for a
          new module. Link modules with add_module_dependency, and remove one with remove_module.
        - project_overview lists the modules as the IDE sees them right now.
        - If you edit a module.toml as a file (or anything else changed one), call reload_project so the IDE
          reads it; until then the editor, the build and the module list still use the old model. In a project
          imported from Gradle, the build scripts own the model: edit those and call reload_project to sync.
    """.trimIndent()

    /**
     * [engine] is the open project's services, null when none is open. [onChanged] runs after a tool changed
     * the model or the disk, so the UI re-reads the tree and the module list.
     */
    fun tools(engine: () -> IdeServices?, onChanged: () -> Unit): List<AgentTool> = listOf(
        object : AgentTool {
            override val spec = ToolSpec(
                name = "list_module_types",
                description = "List the module types a new module can be created as (see create_module), with each " +
                    "type's id, its language levels, and its settings with their defaults.",
                parameters = toolSchema { },
            )

            override fun summarize(args: ToolArgs) = "list module types"

            override suspend fun execute(args: ToolArgs): ToolExecutionResult {
                val svc = engine() ?: return noProject()
                val types = svc.moduleService.availableModuleTypes()
                if (types.isEmpty()) return ToolExecutionResult.error("This build has no module types.")
                return ToolExecutionResult.ok(types.joinToString("\n\n", transform = ::describe))
            }
        },
        object : AgentTool {
            override val spec = ToolSpec(
                name = "create_module",
                description = "Create a new module in the open project: registers it with the IDE, writes its " +
                    "module.toml and lays down its source folders. It is usable at once (no reload needed). Settings " +
                    "not given take the type's defaults.",
                parameters = toolSchema {
                    string("name", "The module's name and directory name: starts with a letter; letters, digits, '-' or '_'.")
                    string("type", "The module type id, from list_module_types (e.g. java-lib, android-lib).")
                    string("parent_dir", "A folder under the project root to create it in, e.g. features. Omit for the project root.", required = false)
                    string("language_level", "The Java language level, e.g. JAVA_17. Omit for the type's default.", required = false)
                    string(
                        "settings",
                        "The type's settings as a JSON object of setting key to value, e.g. " +
                            "{\"namespace\":\"com.example.feature\",\"minSdk\":24}. Omit for defaults.",
                        required = false,
                    )
                },
            )
            override val mutating = true

            override fun summarize(args: ToolArgs) = "create module ${args.optString("name").orEmpty()}"

            override suspend fun execute(args: ToolArgs): ToolExecutionResult {
                val svc = engine() ?: return noProject()
                if (svc.isCompatibilityMode()) return ToolExecutionResult.error(GRADLE_OWNED)
                val name = args.string("name").trim()
                val typeId = args.string("type").trim()
                val type = svc.moduleService.availableModuleTypes().firstOrNull { it.id == typeId }
                    ?: return ToolExecutionResult.error("No module type \"$typeId\". Call list_module_types for the ids.")
                val settings = args.optString("settings")?.takeIf { it.isNotBlank() }?.let {
                    parseObject(it) ?: return ToolExecutionResult.error("settings is not a JSON object.")
                } ?: emptyMap()
                val (facets, unknown) = facetValues(type, settings)
                val result = withContext(Dispatchers.IO) {
                    svc.moduleService.createModule(
                        name, typeId, args.optString("language_level")?.takeIf { it.isNotBlank() }, facets,
                        args.optString("parent_dir").orEmpty(),
                    )
                }
                if (!result.success) return ToolExecutionResult.error(result.message)
                onChanged()
                return ToolExecutionResult.ok(buildString {
                    append(result.message.trimEnd('.')).append(". ")
                    svc.modules().firstOrNull { it.name == name }?.let { m ->
                        append("Its directory is ").append(svc.moduleRoot(m)).append(", with source roots ")
                        append(svc.sourceRoots(m).joinToString(", ").ifEmpty { "(none)" }).append(". ")
                    }
                    append("It has no dependencies yet: use add_dependency for libraries and add_module_dependency to use it from another module.")
                    if (unknown.isNotEmpty()) {
                        append("\nIgnored settings this type does not have: ").append(unknown.joinToString(", ")).append('.')
                    }
                })
            }
        },
        object : AgentTool {
            override val spec = ToolSpec(
                name = "remove_module",
                description = "Remove a module from the project. Its files stay on disk (delete them with delete_path " +
                    "if they should go); other modules' dependencies on it are dropped.",
                parameters = toolSchema { string("name", "The module's name.") },
            )
            override val mutating = true

            override fun summarize(args: ToolArgs) = "remove module ${args.optString("name").orEmpty()}"

            override suspend fun execute(args: ToolArgs): ToolExecutionResult {
                val svc = engine() ?: return noProject()
                if (svc.isCompatibilityMode()) return ToolExecutionResult.error(GRADLE_OWNED)
                val name = args.string("name").trim()
                val ok = withContext(Dispatchers.IO) { svc.moduleService.removeModule(name) }
                if (!ok) return ToolExecutionResult.error("No module \"$name\". The modules are: ${moduleNames(svc)}.")
                onChanged()
                return ToolExecutionResult.ok("Removed module \"$name\" from the project. Its files are still on disk.")
            }
        },
        object : AgentTool {
            override val spec = ToolSpec(
                name = "add_module_dependency",
                description = "Make one module depend on another module of the project, so its code can use the " +
                    "other's classes. Refused when it would create a cycle.",
                parameters = toolSchema {
                    string("module", "The module that will use the other one.")
                    string("depends_on", "The module it should depend on.")
                    string(
                        "scope", "implementation (default), api (also visible to modules depending on this one), or testImplementation.",
                        required = false, enum = listOf("implementation", "api", "testImplementation"),
                    )
                },
            )
            override val mutating = true

            override fun summarize(args: ToolArgs) =
                "${args.optString("module").orEmpty()} depends on ${args.optString("depends_on").orEmpty()}"

            override suspend fun execute(args: ToolArgs): ToolExecutionResult {
                val svc = engine() ?: return noProject()
                if (svc.isCompatibilityMode()) return ToolExecutionResult.error(GRADLE_OWNED)
                val module = args.string("module").trim()
                val target = args.string("depends_on").trim()
                val result = withContext(Dispatchers.IO) {
                    svc.dependencies.addModuleDependency(module, target, args.optString("scope")?.takeIf { it.isNotBlank() } ?: "implementation")
                }
                if (!result.success) return ToolExecutionResult.error(result.message)
                onChanged()
                return ToolExecutionResult.ok("\"$module\" now depends on \"$target\".")
            }
        },
        object : AgentTool {
            override val spec = ToolSpec(
                name = "reload_project",
                description = "Make the IDE re-read the project's structure from disk: every module.toml (picking up " +
                    "edits made to it as a file, a module.toml written in a new directory, and modules whose directory " +
                    "is gone), or, in a project imported from Gradle, its build scripts. Then re-resolves libraries " +
                    "and refreshes code analysis. Call it after changing a module.toml or a build script by hand.",
                parameters = toolSchema { },
            )
            override val mutating = true

            override fun summarize(args: ToolArgs) = "reload the project from disk"

            override suspend fun execute(args: ToolArgs): ToolExecutionResult {
                val svc = engine() ?: return noProject()
                return runCatching { reload(svc, onChanged) }
                    .getOrElse { ToolExecutionResult.error("The project could not be reloaded: ${it.message ?: it.javaClass.simpleName}") }
            }
        },
    )

    private suspend fun reload(svc: IdeServices, onChanged: () -> Unit): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (svc.isCompatibilityMode()) {
            val outcome = svc.syncFromBuildFiles()
            if (outcome.ok && outcome.modelChanged) {
                svc.dependencies.retryDependencyResolution()
                svc.reindex()
            }
            onChanged()
            val text = (listOf(outcome.message) + outcome.notes).joinToString("\n") + "\nModules: ${moduleNames(svc)}." + unresolved(svc)
            return@withContext if (outcome.ok) ToolExecutionResult.ok(text) else ToolExecutionResult.error(text)
        }
        val r = svc.moduleService.reloadModulesFromDisk()
        if (r.changed) {
            // A manifest may declare libraries the project has never resolved.
            svc.dependencies.retryDependencyResolution()
            onChanged()
        }
        val text = buildString {
            if (!r.changed) append("Nothing changed: the IDE's modules already match the module.toml files on disk.")
            if (r.added.isNotEmpty()) append("Added: ").append(r.added.joinToString(", ")).append(". ")
            if (r.updated.isNotEmpty()) append("Re-read: ").append(r.updated.joinToString(", ")).append(". ")
            if (r.removed.isNotEmpty()) append("Removed (directory gone): ").append(r.removed.joinToString(", ")).append(". ")
            append("\nModules: ").append(moduleNames(svc)).append('.')
            if (r.failed.isNotEmpty()) {
                append("\nCould not be read, so left as they were:")
                r.failed.forEach { append("\n- ").append(it) }
            }
            append(unresolved(svc))
        }
        // A manifest the agent just wrote and that did not load is the failure it needs to see as one.
        if (r.failed.isNotEmpty() && !r.changed) ToolExecutionResult.error(text) else ToolExecutionResult.ok(text)
    }

    private fun unresolved(svc: IdeServices): String {
        val missing = svc.dependencies.depsState.value.unresolved
        if (missing.isEmpty()) return ""
        return "\nLibraries that could not be resolved (code using them will not compile):\n" +
            missing.joinToString("\n") { "- ${it.coordinate} (${it.module}): ${it.reason}" }
    }

    private fun moduleNames(svc: IdeServices): String =
        svc.modules().joinToString(", ") { "${it.name} [${it.type.id}]" }.ifEmpty { "(none)" }

    private fun noProject() = ToolExecutionResult.error("No project is open.")

    private const val GRADLE_OWNED =
        "This project is imported from Gradle, so its build scripts own the modules. Edit settings.gradle / the " +
            "module's build.gradle instead, then call reload_project to sync."

    private fun describe(t: UiModuleTypeOption): String = buildString {
        append(t.id).append(": ").append(t.displayName)
        append("\n  language_level: one of ").append(t.languageLevels.joinToString(", ")).append(", default ").append(t.defaultLanguageLevel)
        t.defaultFacets.forEach { facet ->
            facet.fields.forEach { f ->
                val shape = when (f) {
                    is UiConfigField.Text -> "text, default \"${f.value}\""
                    is UiConfigField.Bool -> "true/false, default ${f.value}"
                    is UiConfigField.Number -> "number, default ${f.value}"
                    is UiConfigField.StringList -> "list of text, default ${f.values}"
                    is UiConfigField.TableList -> return@forEach // not settable at creation
                }
                append("\n  ").append(f.key).append(" (").append(f.label).append("): ").append(shape)
            }
        }
    }

    /**
     * The facet tables to create a module of [type] with: every table's defaults, overlaid with [settings]. A
     * setting is matched to the table that has a field of that key, and coerced to that field's type (a model
     * writes `"24"` as readily as `24`). Returns the tables and the setting keys no table has.
     *
     * The defaults are restated because the tables given at creation REPLACE the type's own, as the New Module
     * dialog relies on: it sends every field it showed.
     */
    fun facetValues(type: UiModuleTypeOption, settings: Map<String, JsonElement>): Pair<Map<String, Map<String, Any?>>, List<String>> {
        val used = HashSet<String>()
        val tables = LinkedHashMap<String, Map<String, Any?>>()
        for (facet in type.defaultFacets) {
            val values = LinkedHashMap<String, Any?>()
            for (f in facet.fields) {
                val given = settings[f.key]
                if (given != null) used += f.key
                when (f) {
                    is UiConfigField.Text -> values[f.key] = (given as? JsonPrimitive)?.contentOrNull ?: f.value
                    is UiConfigField.Bool -> values[f.key] = (given as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() } ?: f.value
                    is UiConfigField.Number -> values[f.key] = (given as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: f.value
                    is UiConfigField.StringList -> values[f.key] =
                        (given as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: f.values
                    is UiConfigField.TableList -> Unit
                }
            }
            tables[facet.table] = values
        }
        return tables to settings.keys.filter { it !in used }
    }

    private fun parseObject(raw: String): Map<String, JsonElement>? =
        runCatching { (Json.parseToJsonElement(raw) as JsonObject).toMap() }.getOrNull()
}
