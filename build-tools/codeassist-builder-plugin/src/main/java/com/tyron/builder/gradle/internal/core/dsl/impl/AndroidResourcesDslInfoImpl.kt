package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.AndroidResources
import com.tyron.builder.api.dsl.ApplicationBuildType
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.DynamicFeatureBuildType
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.variant.ResValue
import com.tyron.builder.api.variant.impl.ResValueKeyImpl
import com.tyron.builder.gradle.internal.core.MergedFlavor
import com.tyron.builder.gradle.internal.core.dsl.features.AndroidResourcesDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.model.ClassField
import com.tyron.builder.model.VectorDrawablesOptions
import com.google.common.collect.ImmutableSet

internal class AndroidResourcesDslInfoImpl(
    private val defaultConfig: DefaultConfig,
    private val buildTypeObj: BuildType,
    private val productFlavorList: List<ProductFlavor>,
    private val mergedFlavor: MergedFlavor,
    private val extension: CommonExtension<*, *, *, *>
): AndroidResourcesDslInfo {
    override val androidResources: AndroidResources
        get() = extension.androidResources

    // build type delegates

    override val isPseudoLocalesEnabled: Boolean
        get() = buildTypeObj.isPseudoLocalesEnabled

    override val isCrunchPngs: Boolean?
        get() {
            return when (buildTypeObj) {
                is ApplicationBuildType -> buildTypeObj.isCrunchPngs
                is DynamicFeatureBuildType -> buildTypeObj.isCrunchPngs
                else -> false
            }
        }

    override val resourceConfigurations: ImmutableSet<String>
        get() = ImmutableSet.copyOf(mergedFlavor.resourceConfigurations)

    override val vectorDrawables: VectorDrawablesOptions
        get() = mergedFlavor.vectorDrawables

    override val isCrunchPngsDefault: Boolean
        // does not exist in the new DSL
        get() = (buildTypeObj as com.tyron.builder.gradle.internal.dsl.BuildType).isCrunchPngsDefault

    override fun getResValues(): Map<ResValue.Key, ResValue> {
        val resValueFields = mutableMapOf<ResValue.Key, ResValue>()

        fun addToListIfNotAlreadyPresent(classField: ClassField, comment: String) {
            val key = ResValueKeyImpl(classField.type, classField.name)
            if (!resValueFields.containsKey(key)) {
                resValueFields[key] = ResValue(
                    value = classField.value,
                    comment = comment
                )
            }
        }

        (buildTypeObj as com.tyron.builder.gradle.internal.dsl.BuildType).resValues.values.forEach { classField ->
            addToListIfNotAlreadyPresent(classField, "Value from build type: ${buildTypeObj.name}")
        }

        productFlavorList.forEach { flavor ->
            (flavor as com.tyron.builder.gradle.internal.dsl.ProductFlavor)
                .resValues.values.forEach { classField ->
                addToListIfNotAlreadyPresent(
                    classField,
                    "Value from product flavor: ${flavor.name}"
                )
            }
        }

        defaultConfig.resValues.values.forEach { classField ->
            addToListIfNotAlreadyPresent(classField, "Value from default config.")
        }

        return resValueFields
    }
}
