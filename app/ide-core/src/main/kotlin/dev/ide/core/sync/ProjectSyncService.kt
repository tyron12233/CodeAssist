package dev.ide.core.sync

import dev.ide.core.EngineContext
import dev.ide.model.LanguageLevel
import dev.ide.model.impl.ExternalModelApplier
import dev.ide.model.sync.BUILD_FILE_WRITER_EP
import dev.ide.model.sync.BuildFileWriter
import dev.ide.model.sync.ExternalProjectModel
import dev.ide.model.sync.ModelOwnership
import dev.ide.model.sync.PROJECT_IMPORTER_EP
import dev.ide.model.sync.ProjectImporter
import dev.ide.model.sync.SyncMessage
import dev.ide.model.sync.SyncReason
import dev.ide.model.sync.SyncRequest
import dev.ide.model.sync.SyncSeverity
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.ProgressReporter
import dev.ide.platform.log.Log
import java.nio.file.Path

/** What a sync did: whether it ran, a one-line message for the UI, and the importer notes to surface. */
internal data class ProjectSyncOutcome(
    val ok: Boolean,
    val message: String,
    val notes: List<String> = emptyList(),
    /** True when the model changed, so the caller re-resolves dependencies and re-indexes. */
    val modelChanged: Boolean = false,
)

/**
 * WORKSPACE-scoped driver for projects whose model comes from a foreign build system: it picks the
 * [ProjectImporter] that claims the workspace, runs it, applies the resulting snapshot to the live model
 * ([ExternalModelApplier]), and records what it read so staleness can be detected later.
 *
 * Everything build-system-specific lives in the importer; this class owns the parts that are the same for
 * every one of them: selection, the transaction, persistence, the repositories file, the marker, the stamp.
 */
internal class ProjectSyncService(private val ctx: EngineContext) {

    private val log = Log.logger("ide.sync")

    /** The importer that claims this workspace, or null for a native project. */
    fun importer(): ProjectImporter? = importerFor(ctx.platform.extensions, ctx.workspaceRoot)

    /** The recorded owner of this workspace (present for an imported project, including legacy Gradle ones). */
    fun marker(): ExternalProjectMarker.Info? = ExternalProjectMarker.read(ctx.workspaceRoot)

    /** True when the build files own the model, so the IDE's own model is a projection of them. */
    fun isExternal(): Boolean {
        val importer = importer() ?: return marker() != null
        return importer.ownership == ModelOwnership.EXTERNAL
    }

    /** The build-file writer for this project's importer, or null when declarations can't be written back. */
    fun writer(): BuildFileWriter? {
        val id = importer()?.id ?: return null
        return ctx.platform.extensions.extensions(BUILD_FILE_WRITER_EP).lastOrNull { it.id == id }
    }

    /** True when a watched build file changed (or was added or deleted) since the last sync. */
    fun isStale(): Boolean {
        val importer = importer() ?: return false
        return SyncStamp.isStale(
            ctx.workspaceRoot, importer.id.value, SyncStamp.match(ctx.workspaceRoot, importer.syncFiles())
        )
    }

    /**
     * Re-read the build files into the open model. Adds modules the build files declare, refreshes each
     * module's dependencies and facets from them, and removes the modules they no longer declare (the build
     * files are the source of truth for an [ModelOwnership.EXTERNAL] project). Model, persistence, and the
     * recorded state only: the caller re-resolves dependencies and re-indexes.
     */
    suspend fun sync(
        reason: SyncReason = SyncReason.MANUAL,
        progress: ProgressReporter = NoSyncProgress,
    ): ProjectSyncOutcome {
        val importer = importer()
            ?: return ProjectSyncOutcome(false, "No build files to sync from were found.")
        val outcome = runCatching {
            importer.resolve(SyncRequest(ctx.workspaceRoot, progress, reason))
        }.getOrElse { e ->
            log.error("${importer.displayName} sync failed", e)
            return ProjectSyncOutcome(false, "${importer.displayName} sync failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val model = outcome.model ?: return ProjectSyncOutcome(
            false,
            outcome.messages.firstOrNull { it.severity == SyncSeverity.ERROR }?.text
                ?: "Couldn't read the ${importer.displayName} build files.",
            notes(outcome.messages),
        )

        val report = applyToModel(model, importer)
        ExternalRepositories.merge(ctx.workspaceRoot, model.repositories)
        record(importer, outcome.messages)

        return ProjectSyncOutcome(
            ok = true,
            message = summary(importer.displayName, report),
            notes = notes(outcome.messages),
            modelChanged = report.changed,
        )
    }

    /**
     * Apply [model] to the workspace and persist it. A module the build files no longer declare is removed
     * for an externally-owned project; for an IDE-owned one nothing is removed, since the IDE model is then
     * the source of truth and the snapshot is only additive.
     */
    private fun applyToModel(model: ExternalProjectModel, importer: ProjectImporter): ExternalModelApplier.Report {
        val report = ExternalModelApplier(ctx.store).apply(
            model,
            defaultLanguageLevel(),
            removeAbsent = importer.ownership == ModelOwnership.EXTERNAL,
        )
        ctx.store.save()
        return report
    }

    /** The language level a module with none of its own inherits: the project's existing one. */
    private fun defaultLanguageLevel(): LanguageLevel =
        ctx.modules().firstOrNull()?.languageLevel ?: LanguageLevel.JAVA_17

    /** Write the marker (owner + notes) and stamp the build files this sync read. */
    private fun record(importer: ProjectImporter, messages: List<SyncMessage>) {
        if (importer.ownership == ModelOwnership.EXTERNAL) {
            ExternalProjectMarker.write(
                ctx.workspaceRoot,
                importer.id.value,
                "Imported from ${importer.displayName}. The build files were read statically, not executed, " +
                    "so dependencies and versions are extracted as far as they can be read.",
                notes(messages),
            )
        }
        SyncStamp.write(
            ctx.workspaceRoot, importer.id.value, SyncStamp.match(ctx.workspaceRoot, importer.syncFiles())
        )
    }

    private fun notes(messages: List<SyncMessage>): List<String> =
        messages.filter { it.severity != SyncSeverity.INFO }.map { it.text }

    private fun summary(displayName: String, report: ExternalModelApplier.Report): String = buildString {
        append("Synced from $displayName")
        if (report.added.isNotEmpty()) append(" · ${report.added.size} module${plural(report.added.size)} added")
        if (report.updated.isNotEmpty()) append(" · ${report.updated.size} module${plural(report.updated.size)} updated")
        if (report.removed.isNotEmpty()) append(" · ${report.removed.size} module${plural(report.removed.size)} removed")
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    companion object {
        /**
         * The importer claiming [root], highest [dev.ide.model.sync.Detection.confidence] first. Static so the
         * import flow can select one before any engine exists for the folder.
         */
        fun importerFor(extensions: ExtensionRegistry, root: Path): ProjectImporter? =
            extensions.extensions(PROJECT_IMPORTER_EP)
                .mapNotNull { importer ->
                    runCatching { importer.detect(root) }.getOrNull()?.let { importer to it }
                }
                .maxByOrNull { it.second.confidence }
                ?.first
    }
}
