package com.tyron.builder.api.component.impl.features

import com.google.common.base.Preconditions
import com.tyron.builder.api.variant.AndroidResources
import com.tyron.builder.api.variant.impl.initializeAaptOptionsFromDsl
import com.tyron.builder.core.ComponentTypeImpl
import com.tyron.builder.errors.IssueReporter
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.component.ConsumableCreationConfig
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.component.UnitTestCreationConfig
import com.tyron.builder.gradle.internal.component.features.AndroidResourcesCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.ComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.features.AndroidResourcesDslInfo
import com.tyron.builder.gradle.internal.dsl.AaptOptions
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.scope.InternalArtifactType.COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.model.VectorDrawablesOptions
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

class AndroidResourcesCreationConfigImpl(
    private val component: ComponentCreationConfig,
    private val dslInfo: ComponentDslInfo,
    private val androidResourcesDsl: AndroidResourcesDslInfo,
    private val internalServices: VariantServices
): AndroidResourcesCreationConfig {

    override val androidResources: AndroidResources by lazy {
        initializeAaptOptionsFromDsl(androidResourcesDsl.androidResources, internalServices)
    }
    override val pseudoLocalesEnabled: Property<Boolean> by lazy {
        internalServices.newPropertyBackingDeprecatedApi(
            Boolean::class.java,
            androidResourcesDsl.isPseudoLocalesEnabled
        )
    }

    override val isCrunchPngs: Boolean
        get() {
            // If set for this build type, respect that.
            val buildTypeOverride = androidResourcesDsl.isCrunchPngs
            if (buildTypeOverride != null) {
                return buildTypeOverride
            }
            // Otherwise, if set globally, respect that.
            val globalOverride = (androidResourcesDsl.androidResources as AaptOptions).cruncherEnabledOverride

            // If not overridden, use the default from the build type.
            return globalOverride ?: androidResourcesDsl.isCrunchPngsDefault
        }

    // Resource shrinker expects MergeResources task to have all the resources merged and with
    // overlay rules applied, so we have to go through the MergeResources pipeline in case it's
    // enabled, see b/134766811.
    override val isPrecompileDependenciesResourcesEnabled: Boolean
        get() = internalServices.projectOptions[BooleanOption.PRECOMPILE_DEPENDENCIES_RESOURCES] &&
                !useResourceShrinker

    override val resourceConfigurations: Set<String>
        get() = androidResourcesDsl.resourceConfigurations
    override val vectorDrawables: VectorDrawablesOptions
        get() = androidResourcesDsl.vectorDrawables

    override val useResourceShrinker: Boolean
        get() {
            if (component !is ConsumableCreationConfig || !component.resourcesShrink
                || dslInfo.componentType.isForTesting) {
                return false
            }
            val newResourceShrinker =
                component.services.projectOptions[BooleanOption.ENABLE_NEW_RESOURCE_SHRINKER]
            if (!newResourceShrinker && component.global.hasDynamicFeatures) {
                val message = String.format(
                    "Resource shrinker for multi-apk applications can be enabled via " +
                            "experimental flag: '%s'.",
                    BooleanOption.ENABLE_NEW_RESOURCE_SHRINKER.propertyName)
                internalServices
                    .issueReporter
                    .reportError(IssueReporter.Type.GENERIC, message)
                return false
            }
            if (component is ConsumableCreationConfig && !component.minifiedEnabled) {
                internalServices
                    .issueReporter
                    .reportError(
                        IssueReporter.Type.GENERIC,
                        "Removing unused resources requires unused code shrinking to be turned on. See "
                                + "http://d.android.com/r/tools/shrink-resources.html "
                                + "for more information.")
                return false
            }
            return true
        }

    override val compiledRClassArtifact: Provider<RegularFile>
        get() {
            return if (component.global.namespacedAndroidResources) {
                component.artifacts.get(InternalArtifactType.COMPILE_R_CLASS_JAR)
            } else {
                val componentType = dslInfo.componentType

                if (componentType == ComponentTypeImpl.ANDROID_TEST) {
                    component.artifacts.get(COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR)
                } else if (componentType == ComponentTypeImpl.UNIT_TEST) {
                    getRJarForUnitTests()
                } else {
                    // TODO(b/138780301): Also use it in android tests.
                    val useCompileRClassInApp = (internalServices
                        .projectOptions[BooleanOption
                        .ENABLE_APP_COMPILE_TIME_R_CLASS]
                            && !componentType.isForTesting)
                    if (componentType.isAar || useCompileRClassInApp) {
                        component.artifacts.get(InternalArtifactType.COMPILE_R_CLASS_JAR)
                    } else {
                        Preconditions.checkState(
                            componentType.isApk,
                            "Expected APK type but found: $componentType"
                        )
                        component.artifacts.get(COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR)
                    }
                }
            }
        }

    override fun getCompiledRClasses(
        configType: AndroidArtifacts.ConsumedConfigType
    ): FileCollection {
        return if (component.global.namespacedAndroidResources) {
            internalServices.fileCollection().also { fileCollection ->
                val namespacedRClassJar = component.artifacts.get(InternalArtifactType.COMPILE_R_CLASS_JAR)
                val fileTree = internalServices.fileTree(namespacedRClassJar).builtBy(namespacedRClassJar)
                fileCollection.from(fileTree)
                fileCollection.from(
                    component.variantDependencies.getArtifactFileCollection(
                        configType,
                        AndroidArtifacts.ArtifactScope.ALL,
                        AndroidArtifacts.ArtifactType.SHARED_CLASSES
                    )
                )
                (component as? TestComponentCreationConfig)?.mainVariant?.let {
                    fileCollection.from(it.artifacts.get(InternalArtifactType.COMPILE_R_CLASS_JAR).get())
                }
            }
        } else {
            internalServices.fileCollection(compiledRClassArtifact)
        }
    }

    private fun getRJarForUnitTests(): Provider<RegularFile> {
        Preconditions.checkState(
            component.componentType === ComponentTypeImpl.UNIT_TEST && component is UnitTestCreationConfig,
            "Expected unit test type but found: ${component.componentType}"
        )
        val mainVariant = (component as UnitTestCreationConfig).mainVariant
        return if (mainVariant.componentType.isAar) {
            component.artifacts.get(COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR)
        } else {
            Preconditions.checkState(
                mainVariant.componentType.isApk,
                "Expected APK type but found: " + mainVariant.componentType
            )
            mainVariant
                .artifacts
                .get(COMPILE_AND_RUNTIME_NOT_NAMESPACED_R_CLASS_JAR)
        }
    }
}
