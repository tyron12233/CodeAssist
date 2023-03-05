package com.tyron.builder.gradle.internal.core

import com.tyron.builder.api.dsl.AarMetadata

/** Used to merge multiple instances of [AarMetadata] together.  */
class MergedAarMetadata : MergedOptions<AarMetadata>, AarMetadata {

    override var minCompileSdk: Int? = null
    override var minCompileSdkExtension: Int? = null
    override var minAgpVersion: String? = null

    override fun reset() {
        minCompileSdk = null
        minCompileSdkExtension = null
        minAgpVersion = null
    }

    override fun append(option: AarMetadata) {
        option.minCompileSdk?.let { minCompileSdk = it }
        option.minCompileSdkExtension?.let { minCompileSdkExtension = it }
        option.minAgpVersion?.let { minAgpVersion = it }
    }

    fun append(option: MergedAarMetadata) {
        option.minCompileSdk?.let { minCompileSdk = it }
        option.minCompileSdkExtension?.let { minCompileSdkExtension = it }
        option.minAgpVersion?.let { minAgpVersion = it }
    }
}
