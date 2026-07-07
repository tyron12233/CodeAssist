package dev.ide.model

import dev.ide.platform.ContentHash
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceScope
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

    /**
     * Assemble the classpath for a scope, enforcing api/implementation export rules (see ClasspathSnapshot).
     * [variant] is the set of active build-variant config names (e.g. `{main, free, debug, freeDebug}`); a
     * `null` variant includes every entry (the build-variant-agnostic default), while a non-null set drops
     * any [OrderEntry] whose [OrderEntry.variant] qualifier isn't in it (a shared, unqualified entry always
     * stays). The same set filters the module-dependency closure.
     */
    fun classpath(scope: DependencyScope, transitive: Boolean = true, variant: Set<String>? = null): ClasspathSnapshot

    /** This module's MODULE-scoped service for [key], falling back to the workspace then application scope. */
    fun <T : Any> service(key: ServiceKey<T>): T
}

/** The bound [Workspace], resolvable from a workspace- or module-scoped service factory. */
val WORKSPACE_SERVICE = ServiceKey<Workspace>("model.workspace")

/** The [Module] a MODULE-scoped service factory is bound to. */
fun ServiceScope.module(): Module =
    scopeObject as? Module ?: error("module() is only valid in a MODULE-scoped service (scope=$level)")

/** The [Workspace] above a workspace- or module-scoped service factory. */
fun ServiceScope.workspace(): Workspace = getService(WORKSPACE_SERVICE)

interface SourceSet {
    val name: String                        // "main", "test", "debug", ...
    val scope: DependencyScope
    val contentRoots: List<ContentRoot>
}

interface ContentRoot {
    val dir: VirtualFile
    val roles: Set<ContentRole>
}

enum class ContentRole {
    SOURCE,
    /** Java/JVM resources (`src/<set>/resources`) — non-code files packaged into the jar/APK root. */
    RESOURCE,
    ANDROID_RES,
    AIDL,
    ASSETS,
    /** Prebuilt native libraries (`src/<set>/jniLibs`), laid out `<abi>/lib*.so`, packaged under `lib/`. */
    JNI_LIBS,
    GENERATED,
    EXCLUDED,
}

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

    /**
     * The dependency-config names active in this variant: the candidate source-set names — `{main, each
     * flavor, the combined-flavor name, the build type, the variant name}`. This is the set passed as the
     * `variant` filter to [Module.classpath]: an [OrderEntry] (or source set) qualified by one of these
     * names belongs to the variant. Generic so `project-model-impl` can filter without knowing Android.
     */
    val configurations: Set<String> get() = emptySet()
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
    /**
     * Build-variant config name this entry is scoped to — the Gradle `debugImplementation` /
     * `freeImplementation` semantics (the config name is a build type, a flavor, or a full variant name).
     * `null` == shared: present in every variant. [Module.classpath] keeps an entry iff its variant
     * filter is `null`, or this is `null`, or this is in the active config-name set.
     */
    val variant: String? get() = null
}

data class ModuleDependency(
    val target: ModuleId,
    override val scope: DependencyScope,
    override val exported: Boolean = false,
    override val variant: String? = null,
) : OrderEntry

data class LibraryDependency(
    val library: LibraryRef,
    override val scope: DependencyScope,
    override val exported: Boolean = false,
    /**
     * Transitive dependencies to drop from this declaration's closure — the Gradle
     * `exclude group:…, module:…` / Maven `<exclusions>` semantics. Applied per declaration: a transitive
     * excluded here can still arrive through another declaration that doesn't exclude it. Empty by default.
     */
    val exclusions: List<Exclusion> = emptyList(),
    override val variant: String? = null,
) : OrderEntry

/**
 * A transitive dependency to exclude from a [LibraryDependency]'s closure, matched by `group:name`. Either
 * field may be the wildcard `"*"` (e.g. `Exclusion("com.google.guava", "*")` drops every guava artifact;
 * `Exclusion("*", "*")` drops all transitives, leaving only the declared artifact).
 */
data class Exclusion(val group: String, val name: String) {
    override fun toString(): String = "$group:$name"

    companion object {
        /** Parse a `group:name` exclusion string (either side may be `*`). Null if it isn't two colon parts. */
        fun parse(s: String): Exclusion? =
            s.split(":").map { it.trim() }.takeIf { it.size == 2 && it.none(String::isEmpty) }
                ?.let { Exclusion(it[0], it[1]) }
    }
}

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
    override val variant: String? = null,
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
    /** Append a typed content root to the [sourceSetName] source set (creating the set if it doesn't
     *  exist). [dirRelPath] is relative to the module dir; re-adding the same dir merges [roles]. */
    fun addContentRoot(sourceSetName: String, dirRelPath: String, roles: Set<ContentRole>)
    /** Drop the content root at [dirRelPath] from [sourceSetName] (model-only; doesn't touch disk). */
    fun removeContentRoot(sourceSetName: String, dirRelPath: String)
    fun <T : Facet> putFacet(facet: T)
}
