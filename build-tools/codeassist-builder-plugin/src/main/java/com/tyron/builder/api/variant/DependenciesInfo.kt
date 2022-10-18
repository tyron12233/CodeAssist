package com.tyron.builder.api.variant

/**
 * Read-only object to access dependencies information properties during
 * [com.android.build.api.variant.AndroidComponentsExtension#onVariants]
 */
interface DependenciesInfo {
    val includedInApk: Boolean
    val includedInBundle: Boolean
}