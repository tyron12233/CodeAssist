package com.tyron.builder.gradle.internal.services

import com.android.sdklib.AndroidVersion
import com.android.sdklib.IAndroidTarget
import com.tyron.builder.BuildModule
import com.tyron.builder.gradle.internal.SdkComponentsBuildService
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File

/**
 * Object responsible for creating (and memoizing) the instance of [SdkComponentsBuildService.VersionedSdkLoader]
 *
 * This is used by several other services
 */
class VersionedSdkLoaderService(
    private val services: BaseServices,
    private val project: Project,
    private val compileSdkVersionAction: () -> String?,
) {
    val versionedSdkLoader: Provider<SdkComponentsBuildService.VersionedSdkLoader>

    = project.provider {
        return@provider object : SdkComponentsBuildService.VersionedSdkLoader() {
            override val targetBootClasspathProvider: Provider<List<File>>
                get() = project.provider {
                    listOf(BuildModule.getAndroidJar())
                }
            override val coreLambdaStubsProvider: Provider<RegularFile>
                get() = project.objects.fileProperty().fileProvider(project.provider { BuildModule.getLambdaStubs() })
            override val annotationsJarProvider: Provider<File>
                get() = project.provider { File("annotations.jar") }
            override val adbExecutableProvider: Provider<RegularFile>
                get() = project.objects.fileProperty().fileProvider(project.provider { File("adb") })
            override val optionalLibrariesProvider: Provider<List<IAndroidTarget.OptionalLibrary>>
                get() = project.provider { emptyList() }
            override val targetAndroidVersionProvider: Provider<AndroidVersion>
                get() = project.provider { AndroidVersion(31) }
            override val additionalLibrariesProvider: Provider<List<IAndroidTarget.OptionalLibrary>>
                get() = project.provider { emptyList() }
            override val sdkSetupCorrectly: Provider<Boolean>
                get() = project.provider { true }
            override val apiVersionsFile: Provider<RegularFile>
                get() = super.apiVersionsFile
        }
    }

//    by lazy {
//        val buildService =
//            getBuildService(services.buildServiceRegistry, SdkComponentsBuildService::class.java)
//        buildService
//            .map { sdkComponentsBuildService ->
////                sdkComponentsBuildService.sdkLoader(
////                    project.provider(compileSdkVersionAction),
////                    project.provider(buildToolsRevision)
////                )
//

//            }
//    }
}
