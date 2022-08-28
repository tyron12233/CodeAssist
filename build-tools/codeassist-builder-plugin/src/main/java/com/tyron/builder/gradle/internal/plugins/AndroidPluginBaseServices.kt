package com.tyron.builder.gradle.internal.plugins

import org.gradle.api.Project
import org.gradle.build.event.BuildEventsListenerRegistry

abstract class AndroidPluginBaseServices(
    private val listenerRegistry: BuildEventsListenerRegistry
) {



    @JvmField
    protected var project: Project? = null

    protected open fun basePluginApply(project: Project) {
        // We run by default in headless mode, so the JVM doesn't steal focus.
        System.setProperty("java.awt.headless", "true")

        this.project = project

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