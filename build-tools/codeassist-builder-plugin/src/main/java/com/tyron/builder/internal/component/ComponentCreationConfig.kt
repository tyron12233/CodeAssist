package com.tyron.builder.internal.component

import com.tyron.builder.api.artifact.impl.ArtifactsImpl
import com.tyron.builder.api.variant.ComponentIdentity
import com.tyron.builder.internal.variant.VariantPathHelper
import com.tyron.builder.plugin.builder.ProductFlavor

interface ComponentCreationConfig : ComponentIdentity {

    // ---------------------------------------------------------------------------------------------
    // BASIC INFO
    // ---------------------------------------------------------------------------------------------
    val dirName: String
    val baseName: String
//    val componentType: ComponentType
    val description: String
    val productFlavorList: List<ProductFlavor>
    fun computeTaskName(prefix: String, suffix: String): String
    fun computeTaskName(prefix: String): String

    // ---------------------------------------------------------------------------------------------
    // INTERNAL DELEGATES
    // ---------------------------------------------------------------------------------------------
    val artifacts: ArtifactsImpl
    val paths: VariantPathHelper
}
