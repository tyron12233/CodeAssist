package com.tyron.builder.gradle.internal.plugins

import com.tyron.builder.gradle.errors.NoOpDeprecationReporter
import com.tyron.builder.gradle.errors.NoOpSyncIssueReporter
import com.tyron.builder.gradle.errors.SyncIssueReporter
import com.tyron.builder.gradle.internal.errors.SyncIssueReporterImpl
import com.tyron.builder.gradle.internal.scope.ProjectInfo
import com.tyron.builder.gradle.internal.services.AndroidLocationsBuildService
import com.tyron.builder.gradle.internal.services.ProjectServices
import com.tyron.builder.gradle.options.ProjectOptionService
import com.tyron.builder.gradle.options.ProjectOptions
import com.tyron.builder.plugin.options.SyncOptions
import org.gradle.api.Project
import org.gradle.build.event.BuildEventsListenerRegistry

abstract class AndroidPluginBaseServices(
    private val listenerRegistry: BuildEventsListenerRegistry
) {


    private val optionService: ProjectOptionService by lazy {
        withProject("optionService") {
            ProjectOptionService.RegistrationAction(it).execute().get()
        }
    }

    protected val syncIssueReporter: SyncIssueReporter by lazy {
        withProject("syncIssueReporter") {
            NoOpSyncIssueReporter()
        }
    }

    @JvmField
    protected var project: Project? = null

    protected val projectServices: ProjectServices by lazy {
        withProject("projectServices") { project ->
            val projectOptions = optionService.projectOptions
            ProjectServices(
                syncIssueReporter,
//                DeprecationReporterImpl(syncIssueReporter, projectOptions, project.path),
                NoOpDeprecationReporter(),
                project.objects,
                project.logger,
                project.providers,
                project.layout,
                projectOptions,
                project.gradle.sharedServices,
//                from(project, projectOptions, syncIssueReporter),
//                create(project, projectOptions::get),
                null,
                project.gradle.startParameter.maxWorkerCount,
                ProjectInfo(project),
                { o: Any -> project.file(o) },
                project.configurations,
                project.dependencies,
                project.extensions.extraProperties)
        }
    }

    protected open fun basePluginApply(project: Project) {
        // We run by default in headless mode, so the JVM doesn't steal focus.
        System.setProperty("java.awt.headless", "true")

        this.project = project
        val projectOptions: ProjectOptions = projectServices.projectOptions

        SyncIssueReporterImpl.GlobalSyncIssueService.RegistrationAction(
            project,
            SyncOptions.getModelQueryMode(projectOptions),
            SyncOptions.getErrorFormatMode(projectOptions)
        ).execute()

        AndroidLocationsBuildService.RegistrationAction(project).execute()

        configureProject(project);
        configureExtension(project);
        createTasks(project)
    }

    protected abstract fun configureProject(project: Project)

    protected abstract fun configureExtension(project: Project)

    protected abstract fun createTasks(project: Project)

    /**
     * Runs a lambda function if [project] has been initialized and return the function's result or
     * generate an exception if [project] is null.
     *
     * This is useful to have not nullable val field that depends on [project] being initialized.
     */
    protected fun <T> withProject(context: String, action: (project: Project) -> T): T =
        project?.let {
            action(it)
        } ?: throw IllegalStateException("Cannot obtain $context until Project is known")
}