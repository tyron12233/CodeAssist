package dev.ide.android.fork

import java.io.File

/**
 * Which of the app's OS processes this code is running in.
 *
 * The app runs the IDE in its main process and builds in `:build` (see `BuildDaemonService`), and
 * `AndroidIde.createProjectManager` stands up an engine in both. A resource as expensive as a persistent
 * compiler VM must only be claimed by the process that will actually compile, so [isBuildProcess] tells the
 * two apart.
 *
 * `Application.getProcessName()` would answer this, but only from API 28, and the app's floor is 26. The
 * process name is the first NUL-delimited field of `/proc/self/cmdline` on every version.
 */
internal object ProcessIdentity {

    private const val BUILD_SUFFIX = ":build"

    /** The current process name (`com.tyron.code`, `com.tyron.code:build`, ...), or null if unreadable. */
    val processName: String? by lazy {
        runCatching {
            // argv[0]: the kernel NUL-delimits the arguments and may NUL-pad the rest of the buffer.
            File("/proc/self/cmdline").readText().substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** True in the `:build` daemon, whose only job is running builds. */
    fun isBuildProcess(): Boolean = processName?.endsWith(BUILD_SUFFIX) == true
}
