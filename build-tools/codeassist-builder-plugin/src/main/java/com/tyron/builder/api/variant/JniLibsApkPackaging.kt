package com.tyron.builder.api.variant

import org.gradle.api.provider.Provider

/**
 * Defines an APK variant's packaging options for native library (.so) files.
 */
interface JniLibsApkPackaging : JniLibsPackaging {

    /**
     * Whether to use the legacy convention of compressing all .so files in the APK. This does not
     * affect APKs generated from the app bundle; see [useLegacyPackagingFromBundle].
     */
    val useLegacyPackaging: Provider<Boolean>

    /**
     * Whether to use the legacy convention of compressing all .so files when generating APKs from
     * the app bundle. If true, .so files will always be compressed when generating APKs from the
     * app bundle, regardless of the API level of the target device. If false, .so files will be
     * compressed only when targeting devices with API level < M.
     */
    val useLegacyPackagingFromBundle: Provider<Boolean>
}