package com.tyron.builder.api.dsl

import org.gradle.api.Incubating

@Incubating
interface PrivacySandboxSdkBundle {

    @get:Deprecated(message = "packageName is replaced with applicationId", replaceWith = ReplaceWith("applicationId"))
    @get:Incubating
    @set:Deprecated(message = "packageName is replaced with applicationId", replaceWith = ReplaceWith("applicationId"))
    @set:Incubating
    var packageName: String?

    @get:Incubating
    @set:Incubating
    var applicationId: String?

    @get:Incubating
    @set:Incubating
    var sdkProviderClassName: String?

    @Incubating
    fun setVersion(major: Int, minor: Int, patch: Int)
}
