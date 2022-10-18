package com.tyron.builder.gradle.internal

import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import org.gradle.api.NonExtensible
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import java.io.File

/** This can be used by tasks requiring build-tools executables as input with [org.gradle.api.tasks.Nested]. */
@NonExtensible
abstract class BuildToolsExecutableInput {
    @get:Internal //used to create the SdkLoader but not an dependency input.
    abstract val compileSdkVersion: Property<String>

//    @get:Input
//    abstract val buildToolsRevision: Property<Revision>

    @get:Internal
    abstract val sdkBuildService: Property<SdkComponentsBuildService>

//    private fun sdkLoader(): Provider<SdkComponentsBuildService.VersionedSdkLoader> =
//        sdkBuildService.map {
//            it.sdkLoader(compileSdkVersion, buildToolsRevision)
//        }

    fun adbExecutable(): Provider<RegularFile> = TODO()
//        sdkLoader().flatMap { it.adbExecutableProvider }

    fun splitSelectExecutable(): Provider<File> = TODO()
//        sdkLoader().map {
//            it.sdkLoadStrategy.getSplitSelectExecutable()
//                ?: throw RuntimeException("Cannot find split-select executable from build-tools $buildToolsRevision")
//        }

    fun supportBlasLibFolderProvider(): Provider<File> = TODO()
//        sdkLoader().map {
//            it.sdkLoadStrategy.getSupportBlasLibFolder()
//                ?: throw RuntimeException("Cannot find BLAS support libraries from build-tools $buildToolsRevision")
//        }

    fun supportNativeLibFolderProvider(): Provider<File> = TODO()
//        sdkLoader().map {
//            it.sdkLoadStrategy.getSupportNativeLibFolder()
//                ?: throw RuntimeException("Cannot find native libraries folder from build-tools $buildToolsRevision")
//
//        }

    fun aidlExecutableProvider(): Provider<File> = TODO("""
        AIDL is not yet supported. If you don't need to to compile aidl files, temporarily disable
        the feature by adding the following on the android block of your module's gradle file.
        
        android {
            buildFeatures {
                aidl false
            }
        }
    """.trimIndent())
//        sdkLoader().map {
//            it.sdkLoadStrategy.getAidlExecutable()
//                ?: throw RuntimeException("Cannot find aidl compiler from build-tools $buildToolsRevision")
//        }

    fun aidlFrameworkProvider(): Provider<File> = TODO()
//        sdkLoader().map {
//            it.sdkLoadStrategy.getAidlFramework()
//                ?: throw RuntimeException("Cannot find aidl framework from build-tools $buildToolsRevision")
//        }
}

fun BuildToolsExecutableInput.initialize(creationConfig: ComponentCreationConfig) {

//    sdkBuildService.setDisallowChanges(
//        getBuildService(creationConfig.services.buildServiceRegistry)
//    )
//    this.compileSdkVersion.setDisallowChanges(
//        creationConfig.global.compileSdkHashString
//    )
//    this.buildToolsRevision.setDisallowChanges(
//        creationConfig.global.buildToolsRevision
//    )
}