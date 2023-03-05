package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.ApplicationBuildType
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.BuildConfigField
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.gradle.internal.core.MergedOptimization
import com.tyron.builder.gradle.internal.core.dsl.ConsumableComponentDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.dsl.OptimizationImpl
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.gradle.options.IntegerOption
import com.tyron.builder.core.ComponentType
import com.tyron.builder.model.ClassField
import com.google.common.collect.ImmutableList
import com.google.common.collect.Lists
import com.google.common.collect.Maps
import com.google.common.collect.Sets
import org.gradle.api.file.DirectoryProperty
import java.io.File

internal abstract class ConsumableComponentDslInfoImpl internal constructor(
    componentIdentity: ComponentIdentity,
    componentType: ComponentType,
    defaultConfig: DefaultConfig,
    buildTypeObj: BuildType,
    productFlavorList: List<ProductFlavor>,
    services: VariantServices,
    buildDirectory: DirectoryProperty,
    extension: CommonExtension<*, *, *, *>
) : ComponentDslInfoImpl(
    componentIdentity,
    componentType,
    defaultConfig,
    buildTypeObj,
    productFlavorList,
    services,
    buildDirectory,
    extension
), ConsumableComponentDslInfo {

    // merged options

    private val mergedOptimization = MergedOptimization()

    init {
        mergeOptions()
    }

    private fun mergeOptions() {
        computeMergedOptions(
            mergedOptimization,
            { optimization as OptimizationImpl },
            { optimization as OptimizationImpl }
        )
    }

    override val ignoredLibraryKeepRules: Set<String>
        get() = mergedOptimization.ignoredLibraryKeepRules

    override val ignoreAllLibraryKeepRules: Boolean
        get() = mergedOptimization.ignoreAllLibraryKeepRules

    // merged flavor delegates

    override val renderscriptTarget: Int
        get() = mergedFlavor.renderscriptTargetApi ?: -1
    override val renderscriptSupportModeEnabled: Boolean
        get() = mergedFlavor.renderscriptSupportModeEnabled ?: false
    override val renderscriptSupportModeBlasEnabled: Boolean
        get() {
            val value = mergedFlavor.renderscriptSupportModeBlasEnabled
            return value ?: false
        }
    override val renderscriptNdkModeEnabled: Boolean
        get() = mergedFlavor.renderscriptNdkModeEnabled ?: false

    override val manifestPlaceholders: Map<String, String> by lazy {
        val mergedFlavorsPlaceholders: MutableMap<String, String> = mutableMapOf()
        mergedFlavor.manifestPlaceholders.forEach { (key, value) ->
            mergedFlavorsPlaceholders[key] = value.toString()
        }
        // so far, blindly override the build type placeholders
        buildTypeObj.manifestPlaceholders.forEach { (key, value) ->
            mergedFlavorsPlaceholders[key] = value.toString()
        }
        mergedFlavorsPlaceholders
    }

    // build type delegates

    // Only require specific multidex opt-in for legacy multidex.
    override val isMultiDexEnabled: Boolean?
        get() {
            // Only require specific multidex opt-in for legacy multidex.
            return (buildTypeObj as? ApplicationBuildType)?.multiDexEnabled
                ?: mergedFlavor.multiDexEnabled
        }
    override val multiDexKeepProguard: File?
        get() {
            var value = buildTypeObj.multiDexKeepProguard
            if (value != null) {
                return value
            }
            value = mergedFlavor.multiDexKeepProguard
            return value
        }
    override val multiDexKeepFile: File?
        get() {
            var value = buildTypeObj.multiDexKeepFile
            if (value != null) {
                return value
            }
            value = mergedFlavor.multiDexKeepFile
            return value
        }

    override val renderscriptOptimLevel: Int
        get() = buildTypeObj.renderscriptOptimLevel

    // add the lower priority one, to override them with the higher priority ones.
    // can't use merge flavor as it's not a prop on the base class.
    // reverse loop for proper order
    override val defaultGlslcArgs: List<String>
        get() {
            val optionMap: MutableMap<String, String> =
                Maps.newHashMap()
            // add the lower priority one, to override them with the higher priority ones.
            for (option in defaultConfig.shaders.glslcArgs) {
                optionMap[getKey(option)] = option
            }
            // can't use merge flavor as it's not a prop on the base class.
            // reverse loop for proper order
            for (i in productFlavorList.indices.reversed()) {
                for (option in productFlavorList[i].shaders.glslcArgs) {
                    optionMap[getKey(option)] = option
                }
            }
            // then the build type
            for (option in buildTypeObj.shaders.glslcArgs) {
                optionMap[getKey(option)] = option
            }
            return Lists.newArrayList(optionMap.values)
        }

    // first collect all possible keys.
    override val scopedGlslcArgs: Map<String, List<String>>
        get() {
            val scopedArgs: MutableMap<String, List<String>> =
                Maps.newHashMap()
            // first collect all possible keys.
            val keys = scopedGlslcKeys
            for (key in keys) { // first add to a temp map to resolve overridden values
                val optionMap: MutableMap<String, String> =
                    Maps.newHashMap()
                // we're going to go from lower priority, to higher priority elements, and for each
                // start with the non scoped version, and then add the scoped version.
                // 1. default config, global.
                for (option in defaultConfig.shaders.glslcArgs) {
                    optionMap[getKey(option)] = option
                }
                // 1b. default config, scoped.
                for (option in defaultConfig.shaders.scopedGlslcArgs[key]) {
                    optionMap[getKey(option)] = option
                }
                // 2. the flavors.
                // can't use merge flavor as it's not a prop on the base class.
                // reverse loop for proper order
                for (i in productFlavorList.indices.reversed()) { // global
                    for (option in productFlavorList[i].shaders.glslcArgs) {
                        optionMap[getKey(option)] = option
                    }
                    // scoped.
                    for (option in productFlavorList[i].shaders.scopedGlslcArgs[key]) {
                        optionMap[getKey(option)] = option
                    }
                }
                // 3. the build type, global
                for (option in buildTypeObj.shaders.glslcArgs) {
                    optionMap[getKey(option)] = option
                }
                // 3b. the build type, scoped.
                for (option in buildTypeObj.shaders.scopedGlslcArgs[key]) {
                    optionMap[getKey(option)] = option
                }
                // now add the full value list.
                scopedArgs[key] = ImmutableList.copyOf(optionMap.values)
            }
            return scopedArgs
        }

    // helper methods

    // TODO: move to global scope
    override val targetDeployApiFromIDE: Int? =
        services.projectOptions.get(IntegerOption.IDE_TARGET_DEVICE_API)

    override fun getBuildConfigFields(): Map<String, BuildConfigField<out java.io.Serializable>> {
        val buildConfigFieldsMap =
            mutableMapOf<String, BuildConfigField<out java.io.Serializable>>()

        fun addToListIfNotAlreadyPresent(classField: ClassField, comment: String) {
            if (!buildConfigFieldsMap.containsKey(classField.name)) {
                buildConfigFieldsMap[classField.name] =
                    BuildConfigField(classField.type , classField.value, comment)
            }
        }

        (buildTypeObj as com.tyron.builder.gradle.internal.dsl.BuildType).buildConfigFields.values.forEach { classField ->
            addToListIfNotAlreadyPresent(classField, "Field from build type: ${buildTypeObj.name}")
        }

        for (flavor in productFlavorList) {
            (flavor as com.tyron.builder.gradle.internal.dsl.ProductFlavor).buildConfigFields.values.forEach { classField ->
                addToListIfNotAlreadyPresent(
                    classField,
                    "Field from product flavor: ${flavor.name}"
                )
            }
        }
        defaultConfig.buildConfigFields.values.forEach { classField ->
            addToListIfNotAlreadyPresent(classField, "Field from default config.")
        }
        return buildConfigFieldsMap
    }

    private val scopedGlslcKeys: Set<String>
        get() {
            val keys: MutableSet<String> =
                Sets.newHashSet()
            keys.addAll(defaultConfig.shaders.scopedGlslcArgs.keySet())
            for (flavor in productFlavorList) {
                keys.addAll(flavor.shaders.scopedGlslcArgs.keySet())
            }
            keys.addAll(buildTypeObj.shaders.scopedGlslcArgs.keySet())
            return keys
        }

    private fun getKey(fullOption: String): String {
        val pos = fullOption.lastIndexOf('=')
        return if (pos == -1) {
            fullOption
        } else fullOption.substring(0, pos)
    }
}
