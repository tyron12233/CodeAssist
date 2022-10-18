package com.tyron.builder.model.v2.models

import com.tyron.builder.model.v2.AndroidModel

/**
 * Basic model providing version information about the actual models.
 *
 * This model is meant to be very stable and never change, so that Studio can safely query it.
 */
interface Versions: AndroidModel {
    interface Version {
        val major: Int
        val minor: Int
    }

    val versions: Map<String, Version>

    companion object {
        const val BASIC_ANDROID_PROJECT = "basic_android_project"
        const val ANDROID_PROJECT = "android_project"
        const val ANDROID_DSL = "android_dsl"
        const val VARIANT_DEPENDENCIES = "variant_dependencies"
        const val NATIVE_MODULE = "native_module"
    }

    /**
     * The version of AGP.
     */
    val agp: String
}
