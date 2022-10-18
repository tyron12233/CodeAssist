package com.tyron.builder.gradle.tasks.sync

import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationAction
import com.tyron.builder.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.android.ide.common.build.filebasedproperties.module.AppIdListSync
import com.android.ide.common.build.filebasedproperties.module.AppIdSync
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.tasks.BaseTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.work.DisableCachingByDefault
import java.io.BufferedOutputStream
import java.io.FileOutputStream

/**
 * Task to write the list of application ids for all variants of this module.
 */
@DisableCachingByDefault
abstract class AppIdListTask: BaseTask() {

    @get:OutputFile
    abstract val outputModelFile: RegularFileProperty

    @get:Nested
    abstract val variantsApplicationId: ListProperty<VariantInformation>

    abstract class VariantInformation {
        @get:Input
        abstract val variantName: Property<String>

        @get:Input
        abstract val applicationId: Property<String>
    }

    @TaskAction
    fun doTaskAction() {
        val listOfAppIds = AppIdListSync.newBuilder()
        variantsApplicationId.get().forEach { variantInformation ->
            listOfAppIds.addAppIds(
                AppIdSync.newBuilder().also {
                    it.name = variantInformation.variantName.get()
                    it.applicationId = variantInformation.applicationId.get()
                }.build()
            )
        }
        BufferedOutputStream(FileOutputStream(outputModelFile.asFile.get())).use {
            listOfAppIds.build().writeTo(it)
        }
    }

    companion object {
        fun getTaskName() = "appIdListTask"

        const val FN_APP_ID_LIST = "app_id_list.pb"
    }

    class CreationAction(
        private val config: GlobalTaskCreationConfig,
        private val applicationIds: Map<String, Provider<String>>,
        ) : GlobalTaskCreationAction<AppIdListTask>(config) {

        override val name: String = getTaskName()

        override val type: Class<AppIdListTask> = AppIdListTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<AppIdListTask>) {
            config.globalArtifacts
                .setInitialProvider(taskProvider, AppIdListTask::outputModelFile)
                .withName(FN_APP_ID_LIST)
                .on(InternalArtifactType.APP_ID_LIST_MODEL)
        }

        override fun configure(task: AppIdListTask) {
            super.configure(task)
            applicationIds.forEach { (variantName, applicationId) ->
                task.variantsApplicationId.add(
                    config.services.newInstance(VariantInformation::class.java).also {
                        it.variantName.setDisallowChanges(variantName)
                        it.applicationId.setDisallowChanges(applicationId)
                    }
                )
            }
            task.variantsApplicationId.disallowChanges()
        }
    }
}
