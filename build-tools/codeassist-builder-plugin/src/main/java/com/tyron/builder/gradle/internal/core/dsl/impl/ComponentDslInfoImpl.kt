package com.tyron.builder.gradle.internal.core.dsl.impl

import com.tyron.builder.api.dsl.ApplicationBuildType
import com.tyron.builder.api.dsl.ApplicationProductFlavor
import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.CommonExtension
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.api.dsl.VariantDimension
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.gradle.ProguardFiles
import com.tyron.builder.gradle.api.JavaCompileOptions
import com.tyron.builder.gradle.internal.PostprocessingFeatures
import com.tyron.builder.gradle.internal.ProguardFileType
import com.tyron.builder.gradle.internal.core.MergedFlavor
import com.tyron.builder.gradle.internal.core.MergedJavaCompileOptions
import com.tyron.builder.gradle.internal.core.MergedOptions
import com.tyron.builder.gradle.internal.core.PostProcessingBlockOptions
import com.tyron.builder.gradle.internal.core.PostProcessingOptions
import com.tyron.builder.gradle.internal.core.dsl.ComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.MultiVariantComponentDslInfo
import com.tyron.builder.gradle.internal.core.dsl.features.AndroidResourcesDslInfo
import com.tyron.builder.gradle.internal.dsl.DefaultConfig
import com.tyron.builder.gradle.internal.services.VariantServices
import com.tyron.builder.core.AbstractProductFlavor
import com.tyron.builder.core.ComponentType
import com.tyron.builder.model.BaseConfig
import com.google.common.collect.ImmutableMap
import org.gradle.api.file.DirectoryProperty
import java.io.File

internal abstract class ComponentDslInfoImpl internal constructor(
    override val componentIdentity: ComponentIdentity,
    final override val componentType: ComponentType,
    protected val defaultConfig: DefaultConfig,
    /**
     * Public because this is needed by the old Variant API. Nothing else should touch this.
     */
    val buildTypeObj: BuildType,
    override val productFlavorList: List<ProductFlavor>,
    protected val services: VariantServices,
    private val buildDirectory: DirectoryProperty,
    protected val extension: CommonExtension<*, *, *, *>
): ComponentDslInfo, MultiVariantComponentDslInfo {

    /**
     * This should be mostly private and not used outside this class, but is still public for legacy
     * variant API and model v1 support.
     *
     * At some point we should remove this and rely on each property to combine dsl values in the
     * manner that it is meaningful for the property. Take a look at
     * [VariantDslInfoImpl.initApplicationId] for guidance on how will that look like.
     *
     * DO NOT USE. You should mostly use the interfaces which does not give access to this.
     */
    val mergedFlavor: MergedFlavor by lazy {
        MergedFlavor.mergeFlavors(
            defaultConfig,
            productFlavorList.map { it as com.tyron.builder.gradle.internal.dsl.ProductFlavor },
            applicationId,
            services
        )
    }

    final override val javaCompileOptionsSetInDSL = MergedJavaCompileOptions()

    init {
        computeMergedOptions(
            javaCompileOptionsSetInDSL,
            { javaCompileOptions as JavaCompileOptions },
            { javaCompileOptions as JavaCompileOptions }
        )
    }

    // merged flavor delegates

    override val missingDimensionStrategies: ImmutableMap<String, AbstractProductFlavor.DimensionRequest>
        get() = ImmutableMap.copyOf(mergedFlavor.missingDimensionStrategies)

    // build type delegates

    override val postProcessingOptions: PostProcessingOptions by lazy {
        if ((buildTypeObj as com.tyron.builder.gradle.internal.dsl.BuildType)
                .postProcessingConfiguration ==
            com.tyron.builder.gradle.internal.dsl.BuildType.PostProcessingConfiguration.POSTPROCESSING_BLOCK
        ) {
            PostProcessingBlockOptions(
                buildTypeObj.postprocessing, componentType.isTestComponent
            )
        } else object : PostProcessingOptions {
            override fun getProguardFiles(type: ProguardFileType): Collection<File> =
                buildTypeObj.getProguardFiles(type)

            override fun getDefaultProguardFiles(): List<File> =
                listOf(
                    ProguardFiles.getDefaultProguardFile(
                        ProguardFiles.ProguardFile.DONT_OPTIMIZE.fileName,
                        buildDirectory
                    )
                )

            override fun getPostprocessingFeatures(): PostprocessingFeatures? = null

            override fun codeShrinkerEnabled() = buildTypeObj.isMinifyEnabled

            override fun resourcesShrinkingEnabled(): Boolean = buildTypeObj.isShrinkResources

            override fun hasPostProcessingConfiguration() = false
        }
    }

    override fun gatherProguardFiles(type: ProguardFileType): Collection<File> {
        val result: MutableList<File> = ArrayList(defaultConfig.getProguardFiles(type))
        for (flavor in productFlavorList) {
            result.addAll((flavor as com.tyron.builder.gradle.internal.dsl.ProductFlavor).getProguardFiles(type))
        }
        result.addAll(postProcessingOptions.getProguardFiles(type))
        return result
    }

    // helper methods

    override val androidResourcesDsl: AndroidResourcesDslInfo by lazy {
        AndroidResourcesDslInfoImpl(
            defaultConfig, buildTypeObj, productFlavorList, mergedFlavor, extension
        )
    }

    /**
     * Merge a specific option in GradleVariantConfiguration.
     *
     *
     * It is assumed that merged option type with a method to reset and append is created for the
     * option being merged.
     *
     *
     * The order of priority is BuildType, ProductFlavors, and default config. ProductFlavor
     * added earlier has higher priority than ProductFlavor added later.
     *
     * @param mergedOption The merged option store in the GradleVariantConfiguration.
     * @param getFlavorOption A Function to return the option from a ProductFlavor.
     * @param getBuildTypeOption A Function to return the option from a BuildType.
     * takes priority and overwrite option in the first input argument.
     * @param <CoreOptionsT> The core type of the option being merge.
     * @param <MergedOptionsT> The merge option type.
    </MergedOptionsT></CoreOptionsT> */
    protected fun <CoreOptionsT, MergedOptionsT : MergedOptions<CoreOptionsT>> computeMergedOptions(
        mergedOption: MergedOptionsT,
        getFlavorOption: VariantDimension.() -> CoreOptionsT?,
        getBuildTypeOption: BuildType.() -> CoreOptionsT?
    ) {
        mergedOption.reset()

        val defaultOption = defaultConfig.getFlavorOption()
        if (defaultOption != null) {
            mergedOption.append(defaultOption)
        }
        // reverse loop for proper order
        for (i in productFlavorList.indices.reversed()) {
            val flavorOption = productFlavorList[i].getFlavorOption()
            if (flavorOption != null) {
                mergedOption.append(flavorOption)
            }
        }
        val buildTypeOption = buildTypeObj.getBuildTypeOption()
        if (buildTypeOption != null) {
            mergedOption.append(buildTypeOption)
        }
    }

    private fun BaseConfig.getProguardFiles(type: ProguardFileType): Collection<File> = when (type) {
        ProguardFileType.EXPLICIT -> this.proguardFiles
        ProguardFileType.TEST -> this.testProguardFiles
        ProguardFileType.CONSUMER -> this.consumerProguardFiles
    }

    /**
     * Combines all the appId suffixes into a single one.
     *
     * The suffixes are separated by '.' whether their first char is a '.' or not.
     */
    protected fun computeApplicationIdSuffix(): String {
        // for the suffix we combine the suffix from all the flavors. However, we're going to
        // want the higher priority one to be last.
        val suffixes = mutableListOf<String>()
        defaultConfig.applicationIdSuffix?.let {
            suffixes.add(it)
        }

        suffixes.addAll(
            productFlavorList
                .asSequence()
                .filterIsInstance(ApplicationProductFlavor::class.java)
                .mapNotNull { it.applicationIdSuffix })

        // then we add the build type after.
        (buildTypeObj as? ApplicationBuildType)?.applicationIdSuffix?.let {
            suffixes.add(it)
        }
        val nonEmptySuffixes = suffixes.filter { it.isNotEmpty() }
        return if (nonEmptySuffixes.isNotEmpty()) {
            ".${nonEmptySuffixes.joinToString(separator = ".", transform = { it.removePrefix(".") })}"
        } else {
            ""
        }
    }
}
