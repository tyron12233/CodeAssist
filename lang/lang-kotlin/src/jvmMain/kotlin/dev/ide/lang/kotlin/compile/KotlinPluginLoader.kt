package dev.ide.lang.kotlin.compile

import java.nio.file.Path

/**
 * Loads classes from tool jars the IDE BUNDLES, at runtime: today the KSP processors (Room, Moshi, Hilt,
 * Glide), whose jars ship inside the app. On ART (injected by `:ide-android`) it D8-dexes the jars and loads
 * the dex, since a jar's `.class` bytes can't be defined at runtime there; on the desktop KSP uses its own
 * `URLClassLoader`.
 *
 * Never for code the app did not ship. Kotlin compiler plugins in particular are only the bundled ones
 * (Compose, kotlinx.serialization, Parcelize), applied through kotlinc's `-Xplugin` with their registrars
 * dexed into the app; a compiler plugin from anywhere else is not supported (see [BUILTIN_KOTLIN_COMPILER_PLUGINS]).
 */
interface KotlinPluginLoader {
    /** A classloader over [classpath], parented to the app's own classes. */
    fun load(classpath: List<Path>): ClassLoader
}
