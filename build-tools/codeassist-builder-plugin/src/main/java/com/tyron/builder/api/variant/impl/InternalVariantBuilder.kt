package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.VariantBuilder
import com.tyron.builder.gradle.internal.services.ProjectServices

interface InternalVariantBuilder {

    fun <T: VariantBuilder> createUserVisibleVariantObject(
        projectServices: ProjectServices,
        stats: Any?): T
}