package dev.ide.model.impl.graph

import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.Project
import dev.ide.model.ProjectId
import dev.ide.model.Workspace
import dev.ide.model.graph.Cycle
import dev.ide.model.graph.ModuleGraph
import dev.ide.model.graph.ProjectDependency
import dev.ide.model.graph.ProjectGraph

/**
 * Concrete dependency graphs, computed on demand from the read-only model. Both are pure functions
 * of the public api (no impl internals), so they work over any [Project]/[Workspace].
 *
 * [ModuleGraph] is intra-project (edges are [ModuleDependency]s between modules of one project).
 * [ProjectGraph] is the composite/linked-build graph: an edge A→B exists when a module in A depends
 * on a module owned by B (cross-project), which is how the model expresses linked projects today.
 */
class ModuleGraphImpl(project: Project) : ModuleGraph {
    override val nodes: List<Module> = project.modules
    private val byId: Map<ModuleId, Module> = nodes.associateBy { it.id }

    override fun dependenciesOf(m: ModuleId): List<ModuleId> =
        byId[m]?.dependencies.orEmpty()
            .filterIsInstance<ModuleDependency>()
            .map { it.target }
            .filter { byId.containsKey(it) } // intra-project edges only
            .distinct()

    override fun topologicalOrder(): List<List<Module>> =
        topologicalLevels(byId.keys.toList(), ::dependenciesOf).map { level -> level.map { byId.getValue(it) } }

    override fun detectCycles(): List<Cycle<ModuleId>> = detectCycles(byId.keys.toList(), ::dependenciesOf)

    override fun reverseDependents(m: ModuleId): Set<Module> {
        val reverse = HashMap<ModuleId, MutableSet<ModuleId>>()
        for (node in nodes) for (dep in dependenciesOf(node.id)) reverse.getOrPut(dep) { HashSet() }.add(node.id)
        val result = LinkedHashSet<ModuleId>()
        val stack = ArrayDeque(reverse[m].orEmpty().toList())
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (result.add(next)) stack.addAll(reverse[next].orEmpty())
        }
        return result.mapNotNull { byId[it] }.toSet()
    }
}

class ProjectGraphImpl(workspace: Workspace) : ProjectGraph {
    override val nodes: List<Project> = workspace.projects
    private val byId: Map<ProjectId, Project> = nodes.associateBy { it.id }
    private val ownerOfModule: Map<ModuleId, ProjectId> = buildMap {
        for (p in nodes) for (m in p.modules) putIfAbsent(m.id, p.id)
    }

    override fun dependenciesOf(p: ProjectId): List<ProjectDependency> {
        val project = byId[p] ?: return emptyList()
        val targets = LinkedHashSet<ProjectId>()
        for (module in project.modules) {
            for (dep in module.dependencies.filterIsInstance<ModuleDependency>()) {
                val owner = ownerOfModule[dep.target]
                if (owner != null && owner != p) targets.add(owner)
            }
        }
        return targets.map { ProjectDependency(from = p, to = it) }
    }

    override fun topologicalOrder(): List<List<Project>> =
        topologicalLevels(byId.keys.toList()) { id -> dependenciesOf(id).map { it.to } }
            .map { level -> level.map { byId.getValue(it) } }

    override fun detectCycles(): List<Cycle<ProjectId>> =
        detectCycles(byId.keys.toList()) { id -> dependenciesOf(id).map { it.to } }
}

/** Ergonomic accessors. */
fun Project.moduleGraph(): ModuleGraph = ModuleGraphImpl(this)
fun Workspace.projectGraph(): ProjectGraph = ProjectGraphImpl(this)

// --- generic graph algorithms (edges point from a node to its dependencies) ---

/**
 * Batched topological levels (Kahn): level 0 has nodes with no in-set dependencies, each later level
 * depends only on earlier ones. Nodes in a cycle never reach in-degree zero and are omitted — use
 * [detectCycles] to surface those.
 */
internal fun <ID> topologicalLevels(ids: List<ID>, dependencies: (ID) -> List<ID>): List<List<ID>> {
    val present = ids.toSet()
    val remaining = ids.associateWith { dependencies(it).filter { d -> d in present }.toMutableSet() }.toMutableMap()
    val dependents = HashMap<ID, MutableList<ID>>()
    for ((id, deps) in remaining) for (d in deps) dependents.getOrPut(d) { ArrayList() }.add(id)

    val levels = ArrayList<List<ID>>()
    var ready = remaining.filterValues { it.isEmpty() }.keys.toList()
    while (ready.isNotEmpty()) {
        levels.add(ready)
        ready.forEach { remaining.remove(it) }
        val next = LinkedHashSet<ID>()
        for (done in ready) for (dependent in dependents[done].orEmpty()) {
            val deps = remaining[dependent] ?: continue
            deps.remove(done)
            if (deps.isEmpty()) next.add(dependent)
        }
        ready = next.toList()
    }
    return levels
}

/** All cycles, as strongly-connected components of size > 1 (plus self-loops), via Tarjan's algorithm. */
internal fun <ID> detectCycles(ids: List<ID>, dependencies: (ID) -> List<ID>): List<Cycle<ID>> {
    val present = ids.toSet()
    val index = HashMap<ID, Int>()
    val low = HashMap<ID, Int>()
    val onStack = HashSet<ID>()
    val stack = ArrayDeque<ID>()
    var counter = 0
    val cycles = ArrayList<Cycle<ID>>()

    fun strongConnect(v: ID) {
        index[v] = counter; low[v] = counter; counter++
        stack.addLast(v); onStack.add(v)
        for (w in dependencies(v)) {
            if (w !in present) continue
            if (w !in index) {
                strongConnect(w)
                low[v] = minOf(low.getValue(v), low.getValue(w))
            } else if (w in onStack) {
                low[v] = minOf(low.getValue(v), index.getValue(w))
            }
        }
        if (low.getValue(v) == index.getValue(v)) {
            val scc = ArrayList<ID>()
            while (true) {
                val w = stack.removeLast(); onStack.remove(w); scc.add(w)
                if (w == v) break
            }
            val isSelfLoop = scc.size == 1 && dependencies(scc[0]).any { it == scc[0] }
            if (scc.size > 1 || isSelfLoop) cycles.add(Cycle(scc.reversed()))
        }
    }

    for (id in ids) if (id !in index) strongConnect(id)
    return cycles
}
