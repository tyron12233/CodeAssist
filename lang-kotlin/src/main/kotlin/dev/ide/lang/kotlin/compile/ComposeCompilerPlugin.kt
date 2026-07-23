package dev.ide.lang.kotlin.compile

import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Supplies the Jetpack Compose kotlinc compiler plugin (`compose-compiler-plugin-for-ide`, the unshaded
 * build matching :kotlin-compiler-deps) and
 * decides when a module needs it.
 *
 * Compose code can't merely be compiled like ordinary Kotlin: the Compose compiler plugin rewrites every
 * `@Composable` function (threading a synthetic `Composer` + `$changed`/`$default` ints, wrapping bodies in
 * restart groups, …). Without it the emitted bytecode is unusable at runtime. So when a module depends on
 * the Compose runtime we feed kotlinc this plugin via [KotlinJvmCompiler]'s generic `compilerPlugins` seam.
 *
 * Bundled exactly like [BundledKotlinStdlib]: the jar is a lang-kotlin classpath resource
 * (`/kotlin-compose-compiler-plugin.jar`, copied in by the build at the `kotlin` version) extracted to a
 * real file on demand. kotlinc needs a jar on disk to read the plugin's `META-INF/services` descriptor.
 *
 * On ART there's a second requirement the build handles separately: the plugin's `ComposePluginRegistrar`
 * class must be *dexed into the app* (`:ide-android` adds it as an `implementation` dep), because kotlinc
 * resolves the registrar through parent delegation to the app classloader — a jar's `.class` bytes can't be
 * defined at runtime on ART. The jar here only serves the service descriptor and the desktop class-load.
 */
object ComposeCompilerPlugin : KotlinCompilerPlugin {
    /** The Compose plugin version. Kept in lockstep with `libs.versions.toml` `kotlin` (the plugin ships with it). */
    const val VERSION: String = "2.4.0"

    /** The plugin's kotlinc CLI id (the prefix of every `-P plugin:<id>:<k>=<v>` option). */
    const val PLUGIN_ID: String = "androidx.compose.compiler.plugins.kotlin"

    override val pluginId: String get() = PLUGIN_ID
    override val displayName: String get() = "Jetpack Compose"
    override val description: String get() = "Compiles @Composable functions (the Compose compiler plugin)."

    /** Applies when [classpath] carries the Compose runtime; the module then needs the plugin. Ignores [module]. */
    override fun appliesTo(module: Module, classpath: List<Path>): Boolean = isComposeModule(classpath)

    /** The bundled plugin jar, or empty when it is not on the classpath (a stripped-down test setup). */
    override fun classpath(module: Module): List<Path> = listOfNotNull(jar())

    /** The class that marks a Compose dependency on a compile classpath — its presence means "apply the plugin". */
    private const val COMPOSABLE_CLASS_ENTRY = "androidx/compose/runtime/Composable.class"

    private const val RESOURCE = "/kotlin-compose-compiler-plugin.jar"
    private const val FILE_NAME = "kotlin-compose-compiler-plugin-$VERSION.jar"

    @Volatile private var cachedPath: Path? = null

    /** True when the plugin jar is bundled on the classpath (false only in a stripped-down test classpath). */
    fun isBundled(): Boolean = ComposeCompilerPlugin::class.java.getResource(RESOURCE) != null

    /** Extract the bundled jar into [dir], reusing an already-extracted copy. Null when the resource is absent. */
    fun extractTo(dir: Path): Path? = runCatching {
        val target = dir.resolve(FILE_NAME)
        if (Files.isRegularFile(target) && Files.size(target) > 0L) return target
        val stream = ComposeCompilerPlugin::class.java.getResourceAsStream(RESOURCE) ?: return null
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "compose-plugin", ".tmp")
        stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
        runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
        target
    }.getOrNull()

    /** The bundled jar extracted to a process-wide temp cache (the in-process consumer's fallback). */
    fun jar(): Path? {
        cachedPath?.let { if (Files.isRegularFile(it)) return it }
        val dir = Paths.get(System.getProperty("java.io.tmpdir"), "codeassist", "compose-plugin")
        return extractTo(dir)?.also { cachedPath = it }
    }

    /**
     * True when [classpath] carries the Compose runtime (i.e. `androidx.compose.runtime.Composable`), so the
     * module's Kotlin should be compiled with the Compose plugin. Scans jar entries (and class dirs) cheaply;
     * stops at the first hit.
     */
    fun isComposeModule(classpath: List<Path>): Boolean = classpath.any { entry ->
        when {
            !Files.exists(entry) -> false
            Files.isDirectory(entry) -> Files.exists(entry.resolve(COMPOSABLE_CLASS_ENTRY))
            entry.toString().endsWith(".jar") -> runCatching {
                ZipFile(entry.toFile()).use { it.getEntry(COMPOSABLE_CLASS_ENTRY) != null }
            }.getOrDefault(false)
            else -> false
        }
    }
}
