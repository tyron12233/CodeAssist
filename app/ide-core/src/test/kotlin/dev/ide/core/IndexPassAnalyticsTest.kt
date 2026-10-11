package dev.ide.core

import dev.ide.index.IndexBuildStats
import dev.ide.index.IndexStatus
import dev.ide.index.IndexerStat
import dev.ide.index.impl.IndexServiceImpl.BuiltArtifact
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The `index_perf` event must say what kind of artifact a pass had to rebuild, without a file name or path:
 * the analytics policy promises no project names, and a jar under the project is named after it.
 */
class IndexPassAnalyticsTest {

    private val project = Paths.get("/data/projects/MyApp")
    private val caches = Paths.get("/data/home/.codeassist")

    private fun jar(path: String, kind: BuiltArtifact.Kind = BuiltArtifact.Kind.JAR, rekeyed: Boolean = false) =
        BuiltArtifact(kind, Paths.get(path), rekeyed, 10)

    @Test
    fun aJarIsClassifiedByWhereItLives() {
        assertEquals("jar.project_build", builtKind(jar("/data/projects/MyApp/lib/build/outputs/classes.jar"), project, caches))
        assertEquals("jar.project", builtKind(jar("/data/projects/MyApp/app/libs/vendor.jar"), project, caches))
        assertEquals("jar.cache", builtKind(jar("/data/home/.codeassist/caches/maven/a/b/1.0/b-1.0.jar"), project, caches))
        assertEquals("jar.other", builtKind(jar("/sdcard/Download/x.jar"), project, caches))
        // A sibling project whose name starts with this one's is not inside it.
        assertEquals("jar.other", builtKind(jar("/data/projects/MyAppOld/build/x.jar"), project, caches))
    }

    @Test
    fun nonJarArtifactsKeepTheirOwnKind() {
        assertEquals("bundled", builtKind(jar("/data/app/files/android.jar", BuiltArtifact.Kind.STABLE_JAR), project, caches))
        assertEquals("jdk", builtKind(jar("/data/app/jdk", BuiltArtifact.Kind.JDK), project, caches))
        assertEquals("sources", builtKind(jar("/data/home/.codeassist/src.zip", BuiltArtifact.Kind.SOURCE_ARCHIVE), project, caches))
        assertEquals("res.cache", builtKind(jar("/data/home/.codeassist/caches/aar/x/res", BuiltArtifact.Kind.RESOURCE_DIR), project, caches))
        assertEquals("res.project", builtKind(jar("/data/projects/MyApp/lib/src/main/res", BuiltArtifact.Kind.RESOURCE_DIR), project, caches))
    }

    @Test
    fun withoutASharedCachesRootNothingIsCountedAsCache() {
        assertEquals("jar.other", builtKind(jar("/data/home/.codeassist/caches/maven/b.jar"), project, null))
    }

    @Test
    fun theEventCarriesTriggerTimingsAndKindsButNoPath() {
        val pass = IndexPassReport(
            trigger = IndexPassTrigger.MODEL,
            durationMs = 1200,
            scopeMs = 40,
            queuedMs = 300,
            status = IndexStatus(
                building = false, message = "Indexed", fraction = 1.0, ready = true,
                breakdown = listOf(IndexerStat("java.classNames", 800, 1000), IndexerStat("java.packages", 0, 5)),
                stats = IndexBuildStats(libMs = 1100, sourceMs = 90, artifacts = 150, artifactsBuilt = 1, artifactsReused = 149),
            ),
            ioConcurrency = 8,
            buildPermits = 2,
            built = mapOf("jar.project_build" to 1),
            rekeyed = mapOf("jar.project_build" to 1),
            libraryChanged = true,
            coalesced = 4,
        )
        val props = indexPerfProps(pass)
        assertEquals("model", props["trigger"])
        assertEquals("1", props["lib_changed"])
        assertEquals("4", props["coalesced"])
        assertEquals("1200", props["duration_ms"])
        assertEquals("40", props["scope_ms"])
        assertEquals("300", props["queued_ms"])
        assertEquals("1", props["artifacts_built"])
        assertEquals("8", props["par_io"])
        assertEquals("2", props["par_build"])
        assertEquals("1", props["built.jar.project_build"])
        assertEquals("1", props["rekeyed.jar.project_build"])
        assertEquals("800", props["idx.java.classNames.ms"])
        // Sub-millisecond indexers are left out to keep the row bounded.
        assertNull(props["idx.java.packages.ms"])
        assertFalse(props.values.any { it.contains('/') }, "no prop may carry a path: $props")
    }

    /** A pass that failed or timed out ends on a status without stats; the row still says why it ran. */
    @Test
    fun aFailedPassStillReportsItsTriggerAndDuration() {
        val pass = IndexPassReport(
            trigger = IndexPassTrigger.OPEN, durationMs = 900_000, scopeMs = 5, queuedMs = 0,
            status = IndexStatus(false, "Indexing timed out", 1.0),
            ioConcurrency = null, buildPermits = null, built = emptyMap(), rekeyed = emptyMap(),
        )
        val props = indexPerfProps(pass)
        assertEquals("open", props["trigger"])
        assertEquals("900000", props["duration_ms"])
        assertNull(props["artifacts"])
        assertNull(props["par_io"])
        assertNull(props["lib_changed"], "an unfinished pass does not claim either way")
        assertNull(props["coalesced"], "zero is left out")
    }
}
