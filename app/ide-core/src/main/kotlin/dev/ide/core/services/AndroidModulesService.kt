package dev.ide.core.services

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.AndroidVariants
import dev.ide.core.EngineContext
import dev.ide.model.AndroidBuildFeatures
import dev.ide.model.AndroidBuildType
import dev.ide.model.AndroidModuleInfo
import dev.ide.model.AndroidModules
import dev.ide.model.AndroidProductFlavor
import dev.ide.model.AndroidVariantInfo
import dev.ide.model.Module

/**
 * The published [AndroidModules] view over the engine's own Android model: each module's [AndroidFacet], its
 * variants as [AndroidVariants] computes them for the build, and the variant the editor analyses it as.
 *
 * Read on every call, never cached, so a plugin sees a settings edit or a sync the moment the model has it.
 * The answers are snapshots: plain values copied out of the facet, so nothing a plugin holds keeps the model
 * alive or changes under it.
 */
internal class AndroidModulesService(private val engine: EngineContext) : AndroidModules {

    override fun info(module: Module): AndroidModuleInfo? {
        val facet = module.facets.get(AndroidFacet.KEY) ?: return null
        val variants = AndroidVariants.compute(module).map { v ->
            val buildType = facet.buildType(v.buildTypeName)
            Variant(
                name = v.name,
                buildType = v.buildTypeName,
                flavors = v.flavorNames,
                applicationId = if (facet.isApplication) AndroidVariants.applicationId(facet, v) else null,
                debuggable = buildType?.debuggable ?: (v.buildTypeName == "debug"),
                minifyEnabled = buildType?.minifyEnabled ?: false,
            )
        }
        val f = facet.buildFeatures
        return Info(
            moduleName = module.name,
            isApplication = facet.isApplication,
            namespace = facet.namespace,
            applicationId = if (facet.isApplication) facet.applicationId ?: facet.namespace else null,
            compileSdk = facet.compileSdk,
            minSdk = facet.minSdk,
            targetSdk = facet.targetSdk,
            // AGP's defaults are how the model says "not set": the manifest's value then wins at link.
            versionCode = facet.versionCode.takeIf { it != AndroidFacet.DEFAULT_VERSION_CODE },
            versionName = facet.versionName.takeIf { it != AndroidFacet.DEFAULT_VERSION_NAME },
            manifestPath = facet.manifest,
            manifestPlaceholders = facet.manifestPlaceholders.toMap(),
            coreLibraryDesugaring = facet.coreLibraryDesugaringEnabled,
            buildFeatures = Features(f.compose, f.viewBinding, f.buildConfig, f.parcelize, f.serialization),
            flavorDimensions = facet.flavorDimensions.toList(),
            buildTypes = facet.buildTypes.map {
                BuildType(it.name, it.debuggable, it.minifyEnabled, it.shrinkResources, it.applicationIdSuffix, it.versionNameSuffix)
            },
            productFlavors = facet.productFlavors.map {
                Flavor(it.name, it.dimension, it.applicationId, it.applicationIdSuffix, it.versionName)
            },
            variants = variants,
            activeVariant = engine.activeVariant(module),
        )
    }

    override fun info(moduleName: String): AndroidModuleInfo? =
        engine.modules().firstOrNull { it.name == moduleName }?.let(::info)

    override fun all(): List<AndroidModuleInfo> = engine.modules().mapNotNull(::info)

    private data class Info(
        override val moduleName: String,
        override val isApplication: Boolean,
        override val namespace: String,
        override val applicationId: String?,
        override val compileSdk: Int,
        override val minSdk: Int,
        override val targetSdk: Int,
        override val versionCode: Int?,
        override val versionName: String?,
        override val manifestPath: String,
        override val manifestPlaceholders: Map<String, String>,
        override val coreLibraryDesugaring: Boolean,
        override val buildFeatures: AndroidBuildFeatures,
        override val flavorDimensions: List<String>,
        override val buildTypes: List<AndroidBuildType>,
        override val productFlavors: List<AndroidProductFlavor>,
        override val variants: List<AndroidVariantInfo>,
        override val activeVariant: String,
    ) : AndroidModuleInfo

    private data class Features(
        override val compose: Boolean,
        override val viewBinding: Boolean,
        override val buildConfig: Boolean,
        override val parcelize: Boolean,
        override val serialization: Boolean,
    ) : AndroidBuildFeatures

    private data class BuildType(
        override val name: String,
        override val debuggable: Boolean,
        override val minifyEnabled: Boolean,
        override val shrinkResources: Boolean,
        override val applicationIdSuffix: String?,
        override val versionNameSuffix: String?,
    ) : AndroidBuildType

    private data class Flavor(
        override val name: String,
        override val dimension: String?,
        override val applicationId: String?,
        override val applicationIdSuffix: String?,
        override val versionName: String?,
    ) : AndroidProductFlavor

    private data class Variant(
        override val name: String,
        override val buildType: String,
        override val flavors: List<String>,
        override val applicationId: String?,
        override val debuggable: Boolean,
        override val minifyEnabled: Boolean,
    ) : AndroidVariantInfo
}
