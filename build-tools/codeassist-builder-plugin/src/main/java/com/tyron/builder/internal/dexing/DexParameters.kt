package com.tyron.builder.internal.dexing

import com.tyron.builder.gradle.internal.tasks.DexArchiveBuilderTaskDelegate
import com.tyron.builder.plugin.options.SyncOptions
import java.io.File
import java.io.Serializable

/** Parameters required for dexing (with D8). */
class DexParameters(
    val minSdkVersion: Int,
    val debuggable: Boolean,
    val withDesugaring: Boolean,
    val desugarBootclasspath: List<File>,
    val desugarClasspath: List<File>,
    val coreLibDesugarConfig: String?,
    val errorFormatMode: SyncOptions.ErrorFormatMode
) {

    fun toDexParametersForWorkers(
        dexPerClass: Boolean,
        bootClasspath: DexArchiveBuilderTaskDelegate.ClasspathServiceKey,
        classpath: DexArchiveBuilderTaskDelegate.ClasspathServiceKey,
        coreLibDesugarOutputKeepRuleFile: File?): DexParametersForWorkers {
        return DexParametersForWorkers(
            minSdkVersion = minSdkVersion,
            debuggable = debuggable,
            dexPerClass = dexPerClass,
            withDesugaring = withDesugaring,
            desugarBootclasspath = bootClasspath,
            desugarClasspath = classpath,
            coreLibDesugarConfig = coreLibDesugarConfig,
            coreLibDesugarOutputKeepRuleFile = coreLibDesugarOutputKeepRuleFile,
            errorFormatMode = errorFormatMode)
    }
}

/**
 * Parameters required for dexing (with D8). They are slightly different from [DexParameters].
 *
 * This class is serializable as it is passed to Gradle workers.
 */
class DexParametersForWorkers(
    val minSdkVersion: Int,
    val debuggable: Boolean,
    val dexPerClass: Boolean,
    val withDesugaring: Boolean,
    val desugarBootclasspath: DexArchiveBuilderTaskDelegate.ClasspathServiceKey,
    val desugarClasspath: DexArchiveBuilderTaskDelegate.ClasspathServiceKey,
    val coreLibDesugarConfig: String?,
    val coreLibDesugarOutputKeepRuleFile: File?,
    val errorFormatMode: SyncOptions.ErrorFormatMode
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}