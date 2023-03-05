package com.tyron.builder.api.variant

import com.tyron.builder.api.variant.DslExtension.Builder
import org.gradle.api.Incubating

/**
 * Third party DSL extension configuration.
 *
 * When a third party plugins needs to extend the Android Gradle Plugin DSL, it needs to indicate
 * the location of these extension elements.
 *
 * Currently, we support extending :
 * <ul>
 * <li>[com.android.build.api.dsl.DefaultConfig]</li>
 * <li>[com.android.build.api.dsl.BuildType]</li>
 * <li>[com.android.build.api.dsl.ProductFlavor]</li>
 * </ul>
 *
 * A plugin can choose to extend one or more of these elements by calling the appropriate methods on
 * the [Builder] like [Builder.extendBuildTypeWith] to extend [com.android.build.api.dsl.BuildType].
 *
 * Because the number of instances of each extension point is determined during evaluation phase,
 * Gradle will be creating those instances on demand. See full documentation at :
 * https://docs.gradle.org/current/userguide/custom_plugins.html#sec:getting_input_from_the_build
 *
 */
@Incubating
class DslExtension private constructor(
    val dslName: String,
    val projectExtensionType: Class<out Any>? = null,
    val buildTypeExtensionType: Class<out Any>? = null,
    val productFlavorExtensionType: Class<out Any>? = null,
) {
    /**
     * Creates a [Builder] to instance to create a [DslExtension] containing all desired extension
     * points to the Android Gradle Plugin DSL.
     *
     * @param dslName the extension point name as it appears in build files.
     */
    @Incubating
    class Builder(private val dslName: String) {

        private var projectExtensionType: Class<out Any>? = null
        private var buildTypeExtensionType: Class<out Any>? = null
        private var productFlavorExtensionType: Class<out Any>? = null

        /**
         * Registers an extension point for the module's DSL, it will be available under the
         * android block.
         */
        fun extendProjectWith(typeExtension: Class<out Any>): Builder {
            projectExtensionType = typeExtension
            return this
        }

        /**
         * Registers an extension point for the [com.android.build.api.dsl.BuildType]
         */
        fun extendBuildTypeWith(typeExtension: Class<out Any>): Builder {
            buildTypeExtensionType = typeExtension
            return this
        }

        /**
         * Registers an extension point for the [com.android.build.api.dsl.ProductFlavor]
         */
        fun extendProductFlavorWith(typeExtension: Class<out Any>): Builder {
            productFlavorExtensionType = typeExtension
            return this
        }

        /**
         * Builds the final [DslExtension] instance that can be used with the
         * [AndroidComponentsExtension.registerExtension] API
         */
        fun build(): DslExtension =
            DslExtension(
                dslName,
                projectExtensionType,
                buildTypeExtensionType,
                productFlavorExtensionType
            )
    }
}