package com.tyron.builder.gradle

import org.gradle.api.Project

/**
 * The plugin applied with `com.android.application'
 */
class AppPlugin: BasePlugin() {
    override fun apply(project: Project) {
        super.apply(project)

        project.apply(INTERNAL_PLUGIN_ID)
    }
}

private val INTERNAL_PLUGIN_ID = mapOf("plugin" to "com.android.internal.application")