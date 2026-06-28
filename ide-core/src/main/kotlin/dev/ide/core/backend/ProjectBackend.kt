package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateParameter
import dev.ide.model.template.TextValidation
import dev.ide.platform.log.Log
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.ProjectService
import dev.ide.ui.backend.UiOpenTabs
import dev.ide.ui.backend.UiProjectResult
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.backend.UiTemplateParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * [ProjectService]: the project picker + create/open/delete, the Create-Project template gallery, and the
 * per-project open-tab session. Open/create drive the engine swap through [BackendContext.swapEngine] (the
 * swap + epoch bump are lifecycle-owned by the aggregator).
 */
internal class ProjectBackend(private val ctx: BackendContext) : ProjectService {

    private val log = Log.logger("ide.backend")

    override val projectEpoch: StateFlow<Int> get() = ctx.projectEpoch

    override fun projects(): List<ProjectInfo> =
        ctx.manager?.list()?.map { ProjectInfo(it.name, it.rootPath, it.moduleCount, it.compatibility) }
            ?: ctx.servicesOrNull?.let { listOf(ProjectInfo(it.projectDisplayName(), it.workspaceRoot.toString(), it.modules().size)) }
            ?: emptyList()

    override fun projectsRootPath(): String? = ctx.manager?.projectsRoot?.toString()

    // The app storage root a file manager browses: it holds the projects folder alongside the SDK, keystore,
    // caches, and any sibling data such as a previous app version's projects.
    override fun storageRootPath(): String? = ctx.manager?.storageRoot?.toString()

    // Prefer the open engine's registry, but fall back to the ProjectManager's APPLICATION-scoped registry
    // so the picker's Create-Project gallery enumerates templates BEFORE any project is open.
    override fun projectTemplates(): List<UiProjectTemplate> =
        (ctx.servicesOrNull?.projectTemplates() ?: ctx.manager?.projectTemplates() ?: emptyList()).map(::toUiTemplate)

    override suspend fun createProject(templateId: String, args: Map<String, String>): UiProjectResult {
        val mgr = ctx.manager ?: return UiProjectResult(false, "Project creation not supported by this backend")
        return withContext(Dispatchers.IO) {
            runCatching {
                val next = mgr.create(templateId, args)
                ctx.swapEngine(next)
                UiProjectResult(true, "Created ${next.projectDisplayName()}", next.workspaceRoot.toString())
            }.getOrElse { e -> UiProjectResult(false, e.message ?: "Failed to create project") }
        }
    }

    override suspend fun openProject(rootPath: String): Boolean {
        val mgr = ctx.manager ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                if (Paths.get(rootPath) == ctx.servicesOrNull?.workspaceRoot) return@runCatching true
                ctx.swapEngine(mgr.open(rootPath)); true
            }.getOrElse { e ->
                // Surface the failure (console + the critical-error dialog) instead of swallowing it, so a
                // broken project doesn't silently strand the caller — the picker stays put on a false return.
                log.error("Couldn't open the project at $rootPath", e)
                false
            }
        }
    }

    override suspend fun deleteProject(rootPath: String): Boolean {
        val mgr = ctx.manager ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { mgr.delete(rootPath); true }.getOrDefault(false)
        }
    }

    override suspend fun backupProjects(): String? {
        val mgr = ctx.manager ?: return null
        return withContext(Dispatchers.IO) { runCatching { mgr.exportBackup().toString() }.getOrNull() }
    }

    // Open tabs are persisted per project, alongside the other workspace state under `.platform/`. Format:
    // line 1 = active index, each following line = one open file path (tab order). Best-effort — a missing or
    // unreadable file just means "no remembered tabs". Kept out of `.platform/caches/` so a backup includes it.
    private val openTabsFile: Path? get() = ctx.servicesOrNull?.workspaceRoot?.resolve(".platform/open-tabs.txt")

    override fun openTabs(): UiOpenTabs {
        val file = (openTabsFile ?: return UiOpenTabs()).toFile()
        if (!file.exists()) return UiOpenTabs()
        return runCatching {
            val lines = file.readText().split('\n')
            val active = lines.firstOrNull()?.trim()?.toIntOrNull() ?: -1
            val paths = lines.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
            UiOpenTabs(paths, active)
        }.getOrDefault(UiOpenTabs())
    }

    override fun saveOpenTabs(tabs: UiOpenTabs) {
        runCatching {
            val file = openTabsFile ?: return
            Files.createDirectories(file.parent)
            file.toFile().writeText(
                buildString {
                    append(tabs.activeIndex).append('\n')
                    tabs.paths.forEach { append(it).append('\n') }
                },
            )
        }
    }

    private fun toUiTemplate(t: ProjectTemplate): UiProjectTemplate = UiProjectTemplate(
        id = t.id.value,
        displayName = t.displayName,
        description = t.description,
        category = t.category.displayName,
        iconId = t.iconId,
        parameters = t.parameters().map(::toUiParam),
    )

    private fun toUiParam(p: TemplateParameter): UiTemplateParam = when (p) {
        is TemplateParameter.Text -> UiTemplateParam.Text(p.key, p.label, p.default, p.placeholder, mapValidation(p.validation), p.help)
        is TemplateParameter.Choice -> UiTemplateParam.Choice(
            p.key, p.label, p.options.map { UiTemplateParam.Choice.Option(it.value, it.label) }, p.defaultIndex, p.help,
        )
        is TemplateParameter.Toggle -> UiTemplateParam.Toggle(p.key, p.label, p.default, p.help)
    }

    private fun mapValidation(v: TextValidation): String = when (v) {
        TextValidation.NONE -> "none"
        TextValidation.IDENTIFIER -> "identifier"
        TextValidation.PACKAGE_NAME -> "package"
        TextValidation.PROJECT_NAME -> "project"
    }
}
