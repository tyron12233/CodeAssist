package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile

/**
 * D8 *global synthetics*: classes desugaring has to create once for the whole program rather than once per
 * input class. Java records produce one (the tag class that stands in for `java.lang.Record`, which the
 * platform only ships from API 34); API outlining and var-handle desugaring produce others.
 *
 * A per-class-file archive ([Dexer.dexArchive], D8 `OutputMode.DexFilePerClassFile`, which is compiled as an
 * intermediate result) has nowhere to put one: every output `.dex` belongs to exactly one input class, and a
 * global belongs to none. D8 therefore refuses to compile a class that needs one unless it is given somewhere
 * to park it, failing the build with "Invalid build configuration. Attempt to create a global synthetic for
 * 'Record desugaring' without a global-synthetics consumer."
 *
 * The archive step gives it the bucket directory, so D8 writes a `<binaryName>.globals` file beside the `.dex`
 * of each class that produced globals, and the merge that finalizes the intermediates ([Dexer.dex]) reads
 * those files back and materializes each global exactly once in its output. Keeping the two artifacts side by
 * side is what lets the rest of the pipeline stay unchanged: a `.globals` travels with its bucket through the
 * content-addressed dex cache, is dropped along with its class when that class is re-dexed, and reaches the
 * same merge group as the classes that reference it.
 */
internal object DexGlobalSynthetics {

    /** Extension D8 gives a per-class global-synthetics file: `com/example/Foo.class` yields
     *  `com/example/Foo.dex` plus, when it produced globals, `com/example/Foo.globals`. */
    const val EXTENSION = ".globals"

    /** The global-synthetics file that accompanies the per-class dex at [dexFile]. */
    fun fileFor(dexFile: Path): Path =
        dexFile.resolveSibling(dexFile.fileName.toString().removeSuffix(".dex") + EXTENSION)

    /**
     * The existing global-synthetics files accompanying [inputs], a merge's per-class `.dex`. Inputs that are
     * not per-class dex (class jars, class directories) and classes whose desugaring produced no global
     * contribute nothing, so a merge with no records in it passes an empty list.
     */
    fun accompanying(inputs: List<Path>): List<Path> =
        inputs.asSequence()
            .filter { it.toString().endsWith(".dex") }
            .map { fileFor(it) }
            .filter { Files.isRegularFile(it) }
            .toList()

    /**
     * Whether the command-line D8 on [toolClasspath] understands the global-synthetics options
     * (`--globals-output` when archiving, `--globals` when merging), which R8 introduced in version 8. This
     * only matters for a subprocess D8: the desktop runs whichever `d8.jar` the machine's build-tools ship,
     * and an older one both rejects an unknown option outright (failing every dex invocation) and predates the
     * split, having no need for the options in the first place.
     *
     * Read from the tool jar's `r8-version.properties`. A classpath carrying no such marker counts as too old,
     * so an unrecognized tool keeps the behaviour it had rather than being handed options it may not know.
     */
    fun supportedBy(toolClasspath: List<Path>): Boolean =
        toolClasspath.any { (majorVersionOf(it) ?: 0) >= FIRST_GLOBAL_SYNTHETICS_MAJOR }

    /** First R8 major version with global synthetics (build-tools 34.0.0 and up). */
    private const val FIRST_GLOBAL_SYNTHETICS_MAJOR = 8
    private const val VERSION_ENTRY = "r8-version.properties"
    private const val VERSION_KEY = "version.version"

    /** The R8 major version [jar] declares (`version.version=8.13.19` yields 8), or null when it declares none
     *  or cannot be read as an archive. */
    private fun majorVersionOf(jar: Path): Int? = runCatching {
        if (!Files.isRegularFile(jar)) return@runCatching null
        ZipFile(jar.toFile()).use { zf ->
            val entry = zf.getEntry(VERSION_ENTRY) ?: return@use null
            val props = Properties().apply { zf.getInputStream(entry).use { load(it) } }
            props.getProperty(VERSION_KEY)?.substringBefore('.')?.trim()?.toIntOrNull()
        }
    }.getOrNull()
}
