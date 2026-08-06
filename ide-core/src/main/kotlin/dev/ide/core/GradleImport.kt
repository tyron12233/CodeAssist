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
import java.nio.file.StandardCopyOption
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
object GradleImport {

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
    data class BuildTypeSpec(
        val name: String,
        val minifyEnabled: Boolean,
        val shrinkResources: Boolean,
        val debuggable: Boolean? = null,
        val applicationIdSuffix: String? = null,
        val versionNameSuffix: String? = null,
        val proguardFiles: List<String> = emptyList(),
    )
    data class FlavorSpec(val name: String, val dimension: String?)

    /** A custom Maven repository declared in `settings.gradle`/`build.gradle` (name + URL). */
    data class RepoSpec(val name: String, val url: String)

    data class ModuleSpec(
        val name: String,
        val dirRel: String,
        val kind: Kind,
        val namespace: String?,
        val compileSdk: Int?,
        val minSdk: Int?,
        val targetSdk: Int?,
        val versionCode: Int?,
        val versionName: String?,
        val isKotlin: Boolean,
        val isCompose: Boolean,
        val viewBinding: Boolean,
        val parcelize: Boolean,
        val serialization: Boolean,
        val kspProcessors: Set<String>,
        val mavenDeps: List<Dep>,
        val moduleDeps: List<ModuleDep>,
        val platformDeps: List<PlatformDep>,
        val flavorDimensions: List<String>,
        val buildTypes: List<BuildTypeSpec>,
        val productFlavors: List<FlavorSpec>,
    )

    /** Human-readable notes on what the tolerant reader could and couldn't extract (surfaced in the UI). */
    data class SyncReport(val notes: List<String>)

    data class ProjectSpec(
        val name: String,
        val modules: List<ModuleSpec>,
        val report: SyncReport,
        val customRepos: List<RepoSpec> = emptyList(),
    )

    /**
     * A precompiled `.gradle.kts` script plugin discovered under `buildSrc`/`build-logic` ([text] is the
     * comment-stripped body), or a sentinel for a plugin id backed by an imperative `Plugin<Project>` Kotlin
     * class ([imperative] = true, [text] empty) whose configuration the tolerant reader can't evaluate.
     */
    internal data class ConventionScript(val text: String, val imperative: Boolean)

    /**
     * The `buildSrc`/`build-logic` convention plugins + shared `const val` version/coordinate constants a
     * modern multi-module Gradle build hides its dependencies and Android config in. Built once per
     * [parse]; a module applying a convention plugin id inherits that script's `android`/`dependencies`/
     * `plugins` blocks, and `implementation(Deps.x)`-style references resolve through [constants].
     */
    internal data class ConventionIndex(
        val conventions: Map<String, ConventionScript>,
        val constants: Map<String, String>,
    ) {
        val isEmpty: Boolean get() = conventions.isEmpty() && constants.isEmpty()

        companion object {
            val EMPTY = ConventionIndex(emptyMap(), emptyMap())
        }
    }

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
        val conventions = buildConventionIndex(root)

        val paths = parseIncludes(settings).ifEmpty { discoverModuleDirs(root) }
        val modules = paths.mapNotNull { parseModule(root, it, catalog, rootVars, conventions, notes) }
        if (modules.isEmpty()) return null
        if (!catalog.isEmpty) notes.add("Read a version catalog (gradle/libs.versions.toml).")
        if (conventions.conventions.isNotEmpty())
            notes.add("Read ${conventions.conventions.size} convention plugin(s) from buildSrc/build-logic.")
        val repos = parseRepositories(settings, rootBuild, notes)
        return ProjectSpec(name, modules.distinctBy { it.name }, SyncReport(notes), repos)
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
        conventions: ConventionIndex,
        notes: MutableList<String>,
    ): ModuleSpec? {
        val dirRel = gradlePath.trim(':').replace(':', '/')
        val dir = if (dirRel.isEmpty()) root else root.resolve(dirRel)
        if (!Files.isDirectory(dir)) return null
        val build = BUILD_FILES.firstNotNullOfOrNull { readStripped(dir.resolve(it)) } ?: ""
        val name = (gradlePath.trimEnd(':').substringAfterLast(':')).ifEmpty {
            root.fileName?.toString() ?: "app"
        }
        // Constants from buildSrc/build-logic (`object Deps { const val x = "…" }`) join the interpolation
        // map so `implementation(Deps.x)` and `"g:a:${Versions.y}"` resolve; module-local vars win.
        val vars = resolveVars(rootVars + conventions.constants + readProperties(dir) + collectVars(build))

        // Expand the module's own plugins into an EFFECTIVE set by following applied convention plugins, and
        // collect each convention's android/dependencies blocks (module's own FIRST, so it wins on merge).
        val plugins = LinkedHashSet(collectPluginIds(build, catalog))
        val androidTexts = ArrayList<String>()
        val depBodies = ArrayList<String>()
        GradleScript.blockBody(build, "android")?.let { androidTexts.add(it) }
        GradleScript.blockBody(build, "dependencies")?.let { depBodies.add(it) }
        run {
            val visited = HashSet<String>()
            val queue = ArrayDeque(plugins.toList())
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!visited.add(id)) continue
                val conv = conventions.conventions[id] ?: continue
                if (conv.imperative) {
                    notes.add("$name: applies convention plugin `$id` implemented as a Kotlin class; its " +
                        "Android/dependency config could not be read. Configure manually or re-sync after conversion.")
                    continue
                }
                for (p in collectPluginIds(conv.text, catalog)) { plugins.add(p); queue.add(p) }
                GradleScript.blockBody(conv.text, "android")?.let { androidTexts.add(it) }
                GradleScript.blockBody(conv.text, "dependencies")?.let { depBodies.add(it) }
            }
        }

        // Field chain: try each android block (module first, conventions after), then the loose build script.
        val androidSearch = androidTexts + build
        fun androidInt(keyPattern: String): Int? = androidSearch.firstNotNullOfOrNull { androidInt(it, keyPattern, catalog) }
        fun androidStr(pattern: String): String? = androidSearch.firstNotNullOfOrNull { firstGroup(it, pattern) }
        val hasAndroidBlock = androidTexts.isNotEmpty()

        var kind = when {
            "com.android.application" in plugins || "com.android.application" in build -> Kind.ANDROID_APP
            "com.android.library" in plugins || "com.android.library" in build -> Kind.ANDROID_LIB
            else -> Kind.JAVA
        }
        // Kind fallback: a module whose Android plugin is applied via an unreadable (imperative) convention
        // plugin looks like plain Java. Infer an Android module from a manifest / res dir so it isn't lost.
        if (kind == Kind.JAVA) {
            val manifest = dir.resolve("src/main/AndroidManifest.xml")
            val hasManifest = Files.exists(manifest)
            if (hasManifest || Files.isDirectory(dir.resolve("src/main/res"))) {
                val mtext = readOrNull(manifest) ?: ""
                kind = if (mtext.contains("<application") && mtext.contains("android.intent.action.MAIN"))
                    Kind.ANDROID_APP else Kind.ANDROID_LIB
                notes.add("$name: no Android Gradle plugin was found in the readable scripts (likely applied via " +
                    "a convention plugin); inferred an ${if (kind == Kind.ANDROID_APP) "application" else "library"} " +
                    "module from its manifest/resources. SDK levels and some settings may be incomplete.")
            }
        }

        val isKotlin = plugins.any { "kotlin" in it } || "kotlin(\"" in build
        val composeBuildFeature = androidTexts.any {
            GradleScript.blockBody(it, "buildFeatures")?.let { bf -> Regex("""compose\s*=?\s*true""").containsMatchIn(bf) } ?: false
        }
        val isCompose = plugins.any { "compose" in it } || composeBuildFeature
        val viewBinding = androidTexts.any {
            GradleScript.blockBody(it, "buildFeatures")?.let { bf -> Regex("""viewBinding\s*=?\s*true""").containsMatchIn(bf) } ?: false
        }
        val parcelize = plugins.any { "parcelize" in it }
        val serialization = plugins.any { "serialization" in it }

        val (maven, moduleDeps, platforms) =
            parseDependencies(depBodies.joinToString("\n"), catalog, vars, name, notes)

        if (hasAndroidBlock) noteUnmodeledAndroid(name, androidTexts, notes)

        val defaultConfig = androidSearch.firstNotNullOfOrNull { GradleScript.blockBody(it, "defaultConfig") }

        return ModuleSpec(
            name = name,
            dirRel = dirRel,
            kind = kind,
            namespace = androidStr("""namespace\s*=?\s*['"]([\w.]+)['"]""")
                ?: androidStr("""applicationId\s*=?\s*['"]([\w.]+)['"]""")
                ?: manifestPackage(dir),
            compileSdk = androidInt("""compileSdk(?:Version)?"""),
            minSdk = androidInt("""minSdk(?:Version)?"""),
            targetSdk = androidInt("""targetSdk(?:Version)?"""),
            versionCode = defaultConfig?.let { firstGroup(it, """versionCode\s*=?\s*\(?\s*(\d+)""")?.toIntOrNull() },
            versionName = defaultConfig?.let { firstGroup(it, """versionName\s*=?\s*['"]([^'"]+)['"]""") },
            isKotlin = isKotlin,
            isCompose = isCompose,
            viewBinding = viewBinding,
            parcelize = parcelize,
            serialization = serialization,
            kspProcessors = detectKspProcessors(maven.map { it.coordinate }),
            mavenDeps = maven,
            moduleDeps = moduleDeps,
            platformDeps = platforms,
            flavorDimensions = androidTexts.flatMap { flavorDimensions(it) }.distinct(),
            buildTypes = mergeByName(androidTexts.map { parseBuildTypes(it) }) { it.name },
            productFlavors = mergeByName(androidTexts.map { parseProductFlavors(it) }) { it.name },
        )
    }

    /** Merge lists of named specs (module block first), keeping the first occurrence of each name. */
    private fun <T> mergeByName(lists: List<List<T>>, name: (T) -> String): List<T> {
        val out = LinkedHashMap<String, T>()
        for (list in lists) for (item in list) out.putIfAbsent(name(item), item)
        return out.values.toList()
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

        // Resolve a dependency *reference* — a catalog accessor (`libs.x` / `libs.bundles.x`), a
        // `kotlin("x")` shorthand, or an inline `"g:a:v"` coordinate — into [addLib].
        fun addRef(text: String, scope: DependencyScope, variant: String?, isPlatform: Boolean) {
            when {
                "libs.bundles." in text -> {
                    val alias = firstGroup(text, """libs\.bundles\.([\w.]+)""") ?: return
                    val entries = catalog.bundle(alias)
                    if (entries.isEmpty()) notes.add("$module: unresolved catalog bundle `libs.bundles.$alias`.")
                    for (e in entries) addLib(e.coordinate, scope, variant, isPlatform)
                }
                Regex("""\blibs\.[\w.]+""").containsMatchIn(text) && "libs.plugins." !in text -> {
                    val alias = firstGroup(text, """\blibs\.([\w.]+)""") ?: return
                    val e = catalog.library(alias)
                    if (e == null) notes.add("$module: unresolved catalog reference `libs.$alias`.")
                    else addLib(e.coordinate, scope, variant, isPlatform)
                }
                Regex("""\bkotlin\s*\(\s*['"]""").containsMatchIn(text) -> {
                    GradleScript.firstQuoted(text.substringAfter("kotlin"))
                        ?.let { addLib("org.jetbrains.kotlin:kotlin-$it", scope, variant, isPlatform) }
                }
                else -> {
                    // A bare constant reference — `implementation(Deps.okhttp)` — resolved through the
                    // buildSrc/build-logic constants folded into [vars] (full name, then the last segment).
                    val bareRef = firstGroup(text, """^[A-Za-z]\w*\s*\(?\s*([A-Za-z_][\w.]*)\s*\)?\s*$""")
                    val resolved = bareRef?.let { vars[it] ?: vars[it.substringAfterLast('.')] }
                    if (resolved != null && COORD_RE.matches(resolved)) addLib(resolved, scope, variant, isPlatform)
                    else coordinateFrom(text, vars, module, notes)?.let { addLib(it, scope, variant, isPlatform) }
                }
            }
        }

        val statements = GradleScript.statements(depBody)

        // A BOM bound to a local val — `val composeBom = platform(libs.androidx.compose.bom)` — then used as
        // `implementation(composeBom)`. Map the var to the platform's reference so those uses resolve as a BOM
        // (otherwise every versionless catalog library the BOM aligns would fail to resolve a version).
        val platformVals = HashMap<String, String>()
        for (st in statements) {
            firstTwo(st, """^(?:val|def)\s+(\w+)\s*=\s*(?:enforced)?[Pp]latform\s*\(\s*(.+?)\s*\)\s*$""")
                ?.let { (name, arg) -> platformVals[name] = arg.trim() }
        }

        for (st in statements) {
            val (scope, variant) = scopeAndVariant(st) ?: continue
            val isPlatform = Regex("""\b(?:enforced)?[Pp]latform\s*\(""").containsMatchIn(st)
            // `implementation(composeBom)` where `composeBom = platform(...)` → resolve the BOM reference.
            val soleArg = firstGroup(st, """^[A-Za-z]\w*\s*\(?\s*([A-Za-z_]\w*)\s*\)?\s*$""")
            if (soleArg != null && soleArg in platformVals) {
                addRef(platformVals.getValue(soleArg), scope, variant, isPlatform = true)
                continue
            }
            if ("project(" in st) {
                firstGroup(st, """project\s*\(\s*(?:path\s*[:=]\s*)?['"](:[\w:\-]+)['"]""")?.let { path ->
                    val n = path.trimEnd(':').substringAfterLast(':')
                    if (n.isNotEmpty()) modules.putIfAbsent(n, ModuleDep(n, scope, variant))
                }
            } else {
                addRef(st, scope, variant, isPlatform)
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

    /** An android SDK int like `compileSdk 34` / `compileSdk = 34`, or the catalog form
     *  `compileSdk = libs.versions.compileSdk.get().toInt()` resolved through the version [catalog]. */
    private fun androidInt(text: String, keyPattern: String, catalog: GradleVersionCatalog): Int? {
        firstGroup(text, """$keyPattern\s*=?\s*\(?\s*(\d+)""")?.toIntOrNull()?.let { return it }
        val accessor = firstGroup(text, """$keyPattern\s*=?\s*\(?\s*libs\.versions\.([\w.]+)""") ?: return null
        // Drop trailing method calls (`.get`, `.toInt`, …) a segment at a time until the accessor resolves.
        var a = accessor
        repeat(5) {
            catalog.version(a)?.toIntOrNull()?.let { return it }
            if ('.' !in a) return null
            a = a.substringBeforeLast('.')
        }
        return null
    }

    private fun flavorDimensions(android: String): List<String> =
        GradleScript.statements(android).filter { it.trimStart().startsWith("flavorDimensions") }
            .flatMap { Regex("""['"]([\w.\-]+)['"]""").findAll(it).map { m -> m.groupValues[1] } }
            .distinct()

    private val RESERVED_CONFIG_BLOCKS = setOf("all", "each", "configureEach", "forEach", "getByName", "named", "create", "register")

    private fun parseBuildTypes(android: String): List<BuildTypeSpec> {
        val body = GradleScript.blockBody(android, "buildTypes") ?: return emptyList()
        return GradleScript.childBlocks(body).filter { it.name !in RESERVED_CONFIG_BLOCKS }.map { b ->
            val proguard = LinkedHashSet<String>()
            for (st in GradleScript.statements(b.body)) {
                if (!Regex("""proguardFiles?|setProguardFiles|getDefaultProguardFile""").containsMatchIn(st)) continue
                for (m in Regex("""['"]([^'"]+)['"]""").findAll(st)) proguard.add(m.groupValues[1])
            }
            BuildTypeSpec(
                name = b.name,
                minifyEnabled = Regex("""(?:is)?[Mm]inifyEnabled\s*=?\s*true""").containsMatchIn(b.body),
                shrinkResources = Regex("""(?:is)?[Ss]hrinkResources\s*=?\s*true""").containsMatchIn(b.body),
                debuggable = firstGroup(b.body, """(?:is)?[Dd]ebuggable\s*=?\s*(true|false)""")?.toBooleanStrictOrNull(),
                applicationIdSuffix = firstGroup(b.body, """applicationIdSuffix\s*=?\s*['"]([^'"]+)['"]"""),
                versionNameSuffix = firstGroup(b.body, """versionNameSuffix\s*=?\s*['"]([^'"]+)['"]"""),
                proguardFiles = proguard.toList(),
            )
        }
    }

    private val UNMODELED_ANDROID = listOf("buildConfigField", "resValue", "manifestPlaceholders", "abiFilters")

    /** Note the `android {}` features the model doesn't carry, so a converted project isn't silently missing them. */
    private fun noteUnmodeledAndroid(module: String, androidTexts: List<String>, notes: MutableList<String>) {
        val found = UNMODELED_ANDROID.filter { key -> androidTexts.any { it.contains(key) } }
        if (found.isNotEmpty()) notes.add(
            "$module: ${found.joinToString(", ")} isn't modeled and was skipped — set it manually if the build needs it.",
        )
    }

    /** Bundled KSP processors ([dev.ide.ksp.KspProcessorCatalog]) inferred from declared dependency coordinates.
     *  A hint only — real activation is probe-based at build time, so a miss is harmless and a wrong guess self-corrects. */
    private fun detectKspProcessors(coordinates: List<String>): Set<String> {
        val ids = LinkedHashSet<String>()
        for (c in coordinates) {
            val p = c.split(':')
            val group = p.getOrNull(0) ?: continue
            val artifact = p.getOrNull(1) ?: continue
            when {
                group == "androidx.room" && artifact.startsWith("room") -> ids.add("room")
                group == "com.squareup.moshi" && artifact.startsWith("moshi") -> ids.add("moshi")
                group == "com.google.dagger" && (artifact.startsWith("hilt") || artifact.startsWith("dagger")) -> ids.add("hilt")
                group.contains("bumptech.glide") -> ids.add("glide")
            }
        }
        return ids
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
        // NB: the closing brace is escaped (`\}`). The JVM regex engine tolerates a bare `}`, but ART's ICU
        // engine rejects it as a syntax error — so an unescaped `}` here throws PatternSyntaxException on device.
        // A dotted reference (`${Versions.room}`) is tried whole, then by its last segment, so a
        // buildSrc/build-logic constant keyed by its bare name (`room`) still resolves.
        var r = Regex("""\$\{([^}]+)\}""").replace(s) { m ->
            val k = m.groupValues[1].trim(); vars[k] ?: vars[k.substringAfterLast('.')] ?: m.value
        }
        r = Regex("""\$([A-Za-z_][\w.]*)""").replace(r) { m ->
            val k = m.groupValues[1]; vars[k] ?: vars[k.substringAfterLast('.')] ?: m.value
        }
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

    // --- convention plugins (buildSrc / build-logic) ---

    private val CONST_RE = Regex("""(?:const\s+)?val\s+(\w+)\s*(?::\s*[\w<>?., ]+)?\s*=\s*"([^"\\]*)"""")

    /** Scan buildSrc/build-logic for precompiled `.gradle.kts` script plugins, imperative plugin ids
     *  (`gradlePlugin { … id = "…" }`), and shared `const val` string constants. Tolerant; never throws. */
    private fun buildConventionIndex(root: Path): ConventionIndex {
        val roots = listOf(root.resolve("buildSrc"), root.resolve("build-logic")).filter { Files.isDirectory(it) }
        if (roots.isEmpty()) return ConventionIndex.EMPTY
        val conventions = LinkedHashMap<String, ConventionScript>()
        val constants = LinkedHashMap<String, String>()
        val imperativeIds = LinkedHashSet<String>()
        for (base in roots) runCatching {
            Files.walk(base).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val fn = file.fileName.toString()
                    val path = file.toString().replace('\\', '/')
                    when {
                        fn.endsWith(".gradle.kts") && "/src/main/" in path -> {
                            val text = readStripped(file) ?: return@forEach
                            val id = fn.removeSuffix(".gradle.kts")
                            conventions.putIfAbsent(id, ConventionScript(text, imperative = false))
                            firstGroup(text, """^\s*package\s+([\w.]+)""")?.let {
                                conventions.putIfAbsent("$it.$id", ConventionScript(text, imperative = false))
                            }
                        }
                        fn.endsWith(".kt") || fn == "build.gradle.kts" || fn == "build.gradle" -> {
                            val text = readStripped(file) ?: return@forEach
                            for (m in CONST_RE.findAll(text)) constants.putIfAbsent(m.groupValues[1], m.groupValues[2])
                            GradleScript.blockBody(text, "gradlePlugin")?.let { gp ->
                                for (m in Regex("""\bid\s*=\s*['"]([\w.\-]+)['"]""").findAll(gp)) imperativeIds.add(m.groupValues[1])
                            }
                        }
                    }
                }
            }
        }
        // A registered plugin id with no precompiled script → an imperative Plugin<Project> class we can't read.
        for (id in imperativeIds) conventions.putIfAbsent(id, ConventionScript("", imperative = true))
        return if (conventions.isEmpty() && constants.isEmpty()) ConventionIndex.EMPTY
        else ConventionIndex(conventions, constants)
    }

    // --- repositories ---

    private val DEFAULT_REPO_URLS = setOf(
        "https://repo1.maven.org/maven2",
        "https://dl.google.com/android/maven2",
    )

    /** Custom Maven repositories from `settings.gradle` (dependencyResolutionManagement / pluginManagement /
     *  top-level) and the root build's `allprojects`. Skips the built-in google()/mavenCentral() defaults. */
    private fun parseRepositories(settings: String?, rootBuild: String, notes: MutableList<String>): List<RepoSpec> {
        val bodies = ArrayList<String>()
        fun repos(container: String?) { container?.let { GradleScript.blockBody(it, "repositories")?.let(bodies::add) } }
        settings?.let {
            repos(GradleScript.blockBody(it, "dependencyResolutionManagement"))
            repos(GradleScript.blockBody(it, "pluginManagement"))
            GradleScript.blockBody(it, "repositories")?.let(bodies::add)
        }
        repos(GradleScript.blockBody(rootBuild, "allprojects"))
        GradleScript.blockBody(rootBuild, "repositories")?.let(bodies::add)

        val out = LinkedHashMap<String, RepoSpec>()
        val computedNote = "Skipped a custom Maven repository with a computed URL; add it under Dependencies → Repositories."
        fun add(name: String, url: String) {
            val clean = name.replace('\t', ' ').replace('\n', ' ').trim().ifEmpty { hostName(url) }
            out.putIfAbsent(url.trimEnd('/'), RepoSpec(clean, url))
        }
        for (body in bodies) {
            for (b in GradleScript.childBlocks(body)) {
                // `maven { url = uri("…") }` (name "maven") or `maven("…") { … }` (name = the URL literal).
                if (b.name.startsWith("http")) { add(hostName(b.name), b.name); continue }
                if (b.name != "maven") continue
                val url = firstGroup(b.body, """\b(?:url|uri|setUrl)\b[^'"]*['"](https?://[^'"]+)['"]""")
                    ?: firstGroup(b.body, """['"](https?://[^'"]+)['"]""")
                if (url == null) { notes.add(computedNote); continue }
                add(firstGroup(b.body, """name\s*=?\s*['"]([^'"]+)['"]""") ?: hostName(url), url)
            }
            for (st in GradleScript.statements(body)) {
                if (st.contains("{")) continue
                if (firstGroup(st, """^([A-Za-z]\w*)\s*\(""") != "maven") continue
                val url = firstGroup(st, """['"](https?://[^'"]+)['"]""")
                if (url == null) notes.add(computedNote) else add(hostName(url), url)
            }
        }
        return out.values.toList()
    }

    private fun hostName(url: String): String =
        url.substringAfter("://").substringBefore('/').removePrefix("www.").ifEmpty { url }

    /** Merge [repos] into `<root>/.platform/repositories.txt` (the tab-delimited format DependencyService reads),
     *  keeping any repo already there (a re-sync must not drop a manually-added one) and skipping the defaults. */
    fun writeRepositories(root: Path, repos: List<RepoSpec>) {
        val file = root.resolve(".platform").resolve("repositories.txt")
        val merged = LinkedHashMap<String, RepoSpec>()
        readOrNull(file)?.lineSequence()?.forEach { line ->
            val p = line.split('\t')
            if (p.size == 2 && p[1].isNotBlank()) merged[p[1].trimEnd('/')] = RepoSpec(p[0], p[1])
        }
        for (r in repos) {
            val key = r.url.trimEnd('/')
            if (key in DEFAULT_REPO_URLS) continue
            merged.putIfAbsent(key, r)
        }
        if (merged.isEmpty()) return
        Files.createDirectories(file.parent)
        file.writeText(merged.values.joinToString("") { "${it.name}\t${it.url}\n" })
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
        // Only override the facet defaults when actually parsed, so an unset value keeps deferring to the
        // manifest (AndroidFacet's DSL-wins rule).
        versionCode = m.versionCode ?: AndroidFacet.DEFAULT_VERSION_CODE,
        versionName = m.versionName ?: AndroidFacet.DEFAULT_VERSION_NAME,
        isApplication = m.kind == Kind.ANDROID_APP,
        flavorDimensions = m.flavorDimensions,
        buildTypes = if (m.buildTypes.isEmpty()) AndroidFacet.DEFAULT_BUILD_TYPES
        else m.buildTypes.map {
            BuildType(
                it.name,
                debuggable = it.debuggable ?: (it.name == "debug"),
                minifyEnabled = it.minifyEnabled,
                shrinkResources = it.shrinkResources,
                proguardFiles = it.proguardFiles,
                applicationIdSuffix = it.applicationIdSuffix,
                versionNameSuffix = it.versionNameSuffix,
            )
        },
        productFlavors = m.productFlavors.map { ProductFlavor(it.name, dimension = it.dimension) },
        buildFeatures = BuildFeatures(
            viewBinding = m.viewBinding,
            compose = m.isCompose,
            parcelize = m.parcelize,
            serialization = m.serialization,
            kspProcessors = m.kspProcessors,
        ),
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

    // --- convert to a native CodeAssist project ---

    private const val BACKUP_DIR = "gradle-backup"

    /** Gradle build files moved into the backup on convert (matched by name, at any depth). */
    private val GRADLE_FILE_NAMES = setOf(
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "gradle.properties", "gradlew", "gradlew.bat", "local.properties",
    )

    /** Root-level Gradle directories moved wholesale on convert (root depth only, so a nested module dir
     *  that happens to be named `gradle` isn't swept up). */
    private val GRADLE_ROOT_DIRS = setOf("gradle", "buildSrc", "build-logic")

    /** The outcome of a [convertToNative]/[revertToGradle]: whether it ran, a one-line message, reversibility. */
    data class ConvertOutcome(val ok: Boolean, val message: String, val canRevert: Boolean = false)

    private fun backupDir(root: Path): Path = root.resolve(".platform").resolve(BACKUP_DIR)

    /**
     * Convert a compatibility-mode project to a native one: MOVE (never delete) every Gradle build file into
     * `.platform/gradle-backup/` preserving structure, then drop the compat marker. The native model
     * (`workspace.json` / `module.toml` / `repositories.txt`) is the source of truth and is untouched, so no
     * engine swap or re-index is needed. The backup persists on disk → the move is reversible via
     * [revertToGradle]. On any I/O failure the partial move is rolled back and the marker is kept, so the
     * project is never left half-native.
     */
    fun convertToNative(root: Path): ConvertOutcome {
        if (!isCompatibilityMode(root)) return ConvertOutcome(false, "This project isn't a Gradle import.")
        val backup = backupDir(root)
        if (Files.exists(backup)) return ConvertOutcome(false, "This project was already converted.")

        val artifacts = collectGradleArtifacts(root)
        val moved = ArrayList<Pair<Path, Path>>()
        try {
            for (from in artifacts) {
                val to = backup.resolve(root.relativize(from))
                Files.createDirectories(to.parent)
                Files.move(from, to)
                moved.add(from to to)
            }
        } catch (e: Exception) {
            // Roll back so a failure never leaves a half-native project; the marker stays → still valid compat.
            for ((from, to) in moved.asReversed()) runCatching {
                Files.createDirectories(from.parent); Files.move(to, from)
            }
            runCatching { deleteTreeQuietly(backup) }
            return ConvertOutcome(false, "Conversion failed and was rolled back: ${e.message ?: e.javaClass.simpleName}")
        }
        runCatching { Files.deleteIfExists(markerFile(root)) }
        return ConvertOutcome(true, "Converted to a CodeAssist project.", canRevert = moved.isNotEmpty())
    }

    /** Restore a converted project: move the backed-up Gradle files back and re-enter compatibility mode. */
    fun revertToGradle(root: Path): ConvertOutcome {
        val backup = backupDir(root)
        if (!Files.isDirectory(backup)) return ConvertOutcome(false, "There's nothing to revert.")
        try {
            Files.walk(backup).use { stream ->
                stream.filter { Files.isRegularFile(it) }.sorted().forEach { f ->
                    val dest = root.resolve(backup.relativize(f))
                    Files.createDirectories(dest.parent)
                    Files.move(f, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            deleteTreeQuietly(backup)
        } catch (e: Exception) {
            return ConvertOutcome(false, "Revert failed: ${e.message ?: e.javaClass.simpleName}")
        }
        markCompatibilityMode(root, parse(root)?.report?.notes ?: emptyList())
        return ConvertOutcome(true, "Restored the Gradle build files.")
    }

    /** The Gradle build files/dirs to move on convert: the root-level Gradle dirs wholesale, plus every
     *  build file (by name) at any depth outside `.platform` and outside those wholesale dirs. */
    private fun collectGradleArtifacts(root: Path): List<Path> {
        val out = LinkedHashSet<Path>()
        for (d in GRADLE_ROOT_DIRS) root.resolve(d).takeIf { Files.isDirectory(it) }?.let(out::add)
        runCatching {
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { f ->
                    val top = root.relativize(f).getName(0).toString()
                    if (top == ".platform" || top in GRADLE_ROOT_DIRS) return@forEach
                    if (f.fileName.toString() in GRADLE_FILE_NAMES) out.add(f)
                }
            }
        }
        return out.toList()
    }

    private fun deleteTreeQuietly(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
