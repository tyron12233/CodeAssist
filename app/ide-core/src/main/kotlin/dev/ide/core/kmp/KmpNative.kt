package dev.ide.core.kmp

import dev.ide.android.support.AndroidApiLevels
import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.BuildFeatures
import dev.ide.build.jvm.compose.ComposeResourcesFacet
import dev.ide.build.jvm.compose.ComposeResourcesFacetCodec
import dev.ide.core.gradle.GradleImport
import dev.ide.lang.kotlin.build.KotlinFacet
import dev.ide.lang.kotlin.build.KotlinFacetCodec
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetData
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.ModifiableModule
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.PlatformDependency
import dev.ide.model.SourceSetTemplate
import dev.ide.model.sync.ExternalFacet
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalPlatform
import dev.ide.model.sync.ExternalSourceSet

/**
 * A Kotlin Multiplatform module authored natively (the template with Gradle turned off, New Module, Module
 * Settings' Add feature): Android and the desktop JVM, in the layout the KMP wizard uses (`src/commonMain`,
 * `src/androidMain`, `src/desktopMain`), recorded with both targets ([KmpShapes.describe]) and shaped for
 * [Options.active].
 */
object KmpNative {

    data class Options(
        val name: String,
        val dirRelPath: String = name,
        /** The Android target's namespace (and its `applicationId` for an app). */
        val namespace: String,
        /** An application (an APK and a desktop window) rather than a library. */
        val application: Boolean,
        /** Depend on Compose Multiplatform (runtime, foundation, Material 3, UI). */
        val compose: Boolean = true,
        /** Add `src/commonMain/composeResources` and the `Res` generator. */
        val composeResources: Boolean = false,
        /** The target to shape the module for now: [KmpShapes.ANDROID] or [KmpShapes.JVM]. */
        val active: String,
        val composeVersion: String = COMPOSE_VERSION,
        /** The JVM target's source-set directory: `desktopMain` for an app (the wizard's), `jvmMain` for a library. */
        val jvmSourceSet: String = "desktopMain",
    )

    /** The Compose Multiplatform the IDE ships and its KMP template declares (a desktop Run uses the IDE's copy). */
    const val COMPOSE_VERSION = "1.11.1"

    /** The module, shaped for [Options.active] with both targets recorded. */
    fun module(o: Options): ExternalModule = KmpShapes.describe(
        listOf(
            KmpShapes.Shaped(KmpShapes.ANDROID, KmpShapes.ANDROID, shape(o, android = true)),
            KmpShapes.Shaped(KmpShapes.JVM, KmpShapes.JVM, shape(o, android = false)),
        ),
        o.active,
    )

    private fun shape(o: Options, android: Boolean): ExternalModule {
        val platformSet = if (android) "androidMain" else o.jvmSourceSet
        val main = linkedMapOf<String, Set<ContentRole>>(
            "src/commonMain/kotlin" to setOf(ContentRole.SOURCE),
            "src/$platformSet/kotlin" to setOf(ContentRole.SOURCE),
        )
        if (android) main["src/androidMain/res"] = setOf(ContentRole.ANDROID_RES)
        else main["src/desktopMain/resources"] = setOf(ContentRole.RESOURCE)
        val sets = buildList {
            add(ExternalSourceSet("main", DependencyScope.IMPLEMENTATION, main))
            add(ExternalSourceSet("test", DependencyScope.TEST_IMPLEMENTATION, linkedMapOf("src/commonTest/kotlin" to setOf(ContentRole.SOURCE))))
            if (o.composeResources) add(
                ExternalSourceSet(
                    "composeResources", DependencyScope.IMPLEMENTATION,
                    linkedMapOf(
                        ComposeResourcesFacet.GENERATED_KOTLIN_ROOT to setOf(ContentRole.SOURCE),
                        ComposeResourcesFacet.GENERATED_RESOURCES_ROOT to setOf(ContentRole.RESOURCE),
                    ),
                )
            )
        }
        fun compose(path: String) = GradleImport.composeAccessorCoordinate(path, o.composeVersion)!!
        val deps = buildList {
            if (o.compose) {
                listOf("runtime", "foundation", "material3", "ui", "components.uiToolingPreview").forEach { add(ExternalLibrary(compose(it))) }
                if (o.composeResources) add(ExternalLibrary(compose("components.resources")))
            }
            add(ExternalLibrary("org.jetbrains.kotlin:kotlin-test:${KOTLIN_TEST_VERSION}", DependencyScope.TEST_IMPLEMENTATION))
            if (android && o.application && o.compose) add(ExternalLibrary("androidx.activity:activity-compose:1.13.0"))
            if (!android && o.compose) {
                add(ExternalLibrary(compose("desktop.currentOs")))
                add(ExternalLibrary("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0"))
            }
        }
        val facets = buildList {
            if (android) add(
                ExternalFacet(
                    AndroidFacetCodec.tomlTable,
                    AndroidFacetCodec.encode(
                        AndroidFacet(
                            namespace = o.namespace,
                            compileSdk = AndroidApiLevels.LATEST,
                            minSdk = 24,
                            targetSdk = AndroidApiLevels.LATEST,
                            manifest = "src/androidMain/AndroidManifest.xml",
                            isApplication = o.application,
                            buildFeatures = BuildFeatures(compose = o.compose),
                        )
                    ),
                )
            )
            if (o.composeResources) add(
                ExternalFacet(
                    ComposeResourcesFacetCodec.tomlTable,
                    ComposeResourcesFacetCodec.encode(
                        ComposeResourcesFacet(
                            "${o.namespace}.generated.resources",
                            roots = listOf("src/commonMain/composeResources"),
                        )
                    ),
                )
            )
            val common = listOf("src/commonMain/kotlin") +
                (if (o.composeResources) listOf(ComposeResourcesFacet.GENERATED_KOTLIN_ROOT) else emptyList())
            add(ExternalFacet(KotlinFacetCodec.tomlTable, KotlinFacetCodec.encode(KotlinFacet(commonSourceRoots = common))))
        }
        return ExternalModule(
            name = o.name,
            dirRelPath = o.dirRelPath,
            typeId = when {
                !android -> "java-lib"
                o.application -> "android-app"
                else -> "android-lib"
            },
            sourceSets = sets,
            dependencies = deps,
            facets = facets,
        )
    }

    private const val KOTLIN_TEST_VERSION = "2.4.0"

    /**
     * Apply [module] to a module being built in a transaction ([target]), through the public model API: what
     * `ExternalModelApplier` does for an importer, for callers that hold a transaction rather than the store (a
     * template). [moduleIds] resolves a module dependency's name to its id (ids are names).
     */
    fun applyTo(target: ModifiableModule, module: ExternalModule, languageLevel: LanguageLevel) {
        target.dirRelPath = module.dirRelPath
        target.languageLevel = module.languageLevel ?: languageLevel
        for (set in module.sourceSets) target.addSourceSet(SourceSetTemplate(set.name, set.scope, set.roots))
        for (dep in module.dependencies) when (dep) {
            is ExternalLibrary -> target.addDependency(
                LibraryDependency(LibraryRef(dep.coordinate), dep.scope, exported = dep.scope == DependencyScope.API, exclusions = dep.exclusions, variant = dep.variant)
            )
            is ExternalModuleRef -> target.addDependency(
                ModuleDependency(ModuleId(dep.moduleName), dep.scope, exported = dep.scope == DependencyScope.API, variant = dep.variant)
            )
            is ExternalPlatform -> target.addDependency(PlatformDependency(dep.bom, dep.scope, variant = dep.variant))
        }
        for (f in module.facets) target.putFacetData(FacetData(f.table, f.values))
    }
}
