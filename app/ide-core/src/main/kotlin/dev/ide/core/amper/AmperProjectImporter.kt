package dev.ide.core.amper

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.BuildFeatures
import dev.ide.build.jvm.compose.ComposeResourcesFacet
import dev.ide.build.jvm.compose.ComposeResourcesFacetCodec
import dev.ide.core.gradle.GradleImport
import dev.ide.core.gradle.GradleKmp
import dev.ide.core.gradle.GradleProjectExport
import dev.ide.core.gradle.GradleVersionCatalog
import dev.ide.core.kmp.KmpShapes
import dev.ide.lang.kotlin.build.KotlinFacet
import dev.ide.lang.kotlin.build.KotlinFacetCodec
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.sync.Detection
import dev.ide.model.sync.ExternalDependency
import dev.ide.model.sync.ExternalFacet
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalProjectModel
import dev.ide.model.sync.ExternalSourceSet
import dev.ide.model.sync.ModelOwnership
import dev.ide.model.sync.ProjectImporter
import dev.ide.model.sync.SyncMessage
import dev.ide.model.sync.SyncOutcome
import dev.ide.model.sync.SyncRequest
import dev.ide.model.sync.SyncSeverity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Opens a JetBrains Amper project (`project.yaml` + one `module.yaml` per module) by reading its files, the
 * way [dev.ide.core.gradle.GradleProjectImporter] reads Gradle scripts: the YAML stays the source of truth
 * and each sync re-derives the model from it.
 *
 * Amper's conventions, as mapped here:
 *  * **Products.** `jvm/app`/`jvm/lib` become a JVM module, `android/app` an Android application, and a
 *    `kmp/lib` (or `lib`) its Android or JVM target, picked as for a Gradle KMP module ([GradleKmp]).
 *    `ios/app`, `js/app`, `wasm-js/app` and native products have nothing to build here and are left out,
 *    with a note.
 *  * **Layout.** `src/` is common code and `src@<platform>` the platform's; an alias (`aliases:
 *    - jvmAndAndroid: [jvm, android]`) gives an intermediate `src@jvmAndAndroid`. `test/` mirrors it. Android
 *    keeps `res/`, `assets/` and `src/AndroidManifest.xml` (a library's under `src@android/`), the JVM
 *    `resources/`, and Compose resources live in `composeResources/`.
 *  * **Dependencies.** `$libs.x` from the version catalog, Amper's built-in `$compose.x` and `$kotlin.x`,
 *    `//path` or `./path` for a module, and plain coordinates; `exported` is `api`, and `compile-only` /
 *    `runtime-only` are scopes. `dependencies@<platform>` applies when the platform (or an alias of it) is the
 *    one modeled.
 */
class AmperProjectImporter : ProjectImporter {

    override val id: BuildSystemId = BuildSystemId(ID)

    override val displayName: String = "Amper"

    override val ownership: ModelOwnership = ModelOwnership.EXTERNAL

    override fun detect(root: Path): Detection? {
        val markers = listOf(root.resolve(PROJECT_FILE), root.resolve(MODULE_FILE)).filter { Files.isRegularFile(it) }
        if (markers.isEmpty()) return null
        // Above the script reader: a folder with both is an Amper project that happens to keep a Gradle wrapper.
        return Detection(root.fileName?.toString() ?: "amper-project", markers, confidence = 15)
    }

    override fun syncFiles(): List<String> =
        listOf(PROJECT_FILE, MODULE_FILE, "**/$MODULE_FILE", "libs.versions.toml", "gradle/libs.versions.toml")

    override suspend fun resolve(request: SyncRequest): SyncOutcome {
        request.progress.report(-1.0, "Reading the Amper project files")
        val notes = ArrayList<String>()
        val root = request.root
        val dirs = moduleDirs(root, notes)
        if (dirs.isEmpty()) return SyncOutcome.failed("No Amper module.yaml was found to sync from.")
        val catalog = listOf("libs.versions.toml", "gradle/libs.versions.toml").map(root::resolve)
            .firstOrNull { Files.isRegularFile(it) }?.let { GradleVersionCatalog.parse(it.readText()) }
            ?: GradleVersionCatalog.EMPTY
        val platform = GradleKmp.preferredPlatform(root)
        val byDir = dirs.associateBy { it.normalize() }
        val modules = dirs.mapNotNull { dir ->
            runCatching { withAllTargets(root, dir, byDir, catalog, platform, notes) }
                .getOrElse { notes += "${dir.name}: module.yaml could not be read (${it.message})"; null }
        }
        if (modules.isEmpty()) return SyncOutcome.failed("No Amper module has a product CodeAssist can build.")
        val model = ExternalProjectModel(name = root.fileName?.toString() ?: "amper-project", buildSystemId = id, modules = modules)
        return SyncOutcome(model, notes.map { SyncMessage(SyncSeverity.WARNING, it) })
    }

    // ---------------------------------------------------------------------------------------------------------
    // Modules
    // ---------------------------------------------------------------------------------------------------------

    /** The module directories `project.yaml` lists (a trailing star segment, `libs/` then `*`, globs one level), or the root itself. */
    private fun moduleDirs(root: Path, notes: MutableList<String>): List<Path> {
        val project = root.resolve(PROJECT_FILE)
        if (!Files.isRegularFile(project)) {
            return if (Files.isRegularFile(root.resolve(MODULE_FILE))) listOf(root) else emptyList()
        }
        val listed = (AmperYaml.parse(project.readText()) as? Map<*, *>)?.get("modules") as? List<*> ?: emptyList<Any>()
        val out = ArrayList<Path>()
        for (entry in listed.mapNotNull { it as? String }) {
            val rel = entry.trim().removePrefix("./").trimEnd('/')
            if (rel.endsWith("/*")) {
                val parent = root.resolve(rel.removeSuffix("/*"))
                if (Files.isDirectory(parent)) Files.list(parent).use { s ->
                    s.filter { Files.isRegularFile(it.resolve(MODULE_FILE)) }.sorted().forEach { out.add(it) }
                }
            } else {
                val dir = root.resolve(rel)
                if (Files.isRegularFile(dir.resolve(MODULE_FILE))) out.add(dir)
                else notes += "$entry: listed in project.yaml but has no module.yaml"
            }
        }
        if (Files.isRegularFile(root.resolve(MODULE_FILE)) && root !in out) out.add(0, root)
        return out
    }

    /**
     * The module, and for a `kmp/lib` targeting both Android and the JVM, both shapes recorded in its `[kotlin]`
     * facet ([KmpShapes.describe]) so it switches natively like any multiplatform module.
     */
    private fun withAllTargets(
        root: Path,
        dir: Path,
        byDir: Map<Path, Path>,
        catalog: GradleVersionCatalog,
        preferred: GradleKmp.Platform,
        notes: MutableList<String>,
    ): ExternalModule? {
        val active = module(root, dir, byDir, catalog, preferred, notes) ?: return null
        val other = if (preferred == GradleKmp.Platform.ANDROID) GradleKmp.Platform.JVM else GradleKmp.Platform.ANDROID
        val alternate = module(root, dir, byDir, catalog, other, ArrayList()) ?: return active
        // Only a module whose shape depends on the platform has two targets (a jvm/app reads the same either way).
        if (alternate.typeId == active.typeId) return active
        fun id(m: ExternalModule) = if (m.typeId.startsWith("android")) KmpShapes.ANDROID else KmpShapes.JVM
        return KmpShapes.describe(
            listOf(KmpShapes.Shaped(id(active), id(active), active), KmpShapes.Shaped(id(alternate), id(alternate), alternate)),
            id(active),
        )
    }

    private enum class Kind { ANDROID_APP, ANDROID_LIB, JVM }

    private fun module(
        root: Path,
        dir: Path,
        byDir: Map<Path, Path>,
        catalog: GradleVersionCatalog,
        preferred: GradleKmp.Platform,
        notes: MutableList<String>,
    ): ExternalModule? {
        val name = if (dir == root) (root.fileName?.toString() ?: "app") else dir.name
        val yaml = AmperYaml.parse(dir.resolve(MODULE_FILE).readText()) as? Map<*, *> ?: emptyMap<Any, Any>()
        val product = yaml["product"]
        val type = (product as? String) ?: ((product as? Map<*, *>)?.get("type") as? String) ?: "jvm/lib"
        val platforms = ((product as? Map<*, *>)?.get("platforms") as? List<*>).orEmpty().mapNotNull { it as? String }

        val (kind, platform, multiplatform) = when (type) {
            "android/app" -> Triple(Kind.ANDROID_APP, "android", false)
            "android/lib" -> Triple(Kind.ANDROID_LIB, "android", false)
            "jvm/app", "jvm/lib" -> Triple(Kind.JVM, "jvm", false)
            "kmp/lib", "lib" -> {
                val android = "android" in platforms
                val jvm = "jvm" in platforms
                val choice = when {
                    android && jvm -> if (preferred == GradleKmp.Platform.ANDROID) "android" else "jvm"
                    android -> "android"
                    jvm -> "jvm"
                    else -> null
                }
                val skipped = platforms.filter { it != choice }
                if (skipped.isNotEmpty()) notes += "$name: Kotlin Multiplatform library, imported as its " +
                    "${choice ?: "common"} code. Not modeled: ${skipped.joinToString()}."
                Triple(if (choice == "android") Kind.ANDROID_LIB else Kind.JVM, choice ?: "common", true)
            }
            else -> {
                notes += "$name: an Amper `$type` module has nothing CodeAssist can build, so it was left out."
                return null
            }
        }

        val settings = yaml["settings"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val aliases = aliasesOf(yaml)
        // The intermediate source sets this platform belongs to: an alias naming it.
        val platformAliases = aliases.filter { (_, members) -> platform in members }.keys.toList()
        val qualifiers = platformAliases + platform

        val compose = settings["compose"]
        val composeEnabled = compose == "enabled" || ((compose as? Map<*, *>)?.get("enabled")).isTrue()
        val composeVersion = ((compose as? Map<*, *>)?.get("version") as? String) ?: DEFAULT_COMPOSE_VERSION
        val kotlin = settings["kotlin"] as? Map<*, *>
        val kotlinVersion = (kotlin?.get("version") as? String) ?: GradleProjectExport.KOTLIN_VERSION

        // --- source sets ---------------------------------------------------------------------------------
        val android = kind != Kind.JVM
        val main = LinkedHashMap<String, Set<ContentRole>>()
        val test = LinkedHashMap<String, Set<ContentRole>>()
        fun present(rel: String) = Files.isDirectory(dir.resolve(rel))
        fun qualified(base: String) = listOf(base) + qualifiers.map { "$base@$it" }
        // `src` is declared even before it exists, so the first file created lands in a source root.
        main["src"] = setOf(ContentRole.SOURCE)
        qualified("src").drop(1).filter(::present).forEach { main[it] = setOf(ContentRole.SOURCE) }
        qualified("resources").filter(::present).forEach { main[it] = setOf(ContentRole.RESOURCE) }
        if (android) {
            qualified("res").filter(::present).forEach { main[it] = setOf(ContentRole.ANDROID_RES) }
            qualified("assets").filter(::present).forEach { main[it] = setOf(ContentRole.ASSETS) }
        }
        qualified("test").filter(::present).forEach { test[it] = setOf(ContentRole.SOURCE) }
        qualified("testResources").filter(::present).forEach { test[it] = setOf(ContentRole.RESOURCE) }

        val composeDirs = qualified("composeResources").filter(::present)
        val sourceSets = buildList {
            add(ExternalSourceSet("main", DependencyScope.IMPLEMENTATION, main))
            if (test.isNotEmpty()) add(ExternalSourceSet("test", DependencyScope.TEST_IMPLEMENTATION, test))
            if (composeDirs.isNotEmpty()) add(
                ExternalSourceSet(
                    "composeResources", DependencyScope.IMPLEMENTATION,
                    linkedMapOf(
                        ComposeResourcesFacet.GENERATED_KOTLIN_ROOT to setOf(ContentRole.SOURCE),
                        ComposeResourcesFacet.GENERATED_RESOURCES_ROOT to setOf(ContentRole.RESOURCE),
                    ),
                )
            )
        }

        // --- dependencies --------------------------------------------------------------------------------
        val deps = ArrayList<ExternalDependency>()
        fun read(key: String, test: Boolean) {
            for (entry in (yaml[key] as? List<*>).orEmpty()) {
                dependency(entry, name, dir, root, byDir, catalog, composeVersion, kotlinVersion, test, notes)?.let(deps::add)
            }
        }
        for (key in yaml.keys.mapNotNull { it as? String }) {
            val (base, qualifier) = key.split('@', limit = 2).let { it[0] to it.getOrNull(1) }
            if (qualifier != null && qualifier !in qualifiers) continue
            when (base) {
                "dependencies" -> read(key, test = false)
                "test-dependencies" -> read(key, test = true)
            }
        }
        // Amper adds the Compose runtime itself when Compose is on.
        if (composeEnabled && deps.none { it is ExternalLibrary && it.coordinate.startsWith("org.jetbrains.compose.runtime:runtime:") }) {
            deps += ExternalLibrary("org.jetbrains.compose.runtime:runtime:$composeVersion")
        }

        // --- facets --------------------------------------------------------------------------------------
        val facets = ArrayList<ExternalFacet>()
        if (android) facets += ExternalFacet(AndroidFacetCodec.tomlTable, AndroidFacetCodec.encode(androidFacet(name, dir, kind, settings, composeEnabled)))
        val commonRoots = if (!multiplatform) emptyList()
        else (listOf("src") + platformAliases.map { "src@$it" }.filter(::present)) +
            (if (composeDirs.isNotEmpty()) listOf(ComposeResourcesFacet.GENERATED_KOTLIN_ROOT) else emptyList())
        val optIns = (kotlin?.get("optIns") as? List<*>).orEmpty().mapNotNull { it as? String }
        if (commonRoots.isNotEmpty() || optIns.isNotEmpty()) {
            facets += ExternalFacet(KotlinFacetCodec.tomlTable, KotlinFacetCodec.encode(KotlinFacet(commonRoots, optIns)))
        }
        if (composeDirs.isNotEmpty()) {
            val resources = (compose as? Map<*, *>)?.get("resources") as? Map<*, *>
            val pkg = (resources?.get("packageName") as? String)?.takeIf { it.isNotBlank() }
                ?: "${identifierOf(name.lowercase())}.generated.resources"
            val facet = ComposeResourcesFacet(pkg, publicResClass = resources?.get("exposedAccessors").isTrue(), roots = composeDirs)
            facets += ExternalFacet(ComposeResourcesFacetCodec.tomlTable, ComposeResourcesFacetCodec.encode(facet))
        }

        return ExternalModule(
            name = name,
            dirRelPath = if (dir == root) "." else root.relativize(dir).toString().replace('\\', '/'),
            typeId = when (kind) {
                Kind.ANDROID_APP -> "android-app"
                Kind.ANDROID_LIB -> "android-lib"
                Kind.JVM -> "java-lib"
            },
            sourceSets = sourceSets,
            dependencies = deps,
            facets = facets,
        )
    }

    /** `aliases: [ { jvmAndAndroid: [jvm, android] } ]` as alias -> member platforms. */
    private fun aliasesOf(yaml: Map<*, *>): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (entry in (yaml["aliases"] as? List<*>).orEmpty()) {
            (entry as? Map<*, *>)?.forEach { (k, v) ->
                val members = (v as? List<*>).orEmpty().mapNotNull { it as? String }
                if (k is String) out[k] = members
            }
        }
        return out
    }

    private fun androidFacet(name: String, dir: Path, kind: Kind, settings: Map<*, *>, compose: Boolean): AndroidFacet {
        val a = settings["android"] as? Map<*, *> ?: emptyMap<Any, Any>()
        fun int(key: String): Int? = when (val v = a[key]) {
            is String -> v.toIntOrNull()
            is Map<*, *> -> (v["apiLevel"] as? String)?.toIntOrNull()
            else -> null
        }
        val manifest = listOf("src/AndroidManifest.xml", "src@android/AndroidManifest.xml")
            .firstOrNull { Files.isRegularFile(dir.resolve(it)) } ?: "src/AndroidManifest.xml"
        val minSdk = int("minSdk") ?: DEFAULT_MIN_SDK
        return AndroidFacet(
            namespace = (a["namespace"] as? String) ?: (a["applicationId"] as? String) ?: "com.example.${identifierOf(name.lowercase())}",
            compileSdk = int("compileSdk") ?: AndroidApiLevelsDefault,
            minSdk = minSdk,
            targetSdk = int("targetSdk") ?: int("compileSdk") ?: minSdk,
            manifest = manifest,
            versionCode = int("versionCode") ?: AndroidFacet.DEFAULT_VERSION_CODE,
            versionName = (a["versionName"] as? String) ?: AndroidFacet.DEFAULT_VERSION_NAME,
            isApplication = kind == Kind.ANDROID_APP,
            buildFeatures = BuildFeatures(
                compose = compose,
                parcelize = (settings["kotlin"] as? Map<*, *>)?.get("parcelize").let { it == "enabled" || it.isTrue() },
                serialization = (settings["kotlin"] as? Map<*, *>)?.get("serialization").let { it == "enabled" || it.isTrue() || it is Map<*, *> },
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------------------------------------

    private fun dependency(
        entry: Any?,
        module: String,
        dir: Path,
        root: Path,
        byDir: Map<Path, Path>,
        catalog: GradleVersionCatalog,
        composeVersion: String,
        kotlinVersion: String,
        test: Boolean,
        notes: MutableList<String>,
    ): ExternalDependency? {
        // `- ref`, `- ref: exported`, `- ref: compile-only`, or `- ref: { scope: runtime-only, exported: true }`.
        val (ref, modifier) = when (entry) {
            is String -> entry to null
            is Map<*, *> -> (entry.keys.firstOrNull() as? String ?: return null) to entry.values.firstOrNull()
            else -> return null
        }
        val exported = modifier == "exported" || (modifier as? Map<*, *>)?.get("exported").isTrue()
        val scopeName = (modifier as? String)?.takeIf { it != "exported" } ?: ((modifier as? Map<*, *>)?.get("scope") as? String)
        val scope = when {
            test -> DependencyScope.TEST_IMPLEMENTATION
            scopeName == "compile-only" -> DependencyScope.COMPILE_ONLY
            scopeName == "runtime-only" -> DependencyScope.RUNTIME_ONLY
            exported -> DependencyScope.API
            else -> DependencyScope.IMPLEMENTATION
        }
        return when {
            ref.startsWith("//") || ref.startsWith("./") || ref.startsWith("../") -> {
                val target = (if (ref.startsWith("//")) root.resolve(ref.removePrefix("//")) else dir.resolve(ref)).normalize()
                val found = byDir[target]
                if (found == null) { notes += "$module: no module at `$ref`"; null }
                else ExternalModuleRef(if (found == root) (root.fileName?.toString() ?: "app") else found.name, scope)
            }
            ref.startsWith("\$libs.") -> {
                val lib = catalog.library(ref.removePrefix("\$libs."))
                if (lib == null) { notes += "$module: the version catalog has no `${ref.removePrefix("$")}`"; null }
                else ExternalLibrary(lib.coordinate, scope)
            }
            ref.startsWith("\$compose.") -> {
                val coord = GradleImport.composeAccessorCoordinate(ref.removePrefix("\$compose."), composeVersion)
                if (coord == null) { notes += "$module: `$ref` is not a Compose artifact CodeAssist knows"; null }
                else ExternalLibrary(coord, scope)
            }
            ref.startsWith("\$kotlin.") -> {
                val artifact = when (val k = ref.removePrefix("\$kotlin.")) {
                    "test" -> "kotlin-test"
                    "test.junit" -> "kotlin-test-junit"
                    "test.junit5" -> "kotlin-test-junit5"
                    "reflect" -> "kotlin-reflect"
                    "stdlib" -> "kotlin-stdlib"
                    else -> "kotlin-$k"
                }
                ExternalLibrary("org.jetbrains.kotlin:$artifact:$kotlinVersion", scope)
            }
            ref.startsWith("$") -> { notes += "$module: `$ref` names a catalog CodeAssist does not read"; null }
            ref.count { it == ':' } >= 2 -> ExternalLibrary(ref, scope)
            else -> { notes += "$module: `$ref` is not a dependency CodeAssist can read"; null }
        }
    }

    private fun Any?.isTrue(): Boolean = this == true || this == "true"

    /** A name as a package segment: lower-cased, anything Kotlin cannot take made `_`. */
    private fun identifierOf(name: String): String =
        name.map { if (it == '_' || it.isLetterOrDigit()) it else '_' }.joinToString("")
            .let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }

    companion object {
        const val ID = "amper"
        const val PROJECT_FILE = "project.yaml"
        const val MODULE_FILE = "module.yaml"

        /**
         * Compose Multiplatform when a module names no version: the one the IDE ships and its KMP template
         * declares, since a desktop Run executes the IDE's own copy of Compose (see `KmpTemplates`).
         */
        const val DEFAULT_COMPOSE_VERSION = "1.11.1"
        private const val DEFAULT_MIN_SDK = 21
        private val AndroidApiLevelsDefault get() = dev.ide.android.support.AndroidApiLevels.LATEST
    }
}
