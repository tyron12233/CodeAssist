package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.ApplicationBuildType
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.core.ComponentType
import com.tyron.builder.dexing.DexingType
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.core.dsl.TestProjectVariantDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.InternalTestExtension
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.manifest.ManifestDataProvider
import com.tyron.builder.gradle.internal.profile.ProfilingMode
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.options.StringOption
import com.tyron.builder.gradle.options.Version
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

internal class TestProjectVariantDslInfoImpl(
    componentIdentity: ComponentIdentity,
    componentType: ComponentType,
    defaultConfig: DefaultConfig,
    buildTypeObj: BuildType,
    productFlavorList: List<ProductFlavor>,
    dataProvider: ManifestDataProvider,
    services: VariantServices,
    buildDirectory: DirectoryProperty,
    private val signingConfigOverride: SigningConfig?,
    extension: InternalTestExtension
) : VariantDslInfoImpl(
    componentIdentity,
    componentType,
    defaultConfig,
    buildTypeObj,
    productFlavorList,
    dataProvider,
    services,
    buildDirectory,
    extension
), TestProjectVariantDslInfo {

    override val namespace: Provider<String> by lazy {
        // -------------
        // Special case for separate test sub-projects
        // If there is no namespace from the DSL or package attribute in the manifest, we use
        // testApplicationId, if present. This allows the test project to not have a manifest if
        // all is declared in the DSL.
        // TODO(b/170945282, b/172361895) Remove this special case - users should use namespace
        //  DSL instead of testApplicationId DSL for this... currently a warning
        if (extension.namespace != null) {
            services.provider { extension.namespace!! }
        } else {
            val testAppIdFromFlavors =
                productFlavorList.asSequence().map { it.testApplicationId }
                    .firstOrNull { it != null }
                    ?: defaultConfig.testApplicationId

            dataProvider.manifestData.map {
                it.packageName
                    ?: testAppIdFromFlavors?.also {
                        val message =
                            "Namespace not specified. Please specify a namespace for " +
                                    "the generated R and BuildConfig classes via " +
                                    "android.namespace in the test module's " +
                                    "build.gradle file. Currently, this test module " +
                                    "uses the testApplicationId " +
                                    "($testAppIdFromFlavors) as its namespace, but " +
                                    "version ${Version.VERSION_8_0} of the Android " +
                                    "Gradle Plugin will require that a namespace be " +
                                    "specified explicitly like so:\n\n" +
                                    "android {\n" +
                                    "    namespace '$testAppIdFromFlavors'\n" +
                                    "}\n\n"
                        services.issueReporter
                            .reportWarning(IssueReporter.Type.GENERIC, message)
                    }
                    ?: throw RuntimeException(
                        getMissingPackageNameErrorMessage(dataProvider.manifestLocation)
                    )
            }
        }
    }

    override val applicationId: Property<String> =
        services.newPropertyBackingDeprecatedApi(
            String::class.java,
            initTestApplicationId(productFlavorList, defaultConfig, services)
        )

    // TODO : support this
    override val isAndroidTestCoverageEnabled: Boolean
        get() = false// instrumentedTestDelegate.isAndroidTestCoverageEnabled

    // TODO: Test project doesn't have isDebuggable dsl in the build type, we should only have
    //  `debug` variants be debuggable
    override val isDebuggable: Boolean
        get() = ProfilingMode.getProfilingModeType(
            services.projectOptions[StringOption.PROFILING_MODE]
        ).isDebuggable
            ?: (buildTypeObj as? ApplicationBuildType)?.isDebuggable
            ?: false

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

//    private val instrumentedTestDelegate by lazy {
//        InstrumentedTestDslInfoImpl(
//            buildTypeObj,
//            productFlavorList,
//            defaultConfig,
//            dataProvider,
//            services,
//            mergedFlavor.testInstrumentationRunnerArguments
//        )
//    }

    override fun getInstrumentationRunner(dexingType: DexingType): Provider<String> {
//        return instrumentedTestDelegate.getInstrumentationRunner(dexingType)
        return services.provider{""}
    }

    override val instrumentationRunnerArguments: Map<String, String>
        get() = emptyMap()//instrumentedTestDelegate.instrumentationRunnerArguments
    override val handleProfiling: Provider<Boolean>
        get() = services.provider{false}//instrumentedTestDelegate.handleProfiling
    override val functionalTest: Provider<Boolean>
        get() = services.provider{false} // instrumentedTestDelegate.functionalTest
    override val testLabel: Provider<String?>
        get() = services.provider{null}//instrumentedTestDelegate.testLabel
}
