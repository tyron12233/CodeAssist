package dev.ide.model

import dev.ide.platform.ContentHash
import dev.ide.platform.ServiceKey
import dev.ide.vfs.VirtualFile

/**
 * project-model-api — the spine of the framework. Everything (build, language backends, indexing,
 * navigation, refactoring) reads from this model.
 *
 * Design rules: abstract (no Android/Gradle knowledge in core types), extensible ([ModuleType],
 * [Facet] are open), observable (changes broadcast on the message bus), and safely mutable
 * (structural edits go through a [ProjectModelTransaction]).
 */

// ---------------------------------------------------------------------------
// Identity & shared value types
// ---------------------------------------------------------------------------

@JvmInline value class ProjectId(val value: String)
@JvmInline value class ModuleId(val value: String)
@JvmInline value class VariantId(val value: String)

/** Which build system owns a [Project]. The binding is on the Project so linked projects can mix systems. */
@JvmInline value class BuildSystemId(val value: String) {
    companion object {
        val NATIVE = BuildSystemId("native")
        val GRADLE_COMPAT = BuildSystemId("gradle-compat")
    }
}

/** A Maven-style artifact identity. Fundamental enough to live in the model; deps-api reuses it. */
data class Coordinate(val group: String, val name: String, val version: String) {
    override fun toString() = "$group:$name:$version"
}

enum class LanguageLevel { JAVA_8, JAVA_11, JAVA_17, JAVA_21 }

// ---------------------------------------------------------------------------
// Containment hierarchy: Workspace > Project > Module > SourceSet > ContentRoot
// ---------------------------------------------------------------------------

/** Top container the IDE has open. Owns the open projects, the project graph, and shared tables. */
interface Workspace {
    val projects: List<Project>
    val libraryTable: LibraryTable          // workspace-scoped, shared libraries
    val sdkTable: SdkTable
    fun <T : Any> service(key: ServiceKey<T>): T
    fun beginModification(): WorkspaceTransaction
}

/** A buildable unit bound to ONE build system. Maps to a Gradle "build" / root project. */
interface Project {
    val id: ProjectId
    val name: String
    val rootDir: VirtualFile
    val buildSystemId: BuildSystemId
    val modules: List<Module>
    val variants: List<Variant>
    val settings: ProjectSettings
    val libraryTable: LibraryTable          // project-scoped libraries
    fun beginModification(): ProjectModelTransaction
}

/** Unit of compilation (a Gradle "subproject"). NOT a JPMS module — that is a JavaFacet concern. */
interface Module {
    val id: ModuleId
    val name: String
    val type: ModuleType
    val languageLevel: LanguageLevel
    val sourceSets: List<SourceSet>
    val dependencies: List<OrderEntry>      // ordered; order matters for classpath search
    val facets: FacetContainer
    val outputDir: VirtualFile

    /** Assemble the classpath for a scope, enforcing api/implementation export rules (see ClasspathSnapshot). */
    fun classpath(scope: DependencyScope, transitive: Boolean = true): ClasspathSnapshot
}

interface SourceSet {
    val name: String                        // "main", "test", "debug", ...
    val scope: DependencyScope
    val contentRoots: List<ContentRoot>
}

interface ContentRoot {
    val dir: VirtualFile
    val roles: Set<ContentRole>
}

enum class ContentRole { SOURCE, RESOURCE, ANDROID_RES, AIDL, ASSETS, GENERATED, EXCLUDED }

interface ProjectSettings {
    fun get(key: String): String?
    val all: Map<String, String>
}

// ---------------------------------------------------------------------------
// Module types (extension point) and variants
// ---------------------------------------------------------------------------

/** Extensible, not an enum: android-support contributes android-app/android-lib, java-support java-lib/java-cli. */
interface ModuleType {
    val id: String                          // "android-app", "java-lib", ...
    val displayName: String
    fun defaultSourceSets(): List<SourceSetTemplate>
    fun defaultFacets(): List<FacetTemplate>
    fun supportedBuildSystems(): Set<BuildSystemId>
}

data class SourceSetTemplate(val name: String, val scope: DependencyScope, val roots: Map<String, Set<ContentRole>>)
data class FacetTemplate(val key: FacetKey<*>, val defaults: Map<String, Any?>)

/** A resolved build configuration: for Android, the cross-product of build types and flavors. */
interface Variant {
    val id: VariantId
    val name: String                        // "freeDebug"
    val activeSourceSets: List<SourceSet>
    fun resolvedScopes(): Set<DependencyScope>
}

// ---------------------------------------------------------------------------
// Facets: domain-specific config attached to a module without the core knowing the domain
// ---------------------------------------------------------------------------

/** Typed key for looking a facet up. AndroidFacet/JavaFacet are provided by their plugins, not core. */
class FacetKey<T : Facet>(val id: String)

interface Facet {
    val key: FacetKey<*>
}

interface FacetContainer {
    fun <T : Facet> get(key: FacetKey<T>): T?
    val all: List<Facet>
}

// ---------------------------------------------------------------------------
// Dependencies / order entries  (where api vs implementation is defined)
// ---------------------------------------------------------------------------

sealed interface OrderEntry {
    val scope: DependencyScope
    /** true == Gradle `api` semantics: visible to downstream modules' compile classpath. */
    val exported: Boolean
}

data class ModuleDependency(
    val target: ModuleId,
    override val scope: DependencyScope,
    override val exported: Boolean = false,
) : OrderEntry

data class LibraryDependency(
    val library: LibraryRef,
    override val scope: DependencyScope,
    override val exported: Boolean = false,
) : OrderEntry

data class SdkDependency(
    val sdk: SdkRef,
    override val scope: DependencyScope = DependencyScope.COMPILE_ONLY,
) : OrderEntry {
    override val exported: Boolean get() = false
}

/**
 * A Maven BOM ("bill of materials") imported for its `dependencyManagement` only — the Gradle
 * `platform(...)` semantics. Contributes NO classpath artifact; it is a version source that fills in
 * the version for any versionless [LibraryDependency] when its closure is resolved. Held in the model
 * (and persisted) so the IDE knows which BOMs constrain a module's versionless dependencies.
 */
data class PlatformDependency(
    val bom: Coordinate,
    override val scope: DependencyScope = DependencyScope.IMPLEMENTATION,
    override val exported: Boolean = false,
) : OrderEntry

enum class DependencyScope(val onCompile: Boolean, val onRuntime: Boolean, val onTest: Boolean) {
    API(true, true, true),
    IMPLEMENTATION(true, true, true),
    COMPILE_ONLY(true, false, true),
    RUNTIME_ONLY(false, true, true),
    TEST_IMPLEMENTATION(false, false, true),
}

// ---------------------------------------------------------------------------
// Classpath assembly result
// ---------------------------------------------------------------------------

/**
 * A deduplicated, ordered, content-hashed classpath. The [fingerprint] is a build-cache key input
 * AND a language-backend cache key, so a classpath change correctly invalidates both compilation
 * and editor analysis. Built by walking [OrderEntry]s and propagating only `exported` (api) entries.
 */
interface ClasspathSnapshot {
    val entries: List<ClasspathEntry>
    fun fingerprint(): ContentHash
}

data class ClasspathEntry(val root: VirtualFile, val kind: ClasspathEntryKind)
enum class ClasspathEntryKind { MODULE_OUTPUT, LIBRARY, SDK_BOOTCLASSPATH }

// ---------------------------------------------------------------------------
// Library & SDK tables (interned, referenced by name)
// ---------------------------------------------------------------------------

@JvmInline value class LibraryRef(val name: String)
@JvmInline value class SdkRef(val name: String)

interface LibraryTable {
    val libraries: List<Library>
    fun byName(name: String): Library?
    fun create(name: String): ModifiableLibrary
}

interface Library {
    val name: String                        // e.g. "com.squareup.okhttp3:okhttp:4.12.0"
    val kind: LibraryKind
    val classesRoots: List<VirtualFile>
    val sourcesRoots: List<VirtualFile>
}

enum class LibraryKind { JAR, AAR }

interface ModifiableLibrary {
    var kind: LibraryKind
    fun addClassesRoot(root: VirtualFile)
    fun addSourcesRoot(root: VirtualFile)
    fun commit(): Library
}

interface SdkTable {
    val sdks: List<Sdk>
    fun byName(name: String): Sdk?
}

interface Sdk {
    val name: String                        // "android-34", "jdk-17"
    val bootClasspath: List<VirtualFile>    // android.jar / JDK rt
    val buildToolsPath: VirtualFile?
}

// ---------------------------------------------------------------------------
// Mutation: modifiable-model transaction (stage, then commit atomically under the write lock)
// ---------------------------------------------------------------------------

interface WorkspaceTransaction {
    fun addProject(name: String, buildSystem: BuildSystemId, rootDir: VirtualFile): Project
    fun removeProject(id: ProjectId)
    fun commit()
    fun dispose()
}

interface ProjectModelTransaction {
    fun addModule(name: String, type: ModuleType): ModifiableModule
    fun removeModule(id: ModuleId)
    fun module(id: ModuleId): ModifiableModule
    /** Atomic: swaps in a new snapshot and publishes typed events on the message bus. */
    fun commit()
    fun dispose()
}

interface ModifiableModule {
    var languageLevel: LanguageLevel
    fun addDependency(entry: OrderEntry)
    fun removeDependency(entry: OrderEntry)
    fun addSourceSet(template: SourceSetTemplate)
    fun <T : Facet> putFacet(facet: T)
}
