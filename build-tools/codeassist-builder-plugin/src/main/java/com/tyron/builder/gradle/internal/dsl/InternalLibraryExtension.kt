package com.tyron.builder.gradle.internal.dsl

import com.tyron.builder.api.dsl.LibraryBuildFeatures
import com.tyron.builder.api.dsl.LibraryBuildType
import com.tyron.builder.api.dsl.LibraryDefaultConfig
import com.tyron.builder.api.dsl.LibraryExtension
import com.tyron.builder.api.dsl.LibraryProductFlavor
import com.tyron.builder.api.dsl.LibraryPublishing
import org.gradle.api.Action

/** See [InternalCommonExtension] */
interface InternalLibraryExtension :
    LibraryExtension,
    InternalTestedExtension<
            LibraryBuildFeatures,
            LibraryBuildType,
            LibraryDefaultConfig,
            LibraryProductFlavor> {

    override var aidlPackagedList: MutableCollection<String>
    fun publishing(action: Action<LibraryPublishing>)
}
