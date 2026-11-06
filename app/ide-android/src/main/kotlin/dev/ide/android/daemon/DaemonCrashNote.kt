package dev.ide.android.daemon

import android.content.Context
import dev.ide.core.plugins.PluginCrashGuard
import java.io.File

/**
 * The last uncaught error of the `:build` process, left in a file for the UI process to read after the daemon
 * dies. The OS death record of a Java crash says only that it was one; whether it was an OutOfMemoryError
 * (the build needed more heap than the app is allowed) or a bug is what the user and a bug report need, and
 * only the dying process knows it.
 *
 * Installed in the daemon ahead of the default handler, which it then calls, so the process still dies and the
 * death is still recorded exactly as before.
 *
 * When an installed plugin's code is on the dying stack, that plugin is named in a second file ([takePluginBlame]):
 * the daemon loads the same installed plugins as the UI, and one that crashes it would crash every build. The UI
 * process, which owns the preferences, turns it off.
 */
internal object DaemonCrashNote {
    private const val FILE_NAME = "build-daemon-crash.txt"

    private fun file(context: Context): File = File(context.applicationContext.cacheDir, FILE_NAME)

    private const val PLUGIN_FILE_NAME = "build-daemon-plugin-crash.txt"

    private fun pluginFile(context: Context): File = File(context.applicationContext.cacheDir, PLUGIN_FILE_NAME)

    fun install(context: Context, blame: (Throwable) -> PluginCrashGuard.Blamed? = { null }) {
        val target = file(context)
        val pluginTarget = pluginFile(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val rt = Runtime.getRuntime()
                val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                val maxMb = rt.maxMemory() / (1024 * 1024)
                val message = error.message?.take(MAX_MESSAGE)?.let { ": $it" } ?: ""
                // Out of memory is the build's size, not the code that happened to be allocating.
                val plugin = if (error is VirtualMachineError) null else runCatching { blame(error) }.getOrNull()
                val inPlugin = plugin?.let { " in plugin ${it.name} ${it.version}" } ?: ""
                target.writeText("${error.javaClass.name}$message (thread ${thread.name}$inPlugin, heap ${usedMb}MB of ${maxMb}MB)")
                if (plugin != null) pluginTarget.writeText(listOf(plugin.id, plugin.version, plugin.name).joinToString("\n"))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The note, if one was written at or after [sinceMs] (wall clock); older notes belong to an earlier death. */
    fun readSince(context: Context, sinceMs: Long): String? = runCatching {
        val f = file(context)
        if (!f.isFile || f.lastModified() < sinceMs) null else f.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * The plugin the daemon blamed for its death, if it named one in the last [maxAgeMs]; removed as it is read,
     * so one death turns one plugin off once.
     */
    fun takePluginBlame(context: Context, maxAgeMs: Long = 60_000): PluginCrashGuard.Blamed? = runCatching {
        val f = pluginFile(context)
        if (!f.isFile) return@runCatching null
        val fresh = System.currentTimeMillis() - f.lastModified() <= maxAgeMs
        val parts = f.readText().split('\n')
        f.delete()
        if (!fresh || parts.size < 3 || parts[0].isBlank()) null else PluginCrashGuard.Blamed(parts[0], parts[1], parts[2])
    }.getOrNull()

    private const val MAX_MESSAGE = 300
}
