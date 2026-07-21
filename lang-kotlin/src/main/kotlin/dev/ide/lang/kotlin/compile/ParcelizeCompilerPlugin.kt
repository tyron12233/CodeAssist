package dev.ide.lang.kotlin.compile

import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Supplies the kotlin-parcelize kotlinc compiler plugin (`parcelize-compiler-plugin-for-ide`, the unshaded
 * build matching :kotlin-compiler-deps) and decides when a module needs it.
 *
 * A `@Parcelize` class can't be compiled like ordinary Kotlin: the parcelize plugin generates its
 * `Parcelable` implementation (`writeToParcel`/`CREATOR`/…). Without it the class has no Parcelable members.
 * So when a module carries the parcelize runtime we feed kotlinc this plugin via [KotlinJvmCompiler]'s generic
 * `compilerPlugins` seam — exactly like [ComposeCompilerPlugin] and [SerializationCompilerPlugin]. Zero
 * options, so [options] stays empty.
 *
 * Activation is a classpath probe (the `@Parcelize` annotation), so the module's **Build Features ▸ Parcelize**
 * toggle enables it by adding the kotlin-parcelize runtime — no explicit per-module flag is read here.
 *
 * Bundled + dexed like [ComposeCompilerPlugin]: the jar is a lang-kotlin classpath resource
 * (`/kotlin-parcelize-compiler-plugin.jar`) extracted on demand for kotlinc's `-Xplugin`, and on ART the
 * plugin's `ParcelizeComponentRegistrar` is dexed into the app (`:ide-android`) so kotlinc resolves it through
 * parent delegation — which keeps parcelize off the runtime `DexClassLoader` (`ArtKotlinPluginLoader`) path.
 */
object ParcelizeCompilerPlugin : KotlinCompilerPlugin {
    /** The plugin's kotlinc CLI id (the prefix of every `-P plugin:<id>:<k>=<v>` option; unused — no options). */
    const val PLUGIN_ID: String = "org.jetbrains.kotlin.parcelize"

    override val pluginId: String get() = PLUGIN_ID
    override val displayName: String get() = "Parcelize"
    override val description: String get() = "Generates Parcelable implementations for @Parcelize classes."

    /** Applies when [classpath] carries the parcelize runtime; the module then needs the plugin. */
    override fun appliesTo(module: Module, classpath: List<Path>): Boolean = usesParcelize(classpath)

    /** The bundled plugin jar, or empty when it is not on the classpath (a stripped-down test setup). */
    override fun classpath(module: Module): List<Path> = listOfNotNull(jar())

    /** The `@Parcelize` annotation class; its presence on the classpath means "apply the plugin". Ships in
     *  `org.jetbrains.kotlin:kotlin-parcelize-runtime` (the dep the Build Features toggle adds). */
    private const val PARCELIZE_CLASS_ENTRY = "kotlinx/parcelize/Parcelize.class"

    private const val RESOURCE = "/kotlin-parcelize-compiler-plugin.jar"
    private const val FILE_NAME = "kotlin-parcelize-compiler-plugin.jar"

    @Volatile private var cachedPath: Path? = null

    /** True when the plugin jar is bundled on the classpath (false only in a stripped-down test classpath). */
    fun isBundled(): Boolean = ParcelizeCompilerPlugin::class.java.getResource(RESOURCE) != null

    /** Extract the bundled jar into [dir], reusing an already-extracted copy. Null when the resource is absent. */
    fun extractTo(dir: Path): Path? = runCatching {
        val target = dir.resolve(FILE_NAME)
        if (Files.isRegularFile(target) && Files.size(target) > 0L) return target
        val stream = ParcelizeCompilerPlugin::class.java.getResourceAsStream(RESOURCE) ?: return null
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "parcelize-plugin", ".tmp")
        stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
        runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
        target
    }.getOrNull()

    /** The bundled jar extracted to a process-wide temp cache (the in-process consumer's fallback). */
    fun jar(): Path? {
        cachedPath?.let { if (Files.isRegularFile(it)) return it }
        val dir = Paths.get(System.getProperty("java.io.tmpdir"), "codeassist", "parcelize-plugin")
        return extractTo(dir)?.also { cachedPath = it }
    }

    /**
     * True when [classpath] carries the parcelize runtime (i.e. `kotlinx.parcelize.Parcelize`), so the module's
     * Kotlin should be compiled with the parcelize plugin. Scans jar entries (and class dirs) cheaply; stops at
     * the first hit.
     */
    fun usesParcelize(classpath: List<Path>): Boolean = classpath.any { entry ->
        when {
            !Files.exists(entry) -> false
            Files.isDirectory(entry) -> Files.exists(entry.resolve(PARCELIZE_CLASS_ENTRY))
            entry.toString().endsWith(".jar") -> runCatching {
                ZipFile(entry.toFile()).use { it.getEntry(PARCELIZE_CLASS_ENTRY) != null }
            }.getOrDefault(false)
            else -> false
        }
    }
}
