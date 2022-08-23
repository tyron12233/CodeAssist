package com.tyron.builder.dexing

import com.android.tools.r8.DesugarGraphConsumer
import com.android.tools.r8.origin.ArchiveEntryOrigin
import com.android.tools.r8.origin.Origin
import com.android.tools.r8.origin.PathOrigin
import java.io.File

/** Adapter from [DependencyGraphUpdater] to [DesugarGraphConsumer]. */
class D8DesugarGraphConsumerAdapter(private val desugarGraphUpdater: DependencyGraphUpdater<File>) :
    DesugarGraphConsumer {

    override fun accept(dependent: Origin, dependency: Origin) {
        check(dependent != dependency) { "Can't add an edge from a node to itself: $dependent" }

        val dependentFile = originToFile(dependent)
        val dependencyFile = originToFile(dependency)
        // Compare paths as lint doesn't allow comparing File objects using `equals`
        if (dependentFile.path != dependencyFile.path) {
            desugarGraphUpdater.addEdge(dependentFile, dependencyFile)
        }
    }

    /**
     * Returns the path to the regular file if the given [Origin] points to a regular file, or the
     * containing jar if the given [Origin] points to a jar entry.
     */
    private fun originToFile(origin: Origin): File {
        // This is the reverse of D8DiagnosticsHandler.getOrigin(ClassFileEntry)
        return when (origin) {
            is PathOrigin -> File(origin.part())
            is ArchiveEntryOrigin -> File(origin.parent().part())
            else -> error("Unexpected type ${origin.javaClass}")
        }
    }

    override fun finished() {
        // Nothing to do here
    }
}