package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.LibrarySingleVariant
import com.tyron.builder.gradle.internal.services.DslServices
import javax.inject.Inject

abstract class LibrarySingleVariantImpl @Inject constructor(
    dslServices: DslServices,
    override val variantName: String,
) : LibrarySingleVariant, PublishingOptionsImpl()