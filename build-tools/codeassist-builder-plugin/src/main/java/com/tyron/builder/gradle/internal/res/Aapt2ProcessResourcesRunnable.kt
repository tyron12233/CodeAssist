package com.tyron.builder.gradle.internal.res

import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.services.Aapt2DaemonServiceKey
import com.tyron.builder.gradle.internal.services.useAaptDaemon
import com.tyron.builder.internal.aapt.AaptPackageConfig
import com.tyron.builder.internal.aapt.v2.Aapt2
import com.tyron.builder.internal.aapt.v2.Aapt2Exception
import com.tyron.builder.internal.aapt.v2.Aapt2InternalException
import com.android.ide.common.process.ProcessException
import com.android.ide.common.symbols.RGeneration
import com.android.ide.common.symbols.SymbolIo
import com.android.ide.common.symbols.SymbolTable
import com.android.ide.common.symbols.getPackageNameFromManifest
import com.tyron.builder.plugin.options.SyncOptions
import com.tyron.builder.symbols.exportToCompiledJava
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.io.IOException

abstract class Aapt2ProcessResourcesRunnable : WorkAction<Aapt2ProcessResourcesRunnable.Params> {

    override fun execute() {
        val logger = Logging.getLogger(this::class.java)
        useAaptDaemon(parameters.aapt2ServiceKey.get()) { daemon ->
            processResources(
                aapt = daemon,
                aaptConfig = parameters.request.get(),
                rJar = null,
                logger = logger,
                errorFormatMode = parameters.errorFormatMode.get()
            )
        }
    }

    abstract class Params: WorkParameters {
        abstract val aapt2ServiceKey: Property<Aapt2DaemonServiceKey>
        abstract val request: Property<AaptPackageConfig>
        abstract val errorFormatMode: Property<SyncOptions.ErrorFormatMode>
    }
}

@Throws(IOException::class, ProcessException::class)
fun processResources(
    aapt: Aapt2,
    aaptConfig: AaptPackageConfig,
    rJar: File?,
    logger: Logger,
    errorFormatMode: SyncOptions.ErrorFormatMode,
    symbolTableLoader: (Iterable<File>) -> List<SymbolTable> = { SymbolIo().loadDependenciesSymbolTables(it) }
) {

    try {
        aapt.link(aaptConfig, LoggerWrapper(logger))
    } catch (e: Aapt2Exception) {
        throw rewriteLinkException(
                e,
                errorFormatMode,
                aaptConfig.mergeBlameDirectory,
                aaptConfig.manifestMergeBlameFile,
                aaptConfig.identifiedSourceSetMap,
                logger
        )
    } catch (e: Aapt2InternalException) {
        throw e
    } catch (e: Exception) {
        throw ProcessException("Failed to execute aapt", e)
    }

    val sourceOut = aaptConfig.sourceOutputDir
    if (sourceOut != null || rJar != null) {
        // Figure out what the main symbol file's package is.
        var mainPackageName = aaptConfig.customPackageForR
        if (mainPackageName == null) {
            mainPackageName = getPackageNameFromManifest(aaptConfig.manifestFile)
        }

        // Load the main symbol file.
        val mainRTxt = File(aaptConfig.symbolOutputDir, "R.txt")
        val mainSymbols = if (mainRTxt.isFile)
            SymbolIo.readFromAapt(mainRTxt, mainPackageName)
        else
            SymbolTable.builder().tablePackage(mainPackageName).build()

        // For each dependency, load its symbol file.
        var depSymbolTables: List<SymbolTable> = symbolTableLoader.invoke(
            aaptConfig.librarySymbolTableFiles
        )

        val finalIds = aaptConfig.useFinalIds
        if (rJar != null) { // non-namespaced case
            val localSymbolsFile = aaptConfig.localSymbolTableFile
            val nonTransitiveRClass = localSymbolsFile != null

            // If we're generating a non-transitive R class for the current module, we need to read
            // the local symbols file and add it to the dependencies symbol tables. It doesn't have
            // the correct IDs, so we skip the values - they will be resolved in the next line from
            // the R.txt.
            if (nonTransitiveRClass) {
                val localSymbols =
                    SymbolIo.readRDef(localSymbolsFile!!.toPath()).rename(mainSymbols.tablePackage)
                depSymbolTables = depSymbolTables.plus(localSymbols)
            }

            // Replace the default values from the dependency table with the allocated values from
            // the main table.
            depSymbolTables = depSymbolTables.map { t -> t.withValuesFrom(mainSymbols) }

            // If our local R class is transitive, add the table of *all* symbols to generate.
            if (!nonTransitiveRClass)
                depSymbolTables = depSymbolTables.plus(mainSymbols)

            exportToCompiledJava(
                depSymbolTables,
                rJar.toPath(),
                finalIds
            )
        } else { // namespaced case, TODO: use exportToCompiledJava instead b/130110629
            RGeneration.generateRForLibraries(
                mainSymbols, depSymbolTables, sourceOut!!, finalIds
            )
        }
    }
}
