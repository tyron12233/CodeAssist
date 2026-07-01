package dev.ide.deps.impl

/**
 * Gradle Module Metadata (the `*.module` JSON a Gradle-published artifact ships alongside its `.pom`),
 * parsed to the subset this resolver needs to do **variant selection**. A pure-POM resolver can't tell a
 * Kotlin-Multiplatform library's `-android` artifact from its `-jvm` one — they share a coordinate and
 * differ only by Gradle *attributes* — so it pulls both and a downstream dedup picks one. Reading the
 * `.module` lets the resolver negotiate attributes up front and fetch only the right variant.
 *
 * Only the fields used for selection are modeled; anything else in the file is ignored. A malformed or
 * absent file degrades to the POM path (see [GradleModuleParser.parse] returning null). This does NOT
 * model Gradle *capability* conflicts across different modules (e.g. `collection` vs `collection-ktx`
 * defining the same class) — that stays a dex-merge concern.
 */
data class GradleModule(
    val group: String?,
    val name: String?,
    val version: String?,
    val variants: List<GmmVariant>,
)

/**
 * One published variant (Gradle calls these "variants"; e.g. `releaseApiElements-published`,
 * `jvmApiElements-published`). [attributes] hold the Gradle attributes selection negotiates over;
 * [availableAt] redirects a KMP root module to the platform module that actually carries the files.
 */
data class GmmVariant(
    val name: String,
    val attributes: Map<String, String>,
    val dependencies: List<GmmDependency>,
    /** Version constraints (Gradle `dependencyConstraints`): they align a GA *if it is in the graph*, but
     *  do not themselves pull it — the atomic-group alignment AndroidX/Compose libraries publish. */
    val dependencyConstraints: List<GmmConstraint>,
    val files: List<GmmFile>,
    val capabilities: List<GmmCapability>,
    val availableAt: GmmAvailableAt?,
)

/** A variant dependency. The artifact name is the GMM `module` field. [version] is the resolvable version
 *  (`requires`, else `strictly`/`prefers`); [strictly] is set when a `strictly` pin is present (must not be bumped). */
data class GmmDependency(
    val group: String,
    val name: String,
    val version: String?,
    val strictly: String?,
    val attributes: Map<String, String>,
    val excludes: Set<GA>,
)

/** A `dependencyConstraints` entry: a version (and optional strict pin) for a GA, applied only if present. */
data class GmmConstraint(val group: String, val name: String, val version: String?, val strictly: String?)

/** A published file of a variant (`name` is the artifact filename, e.g. `lib-android-1.0.aar`). */
data class GmmFile(val name: String, val url: String)

data class GmmCapability(val group: String, val name: String, val version: String?)

/** `available-at`: this variant has no files of its own; they live in module [group]:[name]:[version]. */
data class GmmAvailableAt(val group: String, val name: String, val version: String)

/** Parses a `*.module` JSON document into a [GradleModule], or null when it's absent-shaped/malformed. */
object GradleModuleParser {

    fun parse(bytes: ByteArray): GradleModule? = runCatching {
        val root = Json.parse(String(bytes, Charsets.UTF_8)) as? Map<*, *> ?: return null
        val component = root["component"] as? Map<*, *>
        val variants = (root["variants"] as? List<*>).orEmpty().mapNotNull { parseVariant(it) }
        GradleModule(
            group = component?.get("group") as? String,
            name = component?.get("module") as? String,
            version = (component?.get("version"))?.toString(),
            variants = variants,
        )
    }.getOrNull()

    private fun parseVariant(any: Any?): GmmVariant? {
        val v = any as? Map<*, *> ?: return null
        val name = v["name"] as? String ?: return null
        val availableAt = (v["available-at"] as? Map<*, *>)?.let { at ->
            val g = at["group"] as? String ?: return@let null
            val m = at["module"] as? String ?: return@let null
            val ver = at["version"]?.toString() ?: return@let null
            GmmAvailableAt(g, m, ver)
        }
        return GmmVariant(
            name = name,
            attributes = stringAttributes(v["attributes"]),
            dependencies = (v["dependencies"] as? List<*>).orEmpty().mapNotNull { parseDependency(it) },
            dependencyConstraints = (v["dependencyConstraints"] as? List<*>).orEmpty().mapNotNull { parseConstraint(it) },
            files = (v["files"] as? List<*>).orEmpty().mapNotNull { parseFile(it) },
            capabilities = (v["capabilities"] as? List<*>).orEmpty().mapNotNull { parseCapability(it) },
            availableAt = availableAt,
        )
    }

    private fun parseDependency(any: Any?): GmmDependency? {
        val d = any as? Map<*, *> ?: return null
        val group = d["group"] as? String ?: return null
        val name = d["module"] as? String ?: return null
        val ver = d["version"] as? Map<*, *>
        val strictly = (ver?.get("strictly"))?.toString()
        val version = ((ver?.let { it["requires"] ?: it["strictly"] ?: it["prefers"] }))?.toString()
        val excludes = (d["excludes"] as? List<*>).orEmpty().mapNotNull { ex ->
            val e = ex as? Map<*, *> ?: return@mapNotNull null
            GA((e["group"] as? String) ?: "*", (e["module"] as? String) ?: "*")
        }.toSet()
        return GmmDependency(group, name, version, strictly, stringAttributes(d["attributes"]), excludes)
    }

    private fun parseConstraint(any: Any?): GmmConstraint? {
        val c = any as? Map<*, *> ?: return null
        val group = c["group"] as? String ?: return null
        val name = c["module"] as? String ?: return null
        val ver = c["version"] as? Map<*, *>
        val strictly = (ver?.get("strictly"))?.toString()
        val version = ((ver?.let { it["requires"] ?: it["strictly"] ?: it["prefers"] }))?.toString()
        return GmmConstraint(group, name, version, strictly)
    }

    private fun parseFile(any: Any?): GmmFile? {
        val f = any as? Map<*, *> ?: return null
        val name = f["name"] as? String ?: return null
        return GmmFile(name, (f["url"] as? String) ?: name)
    }

    private fun parseCapability(any: Any?): GmmCapability? {
        val c = any as? Map<*, *> ?: return null
        val group = c["group"] as? String ?: return null
        val name = c["name"] as? String ?: return null
        return GmmCapability(group, name, c["version"]?.toString())
    }

    /** Coerce an attributes object's values (which may be JSON strings, numbers, or booleans) to strings. */
    private fun stringAttributes(any: Any?): Map<String, String> {
        val m = any as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, String>(m.size)
        for ((k, value) in m) {
            val key = k as? String ?: continue
            out[key] = when (value) {
                is String -> value
                is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
                is Boolean -> value.toString()
                null -> continue
                else -> value.toString()
            }
        }
        return out
    }
}
