package com.tyron.builder.api.dsl
/**
 * Maven publishing DSL object for configuring options related to publishing APK and AAB.
 *
 * This following code example creates a publication for the fullRelease build variant, which
 * publish your app as Android App Bundle.
 *
 * ```
 * android {
 *     // This project has four build variants: fullDebug, fullRelease, demoDebug, demoRelease
 *     flavorDimensions 'mode'
 *     productFlavors {
 *         full {}
 *         demo {}
 *     }
 *
 *     publishing {
 *         // Publish your app as an AAB
 *         singleVariant("fullRelease")
 *     }
 * }
 *
 * afterEvaluate {
 *     publishing {
 *         publications {
 *             fullRelease(MavenPublication) {
 *                 from components.fullRelease
 *                 // ......
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * To publish your app as a ZIP file of APKs, simply use the [ApplicationSingleVariant.publishApk]
 * as shown in the following example.
 *
 * ```
 * android {
 *     publishing {
 *         // Publish your app as a ZIP file of APKs
 *         singleVariant("fullRelease") {
 *             publishApk()
 *         }
 *     }
 * }
 * ```
 */
interface ApplicationPublishing : Publishing<ApplicationSingleVariant>