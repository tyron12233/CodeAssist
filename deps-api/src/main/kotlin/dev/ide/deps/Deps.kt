package dev.ide.deps

import dev.ide.model.Coordinate
import dev.ide.platform.ProgressReporter
import dev.ide.vfs.VirtualFile

/**
 * deps-api — resolve `group:name:version` coordinates into concrete jars/aars (with transitives and
 * conflict resolution). Output feeds the model's LibraryTable; the resolved classpath then flows
 * into ClasspathSnapshot and on into both the build and the language backends.
 *
 * On-device realities: read Maven .pom metadata to walk transitives; newest-wins by default; extract
 * .aar (classes.jar + res + manifest); cache under .platform/caches/resolved-deps with an LRU bound;
 * resolve offline from cache when possible.
 */
interface DependencyResolver {
    /**
     * Resolve [coordinates] into their transitive closure.
     *
     * [platforms] are BOM coordinates (Maven `pom`-packaged "bill of materials") imported for their
     * `dependencyManagement` only — the Gradle `platform(...)` semantics. They contribute no artifact;
     * they supply versions to any [coordinates] (or transitive dependency) declared *without* a version
     * (a blank [Coordinate.version]). Plain-platform semantics: a BOM never overrides a version that was
     * stated explicitly — it only fills the blanks. Earlier platforms win when two manage the same artifact.
     */
    suspend fun resolve(
        coordinates: List<Coordinate>,
        repositories: List<Repository>,
        conflict: ConflictPolicy = ConflictPolicy.NEWEST,
        progress: ProgressReporter,
        platforms: List<Coordinate> = emptyList(),
    ): ResolutionResult
}

data class Repository(val name: String, val url: String)

enum class ConflictPolicy { NEWEST, FAIL_ON_CONFLICT, PINNED }

data class ResolutionResult(
    val resolved: List<ResolvedArtifact>,
    val unresolved: List<Coordinate>,
    val conflicts: List<VersionConflict>,
)

data class ResolvedArtifact(
    val coordinate: Coordinate,
    val kind: ArtifactKind,
    val classesRoot: VirtualFile,            // jar, or classes.jar extracted from an aar
    val sourcesRoot: VirtualFile? = null,
    val dependsOn: List<Coordinate> = emptyList(),
)

enum class ArtifactKind { JAR, AAR }

data class VersionConflict(val coordinate: String, val requested: List<String>, val chosen: String)
