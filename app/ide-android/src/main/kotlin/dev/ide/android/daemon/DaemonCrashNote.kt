package dev.ide.android.daemon

import android.content.Context
import java.io.File

/**
 * The last uncaught error of the `:build` process, left in a file for the UI process to read after the daemon
 * dies. The OS death record of a Java crash says only that it was one; whether it was an OutOfMemoryError
 * (the build needed more heap than the app is allowed) or a bug is what the user and a bug report need, and
 * only the dying process knows it.
 *
 * Installed in the daemon ahead of the default handler, which it then calls, so the process still dies and the
 * death is still recorded exactly as before.
 */
internal object DaemonCrashNote {
    private const val FILE_NAME = "build-daemon-crash.txt"

    private fun file(context: Context): File = File(context.applicationContext.cacheDir, FILE_NAME)

    fun install(context: Context) {
        val target = file(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val rt = Runtime.getRuntime()
                val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                val maxMb = rt.maxMemory() / (1024 * 1024)
                val message = error.message?.take(MAX_MESSAGE)?.let { ": $it" } ?: ""
                target.writeText("${error.javaClass.name}$message (thread ${thread.name}, heap ${usedMb}MB of ${maxMb}MB)")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The note, if one was written at or after [sinceMs] (wall clock); older notes belong to an earlier death. */
    fun readSince(context: Context, sinceMs: Long): String? = runCatching {
        val f = file(context)
        if (!f.isFile || f.lastModified() < sinceMs) null else f.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private const val MAX_MESSAGE = 300
}
