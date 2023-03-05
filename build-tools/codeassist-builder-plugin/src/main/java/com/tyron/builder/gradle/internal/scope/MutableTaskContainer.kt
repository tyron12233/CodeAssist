package com.tyron.builder.gradle.internal.scope

import com.tyron.builder.gradle.tasks.*
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile

/**
 * DO NOT ADD NEW TASKS TO THIS CLASS.
 *
 * Container for the tasks for a variant.
 *
 * This contains 2 different types of tasks.
 * - the tasks needed by the variant API. The goal here is to revamp the API to get rid of the need
 *   to expose the tasks.
 * - tasks for internal usage and wiring. This should not be needed, except in rare cases (anchors).
 *   The goal is to get rid of this as much as possible, progressively; and to use buildable
 *   artifact exclusively to wire tasks.
 *
 * DO NOT ADD NEW TASKS TO THIS CLASS.
 */
class MutableTaskContainer : TaskContainer {

    // implementation of the API setter/getters as required by our old Variant APIs.
    //
    // DO NOT USE THESE GETTERS FOR TASK CONFIGURATION, USE ARTIFACTS INSTEAD
    //
    override lateinit var assembleTask: TaskProvider<out Task>
    override lateinit var javacTask: TaskProvider<out JavaCompile>
    override lateinit var compileTask: TaskProvider<out Task>
    override lateinit var preBuildTask: TaskProvider<out Task>
    override var checkManifestTask: TaskProvider<out Task>? = null
    override var aidlCompileTask: TaskProvider<out AidlCompile>? = null
//    override var renderscriptCompileTask: TaskProvider<out RenderscriptCompile>? = null
    override lateinit var mergeResourcesTask: TaskProvider<out MergeResources>
    override lateinit var mergeAssetsTask: TaskProvider<out MergeSourceSetFolders>
    override lateinit var processJavaResourcesTask: TaskProvider<out Sync>
    override var generateBuildConfigTask: TaskProvider<out GenerateBuildConfig>? = null
    override var processAndroidResTask: TaskProvider<out ProcessAndroidResources>? = null
    override var processManifestTask: TaskProvider<out ManifestProcessorTask>? = null
    override var packageAndroidTask: TaskProvider<out Task>? = null
    override var bundleLibraryTask: TaskProvider<out Zip>? = null

    override var installTask: TaskProvider<out DefaultTask>? = null
    override var uninstallTask: TaskProvider<out DefaultTask>? = null

//    override var connectedTestTask: TaskProvider<out DeviceProviderInstrumentTestTask>? = null
//    override val providerTestTaskList: List<TaskProvider<out DeviceProviderInstrumentTestTask>> = mutableListOf()
//
//    override var generateAnnotationsTask: TaskProvider<out ExtractAnnotations>? = null
//
//    override var externalNativeBuildTask: TaskProvider<out ExternalNativeBuildTask>? = null

    // required by the model.
    lateinit var sourceGenTask: TaskProvider<out Task>

    // anything below is scheduled for removal, using BuildableArtifact to link tasks.

    var bundleTask: TaskProvider<out Task>? = null
    lateinit var resourceGenTask: TaskProvider<Task>
    lateinit var assetGenTask: TaskProvider<Task>
    var microApkTask: TaskProvider<out Task>? = null
//    var cxxConfigurationModel: CxxConfigurationModel? = null
    var packageSplitResourcesTask: TaskProvider<out Task>? = null
    var packageSplitAbiTask: TaskProvider<out Task>? = null
    var generateResValuesTask: TaskProvider<out Task>? = null
    var generateApkDataTask: TaskProvider<out Task>? = null
    var coverageReportTask: TaskProvider<out Task>? = null

//    var validateSigningTask: TaskProvider<out ValidateSigningTask>? = null
}