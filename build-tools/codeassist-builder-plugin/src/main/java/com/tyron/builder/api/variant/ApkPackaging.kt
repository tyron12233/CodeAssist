package com.tyron.builder.api.variant

/**
 * Defines an APK variant's packaging options.
 */
interface ApkPackaging : Packaging {

    /** PackagingOptions for dex files. Initialized from the corresponding DSL. */
    val dex: DexPackagingOptions

    override val jniLibs: JniLibsApkPackaging
}