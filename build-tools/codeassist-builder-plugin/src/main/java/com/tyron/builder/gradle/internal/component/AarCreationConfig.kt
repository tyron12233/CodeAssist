package com.tyron.builder.gradle.internal.component

import com.tyron.builder.api.variant.AarMetadata

interface AarCreationConfig: ComponentCreationConfig {
    val aarMetadata: AarMetadata
}