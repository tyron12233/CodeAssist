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

@JvmInline
value class ProjectId(val value: String)

@JvmInline
value class ModuleId(val value: String)

@JvmInline
value class VariantId(val value: String)

/** Which build system owns a [Project]. The binding is on the Project so linked projects can mix systems. */
@JvmInline
value class BuildSystemId(val value: String) {
    companion object {
        val NATIVE = BuildSystemId("native")
        val GRADLE_COMPAT = BuildSystemId("gradle-compat")
    }
}

/** A Maven-style artifact identity. Fundamental enough to live in the model; deps-api reuses it. */
data class Coordinate(val group: String, val name: String, val version: String) {
    override fun toString() = "$group:$name:$version"
}

/**
 * Strip the characters a `group:name:version` can never legally contain: whitespace (ordinary or
 * non-breaking) and every zero-width, bidi-mark, soft-hyphen [CharCategory.FORMAT] or control character.
 *
 * These arrive by paste. Documentation sites put `U+200B ZERO WIDTH SPACE` into their code blocks as
 * line-break hints, so copying a coordinate off a web page can yield
 * `"\u200Bandroidx.lifecycle:lifecycle-process:2.11.0"`, indistinguishable on screen from the real thing.
 * `String.trim()` does not touch it, since U+200B is not whitespace. Left in, the invisible character
 * reaches the artifact URL, the repository answers 404, and the dependency is reported unresolved
 * against a coordinate that looks perfectly correct. The same corruption inside the VERSION can be worse
 * than a clean failure: on emulated external storage a path lookup ignores zero-width characters, so the
 * cache probe hits an already-downloaded clean artifact and the coordinate resolves to something nothing
 * declared.
 *
 * Normalizing both on the way in (a new declaration) and on the way out of `module.toml` (load) also
 * heals a project that already persisted one.
 */
fun sanitizeCoordinate(raw: String): String = raw.filterNot { it.isInvisibleInCoordinate() }

/** [sanitizeCoordinate] over an already-split coordinate. */
fun sanitizeCoordinate(raw: Coordinate): Coordinate = Coordinate(
    sanitizeCoordinate(raw.group),
    sanitizeCoordinate(raw.name),
    sanitizeCoordinate(raw.version),
)

/**
 * [sanitizeCoordinate] for a [LibraryRef.name], which is a coordinate only when it carries a `:`. The other
 * two forms are left exactly as they are: the bundled-library alias (`kotlin-stdlib`) and a local jar/aar's
 * file name, which is allowed to contain spaces.
 */
fun sanitizeLibraryName(name: String): String =
    if (':' in name) sanitizeCoordinate(name) else name

private fun Char.isInvisibleInCoordinate(): Boolean =
    isWhitespace() || category == CharCategory.FORMAT || category == CharCategory.CONTROL

/**
 * The source/target level a module compiles and is analyzed at.
 *
 * Open rather than an enum so a plugin for a language with its own versioning (a Python interpreter level, a
 * C++ standard) can name one: every [Module] carries a level, and a closed Java-only set forced such a module
 * to claim a Java version it has nothing to do with. [name] is the persisted spelling, so the built-ins keep
 * the `JAVA_17` form already in `module.toml`.
 *
 * [values] lists the built-ins (what the module-settings picker offers); [valueOf] is total, so a level a
 * plugin defined round-trips through persistence even when that plugin is not loaded.
 */
@JvmInline
value class LanguageLevel(val name: String) {
    override fun toString(): String = name

    /**
     * The Java version this level denotes (`JAVA_17` -> `17`). A level the JVM toolchain does not own (a
     * plugin's own language versioning) has no Java version of its own and reads as [DEFAULT]'s, so a build
     * or analysis path that must produce a `-source`/`-target` argument gets one javac accepts rather than a
     * string it rejects.
     */
    val javaVersion: Int get() = name.removePrefix("JAVA_").toIntOrNull() ?: 17

    companion object {
        val JAVA_8 = LanguageLevel("JAVA_8")
        val JAVA_11 = LanguageLevel("JAVA_11")
        val JAVA_17 = LanguageLevel("JAVA_17")
        val JAVA_21 = LanguageLevel("JAVA_21")

        /** The Java levels the IDE itself provides, in ascending order. */
        val entries: List<LanguageLevel> = listOf(JAVA_8, JAVA_11, JAVA_17, JAVA_21)

        /** Assumed when a module records no level, and the JVM reading of a level that names no Java version. */
        val DEFAULT: LanguageLevel = JAVA_17

        fun values(): List<LanguageLevel> = entries

        /** Total: an unrecognized [name] is a level some plugin owns, not a failure. */
        fun valueOf(name: String): LanguageLevel = LanguageLevel(name)
    }
}

// ---------------------------------------------------------------------------
// Containment hierarchy: Workspace > Project > Module > SourceSet > ContentRoot
// ---------------------------------------------------------------------------

/** Top container the IDE has open. Owns the open projects, the project graph, and shared tables. */
interface Workspace {
    val projects: List<Project>
    val libraryTable: LibraryTable          // workspace-scoped, shared libraries
    val sdkTable: SdkTable
    fun <T : Any> service(key: ServiceKey<T>): T

    /**
     * Like [service], but null when nothing up the scope chain defines [key], for an optional capability
     * whose absence is normal: a host that registered no implementation, or a standalone test with no
     * container behind it. The default answers null, so an implementation backed by a real
     * [dev.ide.platform.ServiceContainer] must override it (the IDE's does).
     */
    fun <T : Any> serviceOrNull(key: ServiceKey<T>): T? = null

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

    /**
     * Explicit platform-SDK override: an [SdkRef] into [Workspace.sdkTable]. `null` (the default) means
     * "resolve by [ModuleType.platform]" — pick the first workspace SDK whose [Sdk.kind] matches. Persisted
     * as `sdk = "<name>"` in the `[module]` table of `module.toml`. Use [SdkResolution.sdkFor] to resolve,
     * never read the table directly — that helper applies this precedence uniformly for build and editor.
     */
    val sdk: SdkRef? get() = null

    /**
     * The module's own directory. Intrinsic to a module whatever its language, unlike [outputDir], and the
     * thing to reach for when you want "where does this module live".
     *
     * It used to be recovered as `outputDir.parent.parent`, which happened to work only because the built-in
     * output convention is two levels deep (`<module>/build/classes`); a module that set any other output
     * path silently yielded the wrong directory.
     */
    val dir: VirtualFile

    val sourceSets: List<SourceSet>
    val dependencies: List<OrderEntry>      // ordered; order matters for classpath search
    val facets: FacetContainer

    /**
     * Where this module's compiled output lands, or null for a module whose toolchain produces none.
     *
     * The compiled-language reading of a module, so it is not something every module has: a plugin's
     * interpreted or header-only module answers null, and a build task that needs an output directory should
     * say so rather than assume one. For "where does this module live", use [dir].
     */
    val outputDir: VirtualFile? get() = null

    /**
     * Assemble the classpath for a scope, enforcing api/implementation export rules (see ClasspathSnapshot).
     * [variant] is the set of active build-variant config names (e.g. `{main, free, debug, freeDebug}`); a
     * `null` variant includes every entry (the build-variant-agnostic default), while a non-null set drops
     * any [OrderEntry] whose [OrderEntry.variant] qualifier isn't in it (a shared, unqualified entry always
     * stays). The same set filters the module-dependency closure.
     *
     * The classpath reading of [dependencies], which is the useful one for a JVM toolchain and empty for a
     * language that has no such notion. A backend for one of those reads [dependencies] itself, or is handed
     * what it needs by its own `CompilationContextProvider`; it does not have to answer this question.
     */
    fun classpath(
        scope: DependencyScope, transitive: Boolean = true, variant: Set<String>? = null
    ): ClasspathSnapshot = ClasspathSnapshot.EMPTY

    /** This module's MODULE-scoped service for [key], falling back to the workspace then application scope. */
    fun <T : Any> service(key: ServiceKey<T>): T
}

/** The bound [Workspace], resolvable from a workspace- or module-scoped service factory. */
val WORKSPACE_SERVICE = ServiceKey<Workspace>("model.workspace")

/** The [Module] a MODULE-scoped service factory is bound to. */
fun ServiceScope.module(): Module = scopeObject as? Module
    ?: error("module() is only valid in a MODULE-scoped service (scope=$level)")

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

/**
 * What a [ContentRoot] holds. Open rather than an enum: the built-in set is the JVM/Android one, and a plugin
 * for another language needs roles the core has never heard of (C++ headers, a Python typeshed) to model a
 * module's layout at all.
 *
 * [id] is the identity: two roles with the same id are the same role, and it is what a plugin should keep
 * stable. It is not necessarily the `module.toml` spelling: the built-ins persist under the Gradle
 * source-directory names (`SOURCE` is written as `java`) for compatibility, while a plugin role persists
 * under its [id]. Pick an id unlikely to collide with those names, since the on-disk key is a flat namespace.
 */
@JvmInline
value class ContentRole(val id: String) {
    override fun toString(): String = id

    companion object {
        val SOURCE = ContentRole("source")

        /** Java/JVM resources (`src/<set>/resources`): non-code files packaged into the jar/APK root. */
        val RESOURCE = ContentRole("resource")
        val ANDROID_RES = ContentRole("android-res")
        val AIDL = ContentRole("aidl")
        val ASSETS = ContentRole("assets")

        /** Prebuilt native libraries (`src/<set>/jniLibs`), laid out `<abi>/lib*.so`, packaged under `lib/`. */
        val JNI_LIBS = ContentRole("jni-libs")
        val GENERATED = ContentRole("generated")
        val EXCLUDED = ContentRole("excluded")

        /** The roles the IDE itself provides. A plugin's own roles are not listed here. */
        val entries: List<ContentRole> =
            listOf(SOURCE, RESOURCE, ANDROID_RES, AIDL, ASSETS, JNI_LIBS, GENERATED, EXCLUDED)

        fun values(): List<ContentRole> = entries
    }
}

interface ProjectSettings {
    fun get(key: String): String?
    val all: Map<String, String>
}

// ---------------------------------------------------------------------------
// Module types (extension point) and variants
// ---------------------------------------------------------------------------

/**
 * The platform (boot-classpath) family a module compiles/analyzes against: the JVM/core-Java platform
 * (console + library modules) or the Android SDK (android-app/-lib). A module resolves an [Sdk] of the
 * matching [Sdk.kind]; the two are kept apart so a plain Java/Kotlin module never sees `android.*` and an
 * Android module never sees a raw JDK. Gradle makes the same split by which plugin is applied.
 */
@JvmInline
value class PlatformKind(val name: String) {
    override fun toString(): String = name

    companion object {
        val JVM = PlatformKind("JVM")
        val ANDROID = PlatformKind("ANDROID")

        /** The platforms the IDE itself provides. A plugin's own platform is not listed here. */
        val entries: List<PlatformKind> = listOf(JVM, ANDROID)

        fun values(): List<PlatformKind> = entries

        /** Total: an unrecognized [name] is a platform some plugin owns. [name] is the persisted spelling. */
        fun valueOf(name: String): PlatformKind = PlatformKind(name)
    }
}

/** Extensible, not an enum: android-support contributes android-app/android-lib, java-support java-lib/java-cli. */
interface ModuleType {
    val id: String                          // "android-app", "java-lib", ...
    val displayName: String
    fun defaultSourceSets(): List<SourceSetTemplate>
    fun defaultFacets(): List<FacetTemplate>
    fun supportedBuildSystems(): Set<BuildSystemId>

    /**
     * The platform this module type compiles against when a module doesn't override it via [Module.sdk].
     * Derived from the id prefix (`android-*` → Android, everything else → JVM) so the existing types need
     * no change; a type may override this to declare its platform explicitly.
     */
    val platform: PlatformKind get() = if (id.startsWith("android")) PlatformKind.ANDROID else PlatformKind.JVM
}

data class SourceSetTemplate(
    val name: String, val scope: DependencyScope, val roots: Map<String, Set<ContentRole>>
)

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

/**
 * Which classpaths an [OrderEntry] lands on. Open rather than an enum so a plugin can declare a scope its own
 * toolchain needs (a C++ `linkOnly`, a Python `buildRequires`) instead of forcing every dependency through the
 * five Gradle-shaped ones.
 *
 * Identity is [name] alone, so two scopes agreeing on the name are equal whichever instance is in hand. It
 * carries **two** on-disk spellings, both of which the model already had:
 *  - [name] (`IMPLEMENTATION`) is a source set's persisted `scope = ` value.
 *  - [id] (`implementation`) is the key a `[dependencies]` table groups declarations under, which is also
 *    what the declaration reads as in a Gradle build file.
 *
 * A plugin that defines a scope should [register] it, so that loading a project which uses it resolves the
 * real scope with its real classpath semantics. Without that, an unregistered scope still round-trips by
 * name, but [valueOf] can only re-derive it permissively (on every classpath), and a `[dependencies]` table
 * keyed by its [id] is skipped rather than guessed at.
 */
class DependencyScope(
    val name: String,
    val id: String,
    val onCompile: Boolean,
    val onRuntime: Boolean,
    val onTest: Boolean,
) {
    override fun equals(other: Any?): Boolean = this === other || (other is DependencyScope && other.name == name)
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = name

    companion object {
        val API = DependencyScope("API", "api", onCompile = true, onRuntime = true, onTest = true)

        val IMPLEMENTATION =
            DependencyScope("IMPLEMENTATION", "implementation", onCompile = true, onRuntime = true, onTest = true)

        val COMPILE_ONLY =
            DependencyScope("COMPILE_ONLY", "compileOnly", onCompile = true, onRuntime = false, onTest = true)

        val RUNTIME_ONLY =
            DependencyScope("RUNTIME_ONLY", "runtimeOnly", onCompile = false, onRuntime = true, onTest = true)

        val TEST_IMPLEMENTATION =
            DependencyScope(
                "TEST_IMPLEMENTATION", "testImplementation",
                onCompile = false, onRuntime = false, onTest = true,
            )

        /** The scopes the IDE itself provides, in classpath-declaration order. */
        val entries: List<DependencyScope> =
            listOf(API, IMPLEMENTATION, COMPILE_ONLY, RUNTIME_ONLY, TEST_IMPLEMENTATION)

        fun values(): List<DependencyScope> = entries

        /**
         * Every scope resolvable by [name] or [id]: the built-ins plus whatever plugins have registered.
         * Guarded because plugins register on the load thread while a project may be opening on another.
         */
        private val known = LinkedHashMap<String, DependencyScope>().also { m ->
            for (scope in entries) m[scope.name] = scope
        }

        /**
         * Make [scope] resolvable when a project that persisted it is loaded. Idempotent, and safe to call
         * from a plugin's `register`. Returns [scope] so it can wrap a declaration:
         * `val LINK_ONLY = DependencyScope.register(DependencyScope("LINK_ONLY", "linkOnly", ...))`.
         */
        fun register(scope: DependencyScope): DependencyScope {
            synchronized(known) { known[scope.name] = scope }
            return scope
        }

        /** Every registered scope: the built-ins first, then plugin-defined ones in registration order. */
        fun registered(): List<DependencyScope> = synchronized(known) { known.values.toList() }

        /**
         * Total, so a project whose source set names a scope from a plugin that is not loaded still opens.
         * An unknown name is re-derived permissively (on every classpath), which keeps a dependency visible
         * rather than silently dropping it from the compile classpath.
         */
        fun valueOf(name: String): DependencyScope =
            synchronized(known) { known[name] }
                ?: DependencyScope(name, name, onCompile = true, onRuntime = true, onTest = true)

        /** The scope whose [id] is [id] (the `[dependencies]` table key), or null if nothing registered it. */
        fun byId(id: String): DependencyScope? = synchronized(known) { known.values.firstOrNull { it.id == id } }
    }
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

    companion object {
        /**
         * A snapshot over [entries], fingerprinted by digesting each entry's kind and path in order.
         *
         * The one implementation of the hash. Both things that assemble a classpath ([Module.classpath] and
         * the analysis binding in `ModuleCompilationContext`) go through it, so a path the build sees and the
         * same path the editor sees produce the same cache key rather than two hashes that merely happen to
         * agree today.
         */
        fun of(entries: List<ClasspathEntry>): ClasspathSnapshot = Snapshot(entries.toList())

        /**
         * No entries. What a language with no classpath at all analyzes against, so that language's
         * `CompilationContext` does not have to fabricate a snapshot. Equal in fingerprint to an assembled
         * classpath that came out empty, since it is one.
         */
        val EMPTY: ClasspathSnapshot = of(emptyList())
    }

    private class Snapshot(override val entries: List<ClasspathEntry>) : ClasspathSnapshot {
        override fun fingerprint(): ContentHash {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            for (e in entries) {
                md.update(e.kind.id.toByteArray(Charsets.UTF_8))
                md.update(0)
                md.update(e.root.path.toByteArray(Charsets.UTF_8))
                md.update('\n'.code.toByte())
            }
            return ContentHash(md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        }

        override fun toString(): String =
            if (entries.isEmpty()) "ClasspathSnapshot.EMPTY" else "ClasspathSnapshot(${entries.size} entries)"
    }
}

data class ClasspathEntry(val root: VirtualFile, val kind: ClasspathEntryKind)

/**
 * What a [ClasspathEntry] is, on the path a toolchain is handed. Open rather than an enum: the three
 * built-ins are the JVM reading, and a plugin for another toolchain has kinds of its own that the core
 * cannot enumerate (a C++ include directory or link library, a Python site-packages or stubs directory).
 * A consumer that only understands the built-ins should ignore a kind it does not know rather than fail.
 */
@JvmInline
value class ClasspathEntryKind(val id: String) {
    override fun toString(): String = id

    companion object {
        val MODULE_OUTPUT = ClasspathEntryKind("MODULE_OUTPUT")
        val LIBRARY = ClasspathEntryKind("LIBRARY")
        val SDK_BOOTCLASSPATH = ClasspathEntryKind("SDK_BOOTCLASSPATH")

        /** The kinds the IDE itself provides. A plugin's own kinds are not listed here. */
        val entries: List<ClasspathEntryKind> = listOf(MODULE_OUTPUT, LIBRARY, SDK_BOOTCLASSPATH)

        fun values(): List<ClasspathEntryKind> = entries

        /** Total: an unrecognized [name] is a kind some plugin owns. [name] is the persisted spelling. */
        fun valueOf(name: String): ClasspathEntryKind = ClasspathEntryKind(name)
    }
}

// ---------------------------------------------------------------------------
// Library & SDK tables (interned, referenced by name)
// ---------------------------------------------------------------------------

@JvmInline
value class LibraryRef(val name: String)

@JvmInline
value class SdkRef(val name: String)

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

/**
 * What a [Library]'s classes roots are packaged as. Open so a plugin can name a package format of its own
 * (a Python wheel, a prebuilt native archive) instead of mislabelling it a jar. [name] is persisted.
 */
@JvmInline
value class LibraryKind(val name: String) {
    override fun toString(): String = name

    companion object {
        val JAR = LibraryKind("JAR")
        val AAR = LibraryKind("AAR")

        /** The packaging kinds the IDE itself provides. */
        val entries: List<LibraryKind> = listOf(JAR, AAR)

        fun values(): List<LibraryKind> = entries

        /** Total: an unrecognized [name] is a kind some plugin owns. */
        fun valueOf(name: String): LibraryKind = LibraryKind(name)
    }
}

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
    val name: String                        // "android-34", "core-java", "jdk-17"
    val bootClasspath: List<VirtualFile>    // android.jar / core-Java jar / JDK rt
    val buildToolsPath: VirtualFile?

    /** JVM (core-Java platform) vs ANDROID (android.jar). A [Module] resolves an SDK of its own kind. */
    val kind: PlatformKind get() = PlatformKind.JVM
}

// ---------------------------------------------------------------------------
// Mutation: modifiable-model transaction (stage, then commit atomically under the write lock)
// ---------------------------------------------------------------------------

interface WorkspaceTransaction {
    fun addProject(name: String, buildSystem: BuildSystemId, rootDir: VirtualFile): Project
    fun removeProject(id: ProjectId)

    /** Rebind which build system owns [id]. Used when a project's build system is (re-)identified, e.g. a
     *  workspace first imported natively that a [dev.ide.model.sync.ProjectImporter] has since claimed. */
    fun setBuildSystem(id: ProjectId, buildSystem: BuildSystemId)
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

    /**
     * The module's directory, relative to the project root. Defaults to the module name; set it when the
     * layout differs (a nested module such as `features/home`, or an imported project whose directory names
     * don't match its module names).
     */
    var dirRelPath: String

    /** The explicit platform-SDK override (see [Module.sdk]); `null` clears it (back to the type default). */
    var sdk: SdkRef?
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
