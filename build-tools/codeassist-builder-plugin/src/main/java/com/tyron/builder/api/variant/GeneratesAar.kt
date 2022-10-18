package com.tyron.builder.api.variant
interface GeneratesAar {

    /**
     * Variant's aar metadata, initialized by merging the corresponding
     * [com.android.build.api.dsl.AarMetadata] DSL elements.
     */
    val aarMetadata: AarMetadata
}