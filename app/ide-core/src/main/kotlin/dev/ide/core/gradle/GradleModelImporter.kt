package dev.ide.core.gradle

import dev.ide.android.support.AndroidApiLevels
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.BuildFeatures
import dev.ide.build.jvm.compose.ComposeResourcesFacet
import dev.ide.build.jvm.compose.ComposeResourcesFacetCodec
import dev.ide.lang.kotlin.build.KotlinFacet
import dev.ide.lang.kotlin.build.KotlinFacetCodec
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.impl.format.Json
import dev.ide.model.sync.Detection
import dev.ide.model.sync.ExternalDependency
import dev.ide.model.sync.ExternalFacet
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalPlatform
import dev.ide.model.sync.ExternalProjectModel
import dev.ide.model.sync.ExternalRepository
import dev.ide.model.sync.ExternalSourceSet
import dev.ide.model.sync.ModelOwnership
import dev.ide.model.sync.ProjectImporter
import dev.ide.model.sync.SyncMessage
import dev.ide.model.sync.SyncOutcome
import dev.ide.model.sync.SyncRequest
import dev.ide.model.sync.SyncSeverity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Imports a Gradle build that **exported itself**: `.platform/gradle-model.json`, written by the
 * `generateNativeModel` task (buildSrc's `NativeModelExtractor`).
 *
 * This is the counterpart to [GradleProjectImporter], and the two divide the problem by what produced the
 * information. [GradleProjectImporter] reads build scripts as text, which is the only thing possible for a
 * project the user merely opened; it necessarily loses everything a script computes. This importer reads
 * what Gradle itself resolved after evaluating those scripts, so a build that assigns `projectDir` from a
 * map, spreads `include(...)` across many lines, resolves versions through a catalog, or names its
 * dependencies through plugin DSL accessors (`compose.material3`) arrives exactly as configured. That is
 * what makes CodeAssist's own repository importable, and it works for any Gradle project willing to run one
 * task. The dump is detected with a higher confidence than a bare settings script, so a project carrying one
 * is imported from it rather than re-parsed.
 *
 * Ownership stays [ModelOwnership.IDE]: the dump is a seed, and what it produces is an ordinary native
 * workspace the IDE owns from then on. Re-running the task and re-importing refreshes the model in place
 * (modules are added and updated), which is the workflow when the Gradle build's dependencies change.
 */
class GradleModelImporter : ProjectImporter {

    override val id: BuildSystemId = BuildSystemId("gradle-model")

    override val displayName: String = "Gradle (exported model)"

    override val ownership: ModelOwnership = ModelOwnership.IDE

    override fun detect(root: Path): Detection? {
        val dump = root.resolve(DUMP_PATH)
        if (!Files.isRegularFile(dump)) return null
        val name = runCatching { (Json.parse(dump.readText()) as? Map<*, *>)?.get("name") as? String }.getOrNull()
        return Detection(
            name = name ?: root.fileName?.toString() ?: "project",
            markers = listOf(dump),
            // Above GradleProjectImporter's: an exported model is strictly better information than the
            // script text it was exported from, so it wins whenever both are present.
            confidence = EXPORTED_MODEL_CONFIDENCE,
        )
    }

    override fun syncFiles(): List<String> = listOf(DUMP_PATH)

    override suspend fun resolve(request: SyncRequest): SyncOutcome {
        val dump = request.root.resolve(DUMP_PATH)
        val root = runCatching { Json.parse(dump.readText()) as? Map<*, *> }.getOrElse { e ->
            return SyncOutcome.failed("$DUMP_PATH could not be read: ${e.message}")
        } ?: return SyncOutcome.failed("$DUMP_PATH is not a JSON object")

        val version = (root["version"] as? Long)?.toInt() ?: 0
        if (version > DUMP_VERSION) {
            return SyncOutcome.failed(
                "$DUMP_PATH is version $version, newer than this build understands ($DUMP_VERSION). " +
                    "Update CodeAssist, or re-run generateNativeModel with a matching version."
            )
        }

        val messages = ArrayList<SyncMessage>()
        val modules = (root["modules"] as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .map { module(it, messages) }
        if (modules.isEmpty()) messages.add(SyncMessage(SyncSeverity.WARNING, "$DUMP_PATH declares no modules"))

        val target = root["target"] as? String
        if (target != null) {
            messages.add(
                SyncMessage(
                    SyncSeverity.INFO,
                    "Imported the '$target' target of ${modules.size} module(s). A multiplatform module " +
                        "contributes the source sets and dependencies of that target only; re-export with " +
                        "-PnativeModel.target=<other> to model a different one.",
                )
            )
        }

        return SyncOutcome(
            ExternalProjectModel(
                name = root["name"] as? String ?: request.root.fileName?.toString() ?: "project",
                // The model this produces is native from here on: CodeAssist's own pipelines build it, and
                // the workspace it writes is the source of truth the IDE edits.
                buildSystemId = BuildSystemId.NATIVE,
                modules = modules,
                repositories = repositories(root),
            ),
            messages,
        )
    }

    // --- modules ---

    private fun module(raw: Map<*, *>, messages: MutableList<SyncMessage>): ExternalModule {
        val name = raw["name"] as? String ?: "module"
        val typeId = raw["type"] as? String ?: "java-lib"
        return ExternalModule(
            name = name,
            dirRelPath = raw["dir"] as? String ?: name,
            typeId = typeId,
            // Gradle owns `<module>/build`, and this import describes a project Gradle still builds. Putting
            // CodeAssist's output in its own subtree keeps the two from reading each other's leftovers.
            // Without it, `build/generated` would hand the Compose Gradle plugin's generated sources to
            // CodeAssist's compiler as if they were the module's own, and the jar would package Gradle's
            // classes.
            outputRelPath = OUTPUT_ROOT,
            sourceSets = sourceSets(raw),
            dependencies = dependencies(raw, name, messages),
            facets = facets(raw, name, messages),
        )
    }

    /**
     * The source sets exactly as exported. Unlike [GradleProjectImporter] these are never left to the module
     * type's defaults: the exporter emitted the roots it found on disk, including a multiplatform module's
     * `commonMain`/`androidMain` pair and this repository's own `jvmShared` intermediate set, and a default
     * `src/main/java` would describe none of them.
     */
    private fun sourceSets(raw: Map<*, *>): List<ExternalSourceSet> =
        declaredSourceSets(raw) + generatedComposeResourcesSourceSet(raw)

    /**
     * Where the Compose resource generator writes, declared as ordinary content roots so the rest of the
     * build needs no special case: the `Res` class compiles because its root is GENERATED, and the staged
     * tree is packaged because its root is RESOURCE. These are roots the import adds rather than ones the
     * exporter found on disk, because nothing produces them until the build runs.
     */
    private fun generatedComposeResourcesSourceSet(raw: Map<*, *>): List<ExternalSourceSet> {
        if (raw["composeResources"] !is Map<*, *>) return emptyList()
        return listOf(
            ExternalSourceSet(
                name = "composeResources",
                scope = DependencyScope.IMPLEMENTATION,
                roots = linkedMapOf(
                    // SOURCE, not GENERATED: the JVM pipeline hands the module's first GENERATED root to
                    // `generateSources` as its own output directory, which that task empties on every run.
                    // A second producer writing there would have its output deleted by the first source
                    // generator to run.
                    ComposeResourcesFacet.GENERATED_KOTLIN_ROOT to setOf(ContentRole.SOURCE),
                    ComposeResourcesFacet.GENERATED_RESOURCES_ROOT to setOf(ContentRole.RESOURCE),
                ),
            )
        )
    }

    private fun declaredSourceSets(raw: Map<*, *>): List<ExternalSourceSet> =
        (raw["sourceSets"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { set ->
            val roots = LinkedHashMap<String, Set<ContentRole>>()
            (set["roots"] as? Map<*, *>).orEmpty().forEach { (dir, roles) ->
                val path = dir as? String ?: return@forEach
                roots[path] = (roles as? List<*>).orEmpty()
                    .mapNotNull { it as? String }
                    .map { ContentRole(it) }
                    .toSet()
            }
            ExternalSourceSet(
                name = set["name"] as? String ?: "main",
                scope = DependencyScope.valueOf(set["scope"] as? String ?: "IMPLEMENTATION"),
                roots = roots,
            )
        }

    private fun dependencies(
        raw: Map<*, *>,
        module: String,
        messages: MutableList<SyncMessage>,
    ): List<ExternalDependency> =
        (raw["dependencies"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { dep ->
            val scope = DependencyScope.valueOf(dep["scope"] as? String ?: "IMPLEMENTATION")
            val variant = dep["variant"] as? String
            when (dep["kind"] as? String) {
                "module" -> (dep["name"] as? String)?.let { ExternalModuleRef(it, scope, variant) }
                "library" -> (dep["coordinate"] as? String)?.let { ExternalLibrary(it, scope, variant) }
                "platform" -> (dep["coordinate"] as? String)?.let { coordinate ->
                    Coordinate.parseOrNull(coordinate)?.let { ExternalPlatform(it, scope, variant) }
                        ?: run {
                            messages.add(
                                SyncMessage(SyncSeverity.WARNING, "$module: '$coordinate' is not a BOM coordinate")
                            )
                            null
                        }
                }
                else -> null
            }
        }

    // --- facets ---

    private fun facets(
        raw: Map<*, *>,
        module: String,
        messages: MutableList<SyncMessage>,
    ): List<ExternalFacet> =
        listOfNotNull(kotlinFacet(raw), composeResourcesFacet(raw), androidFacet(raw, module, messages))

    /**
     * `[composeResources]`, for a module the Compose Gradle plugin generates a `Res` class for. Without it
     * the module's own `import <package>.Res` has nothing to resolve to, since that class exists only as
     * generated code.
     */
    private fun composeResourcesFacet(raw: Map<*, *>): ExternalFacet? {
        val compose = raw["composeResources"] as? Map<*, *> ?: return null
        val facet = ComposeResourcesFacet(
            packageName = compose["package"] as? String ?: return null,
            publicResClass = compose["public"] as? Boolean ?: false,
            roots = (compose["dirs"] as? List<*>).orEmpty().mapNotNull { it as? String },
        )
        return ExternalFacet(ComposeResourcesFacetCodec.tomlTable, ComposeResourcesFacetCodec.encode(facet))
    }

    /**
     * `[kotlin]`, for a module the exporter marked multiplatform. Its source sets were collapsed into one
     * compilation, and only multiplatform mode makes the `expect`/`actual` pairs spanning them legal.
     */
    private fun kotlinFacet(raw: Map<*, *>): ExternalFacet? {
        if (raw["multiplatform"] as? Boolean != true) return null
        // The common half of the collapsed multiplatform module: every source root of a source set the
        // exporter marked common.
        val commonRoots = declaredSourceSets(raw)
            .filter { it.name in commonSourceSetNames(raw) }
            .flatMap { it.roots.keys }
        if (commonRoots.isEmpty()) return null
        return ExternalFacet(
            KotlinFacetCodec.tomlTable,
            KotlinFacetCodec.encode(KotlinFacet(commonSourceRoots = commonRoots)),
        )
    }

    private fun commonSourceSetNames(raw: Map<*, *>): Set<String> =
        (raw["sourceSets"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["common"] as? Boolean == true }
            .mapNotNull { it["name"] as? String }
            .toSet()

    private fun androidFacet(
        raw: Map<*, *>,
        module: String,
        messages: MutableList<SyncMessage>,
    ): ExternalFacet? {
        val android = raw["android"] as? Map<*, *> ?: return null
        // The model derives an app's package from the namespace, so a build whose applicationId differs
        // would be renamed silently. Say so instead.
        val applicationId = android["applicationId"] as? String
        val namespace = android["namespace"] as? String ?: "dev.ide.$module"
        if (applicationId != null && applicationId != namespace) {
            messages.add(
                SyncMessage(
                    SyncSeverity.WARNING,
                    "$module: applicationId '$applicationId' is not carried by the model (it derives the " +
                        "package from the namespace '$namespace'); the built artifact will use the namespace.",
                )
            )
        }
        val features = android["buildFeatures"] as? Map<*, *>
        val facet = AndroidFacet(
            namespace = namespace,
            compileSdk = android.int("compileSdk") ?: AndroidApiLevels.LATEST,
            minSdk = android.int("minSdk") ?: DEFAULT_MIN_SDK,
            targetSdk = android.int("targetSdk") ?: android.int("minSdk") ?: DEFAULT_MIN_SDK,
            versionCode = android.int("versionCode") ?: AndroidFacet.DEFAULT_VERSION_CODE,
            versionName = android["versionName"] as? String ?: AndroidFacet.DEFAULT_VERSION_NAME,
            isApplication = android["isApplication"] as? Boolean ?: true,
            buildFeatures = BuildFeatures(
                compose = features?.get("compose") as? Boolean ?: false,
                parcelize = features?.get("parcelize") as? Boolean ?: false,
                serialization = features?.get("serialization") as? Boolean ?: false,
            ),
        )
        return ExternalFacet(AndroidFacetCodec.tomlTable, AndroidFacetCodec.encode(facet))
    }

    private fun repositories(root: Map<*, *>): List<ExternalRepository> =
        (root["repositories"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().mapNotNull { repo ->
            val url = repo["url"] as? String ?: return@mapNotNull null
            ExternalRepository(repo["name"] as? String ?: url, url)
        }

    /** JSON integers parse as [Long]; the model holds API levels as [Int]. */
    private fun Map<*, *>.int(key: String): Int? = (this[key] as? Long)?.toInt()

    companion object {
        /** Where the exporter writes, and the only file this importer reads. */
        const val DUMP_PATH: String = ".platform/gradle-model.json"

        /** The dump schema this build understands; bumped with every incompatible change to the exporter. */
        const val DUMP_VERSION: Int = 1

        /** Beats [GradleProjectImporter]'s script-reading detection (see [detect]). */
        const val EXPORTED_MODEL_CONFIDENCE: Int = 100

        private const val DEFAULT_MIN_SDK = 21

        /** Compile output for an imported module: a subtree of `build/` that is CodeAssist's alone. */
        private const val OUTPUT_ROOT = "build/codeassist/classes"
    }
}
