package com.tyron.builder.gradle.internal.tasks

import com.tyron.builder.api.artifact.MultipleArtifact
import com.tyron.builder.gradle.errors.MessageReceiverImpl
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.scope.InternalMultipleArtifactType
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.multidex.D8MainDexList
import com.tyron.builder.plugin.options.SyncOptions
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File

/**
 * A task calculating the main dex list for bundle using D8.
 */
@CacheableTask
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.DEXING)
abstract class D8BundleMainDexListTask : NonIncrementalTask() {

    @get:Input
    abstract val errorFormat: Property<SyncOptions.ErrorFormatMode>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aaptGeneratedRules: RegularFileProperty

    @get:Optional
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val userMultidexProguardRules: ListProperty<RegularFile>

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val userMultidexKeepFile: Property<File>

    @get:Classpath
    abstract val bootClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseDexDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val featureDexDirs: ConfigurableFileCollection

    @get:Classpath
    abstract val libraryClasses: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(
            MainDexListWorkerAction::class.java
        ) { params ->
//            params.initializeFromAndroidVariantTask(this)
            params.proguardRules.from(aaptGeneratedRules)
            params.proguardRules.from(userMultidexProguardRules.get())
            params.programDexFiles.from(baseDexDirs, featureDexDirs)
            params.libraryClasses.from(libraryClasses)
            params.bootClasspath.from(bootClasspath)
            params.userMultidexKeepFile.set(userMultidexKeepFile)
            params.output.set(output)
            params.errorFormat.set(errorFormat)
        }
    }

    abstract class MainDexListWorkerAction : WorkAction<MainDexListWorkerAction.Params> {
        abstract class Params: WorkParameters {
            abstract val proguardRules: ConfigurableFileCollection
            abstract val programDexFiles: ConfigurableFileCollection
            abstract val libraryClasses: ConfigurableFileCollection
            abstract val bootClasspath: ConfigurableFileCollection
            abstract val userMultidexKeepFile: Property<File>
            abstract val output: RegularFileProperty
            abstract val errorFormat: Property<SyncOptions.ErrorFormatMode>
        }

        override fun execute() {
            val libraryFiles = parameters.libraryClasses.files + parameters.bootClasspath.files
            val logger = Logging.getLogger(D8BundleMainDexListTask::class.java)

            logger.debug("Generating the main dex list using D8.")
            logger.debug("Program files: %s", parameters.programDexFiles.joinToString())
            logger.debug("Library files: %s", libraryFiles.joinToString())
            logger.debug("Proguard rule files: %s", parameters.proguardRules.joinToString())

            val mainDexClasses = mutableSetOf<String>()

            mainDexClasses.addAll(
                D8MainDexList.generate(
                    getPlatformRules(),
                    parameters.proguardRules.map { it.toPath() },
                    parameters.programDexFiles.map { it.toPath() },
                    libraryFiles.map { it.toPath() },
                    MessageReceiverImpl(parameters.errorFormat.get(), logger)
                )
            )

            parameters.userMultidexKeepFile.orNull?.let {
                mainDexClasses.addAll(it.readLines())
            }

            parameters.output.asFile.get().writeText(
                mainDexClasses.joinToString(separator = System.lineSeparator()))
        }
    }

    class CreationAction(
        creationConfig: ApkCreationConfig
    ) : VariantTaskCreationAction<D8BundleMainDexListTask, ApkCreationConfig> (
        creationConfig
    ) {
        private val libraryClasses: FileCollection

        init {
            @Suppress("DEPRECATION") // Legacy support
            val libraryScopes = setOf(
                com.tyron.builder.api.transform.QualifiedContent.Scope.PROVIDED_ONLY,
                com.tyron.builder.api.transform.QualifiedContent.Scope.TESTED_CODE
            )

            @Suppress("DEPRECATION") // Legacy support
            libraryClasses = creationConfig.transformManager
                .getPipelineOutputAsFileCollection { contentTypes, scopes ->
                    contentTypes.contains(
                        com.tyron.builder.api.transform.QualifiedContent.DefaultContentType.CLASSES
                    ) && libraryScopes.intersect(scopes).isNotEmpty()
                }
        }

        override val name: String = creationConfig.computeTaskName("bundleMultiDexList")
        override val type: Class<D8BundleMainDexListTask> = D8BundleMainDexListTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<D8BundleMainDexListTask>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider, D8BundleMainDexListTask::output
            ).withName("mainDexList.txt").on(InternalArtifactType.MAIN_DEX_LIST_FOR_BUNDLE)
        }

        override fun configure(task: D8BundleMainDexListTask) {
            super.configure(task)

            creationConfig.artifacts.setTaskInputToFinalProduct(
                InternalArtifactType.LEGACY_MULTIDEX_AAPT_DERIVED_PROGUARD_RULES,
                task.aaptGeneratedRules
            )

            task.userMultidexProguardRules.setDisallowChanges(
                creationConfig.artifacts.getAll(MultipleArtifact.MULTIDEX_KEEP_PROGUARD)
            )
            creationConfig.multiDexKeepFile?.let {
                task.userMultidexKeepFile.setDisallowChanges(it)
            }
            task.bootClasspath.from(creationConfig.global.bootClasspath).disallowChanges()
            task.errorFormat
                .setDisallowChanges(
                    SyncOptions.getErrorFormatMode(creationConfig.services.projectOptions))

            task.libraryClasses.from(libraryClasses).disallowChanges()

            task.baseDexDirs.from(
                creationConfig.artifacts.getAll(InternalMultipleArtifactType.DEX))

            task.featureDexDirs.from(
                creationConfig.variantDependencies.getArtifactFileCollection(
                    AndroidArtifacts.ConsumedConfigType.REVERSE_METADATA_VALUES,
                    AndroidArtifacts.ArtifactScope.ALL,
                    AndroidArtifacts.ArtifactType.FEATURE_PUBLISHED_DEX
                )
            )
        }
    }
}

internal fun getPlatformRules(): List<String> = listOf(
    "-keep public class * extends android.app.Instrumentation {\n"
            + "  <init>(); \n"
            + "  void onCreate(...);\n"
            + "  android.app.Application newApplication(...);\n"
            + "  void callApplicationOnCreate(android.app.Application);\n"
            + "}",
    "-keep public class * extends android.app.Application { "
            + "  <init>();\n"
            + "  void attachBaseContext(android.content.Context);\n"
            + "}",
    "-keep public class * extends android.app.backup.BackupAgent { <init>(); }",
    "-keep public class * extends android.test.InstrumentationTestCase { <init>(); }"
)
