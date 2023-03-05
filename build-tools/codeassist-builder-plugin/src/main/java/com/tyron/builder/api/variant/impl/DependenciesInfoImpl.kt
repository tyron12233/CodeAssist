package com.tyron.builder.api.variant.impl

import com.tyron.builder.api.variant.DependenciesInfo

open class DependenciesInfoImpl(
    override val includedInApk: Boolean,
    override val includedInBundle: Boolean,
): DependenciesInfo