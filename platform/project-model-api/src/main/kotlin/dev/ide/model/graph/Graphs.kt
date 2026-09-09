package dev.ide.model.graph

import dev.ide.model.Coordinate
import dev.ide.model.Module
import dev.ide.model.ModuleId
import dev.ide.model.Project
import dev.ide.model.ProjectId

/**
 * Two dependency graphs, kept deliberately distinct:
 *  - [ProjectGraph]  — composite/linked builds; each node may use a DIFFERENT build system.
 *  - [ModuleGraph]   — subprojects within one project, built by that project's single build system.
 *
 * This is what makes "linked projects with individual build systems, parallel or sequential" work
 * with no special-casing: the Workspace coordinator only ever talks to the BuildSystem SPI.
 */

interface ProjectGraph {
    val nodes: List<Project>
    fun dependenciesOf(p: ProjectId): List<ProjectDependency>
    /** Batched "levels"; projects within a level have no edge and may build in parallel. */
    fun topologicalOrder(): List<List<Project>>
    fun detectCycles(): List<Cycle<ProjectId>>
}

/**
 * Edge A -> B (A consumes B's published output). [substitutes] records dependency substitution:
 * a Maven coordinate declared by A that is actually produced by linked project B is replaced by
 * B's freshly built output (Gradle's includeBuild substitution).
 */
data class ProjectDependency(
    val from: ProjectId,
    val to: ProjectId,
    val substitutes: Set<Coordinate> = emptySet(),
)

interface ModuleGraph {
    val nodes: List<Module>
    fun dependenciesOf(m: ModuleId): List<ModuleId>
    fun topologicalOrder(): List<List<Module>>
    fun detectCycles(): List<Cycle<ModuleId>>
    /** Who breaks if [m] changes — drives incremental rebuild scope. */
    fun reverseDependents(m: ModuleId): Set<Module>
}

data class Cycle<ID>(val members: List<ID>)

// ---------------------------------------------------------------------------
// Build coordination policy (Workspace level)
// ---------------------------------------------------------------------------

enum class CoordinationPolicy {
    /** Build each topological level concurrently (fastest). */
    PARALLEL_BY_LEVEL,
    /** One project at a time in topological order (lowest memory, easiest to debug). */
    STRICTLY_SEQUENTIAL,
    /** Honor an explicit user order for projects that have no graph edge but a real-world ordering. */
    MANUAL_ORDER,
}

data class BuildCoordinationSettings(
    val policy: CoordinationPolicy = CoordinationPolicy.PARALLEL_BY_LEVEL,
    val maxParallelProjects: Int = 2,
    val continueOnFailure: Boolean = false,
    val manualOrder: List<ProjectId> = emptyList(),
)
