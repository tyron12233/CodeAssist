package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.ApplicationBuildType
import com.tyron.builder.api.dsl.DynamicFeatureBuildType
import com.tyron.builder.api.dsl.LibraryBuildType
import com.tyron.builder.api.dsl.TestBuildType

/**
 * To appease the groovy dynamic method dispatch so that
 * initWith with an instance of the Gradle decorated [BuildType]_Decorated can be passed to
 * initWith in groovy (finding `fun initWith(that: InternalBuildType): BuildType`)
 *
 * ```
 * Tooling Model       New DSL API
 *   BuildType          BuildType
 *       \                 /        below class/iterface implements/extends above
 *        InternalBuildType
 *             |
 *        ...._Decorated (Gradle decorated subclass)
 * ```
 *
 * I'm not sure why, but the groovy dispatch can't handle disambiguating the three methods when
 * InternalBuildType is a class, but can when everything is an interface.
 */
interface InternalBuildType :
    ApplicationBuildType,
    LibraryBuildType,
    DynamicFeatureBuildType,
    TestBuildType,
    com.tyron.builder.model.BuildType