package dev.ide.core.services

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFeatureDependencies
import dev.ide.android.support.AndroidPackaging
import dev.ide.android.support.JniLibsPackaging
import dev.ide.android.support.ResourcePackaging
import dev.ide.core.EngineContext
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.PlatformKind
import dev.ide.model.SdkRef
import dev.ide.model.SdkResolution
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.module
import dev.ide.ui.backend.UiBuildFeature
import dev.ide.ui.backend.UiBuildFeatures
import dev.ide.ui.backend.UiConfigField
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.backend.UiFacetConfig
import dev.ide.ui.backend.UiMissingProguardFile
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiModuleTypeOption
import dev.ide.ui.backend.UiPackagingOptions
import dev.ide.ui.backend.UiPackagingRules
import dev.ide.ui.backend.UiRunConfig
import dev.ide.ui.backend.UiSdkOption
import dev.ide.ui.backend.UiSourceSetInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * WORKSPACE-scoped engine service: module configuration + management — the Module Settings editor (language
 * level, facets, build features, proguard files), add/remove modules, and source sets/roots. Carved out of
 * [dev.ide.core.IdeServices]. Reaches shared infrastructure (model, prefs) through [EngineContext]; enabling
 * an Android build feature pulls its runtime dependency through [EngineContext.dependencies], mirroring AGP.
 */
internal class ModuleService(private val ctx: EngineContext) {

    /** Starter body for a created `proguard-rules.pro` — comments only (the bundled defaults carry the
     *  framework keep rules); applied on top of them when the build type has `minifyEnabled = true`. */
    private val DEFAULT_PROGUARD_RULES: String = """
        # Add project-specific ProGuard/R8 keep rules here.
        # Applied on top of the bundled defaults (proguard-android-optimize.txt) when minifyEnabled = true.
        #
        # Keep a class referenced only by reflection / from XML, e.g.:
        # -keep class com.example.SomeClass { *; }
        #
        # Preserve line numbers for readable crash stack traces, then hide the original file name:
        # -keepattributes SourceFile,LineNumberTable
        # -renamesourcefileattribute SourceFile
    """.trimIndent() + "\n"

    // ---- module configuration (the Module Settings editor) ----

    /** Modules whose configuration can be edited (the settings screen's switcher). */
    fun configurableModules(): List<UiModuleRef> =
        ctx.modules().map { UiModuleRef(it.name, it.type.displayName) }

    /**
     * Read [moduleName]'s editable configuration: type, language level, source sets, and one facet panel
     * per registered facet. Facet fields are derived generically from the codec's value map, so any
     * codec-backed facet (Android, future ones) renders without bespoke UI.
     */
    fun getModuleConfig(moduleName: String): UiModuleConfig? {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return null
        val facets = module.facets.all.mapNotNull { facet ->
            val data = ctx.store.facetCodecs.encode(facet) ?: return@mapNotNull null
            UiFacetConfig(
                data.tomlTable,
                titleCase(data.tomlTable),
                // `packaging` is a nested table edited on its own tab (and is absent when default) — keep it out
                // of the generic field list so it isn't rendered as a raw map here.
                data.values.filterKeys { it != PACKAGING_KEY }.map { (k, v) -> configFieldFor(k, v) })
        }
        val runConfig = if (isConsoleRunModule(module)) {
            val detected = MainClassDetection.detect(ctx, module).map { it.mainClass }
            UiRunConfig(
                mainClass = ctx.mainClassOverride(module) ?: "",
                detectedMainClasses = detected,
                autoDetected = detected.firstOrNull(),
            )
        } else null
        return UiModuleConfig(
            name = module.name,
            typeId = module.type.id,
            typeDisplay = module.type.displayName,
            languageLevel = module.languageLevel.name,
            languageLevels = LanguageLevel.values().map { it.name },
            outputDir = module.outputDir.path,
            sourceSets = module.sourceSets.map { ss ->
                UiSourceSetInfo(ss.name, ss.scope.name, ss.contentRoots.map { it.dir.path })
            },
            facets = facets,
            runConfig = runConfig,
            platformSdk = module.sdk?.name ?: "",
            resolvedSdk = SdkResolution.sdkFor(ctx.store.workspace, module)?.name ?: "",
            availableSdks = ctx.store.workspace.sdkTable.sdks.map {
                UiSdkOption(it.name, "${it.name} · ${if (it.kind == PlatformKind.ANDROID) "Android" else "Java"}")
            },
        )
    }

    /** Persist [edit] (language level + facet values) to [moduleName] through a model transaction + save. */
    fun updateModuleConfig(moduleName: String, edit: UiModuleConfigEdit): UiConfigResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiConfigResult(
            false, "No module '$moduleName'."
        )
        val project =
            ctx.projectOf(module) ?: return UiConfigResult(false, "No project owns '$moduleName'.")
        val newLevel =
            edit.languageLevel?.let { runCatching { LanguageLevel.valueOf(it) }.getOrNull() }
        if (edit.languageLevel != null && newLevel == null) return UiConfigResult(
            false, "Unknown language level '${edit.languageLevel}'."
        )
        val facets = ArrayList<dev.ide.model.Facet>()
        for ((table, values) in edit.facetValues) {
            // Overlay the UI-sent values on the facet's current encoded values so keys the Settings tab does
            // not render (e.g. the `packaging` block, edited on its own tab) survive a Settings save.
            val existing = module.facets.all.firstNotNullOfOrNull { f ->
                ctx.store.facetCodecs.encode(f)?.takeIf { it.tomlTable == table }?.values
            } ?: emptyMap()
            val merged = existing + values
            val facet = ctx.store.facetCodecs.decode(dev.ide.model.impl.FacetData(table, merged))
                ?: return UiConfigResult(false, "No codec registered for facet '$table'.")
            facets += facet
        }
        try {
            project.beginModification().apply {
                val mod = module(module.id)
                if (newLevel != null) mod.languageLevel = newLevel
                // null = leave unchanged; blank = clear the override (follow the module-type default);
                // otherwise pin the named platform SDK. Drives which platform the module compiles against.
                edit.platformSdk?.let { mod.sdk = it.ifBlank { null }?.let(::SdkRef) }
                facets.forEach { mod.putFacet(it) }
                commit()
            }
        } catch (e: Exception) {
            return UiConfigResult(false, "Update failed: ${e.message}")
        }
        // The Run main-class override is a project preference (independent of the model transaction above);
        // a non-null value sets it, blank clears it back to auto-detect.
        edit.mainClass?.let { ctx.setMainClassOverride(module, it) }
        ctx.store.save()
        ctx.invalidateAnalyzers()       // language level + facets affect the compile classpath/source sets
        ctx.invalidateSyntheticClasses() // an Android facet change can move the R package
        ctx.resyncIndex()
        return UiConfigResult(true, "Saved ${module.name}")
    }

    /** The Android `buildFeatures` of [moduleName] as toggle descriptors, or null for a non-Android module. */
    fun getBuildFeatures(moduleName: String): UiBuildFeatures? {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return null
        val facet = module.facets.get(AndroidFacet.KEY) ?: return null
        val bf = facet.buildFeatures
        return UiBuildFeatures(
            moduleName,
            listOf(
                UiBuildFeature(
                    "viewBinding", "View Binding",
                    "Generate a type-safe binding class for each layout — a field per view id, plus inflate()/bind(), no findViewById.",
                    bf.viewBinding,
                    note = "Adds the ViewBinding runtime and generates a <Layout>Binding for every layout.",
                ),
                UiBuildFeature(
                    "compose", "Jetpack Compose",
                    "Compile @Composable UI with the Compose compiler and render @Preview composables in the editor.",
                    bf.compose,
                    note = "Adds the Compose compiler plugin and the Compose runtime + tooling dependencies.",
                ),
            ),
        )
    }

    /**
     * Toggle an Android build feature ([feature] = `viewBinding`/`compose`) on [moduleName]. Persists the
     * facet, then — when switching ON — adds the dependencies the feature needs (the ViewBinding/Compose
     * runtime), matching AGP's auto-provisioning. Turning a feature OFF only clears the flag; the
     * dependencies are left in place (removing them could break code that already uses the feature).
     */
    suspend fun setBuildFeature(
        moduleName: String, feature: String, enabled: Boolean
    ): UiConfigResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiConfigResult(
            false,
            "No module '$moduleName'."
        )
        val facet = module.facets.get(AndroidFacet.KEY) ?: return UiConfigResult(
            false,
            "'$moduleName' is not an Android module."
        )
        val project =
            ctx.projectOf(module) ?: return UiConfigResult(false, "No project owns '$moduleName'.")
        val bf = facet.buildFeatures
        val updated = when (feature) {
            "viewBinding" -> bf.copy(viewBinding = enabled)
            "compose" -> bf.copy(compose = enabled)
            else -> return UiConfigResult(false, "Unknown build feature '$feature'.")
        }
        if (updated == bf) return UiConfigResult(true, "No change.")
        try {
            project.beginModification().apply {
                module(module.id).putFacet(facet.copy(buildFeatures = updated))
                commit()
            }
        } catch (e: Exception) {
            return UiConfigResult(false, "Update failed: ${e.message}")
        }
        ctx.store.save()

        // Enabling a feature pulls in its runtime dependencies (AGP adds these for you). A resolution failure
        // (e.g. offline) doesn't fail the toggle — the flag is set; the deps can be retried from Dependencies.
        var depNote = ""
        if (enabled) {
            val coords = when (feature) {
                "viewBinding" -> AndroidFeatureDependencies.VIEW_BINDING
                "compose" -> AndroidFeatureDependencies.COMPOSE
                else -> emptyList()
            }
            val failures = ensureFeatureDependencies(moduleName, coords)
            if (failures.isNotEmpty()) depNote = " (couldn't add: ${failures.joinToString(", ")})"
        }

        ctx.invalidateAnalyzers()
        ctx.invalidateSyntheticClasses() // viewBinding on/off changes the synthetic binding classes
        ctx.resyncIndex()
        val verb = if (enabled) "Enabled" else "Disabled"
        return UiConfigResult(true, "$verb $feature on ${module.name}$depNote")
    }

    /** The Android packaging options of [moduleName] (Java-resource + native-lib merge rules), or null when
     *  it is not an Android module. Includes AGP's always-applied defaults so the UI can show them read-only. */
    fun getPackagingOptions(moduleName: String): UiPackagingOptions? {
        val facet = ctx.modules().firstOrNull { it.name == moduleName }?.facets?.get(AndroidFacet.KEY) ?: return null
        val p = facet.packaging
        return UiPackagingOptions(
            moduleName = moduleName,
            resources = UiPackagingRules(p.resources.excludes.toList(), p.resources.pickFirsts.toList(), p.resources.merges.toList()),
            jniLibs = UiPackagingRules(p.jniLibs.excludes.toList(), p.jniLibs.pickFirsts.toList()),
            defaultResourceExcludes = AndroidPackaging.DEFAULT_RESOURCE_EXCLUDES,
            defaultResourceMerges = AndroidPackaging.DEFAULT_RESOURCE_MERGES,
        )
    }

    /** Persist [moduleName]'s packaging merge rules. Blank patterns are dropped; all-empty clears the block
     *  (the codec then omits it, so the module falls back to the AGP defaults). */
    fun updatePackagingOptions(
        moduleName: String, resources: UiPackagingRules, jniLibs: UiPackagingRules
    ): UiConfigResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName }
            ?: return UiConfigResult(false, "No module '$moduleName'.")
        val facet = module.facets.get(AndroidFacet.KEY)
            ?: return UiConfigResult(false, "'$moduleName' is not an Android module.")
        val project = ctx.projectOf(module) ?: return UiConfigResult(false, "No project owns '$moduleName'.")
        fun clean(xs: List<String>): Set<String> = xs.map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
        val packaging = AndroidPackaging(
            resources = ResourcePackaging(clean(resources.excludes), clean(resources.pickFirsts), clean(resources.merges)),
            jniLibs = JniLibsPackaging(clean(jniLibs.excludes), clean(jniLibs.pickFirsts)),
        )
        if (packaging == facet.packaging) return UiConfigResult(true, "No change.")
        try {
            project.beginModification().apply {
                module(module.id).putFacet(facet.copy(packaging = packaging))
                commit()
            }
        } catch (e: Exception) {
            return UiConfigResult(false, "Update failed: ${e.message}")
        }
        ctx.store.save()
        ctx.invalidateAnalyzers()
        return UiConfigResult(true, "Saved packaging options for ${module.name}")
    }

    /** Add each of [coordinates] to [moduleName] unless a dependency on the same `group:name` already exists.
     *  Returns the coordinates that failed to resolve (best-effort; the caller surfaces them, not fatal). */
    private suspend fun ensureFeatureDependencies(
        moduleName: String, coordinates: List<String>
    ): List<String> {
        val failures = ArrayList<String>()
        for (coord in coordinates) {
            val module = ctx.modules().firstOrNull { it.name == moduleName } ?: break
            val groupName = coord.substringBeforeLast(':')   // group:name:version → group:name
            val present = module.dependencies.any {
                it is LibraryDependency && it.library.name.substringBeforeLast(':') == groupName
            }
            if (present) continue
            val r = ctx.dependencies.addDependency(moduleName, coord, "implementation")
            if (!r.success && !r.message.contains("already a dependency")) failures += coord
        }
        return failures
    }

    /** The directory `proguardFiles`/`consumerProguardFiles` entries resolve against (the module root,
     *  `<module>/build/classes` → `<module>`), or null when the layout is unexpected. */
    private fun moduleDirOf(module: Module): Path? = Paths.get(module.outputDir.path).parent?.parent

    /**
     * The build types' keep-rule files that are module-relative and missing on disk — the ones R8 would
     * silently skip on a `minifyEnabled` build. Bundled defaults (`proguard-android*.txt`) and absolute
     * entries are excluded; results are deduped by [entry], keeping the first build type that names it.
     */
    fun missingProguardFiles(moduleName: String): List<UiMissingProguardFile> {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return emptyList()
        val facet = module.facets.get(AndroidFacet.KEY) ?: return emptyList()
        val moduleDir = moduleDirOf(module) ?: return emptyList()
        val out = LinkedHashMap<String, UiMissingProguardFile>()
        fun consider(
            bt: dev.ide.android.support.BuildType, entries: List<String>, consumer: Boolean
        ) {
            for (e in entries) {
                if (dev.ide.android.support.DefaultProguardFiles.isDefault(e)) continue
                val p = Paths.get(e)
                if (p.isAbsolute) continue
                if (!Files.isRegularFile(moduleDir.resolve(e))) {
                    out.putIfAbsent(e, UiMissingProguardFile(bt.name, e, consumer))
                }
            }
        }
        for (bt in facet.buildTypes) {
            consider(bt, bt.proguardFiles, consumer = false)
            consider(bt, bt.consumerProguardFiles, consumer = true)
        }
        return out.values.toList()
    }

    /**
     * Create a referenced-but-missing module-relative keep-rule file [entry] (e.g. `proguard-rules.pro`)
     * with a starter template body, returning its path. Null for an unknown/non-Android module, a bundled
     * default or absolute entry, or an I/O failure. Already-present files are returned untouched.
     */
    fun createProguardFile(moduleName: String, entry: String): Path? {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return null
        module.facets.get(AndroidFacet.KEY) ?: return null
        if (dev.ide.android.support.DefaultProguardFiles.isDefault(entry)) return null
        val rel = Paths.get(entry)
        if (rel.isAbsolute) return null
        val moduleDir = moduleDirOf(module) ?: return null
        val target = moduleDir.resolve(entry)
        if (Files.exists(target)) return target
        return runCatching {
            target.parent?.let { Files.createDirectories(it) }
            Files.write(target, DEFAULT_PROGUARD_RULES.toByteArray())
            target
        }.getOrNull()
    }

    // ---- module management (add / remove modules) ----

    private fun moduleTypeRegistry() = ModuleTypeRegistry(ctx.platform.extensions)

    private fun isValidModuleName(n: String): Boolean = n.isNotEmpty() && n.first()
        .isLetter() && n.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * The module types a new module can be created as, each with the language-level choices and starter
     * facet panels derived from the type's default facets (so an Android module surfaces namespace/SDK
     * fields). Fields are codec-derived, so a new facet type appears here without bespoke UI.
     */
    fun availableModuleTypes(): List<UiModuleTypeOption> {
        val levels = LanguageLevel.values().map { it.name }
        return moduleTypeRegistry().all().map { type ->
            val facets = type.defaultFacets().mapNotNull { tmpl ->
                val codec = ctx.store.facetCodecs.codecFor(tmpl.key) ?: return@mapNotNull null
                UiFacetConfig(
                    codec.tomlTable,
                    titleCase(codec.tomlTable),
                    tmpl.defaults.map { (k, v) -> configFieldFor(k, v) })
            }
            UiModuleTypeOption(
                id = type.id,
                displayName = type.displayName,
                languageLevels = levels,
                defaultLanguageLevel = LanguageLevel.JAVA_17.name,
                defaultFacets = facets,
            )
        }
    }

    /**
     * Create a new module [name] of [typeId] with [languageLevel] and [facetValues], laying down the type's
     * default source-set directories on disk, persisting `module.toml`, and refreshing analyzers/index.
     */
    fun createModule(
        name: String,
        typeId: String,
        languageLevel: String?,
        facetValues: Map<String, Map<String, Any?>>
    ): UiConfigResult {
        val moduleName = name.trim()
        if (!isValidModuleName(moduleName)) return UiConfigResult(
            false, "Invalid module name — start with a letter; use letters, digits, '-' or '_'."
        )
        if (ctx.modules().any { it.name == moduleName }) return UiConfigResult(
            false, "A module named '$moduleName' already exists."
        )
        val type = moduleTypeRegistry().byId(typeId) ?: return UiConfigResult(
            false, "Unknown module type '$typeId'."
        )
        val project = ctx.store.workspace.projects.firstOrNull() ?: return UiConfigResult(
            false, "No project to add a module to."
        )
        val level = languageLevel?.let { runCatching { LanguageLevel.valueOf(it) }.getOrNull() }
        if (languageLevel != null && level == null) return UiConfigResult(
            false, "Unknown language level '$languageLevel'."
        )
        val facets = ArrayList<dev.ide.model.Facet>()
        for ((table, values) in facetValues) {
            val facet = ctx.store.facetCodecs.decode(dev.ide.model.impl.FacetData(table, values))
                ?: return UiConfigResult(false, "No codec registered for facet '$table'.")
            facets += facet
        }
        try {
            project.beginModification().apply {
                val mod = addModule(moduleName, type)
                if (level != null) mod.languageLevel = level
                facets.forEach { mod.putFacet(it) }
                // Types that contribute no default source sets (e.g. java-lib) still need somewhere to put
                // code — give them a conventional `src/main/java` so the module is usable immediately.
                if (type.defaultSourceSets().isEmpty()) {
                    mod.addSourceSet(
                        SourceSetTemplate(
                            "main",
                            DependencyScope.IMPLEMENTATION,
                            mapOf("src/main/java" to setOf(ContentRole.SOURCE))
                        )
                    )
                }
                commit()
            }
        } catch (e: Exception) {
            return UiConfigResult(false, "Couldn't create module: ${e.message}")
        }
        ctx.store.save()
        // Lay down the primary source-set directories so the tree shows them immediately. Only the `main`
        // source set is materialized — variant source sets (debug/release) and optional roots (a second
        // language dir, assets/aidl) are left uncreated until the user actually adds code there, so a fresh
        // module isn't cluttered with a dozen empty folders (they still resolve on demand via New ▸).
        ctx.modules().firstOrNull { it.name == moduleName }?.let { created ->
            val primarySets = created.sourceSets.filter { it.name == "main" }.ifEmpty { created.sourceSets }
            primarySets.forEach { ss ->
                materializedRoots(ss).forEach { cr -> runCatching { Files.createDirectories(Paths.get(cr.dir.path)) } }
            }
        }
        ctx.invalidateAnalyzers()
        ctx.invalidateSyntheticClasses()
        ctx.resyncIndex()
        return UiConfigResult(true, "Created module '$moduleName'")
    }

    /**
     * The content roots of [sourceSet] worth creating on disk for a freshly-made module: the primary source
     * dir (the first `SOURCE` root — e.g. `java`, not also an empty `kotlin` sibling) plus any resource / Android
     * `res` root. Optional roots (a second-language source dir, `assets`, `aidl`, generated) are omitted so the
     * module tree isn't littered with empty folders; the "New ▸" flow creates them on demand when needed.
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

    /**
     * Remove [name] from the project model (files left on disk), also dropping any module-on-module
     * dependency other modules declared on it. Refreshes analyzers/index.
     */
    fun removeModule(name: String): Boolean {
        val module = ctx.modules().firstOrNull { it.name == name } ?: return false
        val project = ctx.projectOf(module) ?: return false
        val id = module.id
        try {
            project.beginModification().apply {
                project.modules.forEach { other ->
                    if (other.id != id) other.dependencies.filterIsInstance<ModuleDependency>()
                        .filter { it.target == id }
                        .forEach { module(other.id).removeDependency(it) }
                }
                removeModule(id)
                commit()
            }
        } catch (e: Exception) {
            return false
        }
        ctx.store.save()
        ctx.invalidateAnalyzers()
        ctx.invalidateSyntheticClasses()
        ctx.resyncIndex()
        return true
    }

    /** Conventional source-set leaf-folder name → the [ContentRole] it implies. Drives both explicit
     *  "Add source root" presets and the folder-name auto-detect in [maybeRegisterSourceRoot]. */
    private val conventionRoles: Map<String, ContentRole> = mapOf(
        "java" to ContentRole.SOURCE,
        "kotlin" to ContentRole.SOURCE,
        "resources" to ContentRole.RESOURCE,
        "res" to ContentRole.ANDROID_RES,
        "assets" to ContentRole.ASSETS,
        "aidl" to ContentRole.AIDL,
    )

    /** The source-set names declared on [module], in declaration order. */
    fun sourceSetNamesOf(module: Module): List<String> = module.sourceSets.map { it.name }

    /**
     * The base directory a [sourceSetName]'s roots live under (e.g. `src/main`): the parent of its first
     * content root, or `<moduleRoot>/src/<name>` when the set is empty or absent. New roots go here.
     */
    fun sourceSetBaseFor(module: Module, sourceSetName: String): Path? {
        val moduleDir = ctx.moduleRoot(module) ?: return null
        val fallback = moduleDir.resolve("src").resolve(sourceSetName)
        val firstRoot =
            module.sourceSets.firstOrNull { it.name == sourceSetName }?.contentRoots?.firstOrNull()
                ?: return fallback
        return Paths.get(firstRoot.dir.path).parent ?: fallback
    }

    /**
     * Register a typed content root at `<set-base>/[dirName]` under [sourceSetName] of [moduleName]. See
     * [addSourceRootAt]. Returns the created directory, or null if the module/project can't be resolved.
     */
    fun addSourceRoot(
        moduleName: String, sourceSetName: String, dirName: String, roles: Set<ContentRole>
    ): Path? {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return null
        val base = sourceSetBaseFor(module, sourceSetName) ?: return null
        return addSourceRootAt(moduleName, sourceSetName, base.resolve(dirName), roles)
    }

    /**
     * Add [dir] as a content root with [roles] to [sourceSetName] of [moduleName] (creating the set if
     * needed): persist `module.toml`, create the directory on disk, then refresh analyzers/index. Returns
     * [dir] on success, or null if the module/project can't be resolved or [dir] isn't under the module.
     */
    private fun addSourceRootAt(
        moduleName: String, sourceSetName: String, dir: Path, roles: Set<ContentRole>
    ): Path? {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return null
        val project = ctx.projectOf(module) ?: return null
        val moduleDir = ctx.moduleRoot(module) ?: return null
        val target = dir.toAbsolutePath().normalize()
        val relPath = runCatching {
            moduleDir.toAbsolutePath().normalize().relativize(target).toString()
        }.getOrNull()?.replace('\\', '/')?.takeIf { it.isNotEmpty() && !it.startsWith("..") }
            ?: return null
        try {
            project.beginModification().apply {
                module(module.id).addContentRoot(sourceSetName, relPath, roles)
                commit()
            }
        } catch (e: Exception) {
            return null
        }
        ctx.store.save()
        runCatching { Files.createDirectories(target) }
        ctx.invalidateAnalyzers()
        if (ContentRole.ANDROID_RES in roles) ctx.invalidateSyntheticClasses()
        ctx.resyncIndex()
        return target
    }

    /** Remove the content root at [dirRelPath] (relative to the module dir) from [sourceSetName] of
     *  [moduleName]. Model-only — the directory on disk is left untouched. Returns true on a model change. */
    fun removeSourceRoot(moduleName: String, sourceSetName: String, dirRelPath: String): Boolean {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return false
        val project = ctx.projectOf(module) ?: return false
        try {
            project.beginModification().apply {
                module(module.id).removeContentRoot(sourceSetName, dirRelPath.replace('\\', '/'))
                commit()
            }
        } catch (e: Exception) {
            return false
        }
        ctx.store.save()
        ctx.invalidateAnalyzers()
        ctx.resyncIndex()
        return true
    }

    /** Create an empty source set [name] on [moduleName] (returns false if it already exists). */
    fun addSourceSet(moduleName: String, name: String): Boolean {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return false
        if (module.sourceSets.any { it.name == name }) return false
        val project = ctx.projectOf(module) ?: return false
        try {
            project.beginModification().apply {
                module(module.id).addSourceSet(
                    SourceSetTemplate(
                        name, DependencyScope.IMPLEMENTATION, emptyMap()
                    )
                )
                commit()
            }
        } catch (e: Exception) {
            return false
        }
        ctx.store.save()
        return true
    }

    /**
     * If [newDir] is a conventionally-named folder (`resources`/`java`/`kotlin`/`res`/`assets`/`aidl`)
     * created directly under a source-set base (the parent of an existing content root), register it as the
     * matching typed content root and return true. Conservative: a folder named `java` anywhere else stays
     * a plain folder. Called on every directory creation.
     */
    fun maybeRegisterSourceRoot(newDir: Path): Boolean {
        val role = conventionRoles[newDir.fileName?.toString()] ?: return false
        val dir = newDir.toAbsolutePath().normalize()
        val parent = dir.parent ?: return false
        for (module in ctx.modules()) {
            for (ss in module.sourceSets) {
                val roots =
                    ss.contentRoots.map { Paths.get(it.dir.path).toAbsolutePath().normalize() }
                if (roots.none { it.parent == parent }) continue
                if (dir in roots) return false // already a registered root
                return addSourceRootAt(module.name, ss.name, dir, setOf(role)) != null
            }
        }
        return false
    }

    /** Map a codec value to a typed UI field: Long→Number, Boolean→Bool, String→Text, lists→StringList/TableList. */
    private fun configFieldFor(key: String, value: Any?): UiConfigField = when (value) {
        is Boolean -> UiConfigField.Bool(key, humanizeKey(key), value)
        is Long -> UiConfigField.Number(key, humanizeKey(key), value)
        is Int -> UiConfigField.Number(key, humanizeKey(key), value.toLong())
        is Number -> UiConfigField.Number(key, humanizeKey(key), value.toLong())
        is String -> UiConfigField.Text(key, humanizeKey(key), value)
        is List<*> -> if (value.isNotEmpty() && value.all { it is Map<*, *> }) {
            @Suppress("UNCHECKED_CAST") val rows = value.map { row ->
                (row as Map<String, Any?>).map { (k, v) ->
                    configFieldFor(
                        k, v
                    )
                }
            }
            UiConfigField.TableList(key, humanizeKey(key), rows)
        } else {
            UiConfigField.StringList(key, humanizeKey(key), value.mapNotNull { it as? String })
        }

        else -> UiConfigField.Text(key, humanizeKey(key), value?.toString() ?: "")
    }

    private fun titleCase(s: String): String = s.replaceFirstChar { it.uppercase() }

    /** "compileSdk" → "Compile Sdk", "applicationIdSuffix" → "Application Id Suffix". */
    private fun humanizeKey(key: String): String =
        key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

    private companion object {
        /** The `[android]` codec key for the packaging block — edited via the Packaging tab, not the Settings fields. */
        const val PACKAGING_KEY = "packaging"
    }
}
