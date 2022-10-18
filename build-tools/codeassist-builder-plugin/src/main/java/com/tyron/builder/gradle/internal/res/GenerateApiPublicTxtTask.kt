package com.tyron.builder.gradle.internal.res

import com.android.ide.common.symbols.SymbolIo
import com.android.ide.common.symbols.SymbolTable
import com.android.resources.ResourceType
import com.android.utils.FileUtils
import com.google.common.annotations.VisibleForTesting
import com.tyron.builder.api.artifact.SingleArtifact
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.NonIncrementalTask
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Task to generate the public.txt API artifact [SingleArtifact.PUBLIC_ANDROID_RESOURCES_LIST]
 *
 * The artifact in the AAR has the challenging-for-consumers attribute (They can;t ) of sometimes not existing,
 * so this tasks
 *
 * Task to take the (possibly not existing) internal public API file and generate one that exists unconditionally
 *
 * Caching disabled by default for this task because the task does very little work.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 *  simply executing the task.
 */
@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.METADATA)
abstract class GenerateApiPublicTxtTask : NonIncrementalTask() {

    @get:InputFiles // Use InputFiles rather than InputFile to allow the file not to exist
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val internalPublicTxt: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localOnlyResourceSymbols: RegularFileProperty

    @get:OutputFile
    abstract val externalPublicTxt: RegularFileProperty

    override fun doTaskAction() {
        workerExecutor.noIsolation().submit(WorkAction::class.java) { parameters ->
//            parameters.initializeFromAndroidVariantTask(this)
            parameters.internalPublicTxt.set(internalPublicTxt)
            parameters.symbols.set(localOnlyResourceSymbols)
            parameters.externalPublicTxt.set(externalPublicTxt)
        }
    }

    abstract class WorkAction: org.gradle.workers.WorkAction<WorkAction.Parameters> {
        abstract class Parameters: WorkParameters {
            abstract val internalPublicTxt: RegularFileProperty
            abstract val symbols: RegularFileProperty
            abstract val externalPublicTxt: RegularFileProperty
        }

        override fun execute() {
            writeFile(
                internalPublicTxt = parameters.internalPublicTxt.get().asFile.toPath(),
                symbols = parameters.symbols.get().asFile.toPath(),
                externalPublicTxt = parameters.externalPublicTxt.get().asFile.toPath()
            )
        }
    }

    class CreationAction(creationConfig: ComponentCreationConfig):
        VariantTaskCreationAction<GenerateApiPublicTxtTask, ComponentCreationConfig>(
            creationConfig
        ) {

        override val name: String = computeTaskName("generate", "ExternalPublicTxt")
        override val type: Class<GenerateApiPublicTxtTask> get() = GenerateApiPublicTxtTask::class.java

        override fun handleProvider(taskProvider: TaskProvider<GenerateApiPublicTxtTask>) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(taskProvider,
            GenerateApiPublicTxtTask::externalPublicTxt)
                .withName("public.txt")
                .on(SingleArtifact.PUBLIC_ANDROID_RESOURCES_LIST)
        }

        override fun configure(task: GenerateApiPublicTxtTask) {
            super.configure(task)
            task.internalPublicTxt.setDisallowChanges(creationConfig.artifacts.get(InternalArtifactType.PUBLIC_RES))
            task.localOnlyResourceSymbols.setDisallowChanges(creationConfig.artifacts.get(InternalArtifactType.LOCAL_ONLY_SYMBOL_LIST))
        }
    }

    companion object {
        @VisibleForTesting
        internal fun writeFile(
            internalPublicTxt: Path,
            symbols: Path,
            externalPublicTxt: Path
        ) {
            if (Files.exists(internalPublicTxt)) {
                FileUtils.copyFile(
                    internalPublicTxt,
                    externalPublicTxt
                )
            } else {
                Files.newBufferedWriter(externalPublicTxt).use { writer ->
                    writePublicTxtFile(SymbolIo.readRDef(symbols), writer)
                }
            }
        }
    }
}

/**
 * Writes the symbol table treating all symbols as public in the AAR R.txt format.
 *
 * See [SymbolIo.readFromPublicTxtFile] for the reading counterpart.
 *
 * The format does not include styleable children (see `SymbolExportUtilsTest`)
 */
fun writePublicTxtFile(table: SymbolTable, writer: Writer) {
    for (resType in ResourceType.values()) {
        val symbols =
            table.getSymbolByResourceType(resType)
        for (s in symbols) {
            writer.write(s.resourceType.getName())
            writer.write(' '.code)
            writer.write(s.canonicalName)
            writer.write('\n'.code)
        }
    }
}