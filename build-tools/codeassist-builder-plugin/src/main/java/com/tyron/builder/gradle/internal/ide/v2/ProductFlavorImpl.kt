package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.dsl.ClassField
import com.tyron.builder.model.v2.dsl.ProductFlavor
import com.tyron.builder.model.v2.ide.ApiVersion
import com.tyron.builder.model.v2.ide.VectorDrawablesOptions
import java.io.File
import java.io.Serializable

/**
 * Implementation of [ProductFlavor] for serialization via the Tooling API.
 */
data class ProductFlavorImpl(
    override val applicationId: String?,
    override val versionCode: Int?,
    override val versionName: String?,
    override val minSdkVersion: ApiVersion?,
    override val targetSdkVersion: ApiVersion?,
    override val maxSdkVersion: Int?,
    override val renderscriptTargetApi: Int?,
    override val renderscriptSupportModeEnabled: Boolean?,
    override val renderscriptSupportModeBlasEnabled: Boolean?,
    override val renderscriptNdkModeEnabled: Boolean?,
    override val testApplicationId: String?,
    override val testInstrumentationRunner: String?,
    override val testInstrumentationRunnerArguments: Map<String, String>,
    override val testHandleProfiling: Boolean?,
    override val testFunctionalTest: Boolean?,
    override val resourceConfigurations: Collection<String>,
    override val signingConfig: String?,
    override val vectorDrawables: VectorDrawablesOptions,
    override val wearAppUnbundled: Boolean?,
    override val applicationIdSuffix: String?,
    override val versionNameSuffix: String?,
    override val buildConfigFields: Map<String, ClassField>?,
    override val resValues: Map<String, ClassField>?,
    override val proguardFiles: Collection<File>,
    override val consumerProguardFiles: Collection<File>,
    override val testProguardFiles: Collection<File>,
    override val manifestPlaceholders: Map<String, Any>,
    override val multiDexEnabled: Boolean?,
    override val multiDexKeepFile: File?,
    override val multiDexKeepProguard: File?,
    override val isDefault: Boolean? = null,
    override val name: String,
    override val dimension: String?
) : ProductFlavor, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
