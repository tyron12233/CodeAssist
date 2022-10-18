package com.tyron.builder.api.variant

import org.gradle.api.provider.Property

interface Renderscript {
    /** Returns the renderscript support mode.  */
    val supportModeEnabled: Property<Boolean>

    /** Returns the renderscript BLAS support mode.  */
    val supportModeBlasEnabled: Property<Boolean>

    /** Returns the renderscript NDK mode.  */
    val ndkModeEnabled: Property<Boolean>

    val optimLevel: Property<Int>
}