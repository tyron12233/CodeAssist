package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.TestBuildFeatures
import com.tyron.builder.api.dsl.TestBuildType
import com.tyron.builder.api.dsl.TestDefaultConfig
import com.tyron.builder.api.dsl.TestExtension
import com.tyron.builder.api.dsl.TestProductFlavor

/** See [InternalCommonExtension] */
interface InternalTestExtension :
    TestExtension,
        InternalCommonExtension<
                TestBuildFeatures,
                TestBuildType,
                TestDefaultConfig,
                TestProductFlavor>
