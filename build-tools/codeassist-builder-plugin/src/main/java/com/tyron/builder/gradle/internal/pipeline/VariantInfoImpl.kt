package com.tyron.builder.gradle.internal.pipeline

import com.google.common.collect.ImmutableList
import com.tyron.builder.api.variant.VariantInfo
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig
import com.tyron.builder.gradle.internal.utils.toImmutableList

data class VariantInfoImpl(
    val _isTest: Boolean,
    val _variantName: String,
    val _buildTypeName: String?,
    val _flavorNames: ImmutableList<String>,
    val _isDebuggable: Boolean
) : VariantInfo {

    constructor(creationConfig: ComponentCreationConfig) :
            this(
                _isTest = creationConfig.componentType.isForTesting,
                _variantName = creationConfig.name,
                _buildTypeName = creationConfig.buildType,
                _flavorNames = creationConfig.productFlavorList.map { it.name }.toImmutableList(),
                _isDebuggable = creationConfig.debuggable
            )

    override fun isTest(): Boolean = _isTest
    override fun getFullVariantName(): String = _variantName
    override fun getBuildTypeName(): String = _buildTypeName ?: ""
    override fun getFlavorNames(): ImmutableList<String> = _flavorNames
    override fun isDebuggable(): Boolean = _isDebuggable

}
