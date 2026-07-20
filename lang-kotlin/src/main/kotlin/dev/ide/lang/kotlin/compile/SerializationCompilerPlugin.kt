package dev.ide.lang.kotlin.compile

import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Supplies the kotlinx.serialization kotlinc compiler plugin (`kotlinx-serialization-compiler-plugin-for-ide`,
 * the unshaded build matching :kotlin-compiler-deps) and decides when a module needs it.
 *
 * `@Serializable` classes can't be compiled like ordinary Kotlin: the serialization plugin generates each
 * class's `$serializer`/`serializer()` and wires the `KSerializer` machinery. Without it the emitted bytecode
 * has no serializers. So when a module carries the serialization runtime we feed kotlinc this plugin via
 * [KotlinJvmCompiler]'s generic `compilerPlugins` seam — exactly like [ComposeCompilerPlugin]. The plugin has
 * no options (zero-config), so [options] stays empty.
 *
 * Bundled like [ComposeCompilerPlugin]: the jar is a lang-kotlin classpath resource
 * (`/kotlin-serialization-compiler-plugin.jar`, copied in by the build at the `kotlinForIde` version)
 * extracted to a real file on demand — kotlinc needs a jar on disk to read the plugin's `META-INF/services`
 * descriptor. On ART the plugin's `SerializationComponentRegistrar` must additionally be *dexed into the app*
 * (`:ide-android` adds it as an `implementation` dep), because kotlinc resolves the registrar through parent
 * delegation to the app classloader — a jar's `.class` bytes can't be defined at runtime there. This is what
 * keeps serialization off the runtime `DexClassLoader` (`ArtKotlinPluginLoader`) path.
 */
object SerializationCompilerPlugin : KotlinCompilerPlugin {
    /** The plugin's kotlinc CLI id (the prefix of every `-P plugin:<id>:<k>=<v>` option; unused — no options). */
    const val PLUGIN_ID: String = "org.jetbrains.kotlinx.serialization"

    override val pluginId: String get() = PLUGIN_ID

    /** Applies when [classpath] carries the serialization runtime; the module then needs the plugin. */
    override fun appliesTo(module: Module, classpath: List<Path>): Boolean = usesSerialization(classpath)

    /** The bundled plugin jar, or empty when it is not on the classpath (a stripped-down test setup). */
    override fun classpath(module: Module): List<Path> = listOfNotNull(jar())

    /** The `@Serializable` annotation class; its presence on the classpath means "apply the plugin". Ships in
     *  `kotlinx-serialization-core`, pulled in transitively by every serialization-format runtime (json, cbor…). */
    private const val SERIALIZABLE_CLASS_ENTRY = "kotlinx/serialization/Serializable.class"

    private const val RESOURCE = "/kotlin-serialization-compiler-plugin.jar"
    private const val FILE_NAME = "kotlin-serialization-compiler-plugin.jar"

    @Volatile private var cachedPath: Path? = null

    /** True when the plugin jar is bundled on the classpath (false only in a stripped-down test classpath). */
    fun isBundled(): Boolean = SerializationCompilerPlugin::class.java.getResource(RESOURCE) != null

    /** Extract the bundled jar into [dir], reusing an already-extracted copy. Null when the resource is absent. */
    fun extractTo(dir: Path): Path? = runCatching {
        val target = dir.resolve(FILE_NAME)
        if (Files.isRegularFile(target) && Files.size(target) > 0L) return target
        val stream = SerializationCompilerPlugin::class.java.getResourceAsStream(RESOURCE) ?: return null
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "serialization-plugin", ".tmp")
        stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
        runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
        target
    }.getOrNull()

    /** The bundled jar extracted to a process-wide temp cache (the in-process consumer's fallback). */
    fun jar(): Path? {
        cachedPath?.let { if (Files.isRegularFile(it)) return it }
        val dir = Paths.get(System.getProperty("java.io.tmpdir"), "codeassist", "serialization-plugin")
        return extractTo(dir)?.also { cachedPath = it }
    }

    /**
     * True when [classpath] carries the serialization runtime (i.e. `kotlinx.serialization.Serializable`), so
     * the module's Kotlin should be compiled with the serialization plugin. Scans jar entries (and class dirs)
     * cheaply; stops at the first hit.
     */
    fun usesSerialization(classpath: List<Path>): Boolean = classpath.any { entry ->
        when {
            !Files.exists(entry) -> false
            Files.isDirectory(entry) -> Files.exists(entry.resolve(SERIALIZABLE_CLASS_ENTRY))
            entry.toString().endsWith(".jar") -> runCatching {
                ZipFile(entry.toFile()).use { it.getEntry(SERIALIZABLE_CLASS_ENTRY) != null }
            }.getOrDefault(false)
            else -> false
        }
    }
}
