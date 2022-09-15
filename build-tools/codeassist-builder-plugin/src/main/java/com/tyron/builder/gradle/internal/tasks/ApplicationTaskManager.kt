package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.api.variant.ApplicationVariantBuilder
import com.tyron.builder.core.ComponentType
import com.tyron.builder.gradle.BaseExtension
import com.tyron.builder.gradle.internal.AbstractAppTaskManager
import com.tyron.builder.gradle.internal.component.ApplicationCreationConfig
import com.tyron.builder.gradle.internal.component.TestComponentCreationConfig
import com.tyron.builder.gradle.internal.component.TestFixturesCreationConfig
import com.tyron.builder.gradle.internal.dsl.AbstractPublishing
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.PublishedConfigType
import com.tyron.builder.gradle.internal.publishing.PublishedConfigSpec
import com.tyron.builder.gradle.internal.tasks.databinding.DataBindingExportFeatureNamespacesTask
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.tyron.builder.gradle.internal.tasks.factory.TaskManagerConfig
import com.tyron.builder.gradle.internal.tasks.factory.dependsOn
import com.tyron.builder.gradle.internal.variant.ComponentInfo
import com.tyron.builder.gradle.internal.variant.VariantModel
import com.tyron.builder.gradle.tasks.sync.AppIdListTask
import com.tyron.builder.gradle.tasks.sync.ApplicationVariantModelTask
import org.gradle.api.Project

class ApplicationTaskManager(
    project: Project,
    private val variants: Collection<ComponentInfo<ApplicationVariantBuilder, ApplicationCreationConfig>>,
    testComponents: Collection<TestComponentCreationConfig>,
    testFixturesComponents: Collection<TestFixturesCreationConfig>,
    globalConfig: GlobalTaskCreationConfig,
    localConfig: TaskManagerConfig,
    extension: BaseExtension,
) : AbstractAppTaskManager<ApplicationVariantBuilder, ApplicationCreationConfig>(
    project,
    variants,
    testComponents,
    testFixturesComponents,
    globalConfig,
    localConfig,
    extension,
) {

    override fun createTopLevelTasks(componentType: ComponentType, variantModel: VariantModel) {
        super.createTopLevelTasks(componentType, variantModel)
        taskFactory.register(
            AppIdListTask.CreationAction(
                globalConfig,
                variants.associate {
                    it.variant.name to it.variant.applicationId
                }
            )
        )
    }

    override fun doCreateTasksForVariant(
        variantInfo: ComponentInfo<ApplicationVariantBuilder, ApplicationCreationConfig>
    ) {
        createCommonTasks(variantInfo)

        val variant = variantInfo.variant

        taskFactory.register(ApplicationVariantModelTask.CreationAction(variant))

        // Base feature specific tasks.
//        taskFactory.register(FeatureSetMetadataWriterTask.CreationAction(variant))

        createValidateSigningTask(variant)
        // Add tasks to produce the signing config files.
        taskFactory.register(SigningConfigWriterTask.CreationAction(variant))
        taskFactory.register(SigningConfigVersionsWriterTask.CreationAction(variant))

        // Add a task to produce the app-metadata.properties file
        taskFactory.register(AppMetadataTask.CreationAction(variant))
            .dependsOn(variant.taskContainer.preBuildTask)

//        if (globalConfig.assetPacks.isNotEmpty()) {
//            createAssetPackTasks(variant)
//        }

        taskFactory.register(MergeArtProfileTask.CreationAction(variant))
        taskFactory.register(CompileArtProfileTask.CreationAction(variant))

        if (variant.buildFeatures.dataBinding
            && globalConfig.hasDynamicFeatures) {
            // Create a task that will write the namespaces of all features into a file. This file's
            // path is passed into the Data Binding annotation processor which uses it to know about
            // all available features.
            //
            // <p>see: {@link TaskManager#setDataBindingAnnotationProcessorParams(ComponentCreationConfig)}
            taskFactory.register(
                DataBindingExportFeatureNamespacesTask.CreationAction(variant)
            )
        }
//
//        createDynamicBundleTask(variantInfo)
//
//        handleMicroApp(variant)

        val publishInfo = variant.publishInfo!!
        for (component in publishInfo.components) {
            val configType = if (component.type == AbstractPublishing.Type.APK) {
                PublishedConfigType.APK_PUBLICATION
            } else {
                PublishedConfigType.AAB_PUBLICATION
            }
            createSoftwareComponent(
                variant,
                component.componentName,
                configType
            )
        }
    }

    private fun createSoftwareComponent(
        appVariant: ApplicationCreationConfig,
        componentName: String,
        publication: PublishedConfigType
    ) {
        val component = localConfig.componentFactory.adhoc(componentName)
        val config = appVariant.variantDependencies.getElements(PublishedConfigSpec(publication, componentName, false))!!
        component.addVariantsFromConfiguration(config) { }
        project.components.add(component)
    }
}