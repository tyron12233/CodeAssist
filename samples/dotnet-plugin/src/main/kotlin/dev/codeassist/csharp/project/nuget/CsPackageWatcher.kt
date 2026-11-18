package dev.codeassist.csharp.project.nuget

import android.util.Log
import dev.codeassist.csharp.editor.CsWorkspace
import dev.codeassist.csharp.project.csproj.Csproj
import dev.codeassist.csharp.util.TAG
import dev.ide.platform.MessageBus
import dev.ide.vfs.FileMoved
import dev.ide.vfs.VfsEvent
import dev.ide.vfs.VfsListener
import dev.ide.vfs.VfsTopics
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Restores a project's packages when its `.csproj` file is saved, keeping the package cache
 * warm before the editor next requests diagnostics.
 *
 * The IDE publishes editor saves through [VfsTopics.CHANGES].
 *
 * Saving a file does not by itself make the host rerun editor
 * diagnostics. Squiggles still wait for another trigger, such as an edit, tab switch, or build.
 * This watcher avoids the package-restoration delay when that check eventually runs.
 *
 * When the host provides a workspace through [CsWorkspace], open files are also requested
 * for reanalysis after every save.
 */
internal class CsPackageWatcher(
    bus: MessageBus,
    private val restorer: CsPackageRestorer,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One pending restore per project directory. A newer save replaces the previous job. */
    private val pending = ConcurrentHashMap<Path, Job>()

    private val connection = bus.connect().also {
        it.subscribe(VfsTopics.CHANGES, VfsListener { events -> onEvents(events) })
    }

    // Lifecycle

    override fun close() {
        connection.dispose()
        scope.cancel()
    }

    // Event handling

    private fun onEvents(events: List<VfsEvent>) {
        // Keep the publisher thread (the editor's save path) free of restoration work.
        // Only identify affected projects and schedule work in the coroutine scope.
        for (event in events) {
            val path = if (event is FileMoved) event.to else event.file.path
            val dir = Csproj.dirOfProjectFile(path) ?: continue

            pending.remove(dir)?.cancel()
            pending[dir] = scope.launch {
                // Coalesce bursts of saves, such as autosave, rename, and write operations.
                delay(DEBOUNCE_MS)

                val project = Csproj.load(dir)
                if (project != null) {
                    Log.d(TAG, "watcher: ${project.packageReferences.size} package ref(s) in $dir")
                    restorer.restore(project, dir)
                }

                // A saved project can change the meaning of every open file through its
                // usings, defines, nullable settings, and references. Reanalyze even if
                // the project could no longer be loaded.
                //
                // The restorer only requests reanalysis after a restore that actually ran.
                CsWorkspace.reanalyzeOpenFiles()
            }
        }
    }

    companion object {

        /** Delay used to combine closely spaced project-file saves into one restore. */
        const val DEBOUNCE_MS = 500L
    }
}