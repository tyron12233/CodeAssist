package dev.codeassist.csharp.project.nuget

import android.util.Log
import dev.codeassist.csharp.editor.CsWorkspace
import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.project.csproj.CsprojInfo
import dev.codeassist.csharp.sdk.DotnetSdk
import dev.codeassist.csharp.util.RestoreNotice
import dev.codeassist.csharp.util.TAG
import dev.codeassist.csharp.util.killableTool
import dev.ide.platform.log.Logger
import dev.ide.platform.notify.UserMessages
import dev.ide.platform.notify.info
import dev.ide.platform.notify.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Restores a project's NuGet packages for the editor.
 *
 * Shared by diagnostics, [CsPackageWatcher], and the Run picker's Restore row. The mutex prevents
 * concurrent restores from unpacking the same packages, while the last-attempt check avoids
 * repeating failed attempts.
 *
 * After a restore runs, the editor is asked to reanalyze its open files through [CsWorkspace].
 * This allows errors caused by missing package types to disappear without waiting for another edit.
 *
 * Failed restores are remembered per project folder through [failuresFor] and reported once
 * through a notification.
 */
internal class CsPackageRestorer(
    private val sdk: DotnetSdk,
    private val log: Logger,
    private val messages: UserMessages? = null,
) {

    /** Prevents concurrent restores from writing to the same package cache. */
    private val mutex = Mutex()

    /** Identifies the most recent restore attempt by project folder and requested packages. */
    private var lastAttempt: String? = null

    /** Problems from the last restore of each project folder. Removed after a successful restore. */
    private val failures = ConcurrentHashMap<String, List<String>>()

    /** Scope for background restore operations. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Project folders with a background restore queued or running. */
    private val queued = ConcurrentHashMap.newKeySet<String>()

    // Lifecycle

    fun close() = scope.cancel()

    /** Problems from the last restore of [moduleDir], or an empty list if there are none. */
    fun failuresFor(moduleDir: Path): List<String> = failures[key(moduleDir)].orEmpty()

    /**
     * Returns whether [restore] would fetch packages now.
     *
     * Packages must be missing, and the current set of package requests must not have been tried
     * already. Failed attempts are not repeated automatically.
     */
    fun pending(project: CsprojInfo, moduleDir: Path): Boolean {
        val requests = Csproj.packageRequests(project)
        if (requests.isEmpty()) return false
        if (sdk.nuget.isRestored(requests, sdk.frameworkDllNames())) return false

        val attempt = key(moduleDir) + requests.joinToString { "${it.id}@${it.version}" }
        return synchronized(this) { lastAttempt != attempt }
    }

    /**
     * Starts [restore] in the background so the caller does not need to wait for downloads.
     *
     * Only one background restore is queued or running per project folder. The editor is asked
     * to reanalyze its open files when the restore finishes.
     */
    fun restoreInBackground(project: CsprojInfo, moduleDir: Path) {
        val dirKey = key(moduleDir)
        if (!queued.add(dirKey)) return

        scope.launch {
            try {
                restore(project, moduleDir)
            } finally {
                queued.remove(dirKey)
            }
        }
    }
    /**
     * Restores [project]'s packages if they are not already unpacked.
     *
     * Each set of package requests is attempted only once. Failed attempts, such as offline
     * restores or invalid package IDs, are not repeated on every keystroke. A changed package
     * set or an IDE restart allows another attempt.
     *
     * [force] bypasses the previous-attempt check, allowing the Run picker's Restore action
     * to retry. The build also restores packages before compilation through
     * [CsCompileTask].
     *
     * Returns the problems from the restore performed by this call, or an empty list if the
     * packages were already present or a previous attempt was not repeated.
     *
     * [notify] controls whether restore failures are shown as notifications. The Run picker's
     * Restore row reports failures in its own console instead.
     */
    suspend fun restore(
        project: CsprojInfo,
        moduleDir: Path,
        force: Boolean = false,
        notify: Boolean = true,
    ): List<String> {
        val requests = Csproj.packageRequests(project)
        if (requests.isEmpty()) return emptyList()

        val exclude = sdk.frameworkDllNames()
        val packages = requests.joinToString { "${it.id}@${it.version}" }
        val attempt = key(moduleDir) + packages

        mutex.withLock {
            // Check inside the lock so callers waiting for another restore can see its result.
            if (sdk.nuget.isRestored(requests, exclude)) {
                failures.remove(key(moduleDir))
                return emptyList()
            }

            if (!force && synchronized(this) { lastAttempt == attempt }) return emptyList()

            Log.d(TAG, "restore: ${requests.size} package(s) for $moduleDir")

            val result = killableTool { cancellation ->
                sdk.nuget.restore(requests, exclude) { cancellation.isCancelled }
            }

            result.problems.forEach { log.warn(it) }
            synchronized(this) { lastAttempt = attempt }

            Log.d(
                TAG,
                "restore: done, ${result.dlls.size} dll(s), ${result.problems.size} problem(s)",
            )

            val name = moduleDir.fileName?.toString() ?: moduleDir.toString()

            if (result.problems.isEmpty()) {
                val hadFailed = failures.remove(key(moduleDir)) != null
                if (hadFailed && notify) {
                    messages?.info("NuGet packages restored for $name")
                }
            } else {
                failures[key(moduleDir)] = result.problems
                if (notify) {
                    messages?.warn(RestoreNotice.failure(name, result.problems))
                }
            }

            // Recheck open files because the editor analyzed them before the packages arrived.
            CsWorkspace.reanalyzeOpenFiles()

            return result.problems
        }
    }

    private fun key(moduleDir: Path) =
        moduleDir.toAbsolutePath().normalize().toString()
}