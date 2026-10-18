package dev.ide.core.gradle

import dev.ide.android.support.AndroidApiLevels
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.AndroidPackaging
import dev.ide.android.support.BuildFeatures
import dev.ide.android.support.BuildType
import dev.ide.android.support.BuildConfigField
import dev.ide.android.support.ProductFlavor
import dev.ide.build.jvm.buildconfig.KotlinBuildConfigFacet
import dev.ide.build.jvm.buildconfig.KotlinBuildConfigFacetCodec
import dev.ide.build.jvm.compose.ComposeResourcesFacet
import dev.ide.core.kmp.KmpShapes
import dev.ide.build.jvm.compose.ComposeResourcesFacetCodec
import dev.ide.core.gradle.GradleImport
import dev.ide.lang.kotlin.build.KotlinFacet
import dev.ide.lang.kotlin.build.KotlinFacetCodec
import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
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

/**
 * The [ProjectImporter] for Gradle projects: it reads the scripts with [GradleImport] (a tolerant,
 * non-evaluating reader) and maps what it found onto an [ExternalProjectModel]. The scripts stay the source
 * of truth, so every sync re-derives the model from them.
 *
 * The mapping is all that lives here. Reading the scripts belongs to [GradleImport], applying the snapshot to
 * the model belongs to [dev.ide.model.impl.ExternalModelApplier], and the Android facet crosses the boundary
 * as the `[android]` table's values, encoded by the codec that also persists it.
 */
class GradleProjectImporter : ProjectImporter {

    override val id: BuildSystemId = BuildSystemId.GRADLE_COMPAT

    override val displayName: String = "Gradle"

    override val ownership: ModelOwnership = ModelOwnership.EXTERNAL

    override fun detect(root: Path): Detection? {
        if (!GradleImport.isGradleProject(root)) return null
        return Detection(
            name = root.fileName?.toString() ?: "gradle-project",
            markers = GradleImport.buildFiles(root),
            confidence = 10,
        )
    }

    override fun syncFiles(): List<String> = listOf(
        "settings.gradle", "settings.gradle.kts",
        "build.gradle", "build.gradle.kts",
        "**/build.gradle", "**/build.gradle.kts",
        "gradle.properties", "**/gradle.properties",
        "gradle/libs.versions.toml", "libs.versions.toml",
        // Convention plugins: their scripts and constants feed the parse, so a change there changes the model.
        "buildSrc/**/*.kt", "buildSrc/**/*.gradle.kts",
        "build-logic/**/*.kt", "build-logic/**/*.gradle.kts",
    )

    override suspend fun resolve(request: SyncRequest): SyncOutcome {
        request.progress.report(-1.0, "Reading the Gradle build scripts")
        val spec = GradleImport.parse(request.root)
            ?: return SyncOutcome.failed("No Gradle build scripts were found to sync from.")
        val messages = spec.report.notes.map { SyncMessage(SyncSeverity.WARNING, it) }
        return SyncOutcome(withAllTargets(request.root, spec, model(request.root, spec)), messages)
    }

    /**
     * [model] with every target of each Kotlin Multiplatform module recorded (see [KmpShapes.describe]): a module
     * that can be imported for Android and for the JVM is read the other way too, so its `[kotlin]` facet holds
     * both shapes. A native switch, and a project converted to native, can then change target with no scripts
     * to re-read.
     */
    private fun withAllTargets(root: Path, spec: GradleImport.ProjectSpec, model: ExternalProjectModel): ExternalProjectModel {
        val both = setOf(GradleKmp.Platform.ANDROID, GradleKmp.Platform.JVM)
        val switchable = spec.modules.filter { it.kmp?.available?.containsAll(both) == true }
        if (switchable.isEmpty()) return model
        val others = switchable.map { it.kmp!!.platform }.distinct().associateWith { active ->
            GradleImport.parse(root, if (active == GradleKmp.Platform.ANDROID) GradleKmp.Platform.JVM else GradleKmp.Platform.ANDROID)
        }
        val described = switchable.associate { activeSpec ->
            val activePlatform = activeSpec.kmp!!.platform
            val otherSpec = others[activePlatform]?.modules?.firstOrNull { it.name == activeSpec.name }
            val shapes = listOfNotNull(
                KmpShapes.Shaped(targetName(activePlatform), platformId(activePlatform), module(root, activeSpec)),
                otherSpec?.kmp?.let { KmpShapes.Shaped(targetName(it.platform), platformId(it.platform), module(root, otherSpec)) },
            )
            activeSpec.name to KmpShapes.describe(shapes, targetName(activePlatform))
        }
        return model.copy(modules = model.modules.map { described[it.name] ?: it })
    }

    private fun platformId(p: GradleKmp.Platform) = if (p == GradleKmp.Platform.ANDROID) KmpShapes.ANDROID else KmpShapes.JVM

    /** A target's name in the model: the platform's, so a project reads the same whatever its scripts call it. */
    private fun targetName(p: GradleKmp.Platform) = platformId(p)

    /** The parsed scripts as a snapshot. */
    private fun model(root: Path, spec: GradleImport.ProjectSpec): ExternalProjectModel = ExternalProjectModel(
        name = spec.name,
        buildSystemId = id,
        modules = spec.modules.map { module(root, it) },
        repositories = spec.customRepos.map { ExternalRepository(it.name, it.url) },
    )

    private fun module(root: Path, spec: GradleImport.ModuleSpec): ExternalModule {
        val dirRel = spec.dirRel.ifBlank { spec.name }
        return ExternalModule(
            name = spec.name,
            dirRelPath = dirRel,
            typeId = typeIdFor(spec.kind),
            sourceSets = sourceSets(spec, root.resolve(dirRel)),
            dependencies = dependencies(spec),
            facets = facets(spec),
        )
    }

    private fun typeIdFor(kind: GradleImport.Kind): String = when (kind) {
        GradleImport.Kind.ANDROID_APP -> "android-app"
        GradleImport.Kind.ANDROID_LIB -> "android-lib"
        GradleImport.Kind.JAVA -> "java-lib"
    }

    /** Android module types supply their own `src/main/{java,kotlin,res,assets}` sets, so only a plain
     *  JVM module needs its Gradle-convention source roots spelled out. A KMP module spells out its own. */
    private fun sourceSets(spec: GradleImport.ModuleSpec, dir: Path): List<ExternalSourceSet> =
        baseSourceSets(spec, dir).let { sets ->
            // gmazzo's BuildConfig object, generated before the compile (KotlinBuildConfigPlugin). Not on a plain
            // Android module: an empty list keeps that module type's own sets, and a non-empty one would replace
            // them; such a module has AGP's BuildConfig anyway.
            if (spec.kotlinBuildConfig == null || sets.isEmpty()) sets
            else sets + ExternalSourceSet(
                "buildConfig", DependencyScope.IMPLEMENTATION,
                linkedMapOf(KotlinBuildConfigFacet.GENERATED_KOTLIN_ROOT to setOf(ContentRole.SOURCE)),
            )
        }

    private fun baseSourceSets(spec: GradleImport.ModuleSpec, dir: Path): List<ExternalSourceSet> =
        if (spec.kmp != null) kmpSourceSets(spec, spec.kmp, dir)
        else if (spec.kind != GradleImport.Kind.JAVA) emptyList()
        else listOf(
            ExternalSourceSet(
                name = "main",
                scope = DependencyScope.IMPLEMENTATION,
                roots = linkedMapOf(
                    "src/main/java" to setOf(ContentRole.SOURCE),
                    "src/main/kotlin" to setOf(ContentRole.SOURCE),
                    "src/main/resources" to setOf(ContentRole.RESOURCE),
                ),
            )
        )

    /**
     * A KMP module's source sets, collapsed: every modeled main set's roots go into `main` and every test
     * set's into `test`. The names matter to the Android pipeline, whose variants compile `main` plus the
     * build type and flavor sets and nothing else, so a set kept as `androidMain` would never be built.
     * The platform-set name survives in the paths, and [kotlinFacet] carries which of them are common.
     */
    private fun kmpSourceSets(
        spec: GradleImport.ModuleSpec,
        kmp: GradleImport.KmpModule,
        dir: Path,
    ): List<ExternalSourceSet> {
        val android = spec.kind != GradleImport.Kind.JAVA
        val main = LinkedHashMap<String, Set<ContentRole>>()
        val test = LinkedHashMap<String, Set<ContentRole>>()
        for (set in kmp.sourceSets) {
            val base = "src/${set.name}"
            val roots = if (set.test) test else main
            fun add(rel: String, role: ContentRole) {
                if (Files.isDirectory(dir.resolve(rel))) roots[rel] = setOf(role)
            }
            // A main set's Kotlin root is declared even before it exists, so the first file created in a new
            // project lands in a source root.
            if (set.test) add("$base/kotlin", ContentRole.SOURCE) else main["$base/kotlin"] = setOf(ContentRole.SOURCE)
            add("$base/java", ContentRole.SOURCE)
            add("$base/resources", ContentRole.RESOURCE)
            if (android && !set.test) {
                add("$base/res", ContentRole.ANDROID_RES)
                add("$base/assets", ContentRole.ASSETS)
                add("$base/aidl", ContentRole.AIDL)
                add("$base/jniLibs", ContentRole.JNI_LIBS)
            }
        }
        return buildList {
            add(ExternalSourceSet("main", DependencyScope.IMPLEMENTATION, main))
            if (test.isNotEmpty()) add(ExternalSourceSet("test", DependencyScope.TEST_IMPLEMENTATION, test))
            if (spec.composeResources != null) add(
                // Where the Compose resource generator writes (see GradleModelImporter, which declares the same).
                ExternalSourceSet(
                    "composeResources", DependencyScope.IMPLEMENTATION,
                    linkedMapOf(
                        ComposeResourcesFacet.GENERATED_KOTLIN_ROOT to setOf(ContentRole.SOURCE),
                        ComposeResourcesFacet.GENERATED_RESOURCES_ROOT to setOf(ContentRole.RESOURCE),
                    ),
                )
            )
        }
    }

    private fun dependencies(spec: GradleImport.ModuleSpec): List<ExternalDependency> = buildList {
        for (d in spec.moduleDeps) add(ExternalModuleRef(d.name, d.scope, d.variant))
        for (d in spec.platformDeps) {
            coordinateOrNull(d.coordinate)?.let { add(ExternalPlatform(it, d.scope, d.variant)) }
        }
        for (d in spec.mavenDeps) add(ExternalLibrary(d.coordinate, d.scope, d.variant))
    }

    /** The `android { }` block as the `[android]` facet table (absent for a plain JVM module), plus a KMP
     *  module's `[kotlin]` and `[composeResources]`. */
    private fun facets(spec: GradleImport.ModuleSpec): List<ExternalFacet> = listOfNotNull(
        if (spec.kind == GradleImport.Kind.JAVA) null
        else ExternalFacet(AndroidFacetCodec.tomlTable, AndroidFacetCodec.encode(androidFacet(spec))),
        kotlinFacet(spec),
        spec.composeResources?.let {
            val facet = ComposeResourcesFacet(it.packageName, it.publicResClass, it.dirs)
            ExternalFacet(ComposeResourcesFacetCodec.tomlTable, ComposeResourcesFacetCodec.encode(facet))
        },
        spec.kotlinBuildConfig?.takeIf { spec.kmp != null || spec.kind == GradleImport.Kind.JAVA }?.let { bc ->
            val facet = KotlinBuildConfigFacet(
                bc.packageName, bc.className, bc.internalVisibility,
                bc.fields.map { KotlinBuildConfigFacet.Field(it.type, it.name, it.value) },
            )
            ExternalFacet(KotlinBuildConfigFacetCodec.tomlTable, KotlinBuildConfigFacetCodec.encode(facet))
        },
    )

    /**
     * `[kotlin]` for a KMP module whose modeled target sits under common code: the common sets' Kotlin roots,
     * which is what lets an `expect` in `commonMain` and its `actual` in the platform set compile as one, plus
     * the generated Compose resource accessors.
     */
    private fun kotlinFacet(spec: GradleImport.ModuleSpec): ExternalFacet? {
        val sets = spec.kmp?.sourceSets.orEmpty().filter { it.common }.map { "src/${it.name}/kotlin" }
        if (sets.isEmpty() && spec.optIns.isEmpty()) return null
        // The plugin generates `commonMain`'s Res accessors INTO commonMain, so common code can import them. Left
        // platform-side, the common half of the compilation cannot see `Res` at all.
        val common = if (sets.isEmpty()) sets
        else sets + listOfNotNull(
            ComposeResourcesFacet.GENERATED_KOTLIN_ROOT.takeIf { spec.composeResources != null },
            KotlinBuildConfigFacet.GENERATED_KOTLIN_ROOT.takeIf { spec.kotlinBuildConfig != null },
        )
        val facet = KotlinFacet(commonSourceRoots = common, optIns = spec.optIns)
        return ExternalFacet(KotlinFacetCodec.tomlTable, KotlinFacetCodec.encode(facet))
    }

    private fun androidFacet(spec: GradleImport.ModuleSpec): AndroidFacet = AndroidFacet(
        namespace = spec.namespace ?: "com.example.${spec.name}",
        manifest = spec.manifest ?: DEFAULT_MANIFEST,
        compileSdk = spec.compileSdk ?: DEFAULT_COMPILE_SDK,
        minSdk = spec.minSdk ?: DEFAULT_MIN_SDK,
        targetSdk = spec.targetSdk ?: spec.minSdk ?: DEFAULT_MIN_SDK,
        // Only override the facet defaults when actually parsed, so an unset value keeps deferring to the
        // manifest (AndroidFacet's DSL-wins rule).
        versionCode = spec.versionCode ?: AndroidFacet.DEFAULT_VERSION_CODE,
        versionName = spec.versionName ?: AndroidFacet.DEFAULT_VERSION_NAME,
        manifestPlaceholders = spec.manifestPlaceholders,
        buildConfigFields = spec.buildConfigFields.map(::buildConfigField),
        isApplication = spec.kind == GradleImport.Kind.ANDROID_APP,
        flavorDimensions = spec.flavorDimensions,
        // AGP always has `debug` and `release`: a `buildTypes { }` naming only `release` configures that one and
        // leaves `debug` as it was, so the defaults fill in whichever the script does not mention.
        buildTypes = spec.buildTypes.map {
            BuildType(
                it.name,
                debuggable = it.debuggable ?: (it.name == "debug"),
                minifyEnabled = it.minifyEnabled,
                shrinkResources = it.shrinkResources,
                proguardFiles = it.proguardFiles,
                applicationIdSuffix = it.applicationIdSuffix,
                versionNameSuffix = it.versionNameSuffix,
                manifestPlaceholders = it.manifestPlaceholders,
                buildConfigFields = it.buildConfigFields.map(::buildConfigField),
            )
        }.let { declared ->
            AndroidFacet.DEFAULT_BUILD_TYPES.map { d -> declared.firstOrNull { it.name == d.name } ?: d } +
                declared.filter { t -> AndroidFacet.DEFAULT_BUILD_TYPES.none { it.name == t.name } }
        },
        productFlavors = spec.productFlavors.map {
            ProductFlavor(
                it.name, dimension = it.dimension, manifestPlaceholders = it.manifestPlaceholders,
                buildConfigFields = it.buildConfigFields.map(::buildConfigField),
            )
        },
        buildFeatures = BuildFeatures(
            viewBinding = spec.viewBinding,
            compose = spec.isCompose,
            parcelize = spec.parcelize,
            serialization = spec.serialization,
            kspProcessors = spec.kspProcessors,
            buildConfig = spec.buildConfig ?: true,
        ),
        packaging = AndroidPackaging(noCompress = spec.noCompress),
    )

    private fun buildConfigField(f: GradleImport.BuildConfigFieldSpec) = BuildConfigField(f.type, f.name, f.value)

    /** A declared `group:name[:version[:classifier]]`, or null when the text isn't a coordinate. The
     *  four-part form names a secondary artifact of the module (libGDX's per-ABI `natives-*` jars); it used
     *  to fall through to null, so importing such a build silently dropped those declarations. */
    private fun coordinateOrNull(coordinate: String): Coordinate? = Coordinate.parseOrNull(coordinate)

    private companion object {
        /** Only a fallback: it applies when the scripts declare no `compileSdk` at all (an unreadable
         *  convention plugin), so it tracks the newest level the IDE supports rather than lagging it. */
        const val DEFAULT_COMPILE_SDK = AndroidApiLevels.LATEST
        const val DEFAULT_MIN_SDK = 21
        const val DEFAULT_MANIFEST = "src/main/AndroidManifest.xml"
    }
}
