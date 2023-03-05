package com.tyron.builder.api.component.impl

import com.tyron.builder.gradle.internal.component.AndroidTestCreationConfig
import com.tyron.builder.gradle.internal.core.dsl.AndroidTestComponentDslInfo

class AndroidTestCreationConfigImpl(
    config: AndroidTestCreationConfig,
    variantDslInfo: AndroidTestComponentDslInfo
) : ApkCreationConfigImpl<AndroidTestCreationConfig>(config, variantDslInfo)