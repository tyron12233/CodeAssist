@file:JvmName("L8Tool")
package com.tyron.builder.dexing

import com.android.tools.r8.*
import com.android.tools.r8.origin.Origin
import com.android.utils.FileUtils
import com.google.common.util.concurrent.MoreExecutors
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

// Starting index to make sure output dex files' names differ from classes.dex
internal const val START_CLASSES_DEX_INDEX = 1000

fun runL8(
    inputClasses: Collection<Path>,
    output: Path,
    libConfiguration: String,
    libraries: Collection<Path>,
    minSdkVersion: Int,
    keepRules: KeepRulesConfig,
    isDebuggable: Boolean
) {
    val logger: Logger = Logger.getLogger("L8")
    if (logger.isLoggable(Level.FINE)) {
        logger.fine("*** Using L8 to process code ***")
        logger.fine("Program classes: $inputClasses")
        logger.fine("Special library configuration: $libConfiguration")
        logger.fine("Library classes: $libraries")
        logger.fine("Min Api level: $minSdkVersion")
        logger.fine("Is debuggable: $isDebuggable")
        keepRules.keepRulesFiles.forEach { logger.fine("Keep rules file: $it") }
        keepRules.keepRulesConfigurations.forEach {
            logger.fine("Keep rules configuration: $it") }
    }
    FileUtils.cleanOutputDir(output.toFile())

    // Create our own consumer to write out dex files. We do not want them to be named classes.dex
    // because it confuses the packager in legacy multidex mode. See b/142452386.
    val programConsumer = object : DexIndexedConsumer.ForwardingConsumer(null) {

        override fun accept(
            fileIndex: Int,
            data: ByteDataView?,
            descriptors: MutableSet<String>?,
            handler: DiagnosticsHandler?
        ) {
            data ?: return

            val outputFile =
                output.resolve("classes${START_CLASSES_DEX_INDEX + fileIndex}.dex").toFile()
            outputFile.outputStream().buffered().use {
                it.write(data.buffer, data.offset, data.length)
            }
        }
    }

    val l8CommandBuilder = L8Command.builder()
        .addProgramFiles(inputClasses)
        .setProgramConsumer(programConsumer)
        .addSpecialLibraryConfiguration(libConfiguration)
        .addLibraryFiles(libraries)
        .setMinApiLevel(minSdkVersion)
        .setMode(if (isDebuggable) CompilationMode.DEBUG else CompilationMode.RELEASE)

    if (keepRules.keepRulesFiles.isNotEmpty()) {
        l8CommandBuilder.addProguardConfigurationFiles(keepRules.keepRulesFiles)
    }

    if (keepRules.keepRulesConfigurations.isNotEmpty()) {
        l8CommandBuilder.addProguardConfiguration(
            keepRules.keepRulesConfigurations, Origin.unknown())
    }

    L8.run(l8CommandBuilder.build(), MoreExecutors.newDirectExecutorService())
}

data class KeepRulesConfig(
    val keepRulesFiles: List<Path>,
    val keepRulesConfigurations: List<String>
)