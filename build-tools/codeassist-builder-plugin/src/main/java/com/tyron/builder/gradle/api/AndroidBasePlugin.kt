package com.tyron.builder.gradle.api

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Common plugin applied by all plugins.
 *
 *
 * The purpose of this no-op plugin is to allow other plugin authors to determine if an Android
 * plugin was applied.
 *
 *
 * This is tied to the 'com.android.base' plugin string.
 */
class AndroidBasePlugin : Plugin<Project> {
    override fun apply(project: Project) {}
}