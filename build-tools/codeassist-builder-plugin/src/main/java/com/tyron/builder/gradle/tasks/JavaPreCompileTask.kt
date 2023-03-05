package com.tyron.builder.gradle.tasks

import com.google.common.annotations.VisibleForTesting
import com.tyron.builder.api.component.impl.AnnotationProcessorImpl
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts.*
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.internal.utils.findKaptOrKspConfigurationsForVariant
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.ArtifactView
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/** Task that runs before JavaCompile to collect information about annotation processors. */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.JAVA, secondaryTaskCategories = [TaskCategory.COMPILATION])
abstract class JavaPreCompileTask : NonIncrementalTask() {

    private lateinit var annotationProcessorArtifacts: ArtifactCollection
    private var kspProcessorArtifacts: ArtifactCollection? = null

    @get:Classpath
    val annotationProcessorArtifactFiles: FileCollection
        get() = annotationProcessorArtifacts.artifactFiles

    @get:Optional
    @get:Classpath
    val kspProcessorArtifactFiles: FileCollection?
        get() = kspProcessorArtifacts?.artifactFiles

    @get:Input
    abstract val annotationProcessorClassNames: ListProperty<String>

    @get:OutputFile
    abstract val annotationProcessorListFile: RegularFileProperty

    public override fun doTaskAction() {
        val annotationProcessorArtifacts =
            annotationProcessorArtifacts.artifacts.map { SerializableArtifact(it) } +
                    (kspProcessorArtifacts?.artifacts?.map { SerializableArtifact(it) }
                        ?: emptyList())

        workerExecutor.noIsolation().submit(JavaPreCompileWorkAction::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.annotationProcessorArtifacts.setDisallowChanges(annotationProcessorArtifacts)
            it.annotationProcessorClassNames.setDisallowChanges(annotationProcessorClassNames)
            it.annotationProcessorListFile.setDisallowChanges(annotationProcessorListFile)
        }
    }

    class CreationAction(
        creationConfig: ComponentCreationConfig,
        private val usingKapt: Boolean,
        private val usingKsp: Boolean
    ) :
        VariantTaskCreationAction<JavaPreCompileTask, ComponentCreationConfig>(creationConfig) {

        override val name: String
            get() = computeTaskName("javaPreCompile")

        override val type: Class<JavaPreCompileTask>
            get() = JavaPreCompileTask::class.java

        // Create the configuration early to avoid issues with composite builds (e.g., bug 183952598)
        private fun createKaptOrKspClassPath(kaptOrKsp: String): Configuration {
            val configurations = findKaptOrKspConfigurationsForVariant(
                this.creationConfig,
                kaptOrKsp
            )
            // This is a private detail, so we want to use a detached configuration, but it's not
            // possible because of https://github.com/gradle/gradle/issues/6881.
            return creationConfig.services.configurations
                .create("_agp_internal_${name}_${kaptOrKsp}Classpath")
                .setExtendsFrom(configurations)
                .apply {
                    isVisible = false
                    isCanBeResolved = true
                    isCanBeConsumed = false
                }
        }

        private val kaptClasspath: Configuration? = if (usingKapt) {
            createKaptOrKspClassPath("kapt")
        } else null

        // Create the configuration early to avoid issues with composite builds (e.g., bug 183952598)
        private val kspClasspath: Configuration? = if (usingKsp) {
            createKaptOrKspClassPath("ksp")
        } else null

        override fun handleProvider(taskProvider: TaskProvider<JavaPreCompileTask>) {
            super.handleProvider(taskProvider)

            creationConfig
                .artifacts
                .setInitialProvider(taskProvider) { it.annotationProcessorListFile }
                .withName(ANNOTATION_PROCESSOR_LIST_FILE_NAME)
                .on(InternalArtifactType.ANNOTATION_PROCESSOR_LIST)
        }

        override fun configure(task: JavaPreCompileTask) {
            super.configure(task)

            // Query for JAR instead of PROCESSED_JAR as this task only cares about the original
            // jars.
            if (usingKapt) {
                task.annotationProcessorArtifacts = kaptClasspath!!.incoming
                    .artifactView { config: ArtifactView.ViewConfiguration ->
                        config.attributes { it.attribute(ARTIFACT_TYPE, ArtifactType.JAR.type) }
                    }
                    .artifacts
            } else {
                task.annotationProcessorArtifacts = creationConfig.variantDependencies
                    .getArtifactCollection(
                        ConsumedConfigType.ANNOTATION_PROCESSOR,
                        ArtifactScope.ALL,
                        ArtifactType.JAR
                    )
            }

            task.kspProcessorArtifacts = kspClasspath?.incoming
                ?.artifactView { config: ArtifactView.ViewConfiguration ->
                    config.attributes { it.attribute(ARTIFACT_TYPE, ArtifactType.JAR.type) }
                }
                ?.artifacts

            task.annotationProcessorClassNames.setDisallowChanges(
                (creationConfig.javaCompilation.annotationProcessor as AnnotationProcessorImpl)
                    .finalListOfClassNames
            )
        }
    }
}

abstract class JavaPreCompileParameters : WorkParameters {

    abstract val annotationProcessorArtifacts: ListProperty<SerializableArtifact>
    abstract val annotationProcessorClassNames: ListProperty<String>
    abstract val annotationProcessorListFile: RegularFileProperty
}

abstract class JavaPreCompileWorkAction : WorkAction<JavaPreCompileParameters> {

    override fun execute() {
        val annotationProcessors =
            detectAnnotationProcessors(
                parameters.annotationProcessorClassNames.get(),
                parameters.annotationProcessorArtifacts.get()
            )
        writeAnnotationProcessorsToJsonFile(
            annotationProcessors, parameters.annotationProcessorListFile.get().asFile
        )
    }
}

@VisibleForTesting
const val ANNOTATION_PROCESSOR_LIST_FILE_NAME = "annotationProcessors.json"
