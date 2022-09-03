package com.tyron.builder.api.dsl

/**
 * Maven publishing DSL object for configuring options related to publishing AAR.
 *
 * To publish just one variant, use [singleVariant]. The following sets up publishing of only the
 * fullRelease variant of an android library.
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
 *         // Publishes "fullRelease" build variant with "fullRelease" component created by
 *         // Android Gradle plugin
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
 * To publish multiple variants, use [multipleVariants]. The following sets up publishing of both
 * fullDebug and fullRelease variants of an android library.
 *
 * ```
 * android {
 *     publishing {
 *         // Published fullDebug and fullRelease build variants with "full" component created by
 *         // Android Gradle Plugin. The buildType attribute is added to published build variants.
 *         // As only a single flavor from "mode" dimension is included, no flavor attribute is
 *         // included.
 *         multipleVariants("full") {
 *             includeBuildTypeValues("debug", "release")
 *             includeFlavorDimensionAndValues("mode", "full")
 *         }
 *     }
 * }
 *
 * afterEvaluate {
 *     publishing {
 *         publications {
 *             full(MavenPublication) {
 *                 from components.full
 *                 // ......
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * To publish all the build variants, you can use [MultipleVariants.allVariants] as a shortcut
 * instead of filtering variants with [MultipleVariants.includeBuildTypeValues]
 * and [MultipleVariants.includeFlavorDimensionAndValues].
 *
 * ```
 * android {
 *     publishing {
 *         // Publishes all build variants with "default" component
 *         multipleVariants {
 *             allVariants()
 *         }
 *     }
 * }
 *
 * afterEvaluate {
 *     publishing {
 *         publications {
 *             allVariants(MavenPublication) {
 *                 from components.default
 *                 // ......
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * This following code example shows how to create two publications one for demoRelease build
 * variant, one for fullDebug & fullRelease build variants.
 *
 * ```
 * android {
 *     publishing {
 *         // Publish "demoRelease" build variant with "demoRelease" component
 *         singleVariant("demoRelease")
 *
 *         // Publish "fullDebug" and "fullRelease" build variants with "full" component
 *         multipleVariants("full") {
 *             includeBuildTypeValues("debug", "release")
 *             includeFlavorDimensionAndValues("mode", "full")
 *         }
 *     }
 * }
 *
 * afterEvaluate {
 *     publishing {
 *         publications {
 *             // Creates two publications with different artifactIds
 *             full(MavenPublication) {
 *                 from components.full
 *                 groupId = 'com.example.MyLibrary'
 *                 artifactId = 'final-full'
 *                 version = '1.0'
 *             }
 *             demoRelease(MavenPublication) {
 *                 from components.demoRelease
 *                 groupId = 'com.example.MyLibrary'
 *                 artifactId = 'final-demo'
 *                 version = '1.0'
 *             }
 *         }
 *     }
 * }
 * ```
 * The testFixtures component is published by default with its main variant. To disable publishing
 * the testFixtures component, see the following example.
 *
 * ```
 * afterEvaluate {
 *     // Disable publishing test fixtures of release variant
 *     components.release.withVariantsFromConfiguration(
 *         configurations.releaseTestFixturesVariantReleaseApiPublication) { skip() }
 *     components.release.withVariantsFromConfiguration(
 *         configurations.releaseTestFixturesVariantReleaseRuntimePublication) { skip() }
 * }
 * ```
 */
interface LibraryPublishing : Publishing<LibrarySingleVariant> {

    /**
     * Publish multiple variants to a component.
     */
    fun multipleVariants(componentName: String, action: MultipleVariants.() -> Unit)

    /**
     * Publish multiple variants to the default component.
     */
    fun multipleVariants(action: MultipleVariants.() -> Unit)
}
