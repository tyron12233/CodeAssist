package dev.ide.build.jvm.run

import java.net.URLClassLoader
import java.nio.file.Path

/**
 * Lets the libraries a run BRIDGES find the program's resources while it runs.
 *
 * A run interprets the program's classes but executes `org/jetbrains/` (and the rest of
 * [dev.ide.jvm.InterpretPolicy.DEFAULT]'s bridged namespaces) with the IDE's own copies, and those look resources
 * up on the IDE's class loader, which has never seen the project. Compose Multiplatform resources are the case
 * that matters: `painterResource(Res.drawable.logo)` reads `composeResources/<pkg>/drawable/logo.png` through
 * `JvmResourceReader.Default`, built once over its own class's loader, and fails with
 * `MissingResourceException` (reported: a desktop Compose app's window opened on an "Unknown error" dialog).
 *
 * [install] points that reader at a loader that asks the reader's original loader first and the run's
 * classpath after it, so the IDE's own resources still resolve exactly as before, and [close] puts the
 * original back. Runs are sequential, so swapping a process-wide object for the run's duration is safe (the
 * same reasoning as the stdio redirect in [VmProgramInterpreter]). A host without Compose resources (the
 * device, a console app's IDE) has no such class, and this does nothing.
 */
internal class RunResources private constructor(
    private val reader: Any,
    private val field: java.lang.reflect.Field,
    private val original: ClassLoader,
    private val runLoader: URLClassLoader,
    private val host: ClassLoader,
) : AutoCloseable {

    override fun close() {
        runCatching { field.set(reader, original) }
        // Drop what the run cached under its own resource paths, so a later run reads edited files again.
        clearComposeCaches(host)
        runCatching { runLoader.close() }
    }

    companion object {
        private const val READER = "org.jetbrains.compose.resources.JvmResourceReader"

        fun install(host: ClassLoader, classpath: List<Path>): RunResources? = runCatching {
            val readerClass = Class.forName(READER, true, host)
            val reader = readerClass.getDeclaredField("Default").apply { isAccessible = true }.get(null)
                ?: return null
            val field = readerClass.getDeclaredField("classLoader").apply { isAccessible = true }
            val original = field.get(reader) as ClassLoader
            val runLoader = ResourceOnlyLoader(classpath, original)
            field.set(reader, runLoader)
            // A previous run (or the IDE) may have cached a failure or an older copy under the same path.
            clearComposeCaches(host)
            RunResources(reader, field, original, runLoader, host)
        }.getOrNull()

        private fun clearComposeCaches(host: ClassLoader) {
            runCatching {
                val caches = Class.forName("org.jetbrains.compose.resources.ResourceCaches", true, host)
                val instance = caches.getField("INSTANCE").get(null)
                Class.forName("org.jetbrains.compose.resources.ResourceCaches_desktopKt", true, host)
                    .getMethod("clearBlocking", caches).invoke(null, instance)
            }
        }
    }

    /** Finds resources on [classpath] after [parent], and never defines a class: the program's classes are
     *  interpreted, and loading one into the host here would bypass the VM. */
    private class ResourceOnlyLoader(classpath: List<Path>, parent: ClassLoader) :
        URLClassLoader(classpath.map { it.toUri().toURL() }.toTypedArray(), parent) {
        override fun findClass(name: String): Class<*> = throw ClassNotFoundException(name)
    }
}
