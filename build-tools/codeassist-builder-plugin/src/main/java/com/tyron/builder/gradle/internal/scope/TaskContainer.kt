package com.tyron.builder.gradle.internal.scope

import com.tyron.builder.gradle.tasks.*
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile

/**
 * Task container for the tasks needed by the Variant API.
 */
interface TaskContainer {

    val assembleTask: TaskProvider<out Task>
    val javacTask: TaskProvider<out JavaCompile>
    // empty anchor compile task to set all compilations tasks as dependents.
    val compileTask: TaskProvider<out Task>
    val preBuildTask: TaskProvider<out Task>
    val checkManifestTask: TaskProvider<out Task>?
    val aidlCompileTask: TaskProvider<out AidlCompile>?
//    val renderscriptCompileTask: TaskProvider<out RenderscriptCompile>?
    val mergeResourcesTask: TaskProvider<out MergeResources>
    val mergeAssetsTask: TaskProvider<out MergeSourceSetFolders>
    val processJavaResourcesTask: TaskProvider<out Sync>
    val generateBuildConfigTask: TaskProvider<out GenerateBuildConfig>?
    val processAndroidResTask: TaskProvider<out ProcessAndroidResources>?
    val processManifestTask: TaskProvider<out ManifestProcessorTask>?
    val packageAndroidTask: TaskProvider<out Task>?
    val bundleLibraryTask: TaskProvider<out Zip>?

    val installTask: TaskProvider<out DefaultTask>?
    val uninstallTask: TaskProvider<out DefaultTask>?

//    val connectedTestTask: TaskProvider<out DeviceProviderInstrumentTestTask>?
//    val providerTestTaskList: List<TaskProvider<out DeviceProviderInstrumentTestTask>>
//
//    var generateAnnotationsTask: TaskProvider<out ExtractAnnotations>?
//
//    val externalNativeBuildTask: TaskProvider<out ExternalNativeBuildTask>?
}