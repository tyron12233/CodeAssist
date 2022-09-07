package com.tyron.builder.gradle.internal.component

import com.tyron.builder.gradle.internal.publishing.VariantPublishingInfo

interface PublishableCreationConfig {
    val publishInfo: VariantPublishingInfo?
}