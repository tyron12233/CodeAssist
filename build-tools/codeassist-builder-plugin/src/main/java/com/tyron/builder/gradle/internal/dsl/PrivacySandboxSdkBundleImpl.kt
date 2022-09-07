package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.PrivacySandboxSdkBundle

abstract class PrivacySandboxSdkBundleImpl: PrivacySandboxSdkBundle {

    override var packageName: String?
        get() = applicationId
        set(value) { applicationId = value }

    var version: KotlinVersion? = null

    override fun setVersion(major: Int, minor: Int, patch: Int) {
        version = KotlinVersion(major, minor, patch)
    }
}
