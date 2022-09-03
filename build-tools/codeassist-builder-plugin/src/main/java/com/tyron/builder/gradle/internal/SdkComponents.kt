package com.tyron.builder.gradle.internal

import com.tyron.builder.gradle.internal.services.ServiceRegistrationAction
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.options.IntegerOption
import com.tyron.builder.gradle.options.ProjectOptions
import com.tyron.builder.gradle.options.StringOption
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import javax.inject.Inject

/**
 * Build service used to load SDK components. All build operations requiring access to the SDK
 * components should declare it as input.
 */
@Suppress("UnstableApiUsage")
abstract class SdkComponentsBuildService @Inject constructor(
    private val objectFactory: ObjectFactory,
    private val providerFactory: ProviderFactory
) :
    BuildService<SdkComponentsBuildService.Parameters>, AutoCloseable {

    interface Parameters : BuildServiceParameters {
        val projectRootDir: RegularFileProperty
        val offlineMode: Property<Boolean>
//        val issueReporter: Property<SyncIssueReporterImpl.GlobalSyncIssueService>
//        val androidLocationsServices: Property<AndroidLocationsBuildService>

        val enableSdkDownload: Property<Boolean>
        val androidSdkChannel: Property<Int>
        val useAndroidX: Property<Boolean>
        val suppressWarningUnsupportedCompileSdk: Property<String>
    }

    class RegistrationAction(
        project: Project,
        private val projectOptions: ProjectOptions,
    ) : ServiceRegistrationAction<SdkComponentsBuildService, Parameters>(
        project,
        SdkComponentsBuildService::class.java
    ) {

        override fun configure(parameters: Parameters) {
            parameters.projectRootDir.set(project.rootDir)
            parameters.offlineMode.set(project.gradle.startParameter.isOffline)
//            parameters.issueReporter.set(getBuildService(project.gradle.sharedServices))
//            parameters.androidLocationsServices.set(getBuildService(project.gradle.sharedServices))

            parameters.enableSdkDownload.set(projectOptions.get(BooleanOption.ENABLE_SDK_DOWNLOAD))
            parameters.androidSdkChannel.set(projectOptions.get(IntegerOption.ANDROID_SDK_CHANNEL))
            parameters.useAndroidX.set(projectOptions.get(BooleanOption.USE_ANDROID_X))
            parameters.suppressWarningUnsupportedCompileSdk.set(projectOptions.get(StringOption.SUPPRESS_UNSUPPORTED_COMPILE_SDK))
        }
    }
}

internal const val API_VERSIONS_FILE_NAME = "api-versions.xml"
internal const val PLATFORM_API_VERSIONS_FILE_PATH = "data/$API_VERSIONS_FILE_NAME"
internal const val PLATFORM_TOOLS_API_VERSIONS_FILE_PATH = "api/$API_VERSIONS_FILE_NAME"