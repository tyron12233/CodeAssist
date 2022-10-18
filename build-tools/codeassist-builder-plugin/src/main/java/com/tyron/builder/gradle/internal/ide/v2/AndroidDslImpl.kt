package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.dsl.BuildType
import com.tyron.builder.model.v2.dsl.DependenciesInfo
import com.tyron.builder.model.v2.dsl.ProductFlavor
import com.tyron.builder.model.v2.dsl.SigningConfig
import com.tyron.builder.model.v2.ide.AaptOptions
import com.tyron.builder.model.v2.ide.LintOptions
import com.tyron.builder.model.v2.models.AndroidDsl
import java.io.Serializable

data class AndroidDslImpl(
    override val groupId: String?,
    override val defaultConfig: ProductFlavor,
    override val buildTypes: List<BuildType>,
    override val flavorDimensions: Collection<String>,
    override val productFlavors: List<ProductFlavor>,
    override val compileTarget: String,
    override val signingConfigs: Collection<SigningConfig>,
    override val aaptOptions: AaptOptions,
    override val lintOptions: LintOptions,
    override val buildToolsVersion: String,
    override val dependenciesInfo: DependenciesInfo?
): AndroidDsl, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
