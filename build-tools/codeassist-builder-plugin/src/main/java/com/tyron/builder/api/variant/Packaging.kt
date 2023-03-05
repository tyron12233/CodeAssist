package com.tyron.builder.api.variant

/**
 * Defines a variant's packaging options.
 */
interface Packaging {

    /** PackagingOptions for native libraries. Initialized from the corresponding DSL. */
    val jniLibs: JniLibsPackaging

    /** PackagingOptions for java resources. Initialized from the corresponding DSL. */
    val resources: ResourcesPackaging
}