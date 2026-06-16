package dev.ide.model.impl

import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.ModuleDependency
import dev.ide.model.OrderEntry
import dev.ide.model.PlatformDependency
import dev.ide.model.SdkDependency

/**
 * The immutable snapshot of the whole workspace, stored as plain data. This, rather than the live
 * view objects, is the model snapshot: a commit installs a new [WorkspaceData], readers keep the one
 * they captured, and persistence is just [WorkspaceData] to/from files. Paths are stored relative
 * (project roots relative to the workspace, content roots / outputs relative to their module dir) so
 * a snapshot is location-independent and value-comparable; module types and facets are stored as ids
 * plus data and resolved lazily by the view layer.
 *
 * [normalize] puts a snapshot into canonical form (deterministic ordering of order-insensitive
 * collections; `exported` derived from scope per the `api` semantics) so that a model built in code
 * and the same model saved-then-reloaded compare equal.
 */
const val WORKSPACE_SCHEMA_VERSION = 1
const val MODULE_SCHEMA_VERSION = 1

data class WorkspaceData(
    val schemaVersion: Int = WORKSPACE_SCHEMA_VERSION,
    val projects: List<ProjectData> = emptyList(),
    val libraries: List<LibraryData> = emptyList(), // workspace-scoped
    val sdks: List<SdkData> = emptyList(),
)

data class ProjectData(
    val id: String,
    val name: String,
    val rootRelPath: String,   // relative to the workspace root ("" == the workspace root itself)
    val buildSystemId: String,
    val settings: Map<String, String> = emptyMap(),
    val modules: List<ModuleData> = emptyList(),
    val libraries: List<LibraryData> = emptyList(), // project-scoped
)

data class ModuleData(
    val id: String,
    val name: String,
    val dirRelPath: String,    // relative to the project root
    val typeId: String,
    val languageLevel: LanguageLevel,
    val outputRelPath: String, // relative to the module dir
    val sourceSets: List<SourceSetData> = emptyList(),
    val dependencies: List<OrderEntry> = emptyList(),
    val facets: List<FacetData> = emptyList(),
)

data class SourceSetData(
    val name: String,
    val scope: DependencyScope,
    val contentRoots: List<ContentRootData> = emptyList(),
)

data class ContentRootData(
    val dirRelPath: String,    // relative to the module dir
    val roles: Set<ContentRole>,
)

/** A facet persisted as the name of its `module.toml` table plus its declarative values. */
data class FacetData(
    val tomlTable: String,
    val values: Map<String, Any?>,
)

data class LibraryData(
    val name: String,
    val kind: LibraryKind,
    val classes: List<String>, // paths relative to the workspace root
    val sources: List<String>,
)

data class SdkData(
    val name: String,
    val bootClasspath: List<String>, // absolute (SDKs live outside the workspace)
    val buildToolsPath: String?,
)

// --- canonicalization ---

private fun scopeOrder(scope: DependencyScope): Int = when (scope) {
    DependencyScope.API -> 0
    DependencyScope.IMPLEMENTATION -> 1
    DependencyScope.COMPILE_ONLY -> 2
    DependencyScope.RUNTIME_ONLY -> 3
    DependencyScope.TEST_IMPLEMENTATION -> 4
}

/** `exported` follows `api` semantics: true iff the scope is API. */
private fun OrderEntry.normalizedExported(): OrderEntry {
    val exported = scope == DependencyScope.API
    return when (this) {
        is ModuleDependency -> if (this.exported == exported) this else copy(exported = exported)
        is LibraryDependency -> if (this.exported == exported) this else copy(exported = exported)
        is PlatformDependency -> if (this.exported == exported) this else copy(exported = exported)
        is SdkDependency -> this // exported is always false
    }
}

fun normalize(ws: WorkspaceData): WorkspaceData = ws.copy(
    projects = ws.projects.sortedBy { it.name }.map { p ->
        p.copy(
            modules = p.modules.sortedBy { it.name }.map { normalizeModule(it) },
            libraries = p.libraries.sortedBy { it.name },
        )
    },
    libraries = ws.libraries.sortedBy { it.name },
    sdks = ws.sdks.sortedBy { it.name },
)

private fun normalizeModule(m: ModuleData): ModuleData = m.copy(
    sourceSets = m.sourceSets.sortedBy { it.name }.map { ss ->
        ss.copy(contentRoots = ss.contentRoots.sortedBy { it.dirRelPath })
    },
    // Stable sort by scope groups entries the way module.toml stores them while preserving the
    // declaration order *within* a scope (which is what classpath search order depends on).
    dependencies = m.dependencies.map { it.normalizedExported() }.sortedBy { scopeOrder(it.scope) },
    facets = m.facets.sortedBy { it.tomlTable },
)
