package dev.ide.core

import dev.ide.android.support.AndroidFacet
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.SourceSetTemplate
import dev.ide.model.LanguageLevel
import dev.ide.model.impl.ProjectModelStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Best-effort import of a legacy Gradle project (the structure older CodeAssist versions produced) into the
 * native project model, so it opens in **compatibility mode**. A tolerant, regex-based reader of the Gradle
 * scripts — NOT a Gradle evaluator — extracts what the model needs (modules, plugin/module type, the
 * `android {}` SDK/namespace, and `dependencies {}` coordinates), good enough to browse and edit the code.
 *
 * Deliberately partial: dependency *versions* aren't resolved here and build-script logic (variables,
 * `ext`, conditionals, custom tasks) is ignored, so a compatibility-mode project may show unresolved
 * symbols until its dependencies are re-added and may not build without adjustment. Imported projects are
 * marked with [markCompatibilityMode] so the UI can warn. Full Gradle sync is roadmap step 9.
 */
internal object GradleImport {

    private val SETTINGS_FILES = listOf("settings.gradle", "settings.gradle.kts")
    private val BUILD_FILES = listOf("build.gradle", "build.gradle.kts")
    private const val COMPAT_MARKER = "imported-from-gradle"

    /** True when [root] looks like a Gradle project (has a settings or build script). */
    fun isGradleProject(root: Path): Boolean =
        Files.isDirectory(root) && (SETTINGS_FILES + BUILD_FILES).any { Files.exists(root.resolve(it)) }

    // --- parsing ---

    enum class Kind { ANDROID_APP, ANDROID_LIB, JAVA }

    data class Dep(val coordinate: String, val scope: DependencyScope)
    data class ModuleDep(val name: String, val scope: DependencyScope)

    data class ModuleSpec(
        val name: String,
        val dirRel: String,
        val kind: Kind,
        val namespace: String?,
        val compileSdk: Int?,
        val minSdk: Int?,
        val targetSdk: Int?,
        val mavenDeps: List<Dep>,
        val moduleDeps: List<ModuleDep>,
    )

    data class ProjectSpec(val name: String, val modules: List<ModuleSpec>)

    /** Parse the Gradle project at [root], or null if it doesn't look importable. */
    fun parse(root: Path): ProjectSpec? {
        if (!isGradleProject(root)) return null
        val settings = SETTINGS_FILES.firstNotNullOfOrNull { readOrNull(root.resolve(it)) }
        val name = parseRootName(settings) ?: root.fileName?.toString() ?: "project"

        val paths = parseIncludes(settings).ifEmpty { discoverModuleDirs(root) }
        val modules = paths.mapNotNull { parseModule(root, it) }
        return if (modules.isEmpty()) null else ProjectSpec(name, modules.distinctBy { it.name })
    }

    private fun parseRootName(settings: String?): String? {
        if (settings == null) return null
        return Regex("""rootProject\.name\s*=\s*['"]([^'"]+)['"]""").find(settings)?.groupValues?.get(1)
    }

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

    private fun parseModule(root: Path, gradlePath: String): ModuleSpec? {
        val dirRel = gradlePath.trim(':').replace(':', '/')
        val dir = if (dirRel.isEmpty()) root else root.resolve(dirRel)
        if (!Files.isDirectory(dir)) return null
        val build = BUILD_FILES.firstNotNullOfOrNull { readOrNull(dir.resolve(it)) } ?: ""
        val name = (gradlePath.trimEnd(':').substringAfterLast(':')).ifEmpty {
            root.fileName?.toString() ?: "app"
        }

        val kind = when {
            "com.android.application" in build -> Kind.ANDROID_APP
            "com.android.library" in build -> Kind.ANDROID_LIB
            else -> Kind.JAVA
        }
        val namespace = firstGroup(build, """namespace\s*=?\s*['"]([\w.]+)['"]""")
            ?: firstGroup(build, """applicationId\s*=?\s*['"]([\w.]+)['"]""")
            ?: manifestPackage(dir)
        return ModuleSpec(
            name = name,
            dirRel = dirRel,
            kind = kind,
            namespace = namespace,
            compileSdk = firstInt(build, """compileSdk(?:Version)?\s*=?\s*\(?\s*(\d+)"""),
            minSdk = firstInt(build, """minSdk(?:Version)?\s*=?\s*\(?\s*(\d+)"""),
            targetSdk = firstInt(build, """targetSdk(?:Version)?\s*=?\s*\(?\s*(\d+)"""),
            mavenDeps = parseMavenDeps(build),
            moduleDeps = parseModuleDeps(build),
        )
    }

    private val SCOPE_KEYWORDS = mapOf(
        "api" to DependencyScope.API,
        "implementation" to DependencyScope.IMPLEMENTATION,
        "compile" to DependencyScope.IMPLEMENTATION, // ancient Gradle alias
        "compileOnly" to DependencyScope.COMPILE_ONLY,
        "provided" to DependencyScope.COMPILE_ONLY,
        "runtimeOnly" to DependencyScope.RUNTIME_ONLY,
        "testImplementation" to DependencyScope.TEST_IMPLEMENTATION,
    )

    /** `implementation 'g:a:v'` / `api "g:a:v"` lines → coordinate + scope (skips `project(...)`). */
    private fun parseMavenDeps(build: String): List<Dep> {
        val out = LinkedHashMap<String, Dep>()
        for (line in build.lineSequence()) {
            val scope = scopeOf(line) ?: continue
            if ("project(" in line) continue
            val coord = firstGroup(line, """['"]([\w.\-]+:[\w.\-]+(?::[\w.\-+]+)?)['"]""") ?: continue
            out.putIfAbsent(coord, Dep(coord, scope))
        }
        return out.values.toList()
    }

    /** `implementation project(':core')` / `project(path: ':core')` → module name + scope. */
    private fun parseModuleDeps(build: String): List<ModuleDep> {
        val out = LinkedHashMap<String, ModuleDep>()
        for (line in build.lineSequence()) {
            val scope = scopeOf(line) ?: continue
            if ("project(" !in line) continue
            val path = firstGroup(line, """project\s*\(\s*(?:path\s*[:=]\s*)?['"](:[\w:\-]+)['"]""") ?: continue
            val name = path.trimEnd(':').substringAfterLast(':')
            if (name.isNotEmpty()) out.putIfAbsent(name, ModuleDep(name, scope))
        }
        return out.values.toList()
    }

    private fun scopeOf(line: String): DependencyScope? {
        val keyword = Regex("""^\s*(\w+)[\s(]""").find(line)?.groupValues?.get(1) ?: return null
        return SCOPE_KEYWORDS[keyword]
    }

    private fun manifestPackage(dir: Path): String? {
        val manifest = readOrNull(dir.resolve("src/main/AndroidManifest.xml")) ?: return null
        return firstGroup(manifest, """package\s*=\s*"([\w.]+)"""")
    }

    private fun firstGroup(text: String, pattern: String): String? =
        Regex(pattern).find(text)?.groupValues?.get(1)

    private fun firstInt(text: String, pattern: String): Int? = firstGroup(text, pattern)?.toIntOrNull()

    private fun readOrNull(path: Path): String? =
        if (Files.isRegularFile(path)) runCatching { path.readText() }.getOrNull() else null

    // --- model building ---

    /** Author [spec] into [store] (workspace must be empty). Mirrors how the built-in templates build a project. */
    fun populate(store: ProjectModelStore, spec: ProjectSpec, languageLevel: LanguageLevel) {
        store.workspace.beginModification().apply {
            addProject(spec.name, BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }
        store.workspace.projects.first { it.name == spec.name }.beginModification().apply {
            for (m in spec.modules) {
                val typeId = when (m.kind) {
                    Kind.ANDROID_APP -> "android-app"
                    Kind.ANDROID_LIB -> "android-lib"
                    Kind.JAVA -> "java-lib"
                }
                val module = addModule(m.name, store.moduleTypes.resolve(typeId))
                module.languageLevel = languageLevel
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
                    else -> module.putFacet(
                        AndroidFacet(
                            namespace = m.namespace ?: "com.example.${m.name}",
                            compileSdk = m.compileSdk ?: 34,
                            minSdk = m.minSdk ?: 21,
                            targetSdk = m.targetSdk ?: m.minSdk ?: 21,
                            isApplication = m.kind == Kind.ANDROID_APP,
                        ),
                    )
                }
                for (d in m.moduleDeps) {
                    module.addDependency(ModuleDependency(ModuleId(d.name), d.scope, exported = d.scope == DependencyScope.API))
                }
                for (d in m.mavenDeps) {
                    module.addDependency(LibraryDependency(LibraryRef(d.coordinate), d.scope, exported = d.scope == DependencyScope.API))
                }
            }
            commit()
        }
    }

    // --- compatibility marker ---

    private fun markerFile(root: Path): Path = root.resolve(".platform").resolve(COMPAT_MARKER)

    /** Record that the project at [root] was imported from Gradle (so the UI shows a compatibility warning). */
    fun markCompatibilityMode(root: Path) {
        val file = markerFile(root)
        Files.createDirectories(file.parent)
        file.writeText("Imported from a Gradle project. Some features and builds may not be fully supported.\n")
    }

    /** True if the project at [root] was imported from Gradle. */
    fun isCompatibilityMode(root: Path): Boolean = Files.exists(markerFile(root))
}
