package com.tyron.builder.gradle.internal

import com.android.sdklib.AndroidVersion
import com.android.sdklib.IAndroidTarget.OptionalLibrary
import com.tyron.builder.gradle.internal.services.AndroidLocationsBuildService
import com.tyron.builder.gradle.internal.services.ServiceRegistrationAction
import com.tyron.builder.gradle.internal.services.getBuildService
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.gradle.options.IntegerOption
import com.tyron.builder.gradle.options.ProjectOptions
import com.tyron.builder.gradle.options.StringOption
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
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
        val androidLocationsServices: Property<AndroidLocationsBuildService>

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
            parameters.androidLocationsServices.set(getBuildService(project.gradle.sharedServices))

            parameters.enableSdkDownload.set(projectOptions.get(BooleanOption.ENABLE_SDK_DOWNLOAD))
            parameters.androidSdkChannel.set(projectOptions.get(IntegerOption.ANDROID_SDK_CHANNEL))
            parameters.useAndroidX.set(projectOptions.get(BooleanOption.USE_ANDROID_X))
            parameters.suppressWarningUnsupportedCompileSdk.set(projectOptions.get(StringOption.SUPPRESS_UNSUPPORTED_COMPILE_SDK))
        }
    }

    /**
     * Lightweight class that cannot be cached since its parameters are not known at construction
     * time (provided as Provider). However, once the [SdkLoadingStrategy] is initialized lazily,
     * those instances are cached and closed at the end of the build.
     *
     * So creating as many instances of VersionedSdkLoader as necessary is fine but instances
     * of [SdkLoadingStrategy] should be allocated wisely and closed once finished.
     *
     * Do not create instances of [VersionedSdkLoader] to store in [org.gradle.api.Task]'s input
     * parameters or [org.gradle.workers.WorkParameters] as it is not serializable. Instead
     * inject the [SdkComponentsBuildService] along with compileSdkVersion and buildToolsRevision
     * for the module and call [SdkComponentsBuildService.sdkLoader] at execution time.
     */
    open class VersionedSdkLoader {
        open val targetBootClasspathProvider: Provider<List<File>> by lazy {TODO()};
        open val targetAndroidVersionProvider: Provider<AndroidVersion> by lazy {TODO()}
        open val adbExecutableProvider: Provider<RegularFile> by lazy {TODO()}
        open val additionalLibrariesProvider: Provider<List<OptionalLibrary>> by lazy {TODO()}
        open val optionalLibrariesProvider: Provider<List<OptionalLibrary>> by lazy {TODO()}
        open val annotationsJarProvider: Provider<File> by lazy {TODO()}
        open val coreLambdaStubsProvider: Provider<RegularFile> by lazy {TODO()}
        open val sdkSetupCorrectly: Provider<Boolean> by lazy { TODO() }

        /**
         * The API versions file from the platform being compiled against.
         *
         * Historically this was distributed in platform-tools. It has been moved to platforms, so it
         * is versioned now. (There was some overlap, so this is available in platforms since platform
         * api 26, and was removed in the platform-tools several years later in 31.x)
         *
         * This will not be present if the compile-sdk version is less than 26 (a fallback to
         * platform-tools would not help for users that update their SDK, as it is removed in recent
         * platform-tools)
         */
        open val apiVersionsFile: Provider<RegularFile> by lazy { TODO() }
    }
}

internal const val API_VERSIONS_FILE_NAME = "api-versions.xml"
internal const val PLATFORM_API_VERSIONS_FILE_PATH = "data/$API_VERSIONS_FILE_NAME"
internal const val PLATFORM_TOOLS_API_VERSIONS_FILE_PATH = "api/$API_VERSIONS_FILE_NAME"