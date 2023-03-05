package com.tyron.builder.gradle.tasks.sync

import com.android.ide.common.build.filebasedproperties.variant.VariantProperties
import com.tyron.builder.gradle.internal.component.ApplicationCreationConfig
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.work.DisableCachingByDefault

/**
 * [org.gradle.api.Task] to create the sync model file for
 * [com.tyron.builder.api.variant.ApplicationVariant].
 *
 * The task is not incremental and not cacheable as execution should be so fast, that it outweighs
 * the benefits in performance.
 */
@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.SYNC)
abstract class ApplicationVariantModelTask: ModuleVariantModelTask() {
    @get:Input
    abstract val applicationId: Property<String>

    override fun addVariantContent(variant: VariantProperties.Builder) {
        super.addVariantContent(variant.applicationVariantPropertiesBuilder.artifactOutputPropertiesBuilder)
        variant.applicationVariantPropertiesBuilder.applicationId = applicationId.get()
    }

    class CreationAction(private val applicationCreationConfig: ApplicationCreationConfig) :
        AbstractVariantModelTask.CreationAction<ApplicationVariantModelTask, VariantCreationConfig>(
            creationConfig = applicationCreationConfig,
        ) {

        override val type: Class<ApplicationVariantModelTask>
            get() = ApplicationVariantModelTask::class.java

        override fun configure(task: ApplicationVariantModelTask) {
            super.configure(task)
            task.applicationId.setDisallowChanges(applicationCreationConfig.applicationId)
            task.manifestPlaceholders.setDisallowChanges(applicationCreationConfig.manifestPlaceholders)
        }
    }
}
