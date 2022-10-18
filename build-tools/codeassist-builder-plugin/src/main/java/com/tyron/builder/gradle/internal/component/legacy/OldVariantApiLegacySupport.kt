package com.tyron.builder.gradle.internal.component.legacy

import com.tyron.builder.api.dsl.BuildType
import com.tyron.builder.api.dsl.ProductFlavor
import com.tyron.builder.gradle.api.JavaCompileOptions
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.core.MergedFlavor
import com.tyron.builder.gradle.internal.publishing.AndroidArtifacts
import com.tyron.builder.gradle.internal.variant.BaseVariantData
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.file.FileCollection
import java.io.Serializable

interface OldVariantApiLegacySupport {
    val buildTypeObj: BuildType
    val productFlavorList: List<ProductFlavor>
    val mergedFlavor: MergedFlavor
    val oldVariantApiJavaCompileOptions: JavaCompileOptions
    val variantData: BaseVariantData
    val dslSigningConfig: com.tyron.builder.gradle.internal.dsl.SigningConfig?

    fun getJavaClasspathArtifacts(
        configType: AndroidArtifacts.ConsumedConfigType,
        classesType: AndroidArtifacts.ArtifactType,
        generatedBytecodeKey: Any?
    ): ArtifactCollection

    fun addBuildConfigField(type: String, key: String, value: Serializable, comment: String?)

    // TODO : b/214316660
    fun getAllRawAndroidResources(component: ComponentCreationConfig): FileCollection

    fun handleMissingDimensionStrategy(dimension: String, alternatedValues: List<String>)
}
