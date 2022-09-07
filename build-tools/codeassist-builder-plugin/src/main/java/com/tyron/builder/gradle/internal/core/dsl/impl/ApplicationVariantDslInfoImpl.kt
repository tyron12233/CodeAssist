package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.ApplicationBuildType
import com.tyron.builder.api.dsl.ApplicationProductFlavor
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.core.ComponentType
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.core.dsl.ApplicationVariantDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.InternalApplicationExtension
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.manifest.ManifestDataProvider
import com.tyron.builder.gradle.internal.profile.ProfilingMode
import com.tyron.builder.gradle.internal.publishing.VariantPublishingInfo
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.options.StringOption
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider

internal class ApplicationVariantDslInfoImpl(
    componentIdentity: ComponentIdentity,
    componentType: ComponentType,
    defaultConfig: DefaultConfig,
    buildTypeObj: BuildType,
    productFlavorList: List<ProductFlavor>,
    dataProvider: ManifestDataProvider,
    services: VariantServices,
    buildDirectory: DirectoryProperty,
    override val publishInfo: VariantPublishingInfo,
    private val signingConfigOverride: SigningConfig?,
    extension: InternalApplicationExtension
) : TestedVariantDslInfoImpl(
    componentIdentity,
    componentType,
    defaultConfig,
    buildTypeObj,
    productFlavorList,
    dataProvider,
    services,
    buildDirectory,
    extension
), ApplicationVariantDslInfo {

    private val applicationBuildType = buildTypeObj as ApplicationBuildType

    override val isDebuggable: Boolean
        get() = ProfilingMode.getProfilingModeType(
            services.projectOptions[StringOption.PROFILING_MODE]
        ).isDebuggable ?: applicationBuildType.isDebuggable

    override val isProfileable: Boolean
        get() {
            val fromProfilingModeOption = ProfilingMode.getProfilingModeType(
                services.projectOptions[StringOption.PROFILING_MODE]
            ).isProfileable
            // When profileable is enabled from the profilingMode option, it ensures all profileable
            // features are supported, therefore the compileSdk => 30.
            val minProfileableSdk = 30
            val compileSdk = extension.compileSdk ?: minProfileableSdk
            if ((fromProfilingModeOption == true || applicationBuildType.isProfileable) &&
                compileSdk < minProfileableSdk
            ) {
                services.issueReporter.reportError(
                    IssueReporter.Type.COMPILE_SDK_VERSION_TOO_LOW,
                    """'profileable' is enabled with compile SDK <30.
                            Recommended action: If possible, upgrade compileSdk from ${minSdkVersion.apiLevel} to 30."""
                        .trimIndent()
                )
            }
            return when {
                fromProfilingModeOption != null -> {
                    fromProfilingModeOption
                }

                applicationBuildType.isProfileable && isDebuggable -> {
                    val projectName = services.projectInfo.name
                    val message =
                        ":$projectName build type '${buildType}' can only have debuggable or profileable enabled.\n" +
                                "Only one of these options can be used at a time.\n" +
                                "Recommended action: Only set one of debuggable=true and profileable=true.\n"
                    services.issueReporter.reportWarning(IssueReporter.Type.GENERIC, message)
                    // Disable profileable when profileable and debuggable are both enabled.
                    false
                }
                else -> applicationBuildType.isProfileable
            }
        }

    override val signingConfig: SigningConfig? by lazy {
        getSigningConfig(
            buildTypeObj,
            mergedFlavor,
            signingConfigOverride,
            extension,
            services
        )
    }

    override val isSigningReady: Boolean
        get() = signingConfig?.isSigningReady == true

    override val versionName: Provider<String?> by lazy {
        // If the version name from the flavors is null, then we read from the manifest and combine
        // with suffixes, unless it's a test at which point we just return.
        // If the name is not-null, we just combine it with suffixes
        val versionNameFromFlavors =
            productFlavorList
                .asSequence()
                .filterIsInstance(ApplicationProductFlavor::class.java)
                .map { it.versionName }
                .firstOrNull { it != null }
                ?: defaultConfig.versionName

        if (versionNameFromFlavors == null) {
            // rely on manifest value
            // using map will allow us to keep task dependency should the manifest be generated or
            // transformed via a task.
            dataProvider.manifestData.map {
                it.versionName?.let { versionName ->
                    "$versionName${computeVersionNameSuffix()}"
                }
            }
        } else {
            // use value from flavors
            services.provider { "$versionNameFromFlavors${computeVersionNameSuffix()}" }
        }
    }
    override val versionCode: Provider<Int?> by lazy {
        // If the version code from the flavors is null, then we read from the manifest and combine
        // with suffixes, unless it's a test at which point we just return.
        // If the name is not-null, we just combine it with suffixes
        val versionCodeFromFlavors =
            productFlavorList
                .asSequence()
                .filterIsInstance(ApplicationProductFlavor::class.java)
                .map { it.versionCode }
                .firstOrNull { it != null }
                ?: defaultConfig.versionCode

        if (versionCodeFromFlavors == null) {
            // rely on manifest value
            // using map will allow us to keep task dependency should the manifest be generated or
            // transformed via a task.
            dataProvider.manifestData.map { it.versionCode }
        } else {
            // use value from flavors
            services.provider { versionCodeFromFlavors }
        }
    }
    override val isWearAppUnbundled: Boolean?
        get() = mergedFlavor.wearAppUnbundled
    override val isEmbedMicroApp: Boolean
        get() = applicationBuildType.isEmbedMicroApp

    private fun computeVersionNameSuffix(): String {
        // for the suffix we combine the suffix from all the flavors. However, we're going to
        // want the higher priority one to be last.
        val suffixes = mutableListOf<String>()
        defaultConfig.versionNameSuffix?.let {
            suffixes.add(it)
        }

        suffixes.addAll(
            productFlavorList
                .asSequence()
                .filterIsInstance(ApplicationProductFlavor::class.java)
                .mapNotNull { it.versionNameSuffix })

        // then we add the build type after.
        applicationBuildType.versionNameSuffix?.let {
            suffixes.add(it)
        }

        return if (suffixes.isNotEmpty()) {
            suffixes.joinToString(separator = "")
        } else {
            ""
        }
    }
}
