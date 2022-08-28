package com.tyron.builder.gradle.internal.tasks.factory

import com.tyron.builder.gradle.internal.dsl.CommonExtensionImpl
import com.tyron.builder.gradle.internal.services.BaseServices
import com.tyron.builder.plugin.BaseExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

class GlobalTaskCreationConfigImpl(
    project: Project,
    private val oldExtension: BaseExtension,
    private val extension: CommonExtensionImpl<*, *, *, *>,
//    override val services: BaseServices,
//    private val versionedSdkLoaderService: VersionedSdkLoaderService,
//    bootClasspathConfig: BootClasspathConfigImpl,
//    override val lintPublish: Configuration,
//    override val lintChecks: Configuration,
    private val androidJar: Configuration,
//    override val settingsOptions: SettingsOptions
) {
}