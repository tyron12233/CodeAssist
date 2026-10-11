package dev.ide.core

import dev.ide.index.IndexStatus
import dev.ide.index.impl.IndexServiceImpl.BuiltArtifact
import java.nio.file.Path

/** Why a full index pass ran. Sent as `index_perf.trigger`, so the fleet shows which changes cause passes. */
internal object IndexPassTrigger {
    /** The project was opened. */
    const val OPEN = "open"

    /** The user asked for a from-scratch rebuild. */
    const val REINDEX = "reindex"

    /** A project-model commit. */
    const val MODEL = "model"

    /** Resolved libraries changed outside a model commit (a finished dependency resolution, an SDK install). */
    const val LIBRARIES = "libraries"

    /** The active build variant changed. */
    const val VARIANT = "variant"

    /** A file event that can change the classpath: a non-source file created, or anything deleted or moved. */
    const val FILES = "files"

    /** A module edit through the engine context (module settings, facets, dependencies). */
    const val MODULE = "module"
}

/**
 * One finished full index pass, published by [IdeServices.indexPasses] for the `index_perf` analytics. Every
 * pass is reported, unlike sampling the conflated status flow, which skipped passes that finished between two
 * collections.
 *
 * Privacy: times, counts and coarse kinds only. [built] and [rekeyed] count artifacts by [builtKind], never by
 * name or path.
 */
class IndexPassReport(
    /** One of the [IndexPassTrigger] values. */
    val trigger: String,
    /** Wall time of the pass itself (`ensureUpToDate`, plus the wipe for a re-index). */
    val durationMs: Long,
    /** Time spent assembling the index scope before the pass (it asks every module's analyzer for roots). */
    val scopeMs: Long,
    /** Time this pass waited for an earlier one to finish. */
    val queuedMs: Long,
    /** The index status the pass ended on: its stats and per-extension breakdown, or a failure. */
    val status: IndexStatus,
    /** The artifact-phase parallelism, when the pass ran to the end. */
    val ioConcurrency: Int?,
    val buildPermits: Int?,
    /** Artifacts rebuilt this pass, counted by [builtKind]. */
    val built: Map<String, Int>,
    /** Of [built], those rebuilt under a different key than earlier this session (a cache that cannot hit). */
    val rekeyed: Map<String, Int>,
    /** False for a quiet pass (classpath unchanged, only the source side updated); null if it did not finish. */
    val libraryChanged: Boolean? = null,
    /** Resync requests that joined this pass instead of queuing their own. */
    val coalesced: Int = 0,
)

/**
 * A coarse, path-free kind for a rebuilt artifact: enough to tell a build output that is rewritten on every
 * build from a Maven jar or a bundled SDK jar, without sending a file name.
 *
 * Jars and resource directories are split by where they live: under the project (and, for a jar, whether that
 * is a `build` output directory), under the shared caches root (Maven downloads, exploded AARs), or elsewhere.
 */
internal fun builtKind(artifact: BuiltArtifact, projectRoot: Path, cachesRoot: Path?): String {
    val path = artifact.path.toAbsolutePath().normalize()
    val inProject = path.startsWith(projectRoot.toAbsolutePath().normalize())
    val inCaches = cachesRoot != null && path.startsWith(cachesRoot.toAbsolutePath().normalize())
    val where = when {
        inProject -> "project"
        inCaches -> "cache"
        else -> "other"
    }
    return when (artifact.kind) {
        BuiltArtifact.Kind.JDK -> "jdk"
        BuiltArtifact.Kind.STABLE_JAR -> "bundled"
        BuiltArtifact.Kind.SOURCE_ARCHIVE -> "sources"
        BuiltArtifact.Kind.RESOURCE_DIR -> "res.$where"
        BuiltArtifact.Kind.JAR -> {
            val underBuildDir = inProject &&
                projectRoot.toAbsolutePath().normalize().relativize(path).any { it.toString() == "build" }
            if (underBuildDir) "jar.project_build" else "jar.$where"
        }
    }
}
