package com.tyron.builder.gradle.internal.dependency

import com.google.common.io.Closer
import com.google.common.io.Files
import com.tyron.builder.api.variant.impl.getFeatureLevel
import com.tyron.builder.common.AndroidVersion
import com.tyron.builder.dexing.*
import com.tyron.builder.dexing.r8.ClassFileProviderFactory
import com.tyron.builder.files.SerializableFileChanges
import com.tyron.builder.gradle.internal.component.ApkCreationConfig
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.internal.dexing.readDesugarGraph
import com.tyron.builder.internal.dexing.writeDesugarGraph
import com.tyron.builder.gradle.errors.MessageReceiverImpl
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.scope.Java8LangSupport
import com.tyron.builder.plugin.options.SyncOptions
import com.tyron.builder.tasks.toSerializable
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.InputArtifactDependencies
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.util.internal.GFileUtils
import org.gradle.work.FileChange
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

@CacheableTransform
abstract class BaseDexingTransform<T : BaseDexingTransform.Parameters> : TransformAction<T> {

    interface Parameters : GenericTransformParameters {
        @get:Input
        val minSdkVersion: Property<Int>
        @get:Input
        val debuggable: Property<Boolean>
        @get:Input
        val enableDesugaring: Property<Boolean>
        @get:Classpath
        val bootClasspath: ConfigurableFileCollection
        @get:Internal
        val errorFormat: Property<SyncOptions.ErrorFormatMode>
        @get:Optional
        @get:Input
        val libConfiguration: Property<String>
    }

    @get:Inject
    abstract val inputChanges: InputChanges

    // Use RELATIVE path sensitivity since we use [FileChange.normalizedPath] to calculate the
    // derived dex file relative path. This will ensure we find the derived file when deletion is
    // required. Revert to CLASSPATH once Gradle 7.5 is used : b/224524454
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputArtifact
    @get:Incremental
    abstract val primaryInput: Provider<FileSystemLocation>

    protected abstract fun computeClasspathFiles(): List<Path>

    override fun transform(outputs: TransformOutputs) {
        //TODO(b/162813654) record transform execution span
        val input = primaryInput.get().asFile
        val outputDir = outputs.dir(Files.getNameWithoutExtension(input.name))
        doTransform(input, outputDir)
    }

    private fun doTransform(inputFile: File, outputDir: File) {
        val outputKeepRulesEnabled =
            parameters.libConfiguration.isPresent && !parameters.debuggable.get()
        val provideIncrementalSupport = !isJarFile(inputFile) && !outputKeepRulesEnabled

        val dexOutputDir =
            if (outputKeepRulesEnabled) {
                outputDir.resolve(DEX_DIR_NAME)
            } else {
                outputDir
            }
        val keepRulesOutputFile =
            if (outputKeepRulesEnabled) {
                outputDir.resolve(KEEP_RULES_FILE_NAME)
            } else null
        // desugarGraphFile != null iff provideIncrementalSupport == true
        val desugarGraphFile =
            if (provideIncrementalSupport) {
                // The desugaring graph file is outside outputDir and is not registered as an
                // output because the graph is not relocatable.
                outputDir.resolve("../$DESUGAR_GRAPH_FILE_NAME")
            } else null

        if (provideIncrementalSupport && inputChanges.isIncremental) {
            // Gradle API currently does not provide classpath changes. When the classpath changes,
            // Gradle will run the transform non-incrementally in a new directory, so it is still
            // correct, but not quite efficient yet.
            // TODO(132615827): Update this code once Gradle provides classpath changes
            // (https://github.com/gradle/gradle/issues/11794)
            val classpathChanges = emptyList<FileChange>()
            check(keepRulesOutputFile == null)
            processIncrementally(
                inputFile,
                inputChanges.getFileChanges(primaryInput).toSerializable(),
                classpathChanges.toSerializable(),
                dexOutputDir,
                desugarGraphFile!!
            )
        } else {
            processNonIncrementally(
                inputFile,
                dexOutputDir,
                keepRulesOutputFile,
                provideIncrementalSupport,
                desugarGraphFile
            )
        }
    }

    private fun processIncrementally(
        input: File,
        inputChanges: SerializableFileChanges,
        classpathChanges: SerializableFileChanges,
        dexOutputDir: File,
        desugarGraphFile: File
    ) {
        val desugarGraph = try {
            readDesugarGraph(desugarGraphFile)
        } catch (e: Exception) {
            Logging.getLogger(BaseDexingTransform::class.java).warn(
                "Failed to read desugaring graph." +
                        " Cause: ${e.javaClass.simpleName}, message: ${e.message}.\n" +
                        "Fall back to non-incremental mode."
            )
            processNonIncrementally(input, dexOutputDir, null, true, desugarGraphFile)
            return
        }

        // Compute impacted files based on the changed files and the desugaring graph
        val removedFiles =
            (inputChanges.removedFiles + classpathChanges.removedFiles).map { it.file }.toSet()
        val modifiedFiles =
            (inputChanges.modifiedFiles + classpathChanges.modifiedFiles).map { it.file }.toSet()
        val addedFiles =
            (inputChanges.addedFiles + classpathChanges.addedFiles).map { it.file }.toSet()
        val unchangedButImpactedFiles = desugarGraph.getAllDependents(removedFiles + modifiedFiles)
        val modifiedImpactedOrAddedFiles = modifiedFiles + unchangedButImpactedFiles + addedFiles
        val removedModifiedOrImpactedFiles =
            removedFiles + modifiedFiles + unchangedButImpactedFiles

        // Remove stale dex outputs (not including those that will be overwritten)
        inputChanges.removedFiles.forEach {
            if (ClassFileInput.CLASS_MATCHER.test(it.file.path)) {
                val staleOutputFile =
                    dexOutputDir.resolve(ClassFileEntry.withDexExtension(it.normalizedPath))
                GFileUtils.deleteQuietly(staleOutputFile)
            }
        }

        // Remove stale nodes in the desugaring graph
        removedModifiedOrImpactedFiles.forEach {
            desugarGraph.removeNode(it)
        }

        // Process only input files that are modified, added, or unchanged-but-impacted
        val filter: (File, String) -> Boolean = { rootPath: File, relativePath: String ->
            rootPath in modifiedImpactedOrAddedFiles /* for jars (we don't track class files in jars) */ ||
                    rootPath.resolve(relativePath) in modifiedImpactedOrAddedFiles /* for class files in dirs */
        }
        process(input, filter, dexOutputDir, null, true, desugarGraph)

        // Store the desugaring graph for use in the next build. If dexing failed earlier, it is
        // intended that we will not store the graph as it is only meant to contain info about a
        // previous successful build.
        writeDesugarGraph(desugarGraphFile, desugarGraph)
    }

    private fun processNonIncrementally(
        input: File,
        dexOutputDir: File,
        keepRulesOutputFile: File?,
        provideIncrementalSupport: Boolean,
        desugarGraphFile: File? // desugarGraphFile != null iff provideIncrementalSupport == true
    ) {
        GFileUtils.deleteIfExists(dexOutputDir)
        GFileUtils.mkdirs(dexOutputDir)
        keepRulesOutputFile?.let {
            GFileUtils.deleteIfExists(it)
            GFileUtils.mkdirs(it.parentFile)
        }
        desugarGraphFile?.let {
            GFileUtils.deleteIfExists(it)
            GFileUtils.mkdirs(it.parentFile)
        }

        val desugarGraph = desugarGraphFile?.let {
            MutableDependencyGraph<File>()
        }

        process(
            input,
            { _, _ -> true },
            dexOutputDir,
            keepRulesOutputFile,
            provideIncrementalSupport,
            desugarGraph
        )

        // Store the desugaring graph for use in the next build. If dexing failed earlier, it is
        // intended that we will not store the graph as it is only meant to contain info about a
        // previous successful build.
        desugarGraphFile?.let {
            writeDesugarGraph(it, desugarGraph!!)
        }
    }

    private fun process(
        input: File,
        inputFilter: (File, String) -> Boolean,
        dexOutputDir: File,
        keepRulesOutputFile: File?,
        provideIncrementalSupport: Boolean,
        // desugarGraphUpdater != null iff provideIncrementalSupport == true
        desugarGraphUpdater: DependencyGraphUpdater<File>?
    ) {
        Closer.create().use { closer ->
            val d8DexBuilder = DexArchiveBuilder.createD8DexBuilder(
                DexParameters(
                    minSdkVersion = parameters.minSdkVersion.get(),
                    debuggable = parameters.debuggable.get(),
                    dexPerClass = provideIncrementalSupport,
                    withDesugaring = parameters.enableDesugaring.get(),
                    desugarBootclasspath = ClassFileProviderFactory(
                        parameters.bootClasspath.files.map(File::toPath)
                    )
                        .also { closer.register(it) },
                    desugarClasspath = ClassFileProviderFactory(computeClasspathFiles()).also {
                        closer.register(it)
                    },
                    coreLibDesugarConfig = parameters.libConfiguration.orNull,
                    coreLibDesugarOutputKeepRuleFile = keepRulesOutputFile,
                    messageReceiver = MessageReceiverImpl(
                        parameters.errorFormat.get(),
                        LoggerFactory.getLogger(BaseDexingTransform::class.java)
                    )
                )
            )

            ClassFileInputs.fromPath(input.toPath()).use { classFileInput ->
                classFileInput.entries { rootPath, relativePath ->
                    inputFilter(rootPath.toFile(), relativePath)
                }.use { classesInput ->
                    d8DexBuilder.convert(
                        classesInput,
                        dexOutputDir.toPath(),
                        desugarGraphUpdater
                    )
                }
            }
        }
    }
}

@CacheableTransform
abstract class DexingNoClasspathTransform : BaseDexingTransform<BaseDexingTransform.Parameters>() {
    override fun computeClasspathFiles() = listOf<Path>()
}

@CacheableTransform
abstract class DexingWithClasspathTransform : BaseDexingTransform<BaseDexingTransform.Parameters>() {
    /**
     * Using compile classpath normalization is safe here due to the design of desugar:
     * Method bodies are only moved to the companion class within the same artifact,
     * not between artifacts.
     */
    @get:CompileClasspath
    @get:InputArtifactDependencies
    abstract val classpath: FileCollection

    override fun computeClasspathFiles() = classpath.files.map(File::toPath)
}

fun getDexingArtifactConfigurations(components: Collection<ComponentCreationConfig>): Set<DexingArtifactConfiguration> {
    return components
        .filterIsInstance<ApkCreationConfig>()
        .map { getDexingArtifactConfiguration(it) }.toSet()
}

fun getDexingArtifactConfiguration(creationConfig: ApkCreationConfig): DexingArtifactConfiguration {
    return DexingArtifactConfiguration(
        minSdk = creationConfig.minSdkVersionForDexing.getFeatureLevel(),
        isDebuggable = creationConfig.debuggable,
        enableDesugaring = creationConfig.getJava8LangSupportType() == Java8LangSupport.D8,
        enableCoreLibraryDesugaring = creationConfig.isCoreLibraryDesugaringEnabled,
        needsShrinkDesugarLibrary = creationConfig.needsShrinkDesugarLibrary,
        asmTransformedVariant = null,
        useJacocoTransformInstrumentation = creationConfig.useJacocoTransformInstrumentation
    )
}


data class DexingArtifactConfiguration(
    private val minSdk: Int,
    private val isDebuggable: Boolean,
    private val enableDesugaring: Boolean,
    private val enableCoreLibraryDesugaring: Boolean,
    private val needsShrinkDesugarLibrary: Boolean,
    private val asmTransformedVariant: String?,
    private val useJacocoTransformInstrumentation: Boolean
) {

    // If we want to do desugaring and our minSdk (or the API level of the device we're deploying
    // to) is lower than N then we need additional classpaths in order to proper do the desugaring.
    private val needsClasspath = enableDesugaring && minSdk < AndroidVersion.VersionCodes.N

    fun registerTransform(
        projectName: String,
        dependencyHandler: DependencyHandler,
        bootClasspath: FileCollection,
        libConfiguration: Provider<String>,
        errorFormat: SyncOptions.ErrorFormatMode
    ) {
        dependencyHandler.registerTransform(getTransformClass()) { spec ->
            spec.parameters { parameters ->
                parameters.projectName.set(projectName)
                parameters.minSdkVersion.set(minSdk)
                parameters.debuggable.set(isDebuggable)
                parameters.enableDesugaring.set(enableDesugaring)
                // bootclasspath is required by d8 to do API conversion for library desugaring
                if (needsClasspath || enableCoreLibraryDesugaring) {
                    parameters.bootClasspath.from(bootClasspath)
                }
                parameters.errorFormat.set(errorFormat)
                if (enableCoreLibraryDesugaring) {
                    parameters.libConfiguration.set(libConfiguration)
                }
            }
            // There are 2 transform flows for DEX:
            //   1. (JACOCO_)CLASSES_DIR -> (JACOCO_)CLASSES -> DEX
            //   2. (JACOCO_)CLASSES_JAR -> (JACOCO_)CLASSES -> DEX
            //
            // For incremental dexing, when requesting DEX the consumer will indicate a
            // preference for CLASSES_DIR over CLASSES_JAR (see DexMergingTask), otherwise
            // Gradle will select CLASSES_JAR by default.
            //
            // However, there could be an issue if CLASSES_DIR is selected: For Java libraries
            // using Kotlin, CLASSES_DIR has two separate directories: one for compiled Java
            // classes and one for compiled Kotlin classes. Classes in one directory may
            // reference classes in the other directory, but each directory is transformed to
            // DEX independently. Therefore, if dexing requires a classpath (desugaring is
            // enabled and minSdk < 24), desugaring may not work correctly.
            //
            // Android libraries do not have this issue, as their CLASSES_DIR is one directory
            // containing both Java and Kotlin classes.
            //
            // Therefore, to ensure correctness in all cases, we transform CLASSES to DEX only
            // when dexing does not require a classpath. Otherwise, we transform CLASSES_JAR to
            // DEX directly so that CLASSES_DIR will not be selected.
            //
            // In the case that the JacocoTransform is executed, the Jacoco equivalent artifact is
            // used. These artifacts are the same as CLASSES, CLASSES_JAR and ASM_INSTRUMENTED_JARS,
            // but they have been offline instrumented by Jacoco and include Jacoco dependencies.
            val inputArtifact: AndroidArtifacts.ArtifactType =
                if (useJacocoTransformInstrumentation) {
                    when {
                        asmTransformedVariant != null ->
                            AndroidArtifacts.ArtifactType.JACOCO_ASM_INSTRUMENTED_JARS
                        !needsClasspath ->
                            AndroidArtifacts.ArtifactType.JACOCO_CLASSES
                        else ->
                            AndroidArtifacts.ArtifactType.JACOCO_CLASSES_JAR
                    }
                } else {
                    when {
                        asmTransformedVariant != null ->
                            AndroidArtifacts.ArtifactType.ASM_INSTRUMENTED_JARS
                        !needsClasspath ->
                            AndroidArtifacts.ArtifactType.CLASSES
                        else ->
                            AndroidArtifacts.ArtifactType.CLASSES_JAR
                    }
                }
            spec.from.attribute(
                ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                inputArtifact.type
            )
            if (needsShrinkDesugarLibrary) {
                spec.to.attribute(
                    ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    AndroidArtifacts.ArtifactType.DEX_AND_KEEP_RULES.type
                )
            } else {
                spec.to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, AndroidArtifacts.ArtifactType.DEX.type)
            }

            getAttributes().apply {
                addAttributesToContainer(spec.from)
                addAttributesToContainer(spec.to)
            }
        }
    }

    private fun getTransformClass(): Class<out BaseDexingTransform<BaseDexingTransform.Parameters>> {
        return if (needsClasspath) {
            DexingWithClasspathTransform::class.java
        } else {
            DexingNoClasspathTransform::class.java
        }
    }

    fun getAttributes(): AndroidAttributes {
        return AndroidAttributes(
            mapOf(
                ATTR_MIN_SDK to minSdk.toString(),
                ATTR_IS_DEBUGGABLE to isDebuggable.toString(),
                ATTR_ENABLE_DESUGARING to enableDesugaring.toString(),
                ATTR_ENABLE_JACOCO_INSTRUMENTATION to useJacocoTransformInstrumentation.toString(),
//                ATTR_ASM_TRANSFORMED_VARIANT to (asmTransformedVariant ?: "NONE")
            )
        )
    }
}

val ATTR_MIN_SDK: Attribute<String> = Attribute.of("dexing-min-sdk", String::class.java)
val ATTR_IS_DEBUGGABLE: Attribute<String> =
    Attribute.of("dexing-is-debuggable", String::class.java)
val ATTR_ENABLE_DESUGARING: Attribute<String> =
    Attribute.of("dexing-enable-desugaring", String::class.java)
val ATTR_ENABLE_JACOCO_INSTRUMENTATION: Attribute<String> =
    Attribute.of("dexing-enable-jacoco-instrumentation", String::class.java)

const val DESUGAR_GRAPH_FILE_NAME = "desugar_graph.bin"