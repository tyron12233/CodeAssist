package com.tyron.builder.gradle.internal.core

import com.tyron.builder.api.dsl.ProductFlavor

data class ProductFlavor(val dimension: String, val name: String) {
    constructor(flavor: ProductFlavor): this(flavor.dimension!!, flavor.name)
}