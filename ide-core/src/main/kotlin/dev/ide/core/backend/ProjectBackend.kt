package dev.ide.core.backend

import dev.ide.android.support.resources.LauncherIcon
import dev.ide.core.BackendContext
import dev.ide.core.ImportableKind
import dev.ide.core.CaprojFormat
import dev.ide.core.ProjectIconLocator
import dev.ide.core.ProjectPackaging
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateParameter
import dev.ide.model.template.TextValidation
import dev.ide.platform.log.Log
import dev.ide.platform.storage.StorageUsage
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.ProjectService
import dev.ide.ui.backend.UiCompatibilityInfo
import dev.ide.ui.backend.UiExportModule
import dev.ide.ui.backend.UiExportOptions
import dev.ide.ui.backend.UiExportPlan
import dev.ide.ui.backend.UiGradleExport
import dev.ide.ui.backend.UiImportPreview
import dev.ide.ui.backend.UiPackagedEntry
import dev.ide.ui.backend.UiPackagedModule
import dev.ide.ui.backend.UiProjectIcon
import dev.ide.ui.backend.UiOpenTab
import dev.ide.ui.backend.UiOpenTabs
import dev.ide.ui.backend.UiConvertResult
import dev.ide.ui.backend.UiProjectFolderKind
import dev.ide.ui.backend.UiProjectResult
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.backend.UiStorageCategory
import dev.ide.ui.backend.UiStorageProject
import dev.ide.ui.backend.UiStorageReport
import dev.ide.ui.backend.UiSyncResult
import dev.ide.ui.backend.UiTemplateParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Marker on line 1 of `.platform/open-tabs.txt` for the caret/scroll/view-mode-aware tab format (see below). */
private const val TAB_FORMAT_V2 = "#v2"

/** Ceiling on an image read for a UI preview (the export screen's screenshot thumbnails). */
private const val MAX_PREVIEW_IMAGE_BYTES = 8L * 1024 * 1024

/**
 * [ProjectService]: the project picker + create/open/delete, the Create-Project template gallery, and the
 * per-project open-tab session. Open/create drive the engine swap through [BackendContext.swapEngine] (the
 * swap + epoch bump are lifecycle-owned by the aggregator).
 */
internal class ProjectBackend(private val ctx: BackendContext) : ProjectService {

    private val log = Log.logger("ide.backend")

    override val projectEpoch: StateFlow<Int> get() = ctx.projectEpoch

    override fun projects(): List<ProjectInfo> =
        ctx.manager?.list()?.map { ProjectInfo(it.name, it.rootPath, it.moduleCount, it.compatibility, it.isAndroid, it.lastOpened) }
            ?: ctx.servicesOrNull?.let {
                listOf(ProjectInfo(it.projectDisplayName(), it.workspaceRoot.toString(), it.modules().size, runCatching { it.isCompatibilityMode() }.getOrDefault(false)))
            }
            ?: emptyList()

    // Resolved launcher icon, cached so revisiting the picker doesn't re-read the model + image each time
    // (an empty Optional marks "no icon", so a fruitless project isn't re-resolved on every visit).
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<UiProjectIcon>>()

    override suspend fun projectIcon(rootPath: String): UiProjectIcon? {
        iconCache[rootPath]?.let { return it.orElse(null) }
        return withContext(Dispatchers.IO) {
            val icon = runCatching { toUiProjectIcon(ProjectIconLocator.locate(Paths.get(rootPath))) }.getOrNull()
            iconCache[rootPath] = java.util.Optional.ofNullable(icon)
            icon
        }
    }

    private fun toUiProjectIcon(icon: LauncherIcon?): UiProjectIcon? = when (icon) {
        is LauncherIcon.Raster ->
            runCatching { Files.readAllBytes(icon.path) }.getOrNull()?.let { UiProjectIcon.Raster(it) }
        is LauncherIcon.Drawable -> UiProjectIcon.Drawable(DrawableMapping.toUi(icon.preview))
        null -> null
    }

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
        iconCache.remove(rootPath)
        return withContext(Dispatchers.IO) {
            runCatching { mgr.delete(rootPath); true }.getOrDefault(false)
        }
    }

    override suspend fun backupProjects(): String? {
        val mgr = ctx.manager ?: return null
        return withContext(Dispatchers.IO) { runCatching { mgr.exportBackup().toString() }.getOrNull() }
    }

    // ---- storage usage + cleanup ----

    override suspend fun storageReport(): UiStorageReport? {
        val mgr = ctx.manager ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val summaries = mgr.list()
                val androidByPath = summaries.associate { it.rootPath to it.isAndroid }
                val r = StorageUsage.report(mgr.storageRoot, summaries.map { Paths.get(it.rootPath) }, mgr.sharedRoot)
                UiStorageReport(
                    storageRootPath = r.storageRoot,
                    totalBytes = r.totalBytes,
                    categories = r.categories.map { c ->
                        UiStorageCategory(
                            id = c.id,
                            bytes = c.bytes,
                            colorId = storageColorId(c.id),
                            clearable = c.id in StorageUsage.CLEARABLE,
                            destructive = c.id in StorageUsage.DESTRUCTIVE,
                        )
                    },
                    projects = r.projects.map { p ->
                        UiStorageProject(p.name, p.rootPath, p.bytes, androidByPath[p.rootPath] ?: false)
                    },
                    openProjectRootPath = ctx.servicesOrNull?.workspaceRoot?.toString(),
                )
            }.getOrElse { e -> log.error("Couldn't compute the storage report", e); null }
        }
    }

    override suspend fun clearStorageCategory(id: String): Boolean {
        val mgr = ctx.manager ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                StorageUsage.clearCategory(id, mgr.list().map { Paths.get(it.rootPath) }, mgr.sharedRoot)
                true
            }.getOrElse { e -> log.error("Couldn't clear storage category $id", e); false }
        }
    }

    /** Map a storage category id to the theme color token the Storage screen tints its segment/dot with. */
    private fun storageColorId(id: String): String = when (id) {
        StorageUsage.DEPENDENCIES -> "accent"
        StorageUsage.INDEX -> "info"
        StorageUsage.BUILD -> "run"
        StorageUsage.PREVIEW -> "success"
        StorageUsage.LANGUAGE -> "gitModified"
        StorageUsage.SDK -> "warning"
        StorageUsage.PROJECTS -> "accentStrong"
        else -> "textTertiary"
    }

    // ---- Gradle compatibility mode ----

    override fun compatibilityInfo(): UiCompatibilityInfo? {
        val svc = ctx.servicesOrNull ?: return null
        if (!svc.isCompatibilityMode()) return null
        return UiCompatibilityInfo(
            summary = "Opened in Gradle compatibility mode. The build scripts were read statically, not run, " +
                "so dependencies and versions come from what could be read; builds and dependency resolution " +
                "may still fail.",
            notes = runCatching { svc.compatibilityNotes() }.getOrDefault(emptyList()),
            syncNeeded = runCatching { svc.isSyncStale() }.getOrDefault(false),
        )
    }

    override suspend fun syncProject(): UiSyncResult {
        val svc = ctx.servicesOrNull ?: return UiSyncResult(false, "No project is open.")
        return withContext(Dispatchers.IO) {
            runCatching {
                val outcome = svc.syncFromBuildFiles()
                if (outcome.ok && outcome.modelChanged) {
                    // The build files (re-)declared the model's dependencies; re-resolve them and rebuild the
                    // index so new modules/sources and changed classpaths take effect in the open project.
                    svc.dependencies.retryDependencyResolution()
                    svc.reindex()
                }
                UiSyncResult(outcome.ok, outcome.message, outcome.notes)
            }.getOrElse { e ->
                log.error("Project sync failed", e)
                UiSyncResult(false, e.message ?: "Project sync failed")
            }
        }
    }

    override suspend fun convertToNative(): UiConvertResult {
        val svc = ctx.servicesOrNull ?: return UiConvertResult(false, "No project is open.")
        return withContext(Dispatchers.IO) {
            runCatching {
                // Pure disk move + marker drop — the native model is already the source of truth, so nothing
                // needs re-resolving or re-indexing.
                val o = svc.convertToNative()
                UiConvertResult(o.ok, o.message, o.canRevert)
            }.getOrElse { e ->
                log.error("Convert to native failed", e)
                UiConvertResult(false, e.message ?: "Convert failed")
            }
        }
    }

    override suspend fun revertToGradle(): UiConvertResult {
        val svc = ctx.servicesOrNull ?: return UiConvertResult(false, "No project is open.")
        return withContext(Dispatchers.IO) {
            runCatching {
                val o = svc.revertToGradle()
                UiConvertResult(o.ok, o.message, o.canRevert)
            }.getOrElse { e ->
                log.error("Revert to Gradle failed", e)
                UiConvertResult(false, e.message ?: "Revert failed")
            }
        }
    }

    override suspend fun inspectProjectFolder(path: String): UiProjectFolderKind = withContext(Dispatchers.IO) {
        val mgr = ctx.manager ?: return@withContext UiProjectFolderKind.UNKNOWN
        when (runCatching { mgr.inspectFolder(Paths.get(path)) }.getOrDefault(ImportableKind.NONE)) {
            ImportableKind.CODE_ASSIST -> UiProjectFolderKind.CODE_ASSIST
            ImportableKind.EXTERNAL -> UiProjectFolderKind.GRADLE
            ImportableKind.NONE -> UiProjectFolderKind.UNKNOWN
        }
    }

    override suspend fun importExternalProject(sourceRootPath: String): UiProjectResult {
        val mgr = ctx.manager ?: return UiProjectResult(false, "Project import not supported by this backend")
        return withContext(Dispatchers.IO) {
            runCatching {
                val next = mgr.importExternalProject(Paths.get(sourceRootPath))
                    ?: return@runCatching UiProjectResult(false, "That folder isn't a CodeAssist or Gradle project.")
                ctx.swapEngine(next)
                UiProjectResult(true, "Imported ${next.projectDisplayName()}", next.workspaceRoot.toString())
            }.getOrElse { e ->
                log.error("Couldn't import the project at $sourceRootPath", e)
                UiProjectResult(false, e.message ?: "Failed to import the project")
            }
        }
    }

    // ---- shareable project packages (.caproj) ----

    override suspend fun exportProject(rootPath: String, options: UiExportOptions): String? {
        val mgr = ctx.manager ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                mgr.exportProject(
                    rootPath,
                    ProjectPackaging.ExportOptions(
                        bundleDependencies = options.bundleDependencies,
                        author = options.author,
                        description = options.description,
                        includedModules = options.includedModules,
                        screenshotPaths = options.screenshotPaths,
                    ),
                ).toString()
            }.getOrElse { e -> log.error("Couldn't export the project at $rootPath", e); null }
        }
    }

    override suspend fun exportGradleProject(rootPath: String): UiGradleExport? {
        val mgr = ctx.manager ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val outcome = mgr.exportGradleProject(rootPath)
                UiGradleExport(outcome.zip.toString(), outcome.notes)
            }.getOrElse { e -> log.error("Couldn't export $rootPath as a Gradle project", e); null }
        }
    }

    override suspend fun exportPlan(rootPath: String): UiExportPlan? {
        val mgr = ctx.manager ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val plan = mgr.exportPlan(rootPath) ?: return@runCatching null
                UiExportPlan(
                    modules = plan.modules.map {
                        UiExportModule(it.name, it.typeId, it.path, it.fileCount, it.sizeBytes, it.dependsOn)
                    },
                    bundledDepsBytes = plan.bundledDepsBytes,
                )
            }.getOrElse { e -> log.error("Couldn't read the export plan for $rootPath", e); null }
        }
    }

    override suspend fun importDestination(projectName: String): String? {
        val mgr = ctx.manager ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { mgr.plannedImportDir(projectName).toString() }.getOrNull()
        }
    }

    override suspend fun imageBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val file = Paths.get(path)
            if (Files.size(file) > MAX_PREVIEW_IMAGE_BYTES) null else Files.readAllBytes(file)
        }.getOrNull()
    }

    override suspend fun previewImportPackage(archivePath: String): UiImportPreview? {
        val mgr = ctx.manager ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val preview = mgr.readPackagePreview(archivePath) ?: return@runCatching null
                val m = preview.manifest
                val compatible = m.format <= CaprojFormat.FORMAT_VERSION
                UiImportPreview(
                    name = m.name,
                    description = m.description,
                    author = m.author,
                    createdBy = m.createdBy,
                    isAndroid = m.isAndroid,
                    packageName = m.packageName,
                    moduleCount = m.moduleCount,
                    modules = preview.modules.map { UiPackagedModule(it.name, it.typeId, it.fileCount, it.sizeBytes) },
                    fileCount = m.fileCount,
                    uncompressedSizeBytes = m.uncompressedSize,
                    hasBundledDeps = m.hasBundledDeps,
                    icon = preview.iconBytes?.let { UiProjectIcon.Raster(it) },
                    files = preview.entries.map { UiPackagedEntry(it.path, it.size) },
                    compatible = compatible,
                    incompatibleReason = if (compatible) null
                    else "This package was created by a newer version of CodeAssist. Update to import it.",
                    screenshots = preview.screenshots,
                )
            }.getOrNull()
        }
    }

    override suspend fun importPackage(archivePath: String, projectName: String?): UiProjectResult {
        val mgr = ctx.manager ?: return UiProjectResult(false, "Project import not supported by this backend")
        return withContext(Dispatchers.IO) {
            runCatching {
                val next = mgr.importProject(archivePath, projectName)
                    ?: return@runCatching UiProjectResult(
                        false,
                        "That file isn't a CodeAssist project package, or it needs a newer version of CodeAssist.",
                    )
                ctx.swapEngine(next)
                UiProjectResult(true, "Imported ${next.projectDisplayName()}", next.workspaceRoot.toString())
            }.getOrElse { e ->
                log.error("Couldn't import the package at $archivePath", e)
                UiProjectResult(false, e.message ?: "Failed to import project")
            }
        }
    }

    // Open tabs are persisted per project, alongside the other workspace state under `.platform/`. Kept out of
    // `.platform/caches/` so a backup includes it. Best-effort — a missing or unreadable file just means "no
    // remembered tabs". Two on-disk formats, both line-based:
    //   v1 (legacy):  line 1 = active index, each following line = one open file path (tab order).
    //   v2 (current): line 1 = "#v2" marker, line 2 = active index, then one `path\tcaret\tscrollLine\tviewMode`
    //                 per tab — so a reopened tab restores its caret, scroll, and view surface, not just its
    //                 path. A v1 first line is always an integer, so the "#v2" marker disambiguates on read.
    private val openTabsFile: Path? get() = ctx.servicesOrNull?.workspaceRoot?.resolve(".platform/open-tabs.txt")

    /** True once a session has been saved for this project — the open-tabs file exists (even recording zero
     *  tabs). Distinguishes a first open from a project deliberately left with no tabs open. */
    override fun hasSavedSession(): Boolean = openTabsFile?.toFile()?.exists() == true

    override fun openTabs(): UiOpenTabs {
        val file = (openTabsFile ?: return UiOpenTabs()).toFile()
        if (!file.exists()) return UiOpenTabs()
        return runCatching {
            val lines = file.readText().split('\n')
            if (lines.firstOrNull()?.trim() == TAB_FORMAT_V2) {
                val active = lines.getOrNull(1)?.trim()?.toIntOrNull() ?: -1
                val tabs = lines.drop(2).mapNotNull { parseTabLine(it) }
                UiOpenTabs(tabs, active)
            } else {
                // Legacy v1: active index + bare paths.
                val active = lines.firstOrNull()?.trim()?.toIntOrNull() ?: -1
                val paths = lines.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
                UiOpenTabs.ofPaths(paths, active)
            }
        }.getOrDefault(UiOpenTabs())
    }

    /** Parse one v2 tab line (`path\tcaret\tscrollLine\tviewMode`); null for a blank/pathless line. */
    private fun parseTabLine(line: String): UiOpenTab? {
        val fields = line.split('\t')
        val path = fields.getOrNull(0)?.trim().orEmpty()
        if (path.isEmpty()) return null
        return UiOpenTab(
            path = path,
            caret = fields.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
            scrollLine = fields.getOrNull(2)?.trim()?.toIntOrNull() ?: 0,
            viewMode = fields.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() } ?: "text",
        )
    }

    override fun saveOpenTabs(tabs: UiOpenTabs) {
        runCatching {
            val file = openTabsFile ?: return
            Files.createDirectories(file.parent)
            file.toFile().writeText(
                buildString {
                    append(TAB_FORMAT_V2).append('\n')
                    append(tabs.activeIndex).append('\n')
                    // Tab/newline separate the fields/rows, so strip them from the path (real paths never carry
                    // either) rather than let a stray one corrupt the record.
                    tabs.tabs.forEach { t ->
                        append(t.path.replace('\t', ' ').replace('\n', ' ')).append('\t')
                        append(t.caret).append('\t')
                        append(t.scrollLine).append('\t')
                        append(t.viewMode).append('\n')
                    }
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
