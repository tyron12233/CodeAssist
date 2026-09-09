package dev.ide.lang

import java.nio.file.Path

/**
 * The roots a JVM [SourceAnalyzer] contributes to the workspace index, exposed as a backend-neutral
 * capability so the index scope is decoupled from any one Java backend (Eclipse JDT vs the IntelliJ-PSI
 * backend). The host (`ide-core`'s `buildIndexScope`) reads these off every analyzer that implements this
 * interface, rather than reaching into a concrete analyzer type — so swapping the `.java` editor backend
 * does not starve the index that completion / go-to-symbol / Kotlin interop all depend on.
 *
 * Paths, not [dev.ide.vfs.VirtualFile]s: these feed the on-disk index segments + the JDK/library scan, which
 * are `java.nio` based. A backend that can't supply a given root returns an empty list / null.
 */
interface JvmIndexScopeProvider {
    /** Library jars on the module's classpath (the artifacts to scan into class-name/member segments). */
    val classpathJarPaths: List<Path>

    /** Project source roots (this module + dependencies) — the resident, editable source index side. */
    val sourceRootPaths: List<Path>

    /** The JDK home whose platform classes back `java.*`, or null on a platform (Android) that has none. */
    val jdkHome: Path?

    /** Attached library/SDK SOURCE archives + dirs (`-sources.jar`, JDK `src.zip`, Android `sources/`) that
     *  feed the source-doc index (real parameter names + javadoc). No project source. */
    val librarySourceArchives: List<Path>
}
