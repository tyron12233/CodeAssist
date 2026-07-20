package dev.ide.lang.kotlin.compile

import dev.ide.model.Module
import dev.ide.platform.ExtensionPoint
import java.nio.file.Path

/**
 * A Kotlin compiler plugin (Compose, kotlinx-serialization, Parcelize, all-open/no-arg, …) the build's
 * `compileKotlin` tasks apply per module. A plugin is a *bytecode transformer* run inside kotlinc; it emits
 * no new source. Source generators (Room, KSP) are a separate concern layered on top of this seam.
 *
 * Contributed through [KOTLIN_COMPILER_PLUGIN_EP], so adding a plugin is a registration rather than a host
 * edit. The compile task asks each registered plugin whether it [appliesTo] the module, then feeds the union
 * of [classpath] (kotlinc's `-Xplugin` set) and [options] (`-P plugin:<id>:<k>=<v>` strings) to
 * [KotlinJvmCompiler]'s generic `compilerPlugins`/`pluginOptions` seam.
 *
 * On ART the plugin's registrar class must additionally be loadable from a dexed classloader (see
 * `docs/kotlin-compiler-plugins-and-codegen.md`); [classpath] is the jar set that path dexes.
 */
interface KotlinCompilerPlugin {
    /** The plugin's kotlinc CLI id, the prefix of every `-P plugin:<pluginId>:<k>=<v>` option. */
    val pluginId: String

    /** How the plugin's registrar is made available to the compiler (see [KotlinPluginLoading]). */
    val loading: KotlinPluginLoading get() = KotlinPluginLoading.COMPILE_CLASSPATH

    /** True when [module] should be compiled with this plugin. [classpath] is the effective compile
     *  classpath (deps + libs + boot), so a classpath probe (Compose: `Composable.class` present) works. */
    fun appliesTo(module: Module, classpath: List<Path>): Boolean

    /** The plugin jar(s). For [KotlinPluginLoading.COMPILE_CLASSPATH] these go to kotlinc as `-Xplugin`; for
     *  [KotlinPluginLoading.RUNTIME_REGISTRAR] they are the classpath a [KotlinPluginLoader] loads. May be
     *  several (e.g. a runner plus its deps). */
    fun classpath(module: Module): List<Path>

    /** `-P` options already in `<pluginId>:<key>=<value>` form. Empty for a plugin with no configuration. */
    fun options(module: Module): List<String> = emptyList()
}

/** How a [KotlinCompilerPlugin]'s registrar reaches the compiler. */
enum class KotlinPluginLoading {
    /**
     * Applied via kotlinc's `-Xplugin` (the jar on the plugin classpath). The registrar must be loadable by
     * the compiler itself: on the desktop that is the jar; on ART it must be **bundled and dexed into the
     * app** (a jar's bytecode can't be defined at runtime there). This is Compose's path today.
     */
    COMPILE_CLASSPATH,

    /**
     * Loaded at runtime through a [KotlinPluginLoader] (a `URLClassLoader` on the desktop, a D8-dexed
     * `DexClassLoader` on ART) and registered programmatically. For plugins not bundled into the app.
     */
    RUNTIME_REGISTRAR,
}

/** Plugins contribute Kotlin compiler plugins here. Resolved per build by the `compileKotlin` tasks. */
val KOTLIN_COMPILER_PLUGIN_EP = ExtensionPoint<KotlinCompilerPlugin>("platform.kotlinCompilerPlugin")

/** The built-in plugins, applied unless a host overrides the list (the default for direct/test wiring). */
val BUILTIN_KOTLIN_COMPILER_PLUGINS: List<KotlinCompilerPlugin> =
    listOf(ComposeCompilerPlugin, SerializationCompilerPlugin, ParcelizeCompilerPlugin)

/**
 * The applied plugins for a compile, split by loading mode:
 *  - [classpaths]/[options]: the `-Xplugin`/`-P` inputs for [KotlinPluginLoading.COMPILE_CLASSPATH] plugins.
 *  - [runtimeClasspaths]: one classpath per [KotlinPluginLoading.RUNTIME_REGISTRAR] plugin, each loaded and
 *    registered programmatically by the compiler.
 */
data class ResolvedKotlinPlugins(
    val classpaths: List<Path>,
    val options: List<String>,
    val runtimeClasspaths: List<List<Path>> = emptyList(),
) {
    companion object { val EMPTY = ResolvedKotlinPlugins(emptyList(), emptyList()) }
}

fun List<KotlinCompilerPlugin>.resolveFor(module: Module, classpath: List<Path>): ResolvedKotlinPlugins {
    if (isEmpty()) return ResolvedKotlinPlugins.EMPTY
    val applicable = filter { it.appliesTo(module, classpath) }
    if (applicable.isEmpty()) return ResolvedKotlinPlugins.EMPTY
    val (runtime, compileClasspath) = applicable.partition { it.loading == KotlinPluginLoading.RUNTIME_REGISTRAR }
    return ResolvedKotlinPlugins(
        classpaths = compileClasspath.flatMap { it.classpath(module) },
        options = compileClasspath.flatMap { it.options(module) },
        runtimeClasspaths = runtime.map { it.classpath(module) }.filter { it.isNotEmpty() },
    )
}
