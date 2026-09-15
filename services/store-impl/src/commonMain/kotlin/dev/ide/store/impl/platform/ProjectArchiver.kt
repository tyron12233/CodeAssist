package dev.ide.store.impl.platform

import dev.ide.store.PackagedProject
import dev.ide.store.StoreResult

/**
 * Zipping a project for submission.
 *
 * A port rather than a concrete class because writing a zip is the one thing in this module a host may
 * genuinely not have: `java.util.zip` supplies it on the JVM and ART, and iOS has no compressor in its
 * SDK that a Kotlin/Native target can reach without a cinterop of its own. An iOS build therefore reports
 * publishing as unavailable, which the UI already draws (`StoreService.submissionsAvailable`) — rather
 * than offering a publish button that fails at the last step.
 */
interface ProjectArchiver {

    /** Whether this build can package a project at all. */
    fun available(): Boolean = true

    /** Zip the project rooted at [rootPath], to [intoPath] or to a temporary file. */
    fun pack(rootPath: String, intoPath: String? = null): StoreResult<PackagedProject>

    companion object {
        val Unsupported: ProjectArchiver = object : ProjectArchiver {
            override fun available() = false
            override fun pack(rootPath: String, intoPath: String?): StoreResult<PackagedProject> =
                StoreResult.Failed("Publishing a project is not supported on this platform")
        }
    }
}

/** The archiver this platform has: the real packager on the JVM, [ProjectArchiver.Unsupported] on iOS. */
internal expect fun defaultArchiver(): ProjectArchiver
