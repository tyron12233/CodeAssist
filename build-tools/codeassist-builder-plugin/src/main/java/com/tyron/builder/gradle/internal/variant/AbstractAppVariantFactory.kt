package com.tyron.builder.gradle.internal.variant

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.api.variant.VariantBuilder
import com.tyron.builder.core.BuilderConstants
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.api.ApplicationVariantImpl
import com.tyron.builder.gradle.internal.api.BaseVariantImpl
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.VariantDslInfo
import com.tyron.builder.gradle.internal.dsl.BuildType
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.ProductFlavor
import com.tyron.builder.gradle.internal.dsl.SigningConfig
import com.tyron.builder.gradle.internal.plugins.DslContainerProvider
import com.tyron.builder.gradle.internal.scope.MutableTaskContainer
import com.tyron.builder.gradle.internal.services.DslServices
import com.tyron.builder.gradle.internal.services.VariantServices
import org.gradle.api.Project

/**
 * An implementation of VariantFactory for a project that generates APKs.
 *
 *
 * This can be an app project, or a test-only project, though the default behavior is app.
 */
abstract class AbstractAppVariantFactory<VariantBuilderT : VariantBuilder, VariantDslInfoT: VariantDslInfo, VariantT : VariantCreationConfig>(
    dslServices: DslServices,
) : BaseVariantFactory<VariantBuilderT, VariantDslInfoT, VariantT>(
    dslServices,
) {

    override fun createVariantData(
        componentIdentity: ComponentIdentity,
        artifacts: ArtifactsImpl,
        services: VariantServices,
        taskContainer: MutableTaskContainer
    ): BaseVariantData {
        return ApplicationVariantData(
            componentIdentity,
            artifacts,
            services,
            taskContainer
        )
    }

    override val variantImplementationClass: Class<out BaseVariantImpl?>
        get() {
            return ApplicationVariantImpl::class.java
        }

    override fun preVariantCallback(
        project: Project,
        dslExtension: CommonExtension<*, *, *, *>,
        model: VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig>
    ) {
        super.preVariantCallback(project, dslExtension, model)
        validateVersionCodes(model)
        if (!componentType.isDynamicFeature) {
            return
        }

        // below is for dynamic-features only.
        val issueReporter: IssueReporter = dslServices.issueReporter
        for (buildType in model.buildTypes.values) {
            if (buildType.buildType.isMinifyEnabled) {
                issueReporter.reportError(
                        IssueReporter.Type.GENERIC,
                        """
                            Dynamic feature modules cannot set minifyEnabled to true. minifyEnabled is set to true in build type '${buildType.buildType.name}'.
                            To enable minification for a dynamic feature module, set minifyEnabled to true in the base module.
                            """.trimIndent())
            }
        }

        // check if any of the build types or flavors have a signing config.
        var message = ("Signing configuration should not be declared in build types of "
                + "dynamic-feature. Dynamic-features use the signing configuration "
                + "declared in the application module.")
        for (buildType in model.buildTypes.values) {
            if (buildType.buildType.signingConfig != null) {
                issueReporter.reportWarning(
                        IssueReporter.Type.SIGNING_CONFIG_DECLARED_IN_DYNAMIC_FEATURE, message)
            }
        }
        message = ("Signing configuration should not be declared in product flavors of "
                + "dynamic-feature. Dynamic-features use the signing configuration "
                + "declared in the application module.")
        for (productFlavor in model.productFlavors.values) {
            if (productFlavor.productFlavor.signingConfig != null) {
                issueReporter.reportWarning(
                        IssueReporter.Type.SIGNING_CONFIG_DECLARED_IN_DYNAMIC_FEATURE, message)
            }
        }

        // check if the default config or any of the build types or flavors try to set abiFilters.
        message =
                ("abiFilters should not be declared in dynamic-features. Dynamic-features use the "
                        + "abiFilters declared in the application module.")
        if (!model.defaultConfigData
                        .defaultConfig
                        .ndkConfig
                        .abiFilters
                        .isEmpty()) {
            issueReporter.reportWarning(IssueReporter.Type.GENERIC, message)
        }
        for (buildType in model.buildTypes.values) {
            if (buildType.buildType.ndkConfig.abiFilters.isNotEmpty()) {
                issueReporter.reportWarning(IssueReporter.Type.GENERIC, message)
            }
        }
        for (productFlavor in model.productFlavors.values) {
            if (productFlavor.productFlavor.ndkConfig.abiFilters.isNotEmpty()) {
                issueReporter.reportWarning(IssueReporter.Type.GENERIC, message)
            }
        }
    }

    override fun createDefaultComponents(
            dslContainers: DslContainerProvider<DefaultConfig, BuildType, ProductFlavor, SigningConfig>) {
        // must create signing config first so that build type 'debug' can be initialized
        // with the debug signing config.
        dslContainers.signingConfigContainer.create(BuilderConstants.DEBUG)
        dslContainers.buildTypeContainer.create(BuilderConstants.DEBUG)
        dslContainers.buildTypeContainer.create(BuilderConstants.RELEASE)
    }

    private fun validateVersionCodes(
            model: VariantInputModel<DefaultConfig, BuildType, ProductFlavor, SigningConfig>) {
        val issueReporter: IssueReporter = dslServices.issueReporter
        val versionCode = model.defaultConfigData.defaultConfig.versionCode
        if (versionCode != null && versionCode < 1) {
            issueReporter.reportError(
                    IssueReporter.Type.GENERIC,
                    """
                        android.defaultConfig.versionCode is set to $versionCode, but it should be a positive integer.
                        See https://developer.android.com/studio/publish/versioning#appversioning for more information.
                        """.trimIndent())
            return
        }
        for (flavorData in model.productFlavors.values) {
            val flavorVersionCode = flavorData.productFlavor.versionCode
            if (flavorVersionCode == null || flavorVersionCode > 0) {
                return
            }
            issueReporter.reportError(
                    IssueReporter.Type.GENERIC,
                    ("versionCode is set to $flavorVersionCode in product flavor " +
                            "${flavorData.productFlavor.name}, but it should be a positive integer. " +
                            "See https://developer.android.com/studio/publish/versioning#appversioning for more information."))
        }
    }
}
