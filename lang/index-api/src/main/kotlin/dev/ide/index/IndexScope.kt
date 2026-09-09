package dev.ide.index

import java.nio.file.Path

/** What to (re)index: project source roots + the classpath/SDK backing the workspace. */
data class IndexScope(
    val sourceRoots: List<Path> = emptyList(),
    val libraryJars: List<Path> = emptyList(),
    val jdkHome: Path? = null,
    /** The project's OWN (+ dependency-module) `res/` roots — walked for `.xml` resource files into the resident,
     *  edit-sensitive source side. Immutable dependency/AAR res goes in [libraryResourceRoots] instead. */
    val resourceRoots: List<Path> = emptyList(),
    /** Immutable dependency/AAR `res/` dirs — content-addressed onto disk segments (parsed once, shared across
     *  projects, read on demand) rather than held resident like [resourceRoots]. A Material/AndroidX resource
     *  set is hundreds of files / a multi-hundred-KB merged `values.xml`, so keeping it off the heap matters. */
    val libraryResourceRoots: List<Path> = emptyList(),
    /** Attached SOURCE archives/dirs (`-sources.jar`, JDK `src.zip`, Android `sources/`) — walked for `.java`/
     *  `.kt`, fed as [IndexOrigin.LIBRARY_SOURCE] units (the source-doc index: real param names + javadoc/KDoc). */
    val sourceArchives: List<Path> = emptyList(),
    /** Stable, PATH- and MTIME-independent content keys for designated [libraryJars] (keyed by the same [Path]).
     *  A jar normally keys its segment by `path + size + mtime`; for a bundled/SDK jar the host re-extracts from
     *  its assets, the on-disk mtime is not stable across launches, so that re-keys it every launch and it is
     *  re-indexed from scratch (android.jar = ~90% of all entries). A value here replaces the whole key with a
     *  caller-chosen stable id (e.g. `android.jar-<size>`), so the segment is reused across launches AND can
     *  match a prebuilt segment shipped under the same id (the device path differs from the build host's, so the
     *  key must not encode the path). Jars absent from this map keep the default `path+size+mtime` key. */
    val stableJarIds: Map<Path, String> = emptyMap(),
)
