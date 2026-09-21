package dev.ide.model.bridge

import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetData
import dev.ide.model.LanguageLevel
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.PlatformKind
import dev.ide.model.Project
import dev.ide.model.SdkRef
import dev.ide.model.SdkResolution
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ProjectModelStore
import dev.ide.platform.createDirectories
import dev.ide.platform.resolvePath
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.backend.UiFacetConfig
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiModuleTypeOption
import dev.ide.ui.backend.UiSdkOption
import dev.ide.ui.backend.UiSourceRootRole
import dev.ide.ui.backend.UiSourceSetInfo

/**
 * The Module Settings screen's questions, answered from the project model alone.
 *
 * Every read here comes out of an open [ProjectModelStore] and every write goes back through a model
 * transaction followed by a save, which is what both hosts were already doing separately. A host still owns
 * what happens AROUND the edit (invalidating analyzers, re-indexing, telling a build file) and calls this for
 * the part that is the model's.
 *
 * **Facets are read and written as TABLES, not as typed facets.** The typed route needs a
 * [dev.ide.model.FacetCodec], and a codec ships with the plugin that defines the facet: `AndroidFacet` lives
 * in `:android-support`, which is JVM-only, so an iOS host has no codec for the `[android]` table a desktop
 * wrote. The model keeps an unclaimed table exactly as written, so reading the module's own [FacetData] and
 * writing it back through [dev.ide.model.ModifiableModule.putFacetData] is total: every host renders every
 * facet, and one that cannot interpret a table still round-trips it untouched. The generic field list a
 * panel is built from is derived from the values, so a new facet needs no new UI anywhere.
 *
 * [hiddenFacetKeys] are value keys to leave out of the generic panels because another tab owns them (the
 * Android `packaging` block). They are only hidden from the FIELD LIST; a save merges what the screen sends
 * onto what the module already carries, so a key nothing rendered survives it.
 */
class ModuleConfigBridge(
    private val store: ProjectModelStore,
    private val hiddenFacetKeys: Set<String> = emptySet(),
) {

    /** Modules whose configuration can be edited: every module of every open project. */
    fun configurableModules(): List<UiModuleRef> =
        modules().map { UiModuleRef(it.name, it.type.displayName) }

    /** Every module of the open workspace, in model order. */
    fun modules(): List<Module> = store.workspace.projects.flatMap { it.modules }

    fun module(name: String): Module? = modules().firstOrNull { it.name == name }

    /** The project that owns [module]. */
    fun projectOf(module: Module): Project? =
        store.workspace.projects.firstOrNull { p -> p.modules.any { it.id == module.id } }

    /**
     * [moduleName]'s editable configuration: type, language level, source sets, facet panels and the SDK
     * picker's state.
     *
     * `runConfig` is left null and filled in by a host that can detect and launch a `main`. The SDK fields
     * are answered here because they are the model's: which SDK a module resolves to is
     * [SdkResolution]'s question over the workspace's own table, and a host with no SDKs installed simply
     * has an empty table and an empty picker.
     */
    fun moduleConfig(moduleName: String): UiModuleConfig? {
        val module = module(moduleName) ?: return null
        return UiModuleConfig(
            name = module.name,
            typeId = module.type.id,
            typeDisplay = module.type.displayName,
            languageLevel = module.languageLevel.name,
            languageLevels = LanguageLevel.values().map { it.name },
            outputDir = module.outputDir?.path.orEmpty(),
            sourceSets = module.sourceSets.map { ss ->
                UiSourceSetInfo(ss.name, ss.scope.name, ss.contentRoots.map { it.dir.path })
            },
            facets = facetPanels(module),
            platformSdk = module.sdk?.name ?: "",
            resolvedSdk = SdkResolution.sdkFor(store.workspace, module)?.name ?: "",
            availableSdks = store.workspace.sdkTable.sdks.map {
                UiSdkOption(it.name, "${it.name} · ${if (it.kind == PlatformKind.ANDROID) "Android" else "Java"}")
            },
        )
    }

    /** One collapsible panel per facet table the module carries, with a codec-free, value-derived field list. */
    fun facetPanels(module: Module): List<UiFacetConfig> = facetTables(module).map { data ->
        UiFacetConfig(
            data.tomlTable,
            titleCase(data.tomlTable),
            data.values.filterKeys { it !in hiddenFacetKeys }.map { (k, v) -> fieldFor(k, v) },
        )
    }

    /**
     * [module]'s facet tables as the snapshot holds them.
     *
     * Read from [ProjectModelStore.data] rather than through `Module.facets.all`, which decodes and silently
     * drops what no registered codec claims. This is the same object a save writes back, so nothing is read
     * twice and nothing that was not understood is lost.
     */
    fun facetTables(module: Module): List<FacetData> =
        store.data.projects.firstNotNullOfOrNull { p -> p.modules.firstOrNull { it.id == module.id.value } }
            ?.facets.orEmpty()

    /**
     * Persist [edit]: the language level, the platform-SDK override, and each facet table sent back.
     *
     * A facet's values are OVERLAID on what the module already carries rather than replacing it, so a key
     * the screen did not render (a nested table, anything a newer build writes, anything this host has no
     * codec for) survives a save made here. That is the difference between editing a project's settings and
     * silently truncating them.
     *
     * [UiModuleConfigEdit.mainClass] is NOT applied: it is a host preference rather than model state, and
     * the host that has one applies it around this call.
     */
    fun updateModuleConfig(moduleName: String, edit: UiModuleConfigEdit): UiConfigResult {
        val module = module(moduleName) ?: return UiConfigResult(false, "No module '$moduleName'.")
        val project = projectOf(module) ?: return UiConfigResult(false, "No project owns '$moduleName'.")
        val level = edit.languageLevel?.let { name ->
            LanguageLevel.values().firstOrNull { it.name == name }
                ?: return UiConfigResult(false, "Unknown language level '$name'.")
        }
        val existing = facetTables(module).associateBy({ it.tomlTable }, { it.values })
        return runCatching {
            project.beginModification().apply {
                val m = module(module.id)
                if (level != null) m.languageLevel = level
                // null = leave unchanged; blank = clear the override (follow the module-type default);
                // otherwise pin the named platform SDK. Drives which platform the module compiles against.
                edit.platformSdk?.let { m.sdk = it.ifBlank { null }?.let(::SdkRef) }
                for ((table, values) in edit.facetValues) {
                    m.putFacetData(FacetData(table, existing[table].orEmpty() + values))
                }
                commit()
            }
            store.save()
            UiConfigResult(true, "Saved ${module.name}")
        }.getOrElse { UiConfigResult(false, "Update failed: ${it.message}") }
    }

    // ---- source sets and roots ------------------------------------------------------------------------

    fun moduleSourceSets(moduleName: String): List<String> =
        module(moduleName)?.sourceSets?.map { it.name }.orEmpty()

    /** Create an empty source set on [moduleName]. False when it already has one by that name. */
    fun addSourceSet(moduleName: String, name: String): Boolean {
        val module = module(moduleName) ?: return false
        if (module.sourceSets.any { it.name == name }) return false
        val project = projectOf(module) ?: return false
        return runCatching {
            project.beginModification().apply {
                module(module.id).addSourceSet(SourceSetTemplate(name, DependencyScope.IMPLEMENTATION, emptyMap()))
                commit()
            }
            store.save()
            true
        }.getOrDefault(false)
    }

    /**
     * Add a typed root named [dirName] to [sourceSetName], returning the absolute directory it declared.
     *
     * The root goes beside the set's existing roots (`src/<set>/<dirName>` for a set with none yet), which is
     * the layout the templates write and every host reads. The directory is created as well as declared: a
     * content root the model names and the filesystem lacks is a root nothing can be put into.
     */
    fun addSourceRoot(
        moduleName: String,
        sourceSetName: String,
        dirName: String,
        roles: Set<ContentRole>,
    ): String? {
        val module = module(moduleName) ?: return null
        val base = sourceSetBase(module, sourceSetName)
        val relPath = if (base.isEmpty()) dirName else "$base/$dirName"
        return addSourceRootAt(moduleName, sourceSetName, relPath, roles)
    }

    /** [addSourceRoot] for a caller that already knows the path, relative to the module directory. */
    fun addSourceRootAt(
        moduleName: String,
        sourceSetName: String,
        dirRelPath: String,
        roles: Set<ContentRole>,
    ): String? {
        val module = module(moduleName) ?: return null
        val project = projectOf(module) ?: return null
        val relPath = dirRelPath.replace('\\', '/').trim('/')
        if (relPath.isEmpty() || relPath.startsWith("..")) return null
        val declared = runCatching {
            project.beginModification().apply {
                module(module.id).addContentRoot(sourceSetName, relPath, roles)
                commit()
            }
            store.save()
            true
        }.getOrDefault(false)
        if (!declared) return null
        val dir = resolvePath(module.dir.path, relPath)
        createDirectories(dir)
        return dir
    }

    /**
     * Unmark a content root. Model only: the directory and everything in it stay on disk.
     *
     * [rootPath] may be absolute (what the screen was shown) or already relative to the module directory.
     */
    fun removeSourceRoot(moduleName: String, sourceSetName: String, rootPath: String): Boolean {
        val module = module(moduleName) ?: return false
        val project = projectOf(module) ?: return false
        val normalized = rootPath.replace('\\', '/')
        val relPath = normalized.removePrefix(module.dir.path).trim('/').ifEmpty { normalized }
        return runCatching {
            project.beginModification().apply {
                module(module.id).removeContentRoot(sourceSetName, relPath)
                commit()
            }
            store.save()
            true
        }.getOrDefault(false)
    }

    /**
     * Where a new root for [sourceSetName] goes, relative to the module directory.
     *
     * Derived from what the set already has (`src/main/kotlin` gives `src/main`) so a second root lands
     * beside the first, and falls back to the `src/<set>` convention for a set with no roots yet.
     */
    private fun sourceSetBase(module: Module, sourceSetName: String): String {
        val existing = module.sourceSets.firstOrNull { it.name == sourceSetName }
            ?.contentRoots?.firstOrNull()?.dir?.path
        if (existing != null) {
            val rel = existing.removePrefix(module.dir.path).trim('/')
            val parent = rel.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) return parent
        }
        return "src/$sourceSetName"
    }

    // ---- adding and removing modules ------------------------------------------------------------------

    /**
     * The module types a new module can be created as, from the store's own registry.
     *
     * [defaultFacetsFor] lets a host that HAS codecs prefill a type's default facet panels; the default
     * answers none, which is right for a host whose registry carries no codec to describe one.
     */
    fun availableModuleTypes(
        defaultFacetsFor: (dev.ide.model.ModuleType) -> List<UiFacetConfig> = { emptyList() },
    ): List<UiModuleTypeOption> {
        val levels = LanguageLevel.values().map { it.name }
        return store.moduleTypes.all().map { type ->
            UiModuleTypeOption(
                id = type.id,
                displayName = type.displayName,
                languageLevels = levels,
                defaultLanguageLevel = LanguageLevel.JAVA_17.name,
                defaultFacets = defaultFacetsFor(type),
            )
        }
    }

    /**
     * Create module [name] of [typeId] in the first open project, with its source-set directories on disk.
     *
     * The type's own [dev.ide.model.ModuleType.defaultSourceSets] are used when it declares any, and
     * [fallbackSourceDir] otherwise: a type that describes its layout should not have one invented for it,
     * and a type that declares none (the plain `java-lib` the built-in templates use) still needs somewhere
     * for the first file to go.
     *
     * Only the PRIMARY roots are created on disk (see [materializedRoots]), so a fresh module shows the one
     * source directory and its resources rather than a dozen empty folders.
     */
    fun createModule(
        name: String,
        typeId: String,
        languageLevel: String?,
        facetValues: Map<String, Map<String, Any?>>,
        fallbackSourceDir: String = DEFAULT_SOURCE_DIR,
    ): UiConfigResult {
        val moduleName = name.trim()
        if (!isValidModuleName(moduleName)) return UiConfigResult(
            false, "Invalid module name. Start with a letter; use letters, digits, '-' or '_'.",
        )
        if (modules().any { it.name == moduleName }) {
            return UiConfigResult(false, "A module named '$moduleName' already exists.")
        }
        val type = store.moduleTypes.byId(typeId)
            ?: return UiConfigResult(false, "Unknown module type '$typeId'.")
        val project = store.workspace.projects.firstOrNull()
            ?: return UiConfigResult(false, "No project to add a module to.")
        val level = languageLevel?.let { requested ->
            LanguageLevel.values().firstOrNull { it.name == requested }
                ?: return UiConfigResult(false, "Unknown language level '$requested'.")
        }
        val sourceSets = type.defaultSourceSets().ifEmpty {
            listOf(
                SourceSetTemplate(
                    "main",
                    DependencyScope.IMPLEMENTATION,
                    mapOf(fallbackSourceDir to setOf(ContentRole.SOURCE)),
                ),
            )
        }
        return runCatching {
            project.beginModification().apply {
                addModule(moduleName, type).apply {
                    this.languageLevel = level ?: LanguageLevel.JAVA_17
                    sourceSets.forEach { addSourceSet(it) }
                    facetValues.forEach { (table, values) -> putFacetData(FacetData(table, values)) }
                }
                commit()
            }
            store.save()
            module(moduleName)?.let { created ->
                val primary = created.sourceSets.filter { it.name == "main" }.ifEmpty { created.sourceSets }
                primary.flatMap { materializedRoots(it) }.forEach { createDirectories(it.dir.path) }
            }
            UiConfigResult(true, "Created $moduleName")
        }.getOrElse { UiConfigResult(false, "Could not create the module: ${it.message}") }
    }

    /**
     * Remove [name] from the model, and with it every other module's dependency ON it.
     *
     * Its files are left on disk. A module is a description of a directory rather than the directory, and
     * deleting sources behind a confirmation that said "remove module" is not the same promise.
     */
    fun removeModule(name: String): Boolean {
        val module = module(name) ?: return false
        val project = projectOf(module) ?: return false
        val id = module.id
        return runCatching {
            project.beginModification().apply {
                project.modules.forEach { other ->
                    if (other.id != id) {
                        other.dependencies.filterIsInstance<ModuleDependency>()
                            .filter { it.target == id }
                            .forEach { module(other.id).removeDependency(it) }
                    }
                }
                removeModule(id)
                commit()
            }
            store.save()
            true
        }.getOrDefault(false)
    }

    /**
     * The content roots of [sourceSet] worth creating on disk for a freshly-made module: the primary source
     * directory (the FIRST `SOURCE` root, so a `java` dir does not also get an empty `kotlin` sibling) plus
     * any resource or Android `res` root.
     *
     * Optional roots (a second-language source dir, `assets`, `aidl`, generated) are left uncreated so the
     * module tree is not littered with empty folders; the New flow creates them on demand.
     */
    private fun materializedRoots(sourceSet: dev.ide.model.SourceSet): List<dev.ide.model.ContentRoot> {
        val roots = sourceSet.contentRoots
        val firstSourceDir = roots.firstOrNull { ContentRole.SOURCE in it.roles }?.dir?.path
        return roots.filter { cr ->
            when {
                ContentRole.SOURCE in cr.roles -> cr.dir.path == firstSourceDir
                ContentRole.ANDROID_RES in cr.roles || ContentRole.RESOURCE in cr.roles -> true
                else -> false
            }
        }
    }

    private fun isValidModuleName(name: String): Boolean =
        name.isNotEmpty() && name.first().isLetter() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    companion object {
        /** Where a module created with no type-declared layout puts its sources. */
        const val DEFAULT_SOURCE_DIR = "src/main/kotlin"

        /** The [ContentRole] a UI role names. Total, so a new role breaks this rather than defaulting. */
        fun roleOf(role: UiSourceRootRole): ContentRole = when (role) {
            UiSourceRootRole.Source -> ContentRole.SOURCE
            UiSourceRootRole.Resource -> ContentRole.RESOURCE
            UiSourceRootRole.AndroidRes -> ContentRole.ANDROID_RES
            UiSourceRootRole.Assets -> ContentRole.ASSETS
            UiSourceRootRole.Aidl -> ContentRole.AIDL
            UiSourceRootRole.JniLibs -> ContentRole.JNI_LIBS
        }

        /** A facet value as the control that edits it, typed by the value so the renderer stays generic. */
        fun fieldFor(key: String, value: Any?): UiConfigField = when (value) {
            is Boolean -> UiConfigField.Bool(key, humanize(key), value)
            is Long -> UiConfigField.Number(key, humanize(key), value)
            is Int -> UiConfigField.Number(key, humanize(key), value.toLong())
            is Number -> UiConfigField.Number(key, humanize(key), value.toLong())
            is String -> UiConfigField.Text(key, humanize(key), value)
            is List<*> -> if (value.isNotEmpty() && value.all { it is Map<*, *> }) {
                @Suppress("UNCHECKED_CAST")
                val rows = value.map { row -> (row as Map<String, Any?>).map { (k, v) -> fieldFor(k, v) } }
                UiConfigField.TableList(key, humanize(key), rows)
            } else {
                UiConfigField.StringList(key, humanize(key), value.mapNotNull { it as? String })
            }

            else -> UiConfigField.Text(key, humanize(key), value?.toString() ?: "")
        }

        fun titleCase(s: String): String = s.replaceFirstChar { it.uppercase() }

        /** "compileSdk" to "Compile Sdk", "applicationIdSuffix" to "Application Id Suffix". */
        fun humanize(key: String): String =
            key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
    }
}
