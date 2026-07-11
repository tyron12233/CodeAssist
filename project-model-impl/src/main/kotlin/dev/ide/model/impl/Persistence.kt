package dev.ide.model.impl

import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Exclusion
import dev.ide.model.LanguageLevel
import dev.ide.model.PlatformKind
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.Coordinate
import dev.ide.model.ModuleId
import dev.ide.model.OrderEntry
import dev.ide.model.PlatformDependency
import dev.ide.model.SdkDependency
import dev.ide.model.SdkRef
import dev.ide.model.impl.format.Json
import dev.ide.model.impl.format.Toml
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText

/**
 * Crash-safe single-file writer: write to a sibling temp file, then atomically rename it over
 * the target. A process death mid-write leaves either the old file intact or the complete new file —
 * never a half-written model file. Falls back to a non-atomic replace only on filesystems that reject
 * `ATOMIC_MOVE`.
 */
object CrashSafeWriter {
    fun write(target: Path, content: String) {
        target.parent?.let { Files.createDirectories(it) }
        val tmp = target.resolveSibling("${target.fileName}.tmp.${System.nanoTime()}")
        Files.write(tmp, content.toByteArray(Charsets.UTF_8))
        try {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}

/**
 * Loads and saves a [WorkspaceData] snapshot as the on-disk format:
 *   `<root>/.platform/{workspace.json, libraries.json, sdks.json}` (model state, version-controlled)
 *   `<root>/<projectRoot>/<moduleDir>/module.toml` (one declarative manifest per module)
 * Every file is written via [CrashSafeWriter]; both top-level files carry a schema `version` gating
 * future migrations.
 */
object ModelPersistence {
    private const val PLATFORM_DIR = ".platform"
    private const val WORKSPACE_FILE = "workspace.json"
    private const val LIBRARIES_FILE = "libraries.json"
    private const val SDKS_FILE = "sdks.json"
    private const val MODULE_FILE = "module.toml"

    private val RESERVED_TABLES = setOf("module", "sourceSets", "dependencies")

    fun exists(root: Path): Boolean = Files.exists(root.resolve(PLATFORM_DIR).resolve(WORKSPACE_FILE))

    // --- save ---

    fun save(store: ProjectModelStore) {
        val root = store.rootPath
        val ws = store.data
        val platformDir = root.resolve(PLATFORM_DIR)

        CrashSafeWriter.write(platformDir.resolve(WORKSPACE_FILE), Json.write(workspaceJson(ws)))
        CrashSafeWriter.write(platformDir.resolve(LIBRARIES_FILE), Json.write(librariesJson(ws.libraries)))
        CrashSafeWriter.write(platformDir.resolve(SDKS_FILE), Json.write(sdksJson(ws.sdks)))

        for (p in ws.projects) {
            val projectRoot = resolveRel(root, p.rootRelPath)
            for (m in p.modules) {
                val moduleDir = resolveRel(projectRoot, m.dirRelPath)
                CrashSafeWriter.write(moduleDir.resolve(MODULE_FILE), Toml.write(moduleToToml(m)))
            }
        }
    }

    private fun workspaceJson(ws: WorkspaceData): Map<String, Any?> = linkedMapOf(
        "version" to ws.schemaVersion,
        "projects" to ws.projects.map { p ->
            linkedMapOf(
                "id" to p.id,
                "name" to p.name,
                "root" to p.rootRelPath,
                "buildSystem" to p.buildSystemId,
                "settings" to LinkedHashMap(p.settings),
                "modules" to p.modules.map { m -> linkedMapOf("id" to m.id, "name" to m.name, "dir" to m.dirRelPath) },
                "libraries" to p.libraries.map { libraryJson(it) },
            )
        },
    )

    private fun librariesJson(libs: List<LibraryData>): Map<String, Any?> =
        linkedMapOf("version" to WORKSPACE_SCHEMA_VERSION, "libraries" to libs.map { libraryJson(it) })

    private fun libraryJson(l: LibraryData): Map<String, Any?> =
        linkedMapOf("name" to l.name, "kind" to l.kind.name, "classes" to l.classes, "sources" to l.sources)

    private fun sdksJson(sdks: List<SdkData>): Map<String, Any?> = linkedMapOf(
        "version" to WORKSPACE_SCHEMA_VERSION,
        "sdks" to sdks.map {
            linkedMapOf(
                "name" to it.name,
                "bootClasspath" to it.bootClasspath,
                "buildTools" to it.buildToolsPath,
                "kind" to it.kind.name,
            )
        },
    )

    private fun moduleToToml(m: ModuleData): Map<String, Any?> {
        val doc = LinkedHashMap<String, Any?>()
        doc["version"] = MODULE_SCHEMA_VERSION
        doc["module"] = linkedMapOf<String, Any?>(
            "type" to m.typeId,
            "name" to m.name,
            "languageLevel" to m.languageLevel.name,
            "output" to m.outputRelPath,
        ).apply {
            // Emitted only when set, so a module on the type default stays byte-identical to the v1 format.
            m.sdk?.let { this["sdk"] = it }
        }
        if (m.sourceSets.isNotEmpty()) {
            val ssMap = LinkedHashMap<String, Any?>()
            for (ss in m.sourceSets) {
                val table = LinkedHashMap<String, Any?>()
                table["scope"] = ss.scope.name
                val byKey = LinkedHashMap<String, MutableList<String>>()
                for (cr in ss.contentRoots) {
                    for (role in cr.roles.sortedBy { it.ordinal }) {
                        byKey.getOrPut(roleKey(role)) { ArrayList() }.add(cr.dirRelPath)
                    }
                }
                for ((k, dirs) in byKey) table[k] = dirs.toList()
                ssMap[ss.name] = table
            }
            doc["sourceSets"] = ssMap
        }
        if (m.dependencies.isNotEmpty()) {
            // Shared (unqualified) deps stay as scope-keyed lists directly under [dependencies] — byte-identical
            // to the pre-variant format. Variant-qualified deps go under nested [dependencies.<config>] tables,
            // config names emitted in sorted order for a deterministic round-trip.
            val depTable = LinkedHashMap<String, Any?>()
            val (shared, qualified) = m.dependencies.partition { it.variant == null }
            for ((k, items) in scopeGrouped(shared)) depTable[k] = items
            for (config in qualified.mapNotNull { it.variant }.distinct().sorted()) {
                depTable[config] = scopeGrouped(qualified.filter { it.variant == config })
            }
            doc["dependencies"] = depTable
        }
        for (f in m.facets) doc[f.tomlTable] = LinkedHashMap(f.values)
        return doc
    }

    /** Group [entries] by scope key (declaration order preserved), each value the list of serialized entries. */
    private fun scopeGrouped(entries: List<OrderEntry>): Map<String, Any?> {
        val byScope = LinkedHashMap<String, MutableList<Any?>>()
        for (e in entries) byScope.getOrPut(scopeKey(e.scope)) { ArrayList() }.add(orderEntryToToml(e))
        return byScope.mapValues { it.value.toList() }
    }

    private fun orderEntryToToml(e: OrderEntry): Any = when (e) {
        // A plain library stays a bare string (unchanged on disk); one carrying exclusions becomes an inline
        // table `{ library = "g:n:v", exclude = ["g:n", "g:*"] }` so the excludes round-trip.
        is LibraryDependency ->
            if (e.exclusions.isEmpty()) e.library.name
            else linkedMapOf("library" to e.library.name, "exclude" to e.exclusions.map { it.toString() })
        is ModuleDependency -> linkedMapOf("module" to e.target.value)
        is PlatformDependency -> linkedMapOf("platform" to e.bom.toString())
        is SdkDependency -> linkedMapOf("sdk" to e.sdk.name)
    }

    // --- load ---

    fun load(root: Path): WorkspaceData {
        val platformDir = root.resolve(PLATFORM_DIR)
        val wsObj = Json.parse((platformDir.resolve(WORKSPACE_FILE)).readText()).asObject()
        val version = (wsObj["version"] as Number).toInt()
        require(version <= WORKSPACE_SCHEMA_VERSION) {
            "workspace.json schema version $version is newer than supported $WORKSPACE_SCHEMA_VERSION"
        }

        val projects = (wsObj["projects"] as List<*>).map { pAny ->
            val p = pAny.asObject()
            val rootRel = p["root"] as String
            val projectRoot = resolveRel(root, rootRel)
            val modules = (p["modules"] as List<*>).map { mAny ->
                val m = mAny.asObject()
                val dir = m["dir"] as String
                val toml = Toml.parse((resolveRel(projectRoot, dir).resolve(MODULE_FILE)).readText())
                tomlToModule(m["id"] as String, m["name"] as String, dir, toml)
            }
            ProjectData(
                id = p["id"] as String,
                name = p["name"] as String,
                rootRelPath = rootRel,
                buildSystemId = p["buildSystem"] as String,
                settings = (p["settings"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap(),
                modules = modules,
                libraries = (p["libraries"] as? List<*>)?.map { libraryFromJson(it) } ?: emptyList(),
            )
        }

        return WorkspaceData(
            schemaVersion = version,
            projects = projects,
            libraries = loadLibraries(platformDir.resolve(LIBRARIES_FILE)),
            sdks = loadSdks(platformDir.resolve(SDKS_FILE)),
        )
    }

    private fun loadLibraries(path: Path): List<LibraryData> {
        if (!Files.exists(path)) return emptyList()
        val obj = Json.parse(path.readText()).asObject()
        return (obj["libraries"] as? List<*>)?.map { libraryFromJson(it) } ?: emptyList()
    }

    private fun loadSdks(path: Path): List<SdkData> {
        if (!Files.exists(path)) return emptyList()
        val obj = Json.parse(path.readText()).asObject()
        return (obj["sdks"] as? List<*>)?.map { sAny ->
            val s = sAny.asObject()
            val name = s["name"] as String
            val buildTools = s["buildTools"] as String?
            // Back-compat: a v1 sdks.json has no `kind`. Infer it — an SDK named `android*` or carrying
            // build-tools is the Android platform; everything else is the JVM/core-Java platform.
            val kind = (s["kind"] as? String)?.let { PlatformKind.valueOf(it) }
                ?: if (name.startsWith("android") || buildTools != null) PlatformKind.ANDROID else PlatformKind.JVM
            SdkData(
                name = name,
                bootClasspath = (s["bootClasspath"] as? List<*>)?.map { it as String } ?: emptyList(),
                buildToolsPath = buildTools,
                kind = kind,
            )
        } ?: emptyList()
    }

    private fun libraryFromJson(any: Any?): LibraryData {
        val o = any.asObject()
        return LibraryData(
            name = o["name"] as String,
            kind = LibraryKind.valueOf(o["kind"] as String),
            classes = (o["classes"] as? List<*>)?.map { it as String } ?: emptyList(),
            sources = (o["sources"] as? List<*>)?.map { it as String } ?: emptyList(),
        )
    }

    private fun tomlToModule(id: String, name: String, dirRelPath: String, doc: Map<String, Any?>): ModuleData {
        (doc["version"] as? Number)?.let { v ->
            require(v.toInt() <= MODULE_SCHEMA_VERSION) {
                "module.toml schema version ${v.toInt()} is newer than supported $MODULE_SCHEMA_VERSION ($name)"
            }
        }
        val moduleTable = doc["module"].asObject()
        val typeId = moduleTable["type"] as String
        val languageLevel = LanguageLevel.valueOf(moduleTable["languageLevel"] as String)
        val output = moduleTable["output"] as String
        val sdk = moduleTable["sdk"] as String?

        val sourceSets = (doc["sourceSets"] as? Map<*, *>)?.map { (ssName, ssTableAny) ->
            val ssTable = ssTableAny.asObject()
            val scope = DependencyScope.valueOf(ssTable["scope"] as String)
            val byDir = LinkedHashMap<String, MutableSet<ContentRole>>()
            for ((k, v) in ssTable) {
                val key = k
                if (key == "scope") continue
                val role = roleForKey(key) ?: continue
                for (dirAny in (v as List<*>)) {
                    byDir.getOrPut(dirAny as String) { linkedSetOf() }.add(role)
                }
            }
            SourceSetData(ssName.toString(), scope, byDir.map { ContentRootData(it.key, it.value.toSet()) })
        } ?: emptyList()

        val deps = ArrayList<OrderEntry>()
        (doc["dependencies"] as? Map<*, *>)?.forEach { (keyAny, valueAny) ->
            val key = keyAny.toString()
            val scope = scopeForKey(key)
            when {
                // A scope-keyed list directly under [dependencies] is a shared (unqualified) dependency.
                scope != null -> for (item in (valueAny as List<*>)) deps.add(tomlToOrderEntry(item, scope, variant = null))
                // A [dependencies.<config>] sub-table holds that variant's scope-keyed lists.
                valueAny is Map<*, *> -> valueAny.forEach { (sKeyAny, itemsAny) ->
                    val s = scopeForKey(sKeyAny.toString()) ?: return@forEach
                    for (item in (itemsAny as List<*>)) deps.add(tomlToOrderEntry(item, s, variant = key))
                }
            }
        }

        val facets = doc.entries
            .filter { it.key !in RESERVED_TABLES && it.key != "version" && it.value is Map<*, *> }
            .map { e -> FacetData(e.key, (e.value as Map<*, *>).entries.associate { it.key.toString() to it.value }) }

        return ModuleData(id, name, dirRelPath, typeId, languageLevel, output, sourceSets, deps, facets, sdk)
    }

    private fun tomlToOrderEntry(item: Any?, scope: DependencyScope, variant: String?): OrderEntry {
        val exported = scope == DependencyScope.API
        return when (item) {
            is String -> LibraryDependency(LibraryRef(item), scope, exported, variant = variant)
            is Map<*, *> -> when {
                item.containsKey("library") -> LibraryDependency(
                    LibraryRef(item["library"] as String), scope, exported,
                    exclusions = (item["exclude"] as? List<*>).orEmpty().mapNotNull { Exclusion.parse(it as String) },
                    variant = variant,
                )
                item.containsKey("module") -> ModuleDependency(ModuleId(item["module"] as String), scope, exported, variant = variant)
                item.containsKey("platform") -> PlatformDependency(parseCoordinate(item["platform"] as String), scope, exported, variant = variant)
                item.containsKey("sdk") -> SdkDependency(SdkRef(item["sdk"] as String), scope)
                else -> error("unrecognized dependency entry: $item")
            }
            else -> error("unrecognized dependency entry: $item")
        }
    }

    // --- helpers ---

    /** Parse a persisted `group:name:version` BOM coordinate (a missing version tolerated as blank). */
    private fun parseCoordinate(s: String): Coordinate {
        val parts = s.split(":")
        return Coordinate(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
    }

    private fun resolveRel(base: Path, rel: String): Path =
        if (rel.isEmpty() || rel == ".") base else base.resolve(rel).normalize()

    private fun roleKey(role: ContentRole): String = when (role) {
        ContentRole.SOURCE -> "java"
        ContentRole.RESOURCE -> "resources"
        ContentRole.ANDROID_RES -> "res"
        ContentRole.AIDL -> "aidl"
        ContentRole.ASSETS -> "assets"
        ContentRole.JNI_LIBS -> "jniLibs"
        ContentRole.GENERATED -> "generated"
        ContentRole.EXCLUDED -> "excluded"
    }

    private fun roleForKey(key: String): ContentRole? = when (key) {
        "java" -> ContentRole.SOURCE
        "resources" -> ContentRole.RESOURCE
        "res" -> ContentRole.ANDROID_RES
        "aidl" -> ContentRole.AIDL
        "assets" -> ContentRole.ASSETS
        "jniLibs" -> ContentRole.JNI_LIBS
        "generated" -> ContentRole.GENERATED
        "excluded" -> ContentRole.EXCLUDED
        else -> null
    }

    private fun scopeKey(scope: DependencyScope): String = when (scope) {
        DependencyScope.API -> "api"
        DependencyScope.IMPLEMENTATION -> "implementation"
        DependencyScope.COMPILE_ONLY -> "compileOnly"
        DependencyScope.RUNTIME_ONLY -> "runtimeOnly"
        DependencyScope.TEST_IMPLEMENTATION -> "testImplementation"
    }

    private fun scopeForKey(key: String): DependencyScope? = when (key) {
        "api" -> DependencyScope.API
        "implementation" -> DependencyScope.IMPLEMENTATION
        "compileOnly" -> DependencyScope.COMPILE_ONLY
        "runtimeOnly" -> DependencyScope.RUNTIME_ONLY
        "testImplementation" -> DependencyScope.TEST_IMPLEMENTATION
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(): Map<String, Any?> = this as Map<String, Any?>
}
