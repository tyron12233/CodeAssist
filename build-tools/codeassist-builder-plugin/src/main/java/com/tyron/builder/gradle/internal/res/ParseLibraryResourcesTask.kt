package com.tyron.builder.gradle.internal.res

import com.android.SdkConstants
import com.android.ide.common.resources.FileStatus
import com.android.ide.common.symbols.*
import com.android.ide.common.symbols.SymbolIo.getPartialRContentsAsString
import com.android.ide.common.symbols.SymbolIo.writePartialR
import com.android.resources.FolderTypeRelationship
import com.android.resources.ResourceFolderType
import com.android.utils.FileUtils
import com.tyron.builder.files.SerializableChange
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.tasks.BuildAnalyzer
import com.tyron.builder.gradle.internal.tasks.TaskCategory
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.gradle.options.BooleanOption
import com.tyron.builder.internal.aapt.v2.Aapt2RenamingConventions
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.tasks.IncrementalTask
import com.tyron.builder.tasks.getChangesInSerializableForm
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.io.IOException
import java.util.*
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Task for parsing local library resources. It generates the local R-def.txt file containing the
 * symbols (see SymbolIo.writeRDef for the format), which is used by the GenerateLibraryRFileTask
 * to merge with the dependencies R.txt files to generate the R.txt for this module and the R.jar
 * for the universe.
 *
 * TODO(imorlowska): Refactor the parsers to work with workers, so we can parse files in parallel.
 */
@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.ANDROID_RESOURCES)
abstract class ParseLibraryResourcesTask : IncrementalTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val platformAttrRTxt: Property<FileCollection>

    @get:Incremental
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputResourcesDir: DirectoryProperty

    @get:Input
    abstract val validateResources: Property<Boolean>

    @get:Input
    abstract val enablePartialRIncrementalBuilds: Property<Boolean>

    @get:OutputFile
    abstract val librarySymbolsFile: RegularFileProperty

    @get:Optional
    @get:OutputDirectory
    abstract val partialRDir: DirectoryProperty

    override fun doTaskAction(inputChanges: InputChanges) {
        val incremental = inputChanges.isIncremental
        val changedResources = if (incremental) {
            // This method already ignores directories, only actual file changes will be reported.
            inputChanges.getChangesInSerializableForm(inputResourcesDir).changes
        } else {
            listOf()
        }
        workerExecutor.noIsolation().submit(ParseResourcesRunnable::class.java) {
//            it.initializeFromAndroidVariantTask(this)
            it.inputResDir.set(inputResourcesDir)
            it.platformAttrsRTxt.set(platformAttrRTxt.get().singleFile)
            it.librarySymbolsFile.set(librarySymbolsFile)
            it.incremental.set(incremental)
            it.changedResources.set(changedResources)
            it.partialRDir.set(partialRDir)
            it.enablePartialRIncrementalBuilds.set(enablePartialRIncrementalBuilds)
            it.validateResources.set(validateResources)
        }
    }

    abstract class ParseResourcesParams: WorkParameters {
        abstract val inputResDir: DirectoryProperty
        abstract val platformAttrsRTxt: RegularFileProperty
        abstract val librarySymbolsFile: RegularFileProperty
        abstract val incremental: Property<Boolean>
        abstract val changedResources: ListProperty<SerializableChange>
        abstract val partialRDir: DirectoryProperty
        abstract val enablePartialRIncrementalBuilds: Property<Boolean>
        abstract val validateResources: Property<Boolean>
    }

    abstract class ParseResourcesRunnable : WorkAction<ParseResourcesParams> {
        override fun execute() {
            if (parameters.incremental.get()) {
                if (parameters.enablePartialRIncrementalBuilds.get() && parameters.partialRDir.isPresent) {
                    doIncrementalPartialRTaskAction(parameters)
                    return
                }
                if (parameters.changedResources.get().all { canBeProcessedIncrementallyWithRDef(it) }) {
                    doIncrementalRDefTaskAction(parameters)
                    return
                }
            }
            doFullTaskAction(parameters)
        }
    }

    class CreateAction(
        creationConfig: ComponentCreationConfig
    ) : VariantTaskCreationAction<ParseLibraryResourcesTask, ComponentCreationConfig>(
        creationConfig
    ) {

        override val name: String
            get() = computeTaskName("parse", "LocalResources")
        override val type: Class<ParseLibraryResourcesTask>
            get() = ParseLibraryResourcesTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<ParseLibraryResourcesTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                ParseLibraryResourcesTask::librarySymbolsFile
            ).withName(SdkConstants.FN_R_DEF_TXT).on(InternalArtifactType.LOCAL_ONLY_SYMBOL_LIST)
            if (creationConfig.services
                            .projectOptions[BooleanOption.ENABLE_PARTIAL_R_INCREMENTAL_BUILDS]) {
                creationConfig.artifacts.setInitialProvider(
                    taskProvider,
                    ParseLibraryResourcesTask::partialRDir
                ).withName(SdkConstants.FD_PARTIAL_R)
                    .on(InternalArtifactType.LOCAL_ONLY_PARTIAL_SYMBOL_DIRECTORY)
            }
        }

        override fun configure(
            task: ParseLibraryResourcesTask
        ) {
            super.configure(task)
            task.platformAttrRTxt.set(creationConfig.global.platformAttrs)
            task.enablePartialRIncrementalBuilds.setDisallowChanges(
                creationConfig.services
                    .projectOptions[BooleanOption.ENABLE_PARTIAL_R_INCREMENTAL_BUILDS])

            creationConfig.artifacts.setTaskInputToFinalProduct(
                    InternalArtifactType.PACKAGED_RES,
                    task.inputResourcesDir
            )

            task.validateResources.setDisallowChanges(
                    !creationConfig.services.projectOptions[
                            BooleanOption.DISABLE_RESOURCE_VALIDATION])
        }
    }
}

data class SymbolTableWithContextPath(val path : String, val symbolTable: SymbolTable)
data class PartialRFileNameContents(val name : String, val contents: String)

internal fun doFullTaskAction(parseResourcesParams: ParseLibraryResourcesTask.ParseResourcesParams) {
    if (parseResourcesParams.enablePartialRIncrementalBuilds.get()
            && parseResourcesParams.partialRDir.isPresent) {
        val partialRDirectory = parseResourcesParams.partialRDir.asFile.get()
        // Generate SymbolTables from resource files are used to generate:
        // 1. Partial R files, which are used for incremental task runs.
        // 2. A merged SymbolTable which is used to generate the librarySymbolsFile.
        val androidPlatformAttrSymbolTable = getAndroidAttrSymbols(
                parseResourcesParams.platformAttrsRTxt.asFile.get())
        val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val resourceFileSymbolTables: SortedSet<SymbolTableWithContextPath> =
                getResourceDirectorySymbolTables(
                        parseResourcesParams.inputResDir.asFile.get(),
                        androidPlatformAttrSymbolTable,
                        documentBuilder,
                        parseResourcesParams.validateResources.get())
        writeSymbolTablesToPartialRFiles(resourceFileSymbolTables, partialRDirectory)
        // Write in the format of R-def.txt since the IDs do not matter. The symbols will be
        // written in a deterministic way (sorted by type, then by canonical name).
        writeRDefFromPartialRDirectory(
                partialRDirectory, parseResourcesParams.librarySymbolsFile.asFile.get())
    } else {
        // IDs do not matter as we will merge all symbols and re-number them in the
        // GenerateLibraryRFileTask anyway. Give a fake package for the same reason.
        val symbolTable = parseResourceSourceSetDirectory(
                parseResourcesParams.inputResDir.asFile.get(),
                IdProvider.constant(),
                getAndroidAttrSymbols(parseResourcesParams.platformAttrsRTxt.asFile.get()),
                "local",
                parseResourcesParams.validateResources.get()
        )
        // Write in the format of R-def.txt since the IDs do not matter. The symbols will be
        // written in a deterministic way (sorted by type, then by canonical name).
        SymbolIo.writeRDef(symbolTable, parseResourcesParams.librarySymbolsFile.asFile.get().toPath())
    }
}

internal fun doIncrementalRDefTaskAction(
        parseResourcesParams: ParseLibraryResourcesTask.ParseResourcesParams) {
    // Read the symbols from the previous run.
    val librarySymbolFile = parseResourcesParams.librarySymbolsFile.asFile.get().toPath()
    val currentSymbols = SymbolIo.readRDef(librarySymbolFile)
    val newSymbols = SymbolTable.builder().tablePackage("local")

    val documentBuilderFactory = DocumentBuilderFactory.newInstance()
    val documentBuilder = try {
        documentBuilderFactory.newDocumentBuilder()
    } catch (e: ParserConfigurationException) {
        throw ResourceDirectoryParseException("Failed to instantiate DOM parser.", e)
    }
    val platformSymbols = getAndroidAttrSymbols(parseResourcesParams.platformAttrsRTxt.asFile.get())

    parseResourcesParams.changedResources.get().forEach { fileChange ->
        val file = fileChange.file
        val type = ResourceFolderType.getFolderType(file.parentFile.name)!!
        // Values and ID generating resources (e.g. layouts) that have a FileStatus
        // different from NEW should have already been filtered out by
        // [canBeProcessedIncrementally].
        // For all other resources (that don't define other resources within them) we just
        // need to reprocess them if they're new - if only their contents changed we don't
        // need to do anything.
        if (fileChange.fileStatus == FileStatus.NEW) {
            parseResourceFile(
                    file,
                    type,
                    newSymbols,
                    documentBuilder,
                    platformSymbols,
                    IdProvider.constant(),
                    parseResourcesParams.validateResources.get()
            )
        }
    }

    // If we found at least one new symbol we need to update the R.txt
    if (!newSymbols.isEmpty()) {
        newSymbols.addAllIfNotExist(currentSymbols.symbols.values())
        SymbolIo.writeRDef(newSymbols.build(), librarySymbolFile)
    }
}


/**
 * Performs task runs incrementally by using previously generated partial R.txt files to acquire
 * resources symbols. This avoids the need to reparse all resource files to obtain symbols.
 * File changes are gathered using input changes and the partial R.txt files are updated.
 * An R def library symbol file is generated by merging the partial R.txt files into a single
 * SymbolTable and written to a R def file.
 */
internal fun doIncrementalPartialRTaskAction(
        parseResourcesParams: ParseLibraryResourcesTask.ParseResourcesParams) {
    val partialRDir = parseResourcesParams.partialRDir.asFile.orNull
        ?: throw IOException("No partial r.txt directory found.")
    val platformSymbols = getAndroidAttrSymbols(parseResourcesParams.platformAttrsRTxt.asFile.get())
    var updateLibrarySymbolsFile = false
    val existingPartialRFiles = partialRDir.walkTopDown()
    val documentBuilderFactory = DocumentBuilderFactory.newInstance()
    for (incrementalRes in parseResourcesParams.changedResources.get()) {
        val incrementalResAsPartialRFileName = getPartialRFileName(incrementalRes.file)
        val documentBuilder = documentBuilderFactory.newDocumentBuilder()
        when (incrementalRes.fileStatus) {
            FileStatus.NEW -> {
                val createdPartialRFile = getPartialRFromResource(
                        incrementalRes.file,
                        platformSymbols,
                        documentBuilder,
                        parseResourcesParams.validateResources.get())
                val fileToAdd = File(partialRDir, createdPartialRFile.name)
                FileUtils.writeToFile(fileToAdd, createdPartialRFile.contents)
                updateLibrarySymbolsFile = true
            }
            FileStatus.CHANGED -> {
                val maybeExistingPartialRFile = FileUtils.join(
                    partialRDir, incrementalResAsPartialRFileName)

                val changedFileExists = maybeExistingPartialRFile.exists()
                val createdPartialRFile = getPartialRFromResource(
                        incrementalRes.file,
                        platformSymbols,
                        documentBuilder,
                        parseResourcesParams.validateResources.get())
                // Only update changed partial R file if the contents are not the same as the
                // previously saved file.
                val updateChangedFile: Boolean =
                        !changedFileExists ||
                                maybeExistingPartialRFile.readLines().toString() !=
                                createdPartialRFile.contents
                if (updateChangedFile) {
                    val fileToAdd =
                            File(partialRDir, createdPartialRFile.name)
                    FileUtils.writeToFile(fileToAdd, createdPartialRFile.contents)
                    updateLibrarySymbolsFile = true
                }
            }
            FileStatus.REMOVED -> if (existingPartialRFiles.map { it.name }
                            .contains(incrementalResAsPartialRFileName)) {
                val fileToDelete = FileUtils.join(partialRDir,
                        incrementalResAsPartialRFileName)
                FileUtils.delete(fileToDelete)
                updateLibrarySymbolsFile = true
            }
        }
    }

    if (updateLibrarySymbolsFile) {
        writeRDefFromPartialRDirectory(partialRDir, parseResourcesParams.librarySymbolsFile.asFile.get())
    }
}

internal fun canGenerateSymbols(type: ResourceFolderType, file: File) =
        type == ResourceFolderType.VALUES
                || (FolderTypeRelationship.isIdGeneratingFolderType(type)
                && file.name.endsWith(SdkConstants.DOT_XML, ignoreCase = true))

internal fun canBeProcessedIncrementallyWithRDef(fileChange: SerializableChange): Boolean {
    if (fileChange.fileStatus == FileStatus.REMOVED){
        return false
    }
    if (fileChange.fileStatus == FileStatus.CHANGED) {
        val file = fileChange.file
        val folderType = ResourceFolderType.getFolderType(file.parentFile.name)
                ?: error("Invalid type '${file.parentFile.name}' for file ${file.absolutePath}")
        // ID generating files (e.g. values or XML layouts) can generate resources
        // within them, if they were modified we cannot tell if a resource was removed
        // so we need to reprocess everything.
        return !canGenerateSymbols(folderType, file)
    }
    return true
}

private fun getAndroidAttrSymbols(platformAttrsRTxt: File): SymbolTable =
        if (platformAttrsRTxt.exists())
            SymbolIo.readFromAapt(platformAttrsRTxt, "android")
        else
            SymbolTable.builder().tablePackage("android").build()

internal fun getResourceDirectorySymbolTables(
        resourceDirectory: File,
        platformAttrsSymbolTable: SymbolTable?,
        documentBuilder: DocumentBuilder,
        validateResource: Boolean = true
): SortedSet<SymbolTableWithContextPath> {
    val resourceSymbolTables =
            TreeSet<SymbolTableWithContextPath> { a, b -> a.path.compareTo(b.path) }
    resourceDirectory
            .walkTopDown()
            .forEach {
                val folderType = ResourceFolderType.getFolderType(it.parentFile.name)
                if (folderType != null) {
                    val symbolTable: SymbolTable.Builder = SymbolTable.builder()
                    symbolTable.tablePackage("local")
                    parseResourceFile(it, folderType, symbolTable, documentBuilder,
                            platformAttrsSymbolTable, IdProvider.constant(), validateResource)
                    resourceSymbolTables.add(SymbolTableWithContextPath(
                            it.relativeTo(resourceDirectory).path, symbolTable.build()))
                }
            }
    return resourceSymbolTables
}

/**
 * Creates and saves partial R.txt formatted files.
 * @param symbolTableWithContextPaths Contains the SymbolTable instance to be written and the path
 *  string in format of <parentDirectoryName>/<resourceFileName>.
 * @param directory The directory where all generated partial R.txt files are saved as children.
 */
internal fun writeSymbolTablesToPartialRFiles(
        symbolTableWithContextPaths: Collection<SymbolTableWithContextPath>, directory: File) {
    symbolTableWithContextPaths
            .filter {
                shouldBeParsed(it.path.substringBefore(File.separatorChar))
            }
            .forEach {
                val namedPartialRFile = File(
                        directory,
                        getPartialRFileName(directory, it.path))
                FileUtils.writeToFile(namedPartialRFile, "")
                writePartialR(it.symbolTable, namedPartialRFile.toPath())
            }
}

private fun writeRDefFromPartialRDirectory(partialRDirectory: File, librarySymbolsFile: File) {
    val partialRFiles = getPartialRFilesFromDirectory(partialRDirectory)
    val mergedSymbolTable = SymbolTable.mergePartialTables(partialRFiles, "local")
    librarySymbolsFile.delete()
    SymbolIo.writeRDef(mergedSymbolTable, librarySymbolsFile.toPath())
}

private fun getPartialRFilesFromDirectory(partialRDirectory: File) = partialRDirectory.listFiles()
        .toList()
        .filter {
            it.isFile && it.name.endsWith("-R.txt")
        }

private fun getPartialRFromResource(
        resourceFile: File,
        platformAttrsSymbolTable: SymbolTable?,
        documentBuilder: DocumentBuilder,
        resourceValidation: Boolean): PartialRFileNameContents {
    val symbolTable = SymbolTable.builder().tablePackage("local")
    parseResourceFile(resourceFile,
            ResourceFolderType.getFolderType(resourceFile.parentFile.name)!!,
            symbolTable,
            documentBuilder,
            platformAttrsSymbolTable,
            IdProvider.constant(),
            resourceValidation)
    val partialRContents = getPartialRContentsAsString(symbolTable.build())
    return PartialRFileNameContents(getPartialRFileName(resourceFile), partialRContents)
}

private fun getPartialRFileName(partialRDirectory: File, resourceFileParentPath: String): String {
    if (!resourceFileParentPath.contains(File.separatorChar) ) {
        throw IOException("Invalid path to resource: $resourceFileParentPath")
    }
    val file = File(partialRDirectory, resourceFileParentPath)
    return getPartialRFileName(file)
}

private fun getPartialRFileName(resourceFile: File): String =
    "${Aapt2RenamingConventions.compilationRename(resourceFile)}-R.txt"