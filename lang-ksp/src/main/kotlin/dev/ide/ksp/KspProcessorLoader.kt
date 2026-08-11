package dev.ide.ksp

import dev.ide.platform.ToolUrlClassLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads the KSP2 runner (`symbol-processing-aa-embeddable`) together with a module's processor jars into one
 * classloader, from which `com.google.devtools.ksp.impl.KotlinSymbolProcessing` and each processor's
 * `SymbolProcessorProvider` can be instantiated.
 *
 * Split by platform reality, exactly like the kotlinc-plugin `KotlinPluginLoader`:
 *  - **Desktop** ([DefaultKspProcessorLoader]): a `URLClassLoader` over the jars — the JVM defines classes
 *    straight from jar bytecode.
 *  - **ART** (injected by `:ide-android`): D8-dex the classpath, then a `DexClassLoader` over the dex, since a
 *    jar's `.class` bytes can't be defined at runtime on Android.
 *
 * **Play policy:** the jars handed here must originate from the app itself (bundled assets / the app's own
 * dex), never a runtime Maven download — loading downloaded executable code violates Google Play's
 * dynamic-code-loading policy. Dexing a *bundled* jar on device is a format transform, not a download, so it
 * stays compliant.
 *
 * The parent MUST carry the thin KSP SPI (`symbol-processing-api`) + config (`symbol-processing-common-deps`),
 * so the `KSPConfig`/`SymbolProcessorProvider`/`KSPLogger` the caller builds are the SAME types the loaded
 * runner and processors resolve (parent-first delegation). The runner's own relocated `ksp.*` Analysis API
 * lives only in the child, so it can never clash with the IDE's own compiler platform.
 *
 * Parent-first has one exception, [dev.ide.platform.ToolClassIsolation]: a package the app carries for its own
 * reasons at an incompatible version (`dagger.*`, via bundletool) comes from the processor's own jars.
 */
fun interface KspProcessorLoader {
    fun load(classpath: List<Path>): ClassLoader
}

/** Desktop default: a [ToolUrlClassLoader] over the runner + processor jars, parented to this module's loader
 *  (which carries the shipped `symbol-processing-api` + `-common-deps`). */
object DefaultKspProcessorLoader : KspProcessorLoader {
    override fun load(classpath: List<Path>): ClassLoader =
        ToolUrlClassLoader(
            classpath.filter { Files.exists(it) }.map { it.toUri().toURL() }.toTypedArray(),
            KspProcessorLoader::class.java.classLoader,
        )
}
