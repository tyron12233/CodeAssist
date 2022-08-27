package com.tyron.builder.api.component.impl

import com.tyron.builder.gradle.internal.component.ApkCreationConfig

open class ApkCreationConfigImpl<T: ApkCreationConfig>(
    config: T,
//    override val dslInfo: ApkProducingComponentDslInfo
): ConsumableCreationConfigImpl<T>(config) {

//    val isDebuggable: Boolean
//        get() = dslInfo.isDebuggable
//
//    override val needsShrinkDesugarLibrary: Boolean
//        get() {
//            if (!isCoreLibraryDesugaringEnabled(config)) {
//                return false
//            }
//            // Assume Java8LangSupport is either D8 or R8 as we checked that in
//            // isCoreLibraryDesugaringEnabled()
//            return !(getJava8LangSupportType() == Java8LangSupport.D8 && config.debuggable)
//        }
//
//    /**
//     * Returns the minimum SDK version which we want to use for dexing.
//     * In most cases this will be equal the minSdkVersion, but when the IDE is deploying to:
//     * - device running API 24+, the min sdk version for dexing is max(24, minSdkVersion)
//     * - device running API 23-, the min sdk version for dexing is minSdkVersion
//     * - there is no device, the min sdk version for dexing is minSdkVersion
//     * It is used to enable some optimizations to build the APK faster.
//     *
//     * This has no relation with targetSdkVersion from build.gradle/manifest.
//     */
//    override val minSdkVersionForDexing: AndroidVersion
//        get() {
//            val targetDeployApiFromIDE = dslInfo.targetDeployApiFromIDE ?: 1
//
//            val minForDexing = if (targetDeployApiFromIDE >= VersionCodes.N) {
//                    max(24, config.minSdkVersion.getFeatureLevel())
//                } else {
//                    config.minSdkVersion.getFeatureLevel()
//                }
//            return AndroidVersionImpl(minForDexing)
//        }
}