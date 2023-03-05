package com.tyron.builder.gradle.internal.ide.v2

import com.tyron.builder.model.v2.ide.PrivacySandboxSdkInfo
import java.io.File
import java.io.Serializable

/**
 * Implementation of [PrivacySandboxSdkInfo] for serialization via the Tooling API.
 */
data class PrivacySandboxSdkInfoImpl(
        override val task: String,
        override val outputListingFile: File,
) : PrivacySandboxSdkInfo, Serializable {

    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
