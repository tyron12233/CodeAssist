package com.tyron.builder.gradle.internal.tasks

import com.android.SdkConstants
import com.android.SdkConstants.DOT_AAR
import com.android.SdkConstants.DOT_JAR
import com.android.utils.FileUtils
import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.io.ByteStreams
import com.google.common.io.Files
import com.tyron.builder.gradle.internal.component.VariantCreationConfig
import com.tyron.builder.gradle.internal.scope.InternalArtifactType
import com.tyron.builder.gradle.internal.scope.InternalArtifactType.MERGED_NATIVE_LIBS
import com.tyron.builder.gradle.internal.scope.InternalArtifactType.STRIPPED_NATIVE_LIBS
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction
import com.tyron.builder.internal.utils.setDisallowChanges
import com.tyron.builder.utils.isValidZipEntryName
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.nio.ByteBuffer
import java.util.regex.Pattern
import java.util.zip.ZipFile

private val pattern = Pattern.compile("lib/[^/]+/[^/]+\\.so")

/**
 * A task that copies the project native libs (and optionally the native libs from local jars)
 *
 * Caching disabled by default for this task because the task does very little work.
 * The task moves files around on the disk, doing no substantial computation.
 * Calculating cache hit/miss and fetching results is likely more expensive than
 * simply executing the task.
 */
@DisableCachingByDefault
@BuildAnalyzer(primaryTaskCategory = TaskCategory.NATIVE)
abstract class LibraryJniLibsTask : NonIncrementalTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectNativeLibs: DirectoryProperty

    @get:Classpath
    @get:Optional
    abstract var localJarsNativeLibs: FileCollection?
        protected set

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    override fun doTaskAction() {
        LibraryJniLibsDelegate(
            projectNativeLibs.get().asFile,
            localJarsNativeLibs?.files ?: listOf(),
            outputDirectory.get().asFile,
            workerExecutor,
            this
        ).copyFiles()
    }

    class LibraryJniLibsDelegate(
        private val projectNativeLibs: File,
        private val localJarsNativeLibs: Collection<File>,
        val outputDirectory: File,
        val workers: WorkerExecutor,
        private val instantiator: AndroidVariantTask
    ) {
        fun copyFiles() {
            FileUtils.cleanOutputDir(outputDirectory)
            val inputFiles = listOf(projectNativeLibs) + localJarsNativeLibs.toList()
            for (inputFile in inputFiles) {
                workers.noIsolation().submit(LibraryJniLibsRunnable::class.java) {
//                    it.initializeFromAndroidVariantTask(instantiator)
                    it.inputFile.set(inputFile)
                    it.outputDirectory.set(outputDirectory)

                }
            }
        }
    }

    abstract class LibraryJniLibsRunnable : WorkAction<LibraryJniLibsRunnable.Params> {

        abstract class Params: WorkParameters {
            abstract val inputFile: Property<File>
            abstract val outputDirectory: DirectoryProperty
        }

        override fun execute() {
            val inputFile = parameters.inputFile.get()
            if (inputFile.isFile) {
                Preconditions.checkState(
                    inputFile.name.endsWith(DOT_JAR)
                            || inputFile.name.endsWith(DOT_AAR),
                    "Non-directory inputs must have .jar or .aar extension: $inputFile")
                copyFromJar(inputFile, parameters.outputDirectory.asFile.get())
            } else {
                copyFromFolder(inputFile, parameters.outputDirectory.asFile.get())
            }
        }
    }

    abstract class CreationAction(
        creationConfig: VariantCreationConfig,
        val artifactType: InternalArtifactType<Directory>
    ) : VariantTaskCreationAction<LibraryJniLibsTask, VariantCreationConfig>(
        creationConfig
    ) {
        override val type = LibraryJniLibsTask::class.java

        override fun handleProvider(
            taskProvider: TaskProvider<LibraryJniLibsTask>
        ) {
            super.handleProvider(taskProvider)
            creationConfig.artifacts.setInitialProvider(
                taskProvider,
                LibraryJniLibsTask::outputDirectory
            ).withName(SdkConstants.FD_JNI)
                .on(artifactType)
        }
    }

    class ProjectOnlyCreationAction(
        creationConfig: VariantCreationConfig,
        artifactType: InternalArtifactType<Directory>
    ): CreationAction(creationConfig, artifactType) {
        override val name: String = computeTaskName("copy", "JniLibsProjectOnly")

        override fun configure(
            task: LibraryJniLibsTask
        ) {
            super.configure(task)
            // Copy the MERGED_NATIVE_LIBS instead of the STRIPPED_NATIVE_LIBS for inter-project
            // intermediate publishing to allow native debug symbols to be extracted via the
            // ExtractNativeDebugMetadataTask in any downstream application or dynamic-feature
            // modules; see b/187734554.
            task.projectNativeLibs.setDisallowChanges(
                creationConfig.artifacts.get(MERGED_NATIVE_LIBS)
            )
            task.localJarsNativeLibs = null
        }
    }

    class ProjectAndLocalJarsCreationAction(
        creationConfig: VariantCreationConfig,
        artifactType: InternalArtifactType<Directory>
    ) : CreationAction(creationConfig, artifactType) {
        override val name: String = computeTaskName("copy", "JniLibsProjectAndLocalJars")

        override fun configure(
            task: LibraryJniLibsTask
        ) {
            super.configure(task)
            task.projectNativeLibs.setDisallowChanges(
                creationConfig.artifacts.get(STRIPPED_NATIVE_LIBS)
            )
            task.localJarsNativeLibs = creationConfig.computeLocalPackagedJars()
        }
    }
}

private fun copyFromFolder(rootDirectory: File, outputDirectory: File) {
    copyFromFolder(rootDirectory, outputDirectory, mutableListOf())
}

private fun copyFromFolder(from: File, outputDirectory: File, pathSegments: MutableList<String>) {
    val children =
        from.listFiles {
                file, name -> file.isDirectory || name.endsWith(SdkConstants.DOT_NATIVE_LIBS)
        }

    if (children != null) {
        for (child in children) {
            pathSegments.add(child.name)
            if (child.isDirectory) {
                copyFromFolder(child, outputDirectory, pathSegments)
            } else if (child.isFile) {
                if (pattern.matcher(Joiner.on('/').join(pathSegments)).matches()) {
                    // copy the file. However we do want to skip the first segment ('lib') here
                    // since the 'jni' folder is representing the same concept.
                    val to = FileUtils.join(outputDirectory, pathSegments.subList(1, 3))
                    FileUtils.mkdirs(to.parentFile)
                    FileUtils.copyFile(child, to)
                }
            }
            pathSegments.removeAt(pathSegments.size - 1)
        }
    }
}

private fun copyFromJar(jarFile: File, outputDirectory: File) {
    ZipFile(jarFile).use { zipFile ->
        val entries = zipFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()

            val entryPath = entry.name
            if (!pattern.matcher(entryPath).matches() || !isValidZipEntryName(entry)) {
                continue
            }

            // read the content.
            val byteBuffer = zipFile.getInputStream(entry).use {
                ByteBuffer.wrap(ByteStreams.toByteArray(it))
            }

            // get the output file and write to it.
            val to = computeFile(outputDirectory, entryPath)
            FileUtils.mkdirs(to.parentFile)
            Files.write(byteBuffer.array(), to)
        }
    }
}

/**
 * computes a file path from a root folder and a zip archive path, removing the lib/ part of the
 * path
 * @param rootFolder the root folder
 * @param path the archive path
 * @return the File
 */
private fun computeFile(rootFolder: File, path: String) =
        File(rootFolder, FileUtils.toSystemDependentPath(path.substring(4)))

