package com.tyron.builder.dexing

import com.android.SdkConstants.*
import com.android.tools.r8.*
import com.android.tools.r8.origin.Origin
import com.android.tools.r8.utils.ArchiveResourceProvider
import com.google.common.io.ByteStreams
import com.tyron.builder.dexing.r8.ClassFileProviderFactory
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun isProguardRule(name: String): Boolean {
    val lowerCaseName = name.lowercase(Locale.US)
    return lowerCaseName.startsWith("$PROGUARD_RULES_FOLDER/")
            || lowerCaseName.startsWith("/$PROGUARD_RULES_FOLDER/")
}

fun isToolsConfigurationFile(name: String): Boolean {
    val lowerCaseName = name.lowercase(Locale.US)
    return lowerCaseName.startsWith("$TOOLS_CONFIGURATION_FOLDER/")
            || lowerCaseName.startsWith("/$TOOLS_CONFIGURATION_FOLDER/")
}

fun getR8Version(): String = Version.getVersionString()

/**
 * Converts the specified inputs, according to the configuration, and writes dex or classes to
 * output path.
 */
fun runR8(
    inputClasses: Collection<Path>,
    output: Path,
    inputJavaResources: Collection<Path>,
    javaResourcesJar: Path,
    libraries: Collection<Path>,
    classpath: Collection<Path>,
    toolConfig: ToolConfig,
    proguardConfig: ProguardConfig,
    mainDexListConfig: MainDexListConfig,
    messageReceiver: com.android.ide.common.blame.MessageReceiver,
    useFullR8: Boolean = false,
    featureClassJars: Collection<Path>,
    featureJavaResourceJars: Collection<Path>,
    featureDexDir: Path?,
    featureJavaResourceOutputDir: Path?,
    libConfiguration: String? = null,
    outputKeepRules: Path? = null
) {
    val logger: Logger = Logger.getLogger("R8")
    if (logger.isLoggable(Level.FINE)) {
        logger.fine("*** Using R8 to process code ***")
        logger.fine("Main dex list config: $mainDexListConfig")
        logger.fine("Proguard config: $proguardConfig")
        logger.fine("Tool config: $toolConfig")
        logger.fine("Program classes: $inputClasses")
        logger.fine("Java resources: $inputJavaResources")
        logger.fine("Library classes: $libraries")
        logger.fine("Classpath classes: $classpath")
        outputKeepRules?.let{ logger.fine("Keep rules for shrinking desugar lib: $it") }
    }
    val r8CommandBuilder =
        R8Command.builder(
            R8DiagnosticsHandler(
                proguardConfig.proguardOutputFiles.missingKeepRules,
                messageReceiver,
                "R8"))

    if (!useFullR8) {
//        r8CommandBuilder.setProguardCompatibility(true);
    }

    if (toolConfig.r8OutputType == R8OutputType.DEX) {
        r8CommandBuilder.minApiLevel = toolConfig.minSdkVersion
        if (toolConfig.minSdkVersion < 21) {
            // specify main dex related options only when minSdkVersion is below 21
            r8CommandBuilder
                .addMainDexRulesFiles(mainDexListConfig.mainDexRulesFiles)
                .addMainDexListFiles(mainDexListConfig.mainDexListFiles)

            if (mainDexListConfig.mainDexRules.isNotEmpty()) {
                r8CommandBuilder.addMainDexRules(mainDexListConfig.mainDexRules, Origin.unknown())
            }
            mainDexListConfig.mainDexListOutput?.let {
                r8CommandBuilder.setMainDexListConsumer(StringConsumer.FileConsumer(it))
            }
        }
        if (libConfiguration != null) {
            r8CommandBuilder
                .addSpecialLibraryConfiguration(libConfiguration)
                .setDesugaredLibraryKeepRuleConsumer(StringConsumer.FileConsumer(outputKeepRules!!))
        }
        if (toolConfig.isDebuggable) {
            r8CommandBuilder.addAssertionsConfiguration(
                AssertionsConfiguration.Builder::enableAllAssertions
            )
        }
    }

    r8CommandBuilder
        .addProguardConfigurationFiles(
            proguardConfig.proguardConfigurationFiles.filter { Files.isRegularFile(it) }
        )
        .addProguardConfiguration(proguardConfig.proguardConfigurations, Origin.unknown())

    if (proguardConfig.proguardMapInput != null
        && Files.exists(proguardConfig.proguardMapInput)
    ) {
        r8CommandBuilder.addProguardConfiguration(
            listOf("-applymapping \"${proguardConfig.proguardMapInput}\""),
            Origin.unknown()
        )
    }

    val proguardOutputFiles = proguardConfig.proguardOutputFiles
    Files.deleteIfExists(proguardOutputFiles.proguardMapOutput)
    Files.deleteIfExists(proguardOutputFiles.proguardSeedsOutput)
    Files.deleteIfExists(proguardOutputFiles.proguardUsageOutput)
    Files.deleteIfExists(proguardOutputFiles.proguardConfigurationOutput)
    Files.deleteIfExists(proguardOutputFiles.missingKeepRules)

    Files.createDirectories(proguardOutputFiles.proguardMapOutput.parent)
    r8CommandBuilder.setProguardMapOutputPath(proguardOutputFiles.proguardMapOutput)
    r8CommandBuilder.setProguardSeedsConsumer(
        StringConsumer.FileConsumer(proguardOutputFiles.proguardSeedsOutput))
    r8CommandBuilder.setProguardUsageConsumer(
        StringConsumer.FileConsumer(proguardOutputFiles.proguardUsageOutput))
    r8CommandBuilder.setProguardConfigurationConsumer(
        StringConsumer.FileConsumer(
            proguardOutputFiles.proguardConfigurationOutput
        )
    )

    val compilationMode =
        if (toolConfig.isDebuggable) CompilationMode.DEBUG else CompilationMode.RELEASE

    val dataResourceConsumer = JavaResourcesConsumer(javaResourcesJar)
    val programConsumer =
        if (toolConfig.r8OutputType == R8OutputType.CLASSES) {
            val baseConsumer: ClassFileConsumer = if (Files.isDirectory(output)) {
                ClassFileConsumer.DirectoryConsumer(output)
            } else {
                ClassFileConsumer.ArchiveConsumer(output)
            }
            object : ClassFileConsumer.ForwardingConsumer(baseConsumer) {
                override fun getDataResourceConsumer(): DataResourceConsumer? {
                    return dataResourceConsumer
                }
            }
        } else {
            val baseConsumer: DexIndexedConsumer = if (Files.isDirectory(output)) {
                DexIndexedConsumer.DirectoryConsumer(output)
            } else {
                DexIndexedConsumer.ArchiveConsumer(output)
            }
            object : DexIndexedConsumer.ForwardingConsumer(baseConsumer) {
                override fun getDataResourceConsumer(): DataResourceConsumer? {
                    return dataResourceConsumer
                }
            }
        }

    @Suppress("UsePropertyAccessSyntax")
    r8CommandBuilder
        .setDisableMinification(toolConfig.disableMinification)
        .setDisableTreeShaking(toolConfig.disableTreeShaking)
        .setDisableDesugaring(toolConfig.disableDesugaring)
        .setMode(compilationMode)
        .setProgramConsumer(programConsumer)

    // Use this to control all resources provided to R8
    val r8ProgramResourceProvider = R8ProgramResourceProvider()

    for (path in inputClasses) {
        when {
            Files.isRegularFile(path) -> r8ProgramResourceProvider.addProgramResourceProvider(
                ArchiveProgramResourceProvider.fromArchive(path))
            Files.isDirectory(path) -> Files.walk(path).use { stream ->
                stream.filter {
                    val relativePath = path.relativize(it).toString()
                    Files.isRegularFile(it) && ClassFileInput.CLASS_MATCHER.test(relativePath)
                }
                    .forEach { r8CommandBuilder.addProgramFiles(it) }
            }
            else -> throw IOException("Unexpected file format: $path")
        }
    }

    val dirResources = inputJavaResources.filter {
        if (!Files.isDirectory(it)) {
            val resourceOnlyProvider =
                ResourceOnlyProvider(ArchiveResourceProvider.fromArchive(it, true))
            r8ProgramResourceProvider.dataResourceProviders.add(resourceOnlyProvider.dataResourceProvider)
            false
        } else {
            true
        }
    }

    r8ProgramResourceProvider.dataResourceProviders.add(R8DataResourceProvider(dirResources))

    r8CommandBuilder.addProgramResourceProvider(r8ProgramResourceProvider)

    val featureClassJarMap =
        featureClassJars.associateBy({ it.toFile().nameWithoutExtension }, { it })
    val featureJavaResourceJarMap =
        featureJavaResourceJars.associateBy({ it.toFile().nameWithoutExtension }, { it })
    // Check that each feature class jar has a corresponding feature java resources jar, and vice
    // versa.
    check(
        featureClassJarMap.keys.containsAll(featureJavaResourceJarMap.keys)
                && featureJavaResourceJarMap.keys.containsAll(featureClassJarMap.keys)
    ) {
        """
            featureClassJarMap and featureJavaResourceJarMap must have the same keys.

            featureClassJarMap keys:
            ${featureClassJarMap.keys.sorted()}

            featureJavaResourceJarMap keys:
            ${featureJavaResourceJarMap.keys.sorted()}
            """.trimIndent()
    }
    if (featureClassJarMap.isNotEmpty()) {
        check(featureDexDir != null && featureJavaResourceOutputDir != null) {
            "featureDexDir == null || featureJavaResourceOutputDir == null."
        }
        Files.createDirectories(featureJavaResourceOutputDir)
        check(toolConfig.r8OutputType == R8OutputType.DEX) {
            "toolConfig.r8OutputType != R8OutputType.DEX."
        }
        for (featureKey in featureClassJarMap.keys) {
            r8CommandBuilder.addFeatureSplit {
                it.addProgramResourceProvider(
                    ArchiveProgramResourceProvider.fromArchive(featureClassJarMap[featureKey])
                )
                it.addProgramResourceProvider(
                    ArchiveResourceProvider.fromArchive(featureJavaResourceJarMap[featureKey], true)
                )
                val javaResConsumer = JavaResourcesConsumer(
                    featureJavaResourceOutputDir.resolve("$featureKey$DOT_JAR")
                )
                it.setProgramConsumer(
                    object : DexIndexedConsumer.DirectoryConsumer(
                        Files.createDirectories(featureDexDir.resolve(featureKey))
                    ) {
                        override fun getDataResourceConsumer(): DataResourceConsumer {
                            return javaResConsumer
                        }
                    }
                )
                return@addFeatureSplit it.build()
            }
        }
    }

    // Enable workarounds for missing library APIs in R8 (see b/231547906).
//    r8CommandBuilder.setEnableExperimentalMissingLibraryApiModeling(true);
    ClassFileProviderFactory(libraries).use { libraryClasses ->
        ClassFileProviderFactory(classpath).use { classpathClasses ->
            r8CommandBuilder.addLibraryResourceProvider(libraryClasses.orderedProvider)
            r8CommandBuilder.addClasspathResourceProvider(classpathClasses.orderedProvider)
            R8.run(r8CommandBuilder.build())
        }
    }

    proguardConfig.proguardOutputFiles.proguardMapOutput.let {
        if (Files.notExists(it)) {
            // R8 might not create a mapping file, so we have to create it, http://b/37053758.
            Files.createFile(it)
        }
    }
}

enum class R8OutputType {
    DEX,
    CLASSES,
}

/** Main dex related parameters for the R8 tool. */
data class MainDexListConfig(
    val mainDexRulesFiles: Collection<Path> = listOf(),
    val mainDexListFiles: Collection<Path> = listOf(),
    val mainDexRules: List<String> = listOf(),
    val mainDexListOutput: Path? = null
)

/** Proguard-related parameters for the R8 tool. */
data class ProguardConfig(
    val proguardConfigurationFiles: List<Path>,
    val proguardMapInput: Path?,
    val proguardConfigurations: List<String>,
    val proguardOutputFiles: ProguardOutputFiles
)

data class ProguardOutputFiles(
    val proguardMapOutput: Path,
    val proguardSeedsOutput: Path,
    val proguardUsageOutput: Path,
    val proguardConfigurationOutput: Path,
    val missingKeepRules: Path
)

/** Configuration parameters for the R8 tool. */
data class ToolConfig(
    val minSdkVersion: Int,
    val isDebuggable: Boolean,
    val disableTreeShaking: Boolean,
    val disableDesugaring: Boolean,
    val disableMinification: Boolean,
    val r8OutputType: R8OutputType,
)

private class ProGuardRulesFilteringVisitor(
    private val visitor: DataResourceProvider.Visitor?
) : DataResourceProvider.Visitor {
    override fun visit(directory: DataDirectoryResource) {
        visitor?.visit(directory)
    }

    override fun visit(resource: DataEntryResource) {
        if (!isProguardRule(resource.name) && !isToolsConfigurationFile(resource.name)) {
            visitor?.visit(resource)
        }
    }
}

private class R8ProgramResourceProvider : ProgramResourceProvider {
    private val programResourcesList: MutableList<ProgramResource> = ArrayList()

    val dataResourceProviders: MutableList<DataResourceProvider> = ArrayList()

    fun addProgramResourceProvider(provider: ProgramResourceProvider) {
        programResourcesList.addAll(provider.programResources)
        provider.dataResourceProvider?.let {
            dataResourceProviders.add(it)
        }
    }

    override fun getProgramResources() = programResourcesList

    override fun getDataResourceProvider() = object : DataResourceProvider {
        override fun accept(visitor: DataResourceProvider.Visitor?) {
            val visitorWrapper = ProGuardRulesFilteringVisitor(visitor)
            for (provider in dataResourceProviders) {
                provider.accept(visitorWrapper)
            }
        }
    }
}

/** Provider that loads all resources from the specified directories.  */
private class R8DataResourceProvider(val dirResources: Collection<Path>) : DataResourceProvider {
    override fun accept(visitor: DataResourceProvider.Visitor?) {
        val seen = mutableSetOf<Path>()
        val logger: Logger = Logger.getLogger("R8")
        for (resourceBase in dirResources) {
            Files.walk(resourceBase).use {
                it.forEach {
                    val relative = resourceBase.relativize(it)
                    if (it != resourceBase
                        && !it.toString().endsWith(DOT_CLASS)
                        && seen.add(relative)) {
                        when {
                            Files.isDirectory(it) -> visitor!!.visit(
                                DataDirectoryResource.fromFile(
                                    resourceBase, resourceBase.relativize(it)
                                )
                            )
                            else -> visitor!!.visit(
                                DataEntryResource.fromFile(
                                    resourceBase, resourceBase.relativize(it)
                                )
                            )
                        }
                    } else {
                        logger.fine { "Ignoring entry $relative from $resourceBase" }
                    }
                }
            }
        }
    }
}

private class ResourceOnlyProvider(val originalProvider: ProgramResourceProvider): ProgramResourceProvider {
    override fun getProgramResources() = listOf<ProgramResource>()

    override fun getDataResourceProvider() = originalProvider.dataResourceProvider
}

/** Custom Java resources consumer to make sure we compress Java resources in the jar. */
private class JavaResourcesConsumer(private val outputJar: Path): DataResourceConsumer {

    private val output = lazy { ZipOutputStream(BufferedOutputStream(outputJar.toFile().outputStream())) }
    private val zipLock = Any()

    /** Accept can be called from multiple threads. */
    override fun accept(directory: DataDirectoryResource, diagnosticsHandler: DiagnosticsHandler) {
        val entry: ZipEntry = createNewZipEntry(directory.name + "/")
        synchronized(zipLock) {
            output.value.putNextEntry(entry)
            output.value.closeEntry()
        }
    }

    /** Accept can be called from multiple threads. */
    override fun accept(file: DataEntryResource, diagnosticsHandler: DiagnosticsHandler) {
        val entry:ZipEntry = createNewZipEntry(file.name)
        synchronized(zipLock) {
            output.value.putNextEntry(entry)
            output.value.write(ByteStreams.toByteArray(file.byteStream))
            output.value.closeEntry()
        }
    }

    override fun finished(handler: DiagnosticsHandler) {
        output.value.close()
    }

    private fun createNewZipEntry(name: String): ZipEntry {
        return ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
            time = 0
        }
    }
}