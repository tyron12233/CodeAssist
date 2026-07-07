package dev.ide.core

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.BuildFeatures
import dev.ide.android.support.BuildType
import dev.ide.android.support.ProductFlavor
import dev.ide.core.gradle.GradleScript
import dev.ide.core.gradle.GradleVersionCatalog
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.ModifiableModule
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.PlatformDependency
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ProjectModelStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The result of a re-sync from the Gradle scripts: whether it ran, a one-line message, and the reader notes. */
internal data class GradleSyncOutcome(val ok: Boolean, val message: String, val notes: List<String>)

/**
 * Best-effort import of a Gradle project into the native project model, so it opens in **compatibility
 * mode**. A tolerant, structure-aware reader of the Gradle scripts (see [GradleScript]) — NOT a Gradle
 * evaluator — extracts what the model needs: modules, plugin/module type, the `android {}` SDK/namespace/
 * build-types/flavors, and `dependencies {}` (inline coordinates, `project(...)`, `platform(...)` BOMs,
 * and version-catalog accessors like `libs.androidx.core.ktx`, with `$var`/`gradle.properties`
 * interpolation). Good enough to browse, edit, and re-sync the code.
 *
 * Deliberately partial: build-script *logic* (conditionals, custom tasks, computed values) is ignored and
 * some versions may be unresolved, so a compatibility-mode project may show unresolved symbols and may not
 * build without adjustment. Anything the reader couldn't extract is collected into a [SyncReport] and
 * surfaced in the UI. Imported projects are marked with [markCompatibilityMode]. Full Gradle sync is
 * roadmap step 9.
 */
internal object GradleImport {

    private val SETTINGS_FILES = listOf("settings.gradle", "settings.gradle.kts")
    private val BUILD_FILES = listOf("build.gradle", "build.gradle.kts")
    private val CATALOG_FILES = listOf("gradle/libs.versions.toml", "libs.versions.toml")
    private const val COMPAT_MARKER = "imported-from-gradle"

    /** True when [root] looks like a Gradle project (has a settings or build script). */
    fun isGradleProject(root: Path): Boolean =
        Files.isDirectory(root) && (SETTINGS_FILES + BUILD_FILES).any { Files.exists(root.resolve(it)) }

    // --- model ---

    enum class Kind { ANDROID_APP, ANDROID_LIB, JAVA }

    data class Dep(val coordinate: String, val scope: DependencyScope, val variant: String? = null)
    data class ModuleDep(val name: String, val scope: DependencyScope, val variant: String? = null)
    data class PlatformDep(val coordinate: String, val scope: DependencyScope, val variant: String? = null)
    data class BuildTypeSpec(val name: String, val minifyEnabled: Boolean, val shrinkResources: Boolean)
    data class FlavorSpec(val name: String, val dimension: String?)

    data class ModuleSpec(
        val name: String,
        val dirRel: String,
        val kind: Kind,
        val namespace: String?,
        val compileSdk: Int?,
        val minSdk: Int?,
        val targetSdk: Int?,
        val isKotlin: Boolean,
        val isCompose: Boolean,
        val mavenDeps: List<Dep>,
        val moduleDeps: List<ModuleDep>,
        val platformDeps: List<PlatformDep>,
        val flavorDimensions: List<String>,
        val buildTypes: List<BuildTypeSpec>,
        val productFlavors: List<FlavorSpec>,
    )

    /** Human-readable notes on what the tolerant reader could and couldn't extract (surfaced in the UI). */
    data class SyncReport(val notes: List<String>)

    data class ProjectSpec(val name: String, val modules: List<ModuleSpec>, val report: SyncReport)

    /** Parse the Gradle project at [root], or null if it doesn't look importable. */
    fun parse(root: Path): ProjectSpec? {
        if (!isGradleProject(root)) return null
        val notes = ArrayList<String>()
        val settings = SETTINGS_FILES.firstNotNullOfOrNull { readStripped(root.resolve(it)) }
        val name = parseRootName(settings) ?: root.fileName?.toString() ?: "project"

        val catalog = CATALOG_FILES.firstNotNullOfOrNull { readOrNull(root.resolve(it)) }
            ?.let { runCatching { GradleVersionCatalog.parse(it) }.getOrNull() } ?: GradleVersionCatalog.EMPTY
        val rootBuild = BUILD_FILES.firstNotNullOfOrNull { readStripped(root.resolve(it)) } ?: ""
        val rootVars = resolveVars(readProperties(root) + collectVars(rootBuild))

        val paths = parseIncludes(settings).ifEmpty { discoverModuleDirs(root) }
        val modules = paths.mapNotNull { parseModule(root, it, catalog, rootVars, notes) }
        if (modules.isEmpty()) return null
        if (!catalog.isEmpty) notes.add("Read a version catalog (gradle/libs.versions.toml).")
        return ProjectSpec(name, modules.distinctBy { it.name }, SyncReport(notes))
    }

    private fun parseRootName(settings: String?): String? =
        settings?.let { firstGroup(it, """rootProject\.name\s*=\s*['"]([^'"]+)['"]""") }

    /** Gradle paths from `include ':app', ':feature:core'` (Groovy + Kotlin DSL). */
    private fun parseIncludes(settings: String?): List<String> {
        if (settings == null) return emptyList()
        val out = LinkedHashSet<String>()
        for (line in settings.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("include")) continue
            for (m in Regex("""['"](:[^'"]+)['"]""").findAll(trimmed)) out.add(m.groupValues[1])
        }
        return out.toList()
    }

    /** Fallback when there's no `include`: child dirs that hold a build script, else `:app` / the root. */
    private fun discoverModuleDirs(root: Path): List<String> {
        val children = runCatching {
            Files.newDirectoryStream(root).use { stream ->
                stream.filter { Files.isDirectory(it) && BUILD_FILES.any { b -> Files.exists(it.resolve(b)) } }
                    .map { ":" + it.fileName.toString() }.sorted()
            }
        }.getOrDefault(emptyList())
        if (children.isNotEmpty()) return children
        if (Files.isDirectory(root.resolve("app"))) return listOf(":app")
        return if (Files.isDirectory(root.resolve("src"))) listOf(":") else emptyList()
    }

    private fun parseModule(
        root: Path,
        gradlePath: String,
        catalog: GradleVersionCatalog,
        rootVars: Map<String, String>,
        notes: MutableList<String>,
    ): ModuleSpec? {
        val dirRel = gradlePath.trim(':').replace(':', '/')
        val dir = if (dirRel.isEmpty()) root else root.resolve(dirRel)
        if (!Files.isDirectory(dir)) return null
        val build = BUILD_FILES.firstNotNullOfOrNull { readStripped(dir.resolve(it)) } ?: ""
        val name = (gradlePath.trimEnd(':').substringAfterLast(':')).ifEmpty {
            root.fileName?.toString() ?: "app"
        }
        val vars = resolveVars(rootVars + readProperties(dir) + collectVars(build))

        val plugins = collectPluginIds(build, catalog)
        val kind = when {
            "com.android.application" in plugins || "com.android.application" in build -> Kind.ANDROID_APP
            "com.android.library" in plugins || "com.android.library" in build -> Kind.ANDROID_LIB
            else -> Kind.JAVA
        }
        val android = GradleScript.blockBody(build, "android")
        val isKotlin = plugins.any { "kotlin" in it } || "kotlin(\"" in build
        val isCompose = plugins.any { "compose" in it } ||
            (android?.let { GradleScript.blockBody(it, "buildFeatures") }
                ?.let { Regex("""compose\s*=?\s*true""").containsMatchIn(it) } ?: false)

        val (maven, moduleDeps, platforms) =
            parseDependencies(GradleScript.blockBody(build, "dependencies") ?: "", catalog, vars, name, notes)

        return ModuleSpec(
            name = name,
            dirRel = dirRel,
            kind = kind,
            namespace = (android?.let { firstGroup(it, """namespace\s*=?\s*['"]([\w.]+)['"]""") })
                ?: firstGroup(build, """applicationId\s*=?\s*['"]([\w.]+)['"]""")
                ?: manifestPackage(dir),
            compileSdk = androidInt(android ?: build, """compileSdk(?:Version)?"""),
            minSdk = androidInt(android ?: build, """minSdk(?:Version)?"""),
            targetSdk = androidInt(android ?: build, """targetSdk(?:Version)?"""),
            isKotlin = isKotlin,
            isCompose = isCompose,
            mavenDeps = maven,
            moduleDeps = moduleDeps,
            platformDeps = platforms,
            flavorDimensions = android?.let { flavorDimensions(it) } ?: emptyList(),
            buildTypes = android?.let { parseBuildTypes(it) } ?: emptyList(),
            productFlavors = android?.let { parseProductFlavors(it) } ?: emptyList(),
        )
    }

    // --- plugins ---

    /** Plugin ids from the `plugins { }` block (`id`, `kotlin(...)`, catalog `alias`) + legacy `apply plugin:`. */
    private fun collectPluginIds(build: String, catalog: GradleVersionCatalog): Set<String> {
        val ids = LinkedHashSet<String>()
        GradleScript.blockBody(build, "plugins")?.let { body ->
            for (st in GradleScript.statements(body)) {
                firstGroup(st, """\bid\b\s*\(?\s*['"]([\w.\-]+)['"]""")?.let { ids.add(it) }
                firstGroup(st, """\bkotlin\b\s*\(?\s*['"]([\w.\-]+)['"]""")?.let { ids.add("org.jetbrains.kotlin.$it") }
                firstGroup(st, """alias\s*\(\s*libs\.plugins\.([\w.]+)""")?.let { catalog.plugin(it)?.let { p -> ids.add(p.id) } }
            }
        }
        for (m in Regex("""apply\s+plugin\s*:\s*['"]([\w.\-]+)['"]""").findAll(build)) ids.add(m.groupValues[1])
        return ids
    }

    // --- dependencies ---

    private val SCOPE_KEYWORDS = mapOf(
        "api" to DependencyScope.API,
        "implementation" to DependencyScope.IMPLEMENTATION,
        "compile" to DependencyScope.IMPLEMENTATION, // ancient Gradle alias
        "compileOnly" to DependencyScope.COMPILE_ONLY,
        "provided" to DependencyScope.COMPILE_ONLY,
        "runtimeOnly" to DependencyScope.RUNTIME_ONLY,
        "testImplementation" to DependencyScope.TEST_IMPLEMENTATION,
        "androidTestImplementation" to DependencyScope.TEST_IMPLEMENTATION,
        // Annotation/symbol processors: keep them on the compile classpath so their generated symbols resolve.
        "annotationProcessor" to DependencyScope.COMPILE_ONLY,
        "kapt" to DependencyScope.COMPILE_ONLY,
        "ksp" to DependencyScope.COMPILE_ONLY,
    )

    private val COORD_RE = Regex("""[\w.\-]+:[\w.\-]+(?::[\w.\-+]*)?""")

    private fun parseDependencies(
        depBody: String,
        catalog: GradleVersionCatalog,
        vars: Map<String, String>,
        module: String,
        notes: MutableList<String>,
    ): Triple<List<Dep>, List<ModuleDep>, List<PlatformDep>> {
        val maven = LinkedHashMap<String, Dep>()
        val modules = LinkedHashMap<String, ModuleDep>()
        val platforms = LinkedHashMap<String, PlatformDep>()

        fun addLib(coord: String, scope: DependencyScope, variant: String?, isPlatform: Boolean) {
            if (isPlatform) platforms.putIfAbsent("$coord|$variant", PlatformDep(coord, scope, variant))
            else maven.putIfAbsent("$coord|$variant", Dep(coord, scope, variant))
        }

        for (st in GradleScript.statements(depBody)) {
            val (scope, variant) = scopeAndVariant(st) ?: continue
            val isPlatform = Regex("""\b(?:enforced)?[Pp]latform\s*\(""").containsMatchIn(st)
            when {
                "project(" in st -> {
                    firstGroup(st, """project\s*\(\s*(?:path\s*[:=]\s*)?['"](:[\w:\-]+)['"]""")?.let { path ->
                        val n = path.trimEnd(':').substringAfterLast(':')
                        if (n.isNotEmpty()) modules.putIfAbsent(n, ModuleDep(n, scope, variant))
                    }
                }
                "libs.bundles." in st -> {
                    val alias = firstGroup(st, """libs\.bundles\.([\w.]+)""") ?: continue
                    val entries = catalog.bundle(alias)
                    if (entries.isEmpty()) notes.add("$module: unresolved catalog bundle `libs.bundles.$alias`.")
                    for (e in entries) addLib(e.coordinate, scope, variant, isPlatform)
                }
                Regex("""\blibs\.[\w.]+""").containsMatchIn(st) && "libs.plugins." !in st -> {
                    val alias = firstGroup(st, """\blibs\.([\w.]+)""") ?: continue
                    val e = catalog.library(alias)
                    if (e == null) notes.add("$module: unresolved catalog reference `libs.$alias`.")
                    else addLib(e.coordinate, scope, variant, isPlatform)
                }
                Regex("""\bkotlin\s*\(\s*['"]""").containsMatchIn(st) -> {
                    GradleScript.firstQuoted(st.substringAfter("kotlin"))
                        ?.let { addLib("org.jetbrains.kotlin:kotlin-$it", scope, variant, isPlatform) }
                }
                else -> coordinateFrom(st, vars, module, notes)?.let { addLib(it, scope, variant, isPlatform) }
            }
        }
        return Triple(maven.values.toList(), modules.values.toList(), platforms.values.toList())
    }

    /** The scope for a dependency statement's leading configuration, plus the build-variant qualifier a
     *  `debugImplementation`/`freeApi`-style config carries (`null` for a plain, shared configuration). */
    private fun scopeAndVariant(st: String): Pair<DependencyScope, String?>? {
        val kw = firstGroup(st, """^\s*([A-Za-z]\w*)[\s(]""") ?: return null
        SCOPE_KEYWORDS[kw]?.let { return it to null }
        fun variantScope(suffix: String, base: DependencyScope): Pair<DependencyScope, String?>? {
            if (!kw.endsWith(suffix) || kw.length == suffix.length) return null
            val prefix = kw.removeSuffix(suffix)
            return if (prefix == "test" || prefix == "androidTest") DependencyScope.TEST_IMPLEMENTATION to null
            else base to prefix.replaceFirstChar { it.lowercase() }
        }
        variantScope("Implementation", DependencyScope.IMPLEMENTATION)?.let { return it }
        variantScope("Api", DependencyScope.API)?.let { return it }
        variantScope("CompileOnly", DependencyScope.COMPILE_ONLY)?.let { return it }
        variantScope("RuntimeOnly", DependencyScope.RUNTIME_ONLY)?.let { return it }
        return null
    }

    /** Extract the coordinate string from a dependency statement, interpolating `$var`/`${var}`; null if none. */
    private fun coordinateFrom(st: String, vars: Map<String, String>, module: String, notes: MutableList<String>): String? {
        val quoted = Regex("""['"]([^'"]*)['"]""").findAll(st).map { it.groupValues[1] }
            .firstOrNull { it.contains(':') } ?: return null
        val resolved = interpolate(quoted, vars)
        if (resolved.contains('$')) {
            notes.add("$module: couldn't resolve a version variable in `$resolved`.")
            return null
        }
        return if (COORD_RE.matches(resolved)) resolved else null
    }

    // --- android block ---

    private fun androidInt(text: String, keyPattern: String): Int? =
        firstGroup(text, """$keyPattern\s*=?\s*\(?\s*(\d+)""")?.toIntOrNull()

    private fun flavorDimensions(android: String): List<String> =
        GradleScript.statements(android).filter { it.trimStart().startsWith("flavorDimensions") }
            .flatMap { Regex("""['"]([\w.\-]+)['"]""").findAll(it).map { m -> m.groupValues[1] } }
            .distinct()

    private val RESERVED_CONFIG_BLOCKS = setOf("all", "each", "configureEach", "forEach", "getByName", "named", "create", "register")

    private fun parseBuildTypes(android: String): List<BuildTypeSpec> {
        val body = GradleScript.blockBody(android, "buildTypes") ?: return emptyList()
        return GradleScript.childBlocks(body).filter { it.name !in RESERVED_CONFIG_BLOCKS }.map { b ->
            BuildTypeSpec(
                name = b.name,
                minifyEnabled = Regex("""(?:is)?[Mm]inifyEnabled\s*=?\s*true""").containsMatchIn(b.body),
                shrinkResources = Regex("""(?:is)?[Ss]hrinkResources\s*=?\s*true""").containsMatchIn(b.body),
            )
        }
    }

    private fun parseProductFlavors(android: String): List<FlavorSpec> {
        val body = GradleScript.blockBody(android, "productFlavors") ?: return emptyList()
        return GradleScript.childBlocks(body).filter { it.name !in RESERVED_CONFIG_BLOCKS }.map { b ->
            FlavorSpec(b.name, firstGroup(b.body, """dimension\s*=?\s*['"]([\w.\-]+)['"]"""))
        }
    }

    // --- variables ---

    /** Gather `ext {}` / `ext.x` / `def`/`val` string assignments from a build script (best-effort). */
    private fun collectVars(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun scanBlock(body: String) { for (st in GradleScript.statements(body)) assignment(st)?.let { out[it.first] = it.second } }
        GradleScript.blockBody(text, "ext")?.let(::scanBlock)
        GradleScript.blockBody(text, "buildscript")?.let { GradleScript.blockBody(it, "ext")?.let(::scanBlock) }
        for (st in GradleScript.statements(text)) {
            firstTwo(st, """^ext\.([\w.]+)\s*=\s*(.+)$""")?.let { out[it.first] = unquote(it.second) }
            firstTwo(st, """^(?:def|val)\s+(\w+)\s*=\s*(.+)$""")?.let { out[it.first] = unquote(it.second) }
        }
        return out
    }

    private fun assignment(st: String): Pair<String, String>? {
        val m = Regex("""^([A-Za-z_][\w.]*)\s*=\s*(.+)$""").find(st) ?: return null
        val value = m.groupValues[2].trim()
        if (value.startsWith("{") || value.startsWith("[")) return null
        return m.groupValues[1] to unquote(value)
    }

    /** Resolve one level of `$var` references between the collected variables so nested `def`s work. */
    private fun resolveVars(vars: Map<String, String>): Map<String, String> =
        vars.mapValues { interpolate(it.value, vars) }

    private fun interpolate(s: String, vars: Map<String, String>): String {
        var r = Regex("""\$\{([^}]+)}""").replace(s) { m -> vars[m.groupValues[1].trim()] ?: m.value }
        r = Regex("""\$([A-Za-z_][\w.]*)""").replace(r) { m -> vars[m.groupValues[1]] ?: m.value }
        return r
    }

    private fun unquote(v: String): String = v.trim().trim('"', '\'')

    private fun readProperties(dir: Path): Map<String, String> {
        val text = readOrNull(dir.resolve("gradle.properties")) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) continue
            val i = l.indexOf('=')
            if (i > 0) out[l.substring(0, i).trim()] = l.substring(i + 1).trim()
        }
        return out
    }

    // --- helpers ---

    private fun manifestPackage(dir: Path): String? =
        readOrNull(dir.resolve("src/main/AndroidManifest.xml"))?.let { firstGroup(it, """package\s*=\s*"([\w.]+)"""") }

    private fun firstGroup(text: String, pattern: String): String? =
        Regex(pattern).find(text)?.groupValues?.get(1)

    private fun firstTwo(text: String, pattern: String): Pair<String, String>? =
        Regex(pattern).find(text)?.let { it.groupValues[1] to it.groupValues[2] }

    private fun readOrNull(path: Path): String? =
        if (Files.isRegularFile(path)) runCatching { path.readText() }.getOrNull() else null

    private fun readStripped(path: Path): String? = readOrNull(path)?.let { GradleScript.stripComments(it) }

    // --- model building ---

    /** Author [spec] into [store] (workspace must be empty). Mirrors how the built-in templates build a project. */
    fun populate(store: ProjectModelStore, spec: ProjectSpec, languageLevel: LanguageLevel) {
        store.workspace.beginModification().apply {
            addProject(spec.name, BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }
        store.workspace.projects.first { it.name == spec.name }.beginModification().apply {
            for (m in spec.modules) {
                val module = addModule(m.name, store.moduleTypes.resolve(typeIdFor(m.kind)))
                module.languageLevel = languageLevel
                configureSourceSetsAndFacet(module, m)
                applyDependencies(module, m)
            }
            commit()
        }
    }

    /** Re-read the scripts at [store]'s root into the OPEN model: add any new modules, and refresh each
     *  module's declared dependencies + Android facet from the scripts. Returns (addedModules, updatedModules). */
    fun reconcile(store: ProjectModelStore, spec: ProjectSpec, languageLevel: LanguageLevel): Pair<Int, Int> {
        val project = store.workspace.projects.firstOrNull() ?: return 0 to 0
        val existing = project.modules.associateBy { it.name }
        var added = 0
        var updated = 0
        project.beginModification().apply {
            for (m in spec.modules) {
                val current = existing[m.name]
                if (current == null) {
                    val module = addModule(m.name, store.moduleTypes.resolve(typeIdFor(m.kind)))
                    module.languageLevel = languageLevel
                    configureSourceSetsAndFacet(module, m)
                    applyDependencies(module, m)
                    added++
                } else {
                    val module = module(current.id)
                    // The scripts are the source of truth: drop the previously-imported external/module
                    // dependencies and re-declare from the (re-read) scripts. SDK entries are left alone.
                    for (e in current.dependencies) {
                        if (e is LibraryDependency || e is PlatformDependency || e is ModuleDependency) module.removeDependency(e)
                    }
                    applyDependencies(module, m)
                    if (m.kind != Kind.JAVA) module.putFacet(buildFacet(m))
                    updated++
                }
            }
            commit()
        }
        return added to updated
    }

    private fun typeIdFor(kind: Kind): String = when (kind) {
        Kind.ANDROID_APP -> "android-app"
        Kind.ANDROID_LIB -> "android-lib"
        Kind.JAVA -> "java-lib"
    }

    private fun configureSourceSetsAndFacet(module: ModifiableModule, m: ModuleSpec) {
        when (m.kind) {
            Kind.JAVA -> module.addSourceSet(
                SourceSetTemplate(
                    "main",
                    DependencyScope.IMPLEMENTATION,
                    linkedMapOf(
                        "src/main/java" to setOf(ContentRole.SOURCE),
                        "src/main/kotlin" to setOf(ContentRole.SOURCE),
                    ),
                ),
            )
            // Android module types supply their own src/main/{java,kotlin,res,assets} source sets.
            else -> module.putFacet(buildFacet(m))
        }
    }

    private fun buildFacet(m: ModuleSpec): AndroidFacet = AndroidFacet(
        namespace = m.namespace ?: "com.example.${m.name}",
        compileSdk = m.compileSdk ?: 34,
        minSdk = m.minSdk ?: 21,
        targetSdk = m.targetSdk ?: m.minSdk ?: 21,
        isApplication = m.kind == Kind.ANDROID_APP,
        flavorDimensions = m.flavorDimensions,
        buildTypes = if (m.buildTypes.isEmpty()) AndroidFacet.DEFAULT_BUILD_TYPES
        else m.buildTypes.map { BuildType(it.name, minifyEnabled = it.minifyEnabled, shrinkResources = it.shrinkResources) },
        productFlavors = m.productFlavors.map { ProductFlavor(it.name, dimension = it.dimension) },
        buildFeatures = BuildFeatures(compose = m.isCompose),
    )

    private fun applyDependencies(module: ModifiableModule, m: ModuleSpec) {
        for (d in m.moduleDeps) {
            module.addDependency(ModuleDependency(ModuleId(d.name), d.scope, exported = d.scope == DependencyScope.API, variant = d.variant))
        }
        for (d in m.platformDeps) {
            coordinateOrNull(d.coordinate)?.let { module.addDependency(PlatformDependency(it, d.scope, variant = d.variant)) }
        }
        for (d in m.mavenDeps) {
            module.addDependency(LibraryDependency(LibraryRef(d.coordinate), d.scope, exported = d.scope == DependencyScope.API, variant = d.variant))
        }
    }

    private fun coordinateOrNull(coord: String): Coordinate? {
        val p = coord.split(":")
        return when (p.size) {
            2 -> Coordinate(p[0], p[1], "")
            3 -> Coordinate(p[0], p[1], p[2])
            else -> null
        }
    }

    // --- compatibility marker ---

    private fun markerFile(root: Path): Path = root.resolve(".platform").resolve(COMPAT_MARKER)

    /** Record that the project at [root] was imported from Gradle (so the UI shows a compatibility warning),
     *  storing the [notes] the reader produced so they can be surfaced later. */
    fun markCompatibilityMode(root: Path, notes: List<String> = emptyList()) {
        val file = markerFile(root)
        Files.createDirectories(file.parent)
        val summary = "Imported from a Gradle project. Some features and builds may not be fully supported."
        file.writeText((listOf(summary) + notes).joinToString("\n", postfix = "\n"))
    }

    /** True if the project at [root] was imported from Gradle. */
    fun isCompatibilityMode(root: Path): Boolean = Files.exists(markerFile(root))

    /** The reader notes recorded at import/sync time (empty if none / not a compatibility-mode project). */
    fun readNotes(root: Path): List<String> =
        readOrNull(markerFile(root))?.lineSequence()?.drop(1)?.filter { it.isNotBlank() }?.toList() ?: emptyList()
}
