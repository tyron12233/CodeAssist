package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Facet
import dev.ide.model.LanguageLevel
import dev.ide.model.ModifiableModule
import dev.ide.model.Module
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.OrderEntry
import dev.ide.model.Project
import dev.ide.model.ProjectId
import dev.ide.model.ProjectModelTransaction
import dev.ide.model.SourceSetTemplate
import dev.ide.model.WorkspaceTransaction
import dev.ide.model.event.DependenciesChanged
import dev.ide.model.event.FacetsChanged
import dev.ide.model.event.ModuleAdded
import dev.ide.model.event.ModuleRemoved
import dev.ide.model.event.ModuleSettingsChanged
import dev.ide.model.event.ProjectAdded
import dev.ide.model.event.ProjectModelEvent
import dev.ide.model.event.ProjectRemoved
import dev.ide.model.event.SourceSetsChanged
import dev.ide.vfs.VirtualFile

/**
 * Workspace-level structural edit: add/remove [Project]s. Changes are staged here and only become
 * visible when [commit] installs a new snapshot atomically and publishes [ProjectAdded]/[ProjectRemoved].
 */
internal class WorkspaceTransactionImpl(private val store: ProjectModelStore) : WorkspaceTransaction {
    private val base = store.data
    private val added = LinkedHashMap<String, ProjectData>()
    private val removed = LinkedHashSet<String>()
    private var done = false

    override fun addProject(name: String, buildSystem: BuildSystemId, rootDir: VirtualFile): Project {
        require(base.projects.none { it.id == name } && !added.containsKey(name)) { "project '$name' already exists" }
        val pd = ProjectData(
            id = name,
            name = name,
            rootRelPath = store.relativizeToWorkspace(rootDir),
            buildSystemId = buildSystem.value,
        )
        added[name] = pd
        return ProjectImpl(pd, store) // a transient view of the staged project
    }

    override fun removeProject(id: ProjectId) {
        added.remove(id.value)
        removed.add(id.value)
    }

    override fun commit() {
        check(!done) { "transaction already committed or disposed" }
        done = true
        val events = ArrayList<ProjectModelEvent>()
        val result = LinkedHashMap<String, ProjectData>()
        for (p in base.projects) result[p.id] = p
        for (id in removed) if (result.remove(id) != null) events.add(ProjectRemoved(ProjectId(id)))
        for ((id, pd) in added) {
            result[id] = pd
            events.add(ProjectAdded(ProjectId(id)))
        }
        store.commit(base.copy(projects = result.values.toList()), events)
    }

    override fun dispose() {
        done = true
    }
}

/**
 * Project-level structural edit: add/remove/modify [Module]s. Staged through [ModuleBuilder]s; [commit]
 * installs the new snapshot and publishes one event per change (ModuleAdded for new modules, otherwise
 * the specific Dependencies/SourceSets/Facets/ModuleSettings changes).
 */
internal class ProjectModelTransactionImpl(
    private val store: ProjectModelStore,
    private val projectId: ProjectId,
) : ProjectModelTransaction {
    private val project = store.data.projects.first { it.id == projectId.value }
    private val builders = LinkedHashMap<String, ModuleBuilder>()
    private val removed = LinkedHashSet<String>()
    private var done = false

    override fun addModule(name: String, type: ModuleType): ModifiableModule {
        require(builders[name] == null && project.modules.none { it.id == name }) { "module '$name' already exists" }
        val builder = ModuleBuilder(id = name, name = name, dirRelPath = name, typeId = type.id, initial = null, codecs = store.facetCodecs)
        type.defaultSourceSets().forEach { builder.addSourceSet(it) }
        type.defaultFacets().forEach { template ->
            store.facetCodecs.codecFor(template.key)?.let { codec ->
                builder.putFacetData(FacetData(codec.tomlTable, template.defaults))
            }
        }
        builders[name] = builder
        return builder
    }

    override fun removeModule(id: ModuleId) {
        builders.remove(id.value)
        removed.add(id.value)
    }

    override fun module(id: ModuleId): ModifiableModule = builders.getOrPut(id.value) {
        val existing = project.modules.first { it.id == id.value }
        ModuleBuilder(existing.id, existing.name, existing.dirRelPath, existing.typeId, existing, store.facetCodecs)
    }

    override fun commit() {
        check(!done) { "transaction already committed or disposed" }
        done = true
        val events = ArrayList<ProjectModelEvent>()
        val result = LinkedHashMap<String, ModuleData>()
        for (m in project.modules) result[m.id] = m
        for (id in removed) if (result.remove(id) != null) events.add(ModuleRemoved(projectId, ModuleId(id)))
        for ((id, b) in builders) {
            result[id] = b.toData()
            if (b.isNew) {
                events.add(ModuleAdded(projectId, ModuleId(id)))
            } else {
                if (b.depsChanged) events.add(DependenciesChanged(projectId, ModuleId(id)))
                if (b.sourceSetsChanged) events.add(SourceSetsChanged(projectId, ModuleId(id)))
                if (b.facetsChanged) events.add(FacetsChanged(projectId, ModuleId(id)))
                if (b.settingsChanged) events.add(ModuleSettingsChanged(projectId, ModuleId(id)))
            }
        }
        val newProject = project.copy(modules = result.values.toList())
        val newWs = store.data.copy(projects = store.data.projects.map { if (it.id == projectId.value) newProject else it })
        store.commit(newWs, events)
    }

    override fun dispose() {
        done = true
    }
}

internal class ModuleBuilder(
    private val id: String,
    private val name: String,
    private val dirRelPath: String,
    private val typeId: String,
    initial: ModuleData?,
    private val codecs: FacetCodecRegistry,
) : ModifiableModule {

    val isNew: Boolean = initial == null
    var depsChanged = false; private set
    var sourceSetsChanged = false; private set
    var facetsChanged = false; private set
    var settingsChanged = false; private set

    private var languageLevelField: LanguageLevel = initial?.languageLevel ?: LanguageLevel.JAVA_17
    private var outputRelPath: String = initial?.outputRelPath ?: "build/classes"
    private val deps = ArrayList<OrderEntry>(initial?.dependencies ?: emptyList())
    private val sourceSets = ArrayList<SourceSetData>(initial?.sourceSets ?: emptyList())
    private val facets = LinkedHashMap<String, FacetData>().apply { initial?.facets?.forEach { put(it.tomlTable, it) } }

    override var languageLevel: LanguageLevel
        get() = languageLevelField
        set(value) {
            languageLevelField = value
            settingsChanged = true
        }

    override fun addDependency(entry: OrderEntry) {
        deps.add(entry)
        depsChanged = true
    }

    override fun removeDependency(entry: OrderEntry) {
        if (deps.remove(entry)) depsChanged = true
    }

    override fun addSourceSet(template: SourceSetTemplate) {
        val roots = template.roots.map { (dir, roles): Map.Entry<String, Set<ContentRole>> -> ContentRootData(dir, roles) }
        sourceSets.add(SourceSetData(template.name, template.scope, roots))
        sourceSetsChanged = true
    }

    override fun <T : Facet> putFacet(facet: T) {
        val fd = codecs.encode(facet) ?: error("no FacetCodec registered for facet '${facet.key.id}'")
        facets[fd.tomlTable] = fd
        facetsChanged = true
    }

    /** Stage facet data directly (used for module-type default facets and internal wiring). */
    fun putFacetData(data: FacetData) {
        facets[data.tomlTable] = data
        facetsChanged = true
    }

    fun toData(): ModuleData = ModuleData(
        id = id,
        name = name,
        dirRelPath = dirRelPath,
        typeId = typeId,
        languageLevel = languageLevelField,
        outputRelPath = outputRelPath,
        sourceSets = sourceSets.toList(),
        dependencies = deps.toList(),
        facets = facets.values.toList(),
    )
}
