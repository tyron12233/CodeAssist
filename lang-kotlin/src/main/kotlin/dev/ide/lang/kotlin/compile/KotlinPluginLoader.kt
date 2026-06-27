package dev.ide.lang.kotlin.compile

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Loads a Kotlin compiler plugin's classes at runtime so its `CompilerPluginRegistrar` can be instantiated
 * and registered programmatically (see [KotlinJvmCompiler]'s registrar path).
 *
 * The two implementations split by platform reality, exactly like the build's other tool ports:
 *  - **Desktop** ([DefaultKotlinPluginLoader]): a `URLClassLoader` over the plugin jars. The JVM can define
 *    classes straight from jar bytecode.
 *  - **ART** (injected by `:ide-android`): D8-dex the plugin classpath, then a `DexClassLoader` over the dex.
 *    A jar's `.class` bytes can't be defined at runtime on ART, so they must be dexed first.
 *
 * In both cases the parent is the compiler's own classloader, which carries the `CompilerPluginRegistrar`
 * base type (and, on ART, any plugin registrar already dexed into the app — e.g. Compose), so a plugin that
 * bottoms out at an already-present class still resolves through parent delegation.
 */
interface KotlinPluginLoader {
    /** A classloader over [classpath] from which the plugin's registrar classes can be loaded. */
    fun load(classpath: List<Path>): ClassLoader
}

/** Desktop default: a `URLClassLoader` over the plugin jars, parented to the compiler classloader. */
object DefaultKotlinPluginLoader : KotlinPluginLoader {
    @OptIn(ExperimentalCompilerApi::class)
    override fun load(classpath: List<Path>): ClassLoader =
        URLClassLoader(
            classpath.filter { Files.exists(it) }.map { it.toUri().toURL() }.toTypedArray(),
            CompilerPluginRegistrar::class.java.classLoader,
        )
}

/**
 * Instantiate the `CompilerPluginRegistrar`s declared on [classpath] (read from each jar's
 * `META-INF/services` descriptor), loaded through [loader]. Returns the registrar instances ready to add to
 * a `CompilerConfiguration`. Best-effort per registrar: a class that fails to load is skipped rather than
 * failing the whole compile.
 */
@OptIn(ExperimentalCompilerApi::class)
internal fun loadCompilerPluginRegistrars(classpath: List<Path>, loader: KotlinPluginLoader): List<CompilerPluginRegistrar> {
    val jars = classpath.filter { Files.isRegularFile(it) }
    if (jars.isEmpty()) return emptyList()
    val serviceEntry = "META-INF/services/${CompilerPluginRegistrar::class.java.name}"
    val classNames = LinkedHashSet<String>()
    for (jar in jars) {
        runCatching {
            ZipFile(jar.toFile()).use { zf ->
                val entry = zf.getEntry(serviceEntry) ?: return@use
                zf.getInputStream(entry).bufferedReader().readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { classNames.add(it) }
            }
        }
    }
    if (classNames.isEmpty()) return emptyList()
    val classLoader = loader.load(jars)
    return classNames.mapNotNull { name ->
        runCatching {
            Class.forName(name, true, classLoader).getDeclaredConstructor().newInstance() as CompilerPluginRegistrar
        }.getOrNull()
    }
}
