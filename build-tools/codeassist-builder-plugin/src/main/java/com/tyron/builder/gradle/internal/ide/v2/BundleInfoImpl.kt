package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.BundleInfo
import java.io.File
import java.io.Serializable

/**
 * Implementation of [BundleInfo] for serialization via the Tooling API.
 */
data class BundleInfoImpl(
    override val bundleTaskName: String,
    override val bundleTaskOutputListingFile: File,
    override val apkFromBundleTaskName: String,
    override val apkFromBundleTaskOutputListingFile: File
) : BundleInfo, Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
