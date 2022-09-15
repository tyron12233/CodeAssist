package com.tyron.builder.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.*

/**
 * Base class for the plugin.
 *
 * @Deprecated Use the plugin classes directly
 */
open class BasePlugin: Plugin<Project> {
    private lateinit var project: Project

    override fun apply(project: Project) {
        this.project = project

        // This is a hack to change the java.runtime.version
        // this is to make annotation processors believe we are using jdk 11


        // This is a hack to change the java.runtime.version
        // this is to make annotation processors believe we are using jdk 11
        val defaultsField =
            Properties::class.java.getDeclaredField("defaults")
        defaultsField.isAccessible = true
        val defaultProps =
            defaultsField[System.getProperties()] as Properties
        defaultProps.setProperty("java.runtime.version", "11.0.0")
        System.setProperty("ANDROID_USER_HOME", "/data/data/com.tyron.code/files/ANDROID_HOME")

//        project.apply(VERSION_CHECK_PLUGIN_ID)
    }

    /**
     * Returns the Android extension.
     *
     * @deprecated Directly call project.extensions.getByName("android") instead. This will be removed in 8.0
     */
    @Deprecated("Use project.extensions.getByName(\"android\"). This method will be removed in 8.0")
    fun getExtension(): BaseExtension {
        // TODO(227795139)
        project.logger.warn("Calls to BasePlugin.getExtension() are deprecated and will be removed in AGP 8.0")
        return project.extensions.getByName("android") as BaseExtension
    }
}

internal val VERSION_CHECK_PLUGIN_ID = mapOf("plugin" to "com.android.internal.version-check")