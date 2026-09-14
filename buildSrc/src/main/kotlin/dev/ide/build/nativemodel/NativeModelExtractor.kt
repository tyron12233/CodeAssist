package dev.ide.build.nativemodel

import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.attributes.Category
import java.io.File

/**
 * Reads the configured Gradle build into the neutral dump CodeAssist's own project model is bootstrapped
 * from (`.platform/gradle-model.json`). This is the Gradle half of self-hosting: Gradle is the only thing
 * that can evaluate this build's scripts, so it exports what it resolved, and the IDE imports the result
 * without ever parsing a `.gradle.kts` (which is what `GradleImport`'s tolerant reader tries to do, and
 * cannot do for a build that assigns `projectDir` from a map and spreads `include(...)` over 80 lines).
 *
 * What it exports is the *declared* model, not the resolved dependency graph: direct coordinates with the
 * versions the catalog gave them, module-to-module edges with their scopes, and the source roots that exist
 * on disk. CodeAssist resolves transitives itself, the same way it does for any project, so exporting the
 * flattened graph here would replace its resolver rather than feed it.
 *
 * ### Targets
 * A Kotlin Multiplatform module has several compilations; a CodeAssist module has one. [target] picks which
 * one the dump describes (`android` for the APK, `desktop` for the JVM shells), and the source sets and
 * dependency configurations of exactly that target (plus the shared ones) are what get emitted. A module
 * built for `android` therefore carries `commonMain` + `jvmShared` + `androidMain` and none of Skiko.
 */
internal object NativeModelExtractor {

    const val DUMP_VERSION = 1

    /** Source-set names that feed each target, nearest-shared first. `jvmShared` is this repo's own
     *  intermediate set (see gradle.properties); a module without it simply contributes no roots. */
    private val SOURCE_SETS_BY_TARGET = mapOf(
        "android" to listOf("commonMain", "jvmShared", "androidMain"),
        "desktop" to listOf("commonMain", "jvmShared", "desktopMain"),
    )

    private val TEST_SOURCE_SETS_BY_TARGET = mapOf(
        "android" to listOf("commonTest", "androidUnitTest"),
        "desktop" to listOf("commonTest", "desktopTest"),
    )

    /** Gradle configuration suffix -> the model scope it maps to. */
    private val SCOPE_BY_SUFFIX = linkedMapOf(
        "Api" to "API",
        "Implementation" to "IMPLEMENTATION",
        "CompileOnly" to "COMPILE_ONLY",
        "RuntimeOnly" to "RUNTIME_ONLY",
    )

    /** Plain (non-KMP) configuration names -> scope. */
    private val PLAIN_SCOPES = linkedMapOf(
        "api" to "API",
        "implementation" to "IMPLEMENTATION",
        "compileOnly" to "COMPILE_ONLY",
        "runtimeOnly" to "RUNTIME_ONLY",
        "testImplementation" to "TEST_IMPLEMENTATION",
        "testRuntimeOnly" to "TEST_IMPLEMENTATION",
    )

    private const val DEFAULT_COMPILE_SDK = 36
    private const val DEFAULT_MIN_SDK = 24

    fun extract(root: Project, target: String): String {
        require(target in SOURCE_SETS_BY_TARGET) { "unknown target '$target' (expected android or desktop)" }
        val modules = root.subprojects
            .filter { it.buildFile.isFile }
            .sortedBy { it.name }
            .mapNotNull { moduleOf(it, root, target) }
        return NativeModelJson.write(
            linkedMapOf(
                "version" to DUMP_VERSION,
                "name" to root.name,
                "target" to target,
                "repositories" to repositoriesOf(root),
                "modules" to modules,
            )
        )
    }

    // --- modules ---

    private fun moduleOf(p: Project, root: Project, target: String): Map<String, Any?>? {
        val kmp = p.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
        val app = p.plugins.hasPlugin("com.android.application")
        val androidLib = p.plugins.hasPlugin("com.android.kotlin.multiplatform.library") ||
            p.plugins.hasPlugin("com.android.library")
        // A module that compiles no JVM code at all builds nothing CodeAssist understands. `java-library`
        // counts: a module can be plain Java, or produce its artifact from a task rather than from sources
        // (this repository has both), and dropping it here would leave a hole its dependents point into.
        val kotlinJvm = p.plugins.hasPlugin("org.jetbrains.kotlin.jvm")
        val javaLibrary = p.plugins.hasPlugin("java-library") || p.plugins.hasPlugin("java")
        if (!kmp && !app && !kotlinJvm && !javaLibrary) return null

        val type = when {
            app -> "android-app"
            kmp && androidLib && target == "android" -> "android-lib"
            else -> "java-lib"
        }
        val sourceSets = if (kmp) kmpSourceSets(p, target) else plainSourceSets(p, app)
        val entry = linkedMapOf<String, Any?>(
            "name" to p.name,
            "dir" to p.projectDir.relativeTo(root.projectDir).invariantPath(),
            "type" to type,
            "multiplatform" to kmp,
            "sourceSets" to sourceSets,
            "dependencies" to (if (kmp) kmpDependencies(p, target) else plainDependencies(p)),
        )
        composeResources(p, if (kmp) SOURCE_SETS_BY_TARGET.getValue(target) else listOf("main"))
            ?.let { entry["composeResources"] = it }
        if (type != "java-lib") entry["android"] = androidFacet(p, app)
        return entry
    }

    // --- source sets ---

    private fun plainSourceSets(p: Project, android: Boolean): List<Map<String, Any?>> = listOfNotNull(
        sourceSet(p, "main", "IMPLEMENTATION", "src/main", android),
        sourceSet(p, "test", "TEST_IMPLEMENTATION", "src/test", android = false),
    )

    private fun kmpSourceSets(p: Project, target: String): List<Map<String, Any?>> {
        val android = target == "android"
        val sets = SOURCE_SETS_BY_TARGET.getValue(target)
        // Everything above the leaf platform set is COMMON: that is what the compiler has to be told, or an
        // `expect` and its `actual` are rejected as "declared in the same module". The leaf is last.
        val platformSet = sets.last()
        val main = sets.mapNotNull { name ->
            sourceSet(p, name, "IMPLEMENTATION", "src/$name", android)
                ?.let { if (name == platformSet) it else it + ("common" to true) }
        }
        val test = TEST_SOURCE_SETS_BY_TARGET.getValue(target)
            .mapNotNull { sourceSet(p, it, "TEST_IMPLEMENTATION", "src/$it", android = false) }
        return main + test
    }

    /**
     * One source set as the model holds it: content roots relative to the module directory, each with its
     * roles. Only roots that exist are emitted, so a module that has no `res/` does not declare one.
     */
    private fun sourceSet(
        p: Project,
        name: String,
        scope: String,
        base: String,
        android: Boolean,
    ): Map<String, Any?>? {
        val roots = linkedMapOf<String, List<String>>()
        fun add(rel: String, vararg roles: String) {
            if (File(p.projectDir, rel).isDirectory) roots[rel] = roles.toList()
        }
        add("$base/java", "source")
        add("$base/kotlin", "source")
        add("$base/resources", "resource")
        if (android) {
            add("$base/res", "android-res")
            add("$base/assets", "assets")
            add("$base/aidl", "aidl")
            add("$base/jniLibs", "jni-libs")
        }
        if (roots.isEmpty()) return null
        return linkedMapOf("name" to name, "scope" to scope, "roots" to roots)
    }

    /**
     * The `src/<set>/composeResources` roots plus the `compose.resources { }` configuration the Compose
     * Gradle plugin would generate the `Res` class from, which CodeAssist's own generator reproduces.
     * Null for a module with no such roots.
     */
    private fun composeResources(p: Project, sets: List<String>): Map<String, Any?>? {
        val dirs = sets.map { "src/$it/composeResources" }.filter { File(p.projectDir, it).isDirectory }
        if (dirs.isEmpty()) return null
        // `compose.resources { }` is an extension ON the compose extension, not a property of it.
        val resources = (p.extensions.findByName("compose") as? ExtensionAware)
            ?.extensions?.findByName("resources")
        return linkedMapOf(
            "dirs" to dirs,
            // The plugin defaults this to `<group>.<name>.generated.resources`; every module here sets it
            // explicitly, and a module that did not would generate into a package its sources do not import.
            "package" to (resources?.readProperty("getPackageOfResClass")?.propertyValue() as? String
                ?: "${p.group}.${p.name}.generated.resources".replace('-', '.')),
            "public" to ((resources?.readProperty("getPublicResClass")?.propertyValue() as? Boolean) ?: false),
        )
    }

    // --- dependencies ---

    private fun plainDependencies(p: Project): List<Map<String, Any?>> =
        PLAIN_SCOPES.flatMap { (config, scope) -> dependenciesOf(p, config, scope, variant = null) } +
            // An Android application's per-variant configurations (`debugImplementation`).
            listOf("debug", "release").flatMap { variant ->
                SCOPE_BY_SUFFIX.flatMap { (suffix, scope) ->
                    dependenciesOf(p, variant + suffix, scope, variant)
                }
            }

    private fun kmpDependencies(p: Project, target: String): List<Map<String, Any?>> {
        val main = SOURCE_SETS_BY_TARGET.getValue(target).flatMap { set ->
            SCOPE_BY_SUFFIX.flatMap { (suffix, scope) -> dependenciesOf(p, set + suffix, scope, variant = null) }
        }
        val test = TEST_SOURCE_SETS_BY_TARGET.getValue(target).flatMap { set ->
            SCOPE_BY_SUFFIX.keys.flatMap { suffix ->
                dependenciesOf(p, set + suffix, "TEST_IMPLEMENTATION", variant = null)
            }
        }
        return main + test
    }

    /**
     * The dependencies DECLARED in one configuration (never its resolved graph, and never a parent
     * configuration's contents: `configuration.dependencies` is exactly what the build script put there).
     */
    private fun dependenciesOf(
        p: Project,
        configName: String,
        scope: String,
        variant: String?,
    ): List<Map<String, Any?>> {
        val config = p.configurations.findByName(configName) ?: return emptyList()
        return config.dependencies.mapNotNull { d -> dependencyOf(d, scope, variant) }
    }

    private fun dependencyOf(d: Dependency, scope: String, variant: String?): Map<String, Any?>? {
        val base = linkedMapOf<String, Any?>()
        when {
            d is ProjectDependency -> {
                base["kind"] = "module"
                base["name"] = d.name
            }
            d is ExternalModuleDependency -> {
                val version = d.version ?: return null // a version-less declaration has no coordinate to record
                base["kind"] = if (isPlatform(d)) "platform" else "library"
                base["coordinate"] = listOfNotNull(d.group, d.name, version).joinToString(":")
            }
            else -> return null // file/self-resolving dependencies have no coordinate; the plugin stages those
        }
        base["scope"] = scope
        if (variant != null) base["variant"] = variant
        return base
    }

    /** Gradle marks `platform(...)` / `enforcedPlatform(...)` with the platform category attribute. */
    private fun isPlatform(d: ModuleDependency): Boolean {
        val category = d.attributes.getAttribute(Category.CATEGORY_ATTRIBUTE) ?: return false
        return category.name == Category.REGULAR_PLATFORM || category.name == Category.ENFORCED_PLATFORM
    }

    // --- android facet + repositories ---

    /**
     * The `android { }` configuration, as the `[android]` facet table. An application module carries it on
     * the `android` extension; a KMP library carries the same properties on its Android *target* inside
     * `kotlin { }`, which is why both are probed.
     */
    private fun androidFacet(p: Project, isApplication: Boolean): Map<String, Any?> {
        val source = p.extensions.findByName("android") ?: androidKmpTarget(p)
        // An application extension keeps the SDK levels and versioning on `defaultConfig`; a KMP library
        // target keeps its own `minSdk` directly. Probe both, nearest first.
        val defaultConfig = source?.readProperty("getDefaultConfig")
        fun level(getter: String): Int? = defaultConfig?.readInt(getter) ?: source?.readInt(getter)
        val facet = linkedMapOf<String, Any?>(
            "namespace" to (source?.readString("getNamespace") ?: "dev.ide.${p.name.replace('-', '.')}"),
            "compileSdk" to (source?.readInt("getCompileSdk") ?: DEFAULT_COMPILE_SDK),
            "minSdk" to (level("getMinSdk") ?: DEFAULT_MIN_SDK),
            "targetSdk" to (level("getTargetSdk") ?: level("getMinSdk") ?: DEFAULT_MIN_SDK),
            "isApplication" to isApplication,
            "buildFeatures" to buildFeatures(p),
        )
        defaultConfig?.readInt("getVersionCode")?.let { facet["versionCode"] = it }
        defaultConfig?.readString("getVersionName")?.let { facet["versionName"] = it }
        // Recorded for fidelity; AndroidFacet has no applicationId field (it derives one from the
        // namespace), so the importer reports it as dropped rather than silently renaming the app.
        defaultConfig?.readString("getApplicationId")?.let { facet["applicationId"] = it }
        return facet
    }

    /** The compiler-plugin toggles `android { buildFeatures { } }` carries, read off the applied plugins. */
    private fun buildFeatures(p: Project): Map<String, Any?> = linkedMapOf(
        "compose" to p.plugins.hasPlugin("org.jetbrains.kotlin.plugin.compose"),
        "parcelize" to p.plugins.hasPlugin("org.jetbrains.kotlin.plugin.parcelize"),
        "serialization" to p.plugins.hasPlugin("org.jetbrains.kotlin.plugin.serialization"),
    )

    /** The Android target of a KMP module (AGP's `com.android.kotlin.multiplatform.library`). */
    private fun androidKmpTarget(p: Project): Any? {
        val kotlin = p.extensions.findByName("kotlin") ?: return null
        val targets = runCatching { kotlin.javaClass.getMethod("getTargets").invoke(kotlin) }.getOrNull()
        return (targets as? Iterable<*>)?.firstOrNull { it != null && it.readString("getNamespace") != null }
    }

    /**
     * The Maven repositories dependency resolution uses. With `RepositoriesMode.FAIL_ON_PROJECT_REPOS` they
     * are declared in `settings.gradle.kts`, where a project-level task cannot reach them, so settings
     * records them on the root project as `nativeModelRepositories` (`name|url` pairs) and this reads them
     * back. A project that declares its own repositories has them merged in.
     */
    @Suppress("UNCHECKED_CAST")
    private fun repositoriesOf(root: Project): List<Map<String, Any?>> {
        val fromSettings = (root.extra("nativeModelRepositories") as? List<String>).orEmpty()
            .mapNotNull { entry ->
                val name = entry.substringBefore('|')
                val url = entry.substringAfter('|', "")
                if (url.isBlank()) null else linkedMapOf<String, Any?>("name" to name, "url" to url)
            }
        val fromProject = root.repositories.filterIsInstance<MavenArtifactRepository>()
            .map { linkedMapOf<String, Any?>("name" to it.name, "url" to it.url.toString()) }
        return (fromSettings + fromProject).distinctBy { it["url"] }
    }

    private fun Project.extra(name: String): Any? =
        if (extensions.extraProperties.has(name)) extensions.extraProperties.get(name) else null

    // --- reflection helpers (AGP types are not on buildSrc's compile classpath) ---

    private fun Any.readString(getter: String): String? =
        runCatching { javaClass.getMethod(getter).invoke(this) as? String }.getOrNull()

    private fun Any.readInt(getter: String): Int? =
        runCatching { javaClass.getMethod(getter).invoke(this) as? Int }.getOrNull()

    private fun Any.readProperty(getter: String): Any? =
        runCatching { javaClass.getMethod(getter).invoke(this) }.getOrNull()

    /** Unwraps a Gradle `Property<T>`; a plain value passes through. */
    private fun Any.propertyValue(): Any? =
        runCatching { javaClass.getMethod("getOrNull").invoke(this) }.getOrNull() ?: this

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')
}
