package com.tyron.builder.api.dsl

/**
 * Encapsulates all product flavors properties for test projects.
 *
 * Test projects have a target application project that they depend on and flavor matching works in
 * the same way as library dependencies. Therefore to test multiple flavors of an application,
 * you can declare corresponding product flavors here. If you want to use some, you can use
 * [missingDimensionStrategy] to resolve any conflicts.
 *
 * See [ApplicationProductFlavor]
 */
interface TestProductFlavor :
    TestBaseFlavor,
    ProductFlavor
