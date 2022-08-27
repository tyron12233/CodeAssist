package com.tyron.builder.api.variant

import org.gradle.api.Incubating
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import java.io.Serializable

/**
 * Parent interface for all types of variants.
 */
interface Variant : Component, HasAndroidResources {

    /**
     * Gets the minimum supported SDK Version for this variant.
     */
    val minSdkVersion: AndroidVersion

    /**
     * Gets the maximum supported SDK Version for this variant.
     */
    val maxSdkVersion: Int?

    /**
     * Gets the target SDK Version for this variant.
     */
    @Deprecated(
        "Will be removed in v9.0",
        replaceWith = ReplaceWith("GeneratesApk.targetSdkVersion")
    )
    val targetSdkVersion: AndroidVersion

    /**
     * The namespace of the generated R and BuildConfig classes. Also, the namespace used to resolve
     * any relative class names that are declared in the AndroidManifest.xml.
     */
    val namespace: Provider<String>

    /**
     * Variant's [BuildConfigField] which will be generated in the BuildConfig class.
     */
    val buildConfigFields: MapProperty<String, BuildConfigField<out Serializable>>

    /**
     * [MapProperty] of the variant's manifest placeholders.
     *
     * Placeholders are organized with a key and a value. The value is a [String] that will be
     * used as is in the merged manifest.
     *
     * @return the [MapProperty] with keys as [String]
     */
    val manifestPlaceholders: MapProperty<String, String>

    /**
     * Variant's packagingOptions, initialized by the corresponding global DSL element.
     */
    val packaging: Packaging

    /**
     * Variant's cmake [ExternalNativeBuild], initialized by merging the product flavor values or
     * null if no cmake external build is configured for this variant.
     */
    val externalNativeBuild: ExternalNativeBuild?

    /**
     * Variant's [UnitTest], or null if the unit tests for this variant are disabled.
     */
    val unitTest: UnitTest?

    /**
     * Returns an extension object registered via the [VariantBuilder.registerExtension] API or
     * null if none were registered under the passed [type].
     *
     * @return the registered object or null.
     */
    fun <T> getExtension(type: Class<T>): T?

    /**
     * List of proguard configuration files for this variant. The list is initialized from the
     * corresponding DSL element, and cannot be queried at configuration time. At configuration time,
     * you can only add new elements to the list.
     *
     * This list will be initialized from [com.android.build.api.dsl.VariantDimension#proguardFile]
     * for non test related variants and from
     * [com.android.build.api.dsl.VariantDimension.testProguardFiles] for test related variants.
     */
    val proguardFiles: ListProperty<RegularFile>

    /**
     * Additional per variant experimental properties.
     *
     * Initialized from [com.android.build.api.dsl.CommonExtension.experimentalProperties]
     */
    @get:Incubating
    val experimentalProperties: MapProperty<String, Any>

    /**
     * List of the components nested in this variant, the returned list will contain:
     *
     * * [UnitTest] component if the unit tests for this variant are enabled,
     * * [AndroidTest] component if this variant [HasAndroidTest] and android tests for this variant
     * are enabled,
     * * [TestFixtures] component if this variant [HasTestFixtures] and test fixtures for this
     * variant are enabled.
     *
     * Use this list to do operations on all nested components of this variant without having to
     * manually check whether the variant has each component.
     *
     * Example:
     *
     * ```kotlin
     *  androidComponents.onVariants(selector().withName("debug")) {
     *      // will return unitTests, androidTests, testFixtures for the debug variant (if enabled).
     *      nestedComponents.forEach { component ->
     *          component.transformClassesWith(NestedComponentsClassVisitorFactory::class.java,
     *                                         InstrumentationScope.Project) {}
     *      }
     *  }
     *  ```
     */
    @get:Incubating
    val nestedComponents: List<Component>

    /**
     * List containing this variant and all of its [nestedComponents]
     *
     * Example:
     *
     * ```kotlin
     *  androidComponents.onVariants(selector().withName("debug")) {
     *      // components contains the debug variant along with its unitTests, androidTests, and
     *      // testFixtures (if enabled).
     *      components.forEach { component ->
     *          component.runtimeConfiguration
     *              .resolutionStrategy
     *              .dependencySubstitution {
     *                  substitute(project(":foo")).using(project(":bar"))
     *              }
     *      }
     *  }
     *  ```
     */
    @get:Incubating
    val components: List<Component>
}